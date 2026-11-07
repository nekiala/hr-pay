package cd.hrpay.security;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The browsers a person is signed in on. A session ended here (by its owner or an administrator) is signed out at
 * its next request, on whichever server it lands.
 */
@Service
public class UserSessions {

    public record View(String id, String method, String ip, String userAgent, OffsetDateTime signedInAt,
                       OffsetDateTime lastSeenAt, boolean current) {}

    /** The id a session is shown by. */
    private static final String TOKEN = "left(encode(sha256(convert_to(session_id, 'UTF8')), 'hex'), 24)";

    private final JdbcClient jdbc;
    private final AuditService audit;

    public UserSessions(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public void record(HttpServletRequest request, long userId, String method) {
        String agent = request.getHeader("User-Agent");
        jdbc.sql("""
                insert into user_session (session_id, user_id, method, ip, user_agent) values (?, ?, ?, ?, ?)
                on conflict (session_id) do update set user_id = excluded.user_id, method = excluded.method,
                    signed_in_at = now(), last_seen_at = now(), ended_at = null, ended_by = null""")
                .params(request.getSession().getId(), userId, method, request.getRemoteAddr(),
                        agent == null ? null : agent.substring(0, Math.min(agent.length(), 300)))
                .update();
    }

    /** False when the session was ended elsewhere; otherwise notes that it is in use. */
    public boolean stillOpen(String sessionId) {
        Boolean ended = jdbc.sql("select ended_at is not null from user_session where session_id = ?").param(sessionId)
                .query(Boolean.class).optional().orElse(false);
        if (ended) {
            return false;
        }
        jdbc.sql("update user_session set last_seen_at = now() where session_id = ? and last_seen_at < now() - interval '1 minute'")
                .param(sessionId).update();
        return true;
    }

    public List<View> list(long userId, String currentSessionId) {
        return jdbc.sql("""
                select session_id, method, ip, user_agent, signed_in_at, last_seen_at, %s from user_session
                where user_id = ? and ended_at is null and last_seen_at > now() - interval '8 hours'
                order by last_seen_at desc""".formatted(TOKEN)).param(userId)
                .query((rs, i) -> new View(rs.getString(7), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class),
                        rs.getString(1).equals(currentSessionId)))
                .list();
    }

    /** Ends one session, named by the id the list shows (never the session's own id, which would let it be used). */
    public void end(long userId, String id, String by) {
        String sessionId = jdbc.sql("select session_id from user_session where user_id = ? and ended_at is null and " + TOKEN + " = ?")
                .params(userId, id).query(String.class).optional()
                .orElseThrow(() -> new NotFoundException("This session is not open."));
        jdbc.sql("update user_session set ended_at = now(), ended_by = ? where session_id = ?").params(by, sessionId).update();
        deleteStored(sessionId);
        audit.record("auth.session_ended", "user", userId, Map.of("by", by));
    }

    /** Signs a person out everywhere, except the session given (null: everywhere). */
    public int endAll(long userId, String except, String by) {
        List<String> ids = jdbc.sql("select session_id from user_session where user_id = ? and ended_at is null and session_id <> coalesce(?, '')")
                .params(userId, except).query(String.class).list();
        for (String id : ids) {
            jdbc.sql("update user_session set ended_at = now(), ended_by = ? where session_id = ?").params(by, id).update();
            deleteStored(id);
        }
        if (!ids.isEmpty()) {
            audit.record("auth.sessions_ended", "user", userId, Map.of("count", ids.size(), "by", by));
        }
        return ids.size();
    }

    /** Removes the session from the session store too, when sessions are kept in the database. */
    private void deleteStored(String sessionId) {
        Boolean store = jdbc.sql("select to_regclass('spring_session') is not null").query(Boolean.class).single();
        if (store) {
            jdbc.sql("delete from spring_session where session_id = ?").param(sessionId).update();
        }
    }
}
