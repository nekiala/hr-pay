package cd.hrpay.ops;

import cd.hrpay.notify.NotificationService;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Problems the administrators must hear about: unexpected errors, failed jobs, missing backups. The same problem is
 * counted, not repeated: people holding system.monitor get one email, then at most one an hour while it goes on.
 */
@Service
public class SystemAlerts {

    private static final Logger log = LoggerFactory.getLogger(SystemAlerts.class);

    public record Alert(long id, String kind, String summary, String detail, java.time.OffsetDateTime firstAt,
                        java.time.OffsetDateTime lastAt, int occurrences, java.time.OffsetDateTime notifiedAt) {}

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final NotificationService notifications;

    public SystemAlerts(JdbcClient jdbc, UserRepository users, NotificationService notifications) {
        this.jdbc = jdbc;
        this.users = users;
        this.notifications = notifications;
    }

    /** Records a problem; never throws, so reporting an error cannot cause another one. */
    public void raise(String kind, String fingerprint, String summary, String detail) {
        try {
            String fp = cut(fingerprint, 200);
            Boolean notify = jdbc.sql("""
                    insert into system_alert (kind, fingerprint, summary, detail) values (:kind, :fp, :summary, :detail)
                    on conflict (kind, fingerprint) do update set last_at = now(), occurrences = system_alert.occurrences + 1,
                        summary = excluded.summary, detail = excluded.detail
                    returning notified_at is null or notified_at < now() - interval '1 hour'""")
                    .param("kind", kind).param("fp", fp).param("summary", cut(summary, 500)).param("detail", cut(detail, 8000))
                    .query(Boolean.class).single();
            if (Boolean.TRUE.equals(notify)) {
                jdbc.sql("update system_alert set notified_at = now() where kind = ? and fingerprint = ?").param(kind).param(fp).update();
                for (AppUser admin : recipients()) {
                    notifications.sendEmail(admin, "HR Pay alert: " + cut(summary, 120), summary
                            + "\n\n" + (detail == null ? "" : cut(detail, 3000))
                            + "\n\nSee the System page in HR Pay. You get at most one email an hour for the same problem.");
                }
            }
        } catch (RuntimeException e) {
            log.error("Could not record the alert '{}': {}", summary, e.getMessage());
        }
    }

    public List<Alert> recent(int limit) {
        return jdbc.sql("""
                select id, kind, summary, detail, first_at, last_at, occurrences, notified_at
                from system_alert order by last_at desc limit ?""").param(limit).query(Alert.class).list();
    }

    private List<AppUser> recipients() {
        return jdbc.sql("""
                select distinct u.id from app_user u join user_role ur on ur.user_id = u.id
                join role_permission rp on rp.role_id = ur.role_id
                where rp.permission_code = 'system.monitor' and u.status = 'ACTIVE'""")
                .query(Long.class).list().stream().map(id -> users.findById(id).orElseThrow()).toList();
    }

    static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
