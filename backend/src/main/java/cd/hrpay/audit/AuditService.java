package cd.hrpay.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/** Writes to the append-only audit log. Every change to users, roles or sites goes through here. */
@Service
public class AuditService {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public AuditService(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(String action, String entityType, Object entityId, Map<String, ?> details) {
        recordAs(currentActor(), action, entityType, entityId, details);
    }

    /** Records even when the caller's transaction rolls back, for refusals that end in an error. */
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recordIndependently(String action, String entityType, Object entityId, Map<String, ?> details) {
        recordAs(currentActor(), action, entityType, entityId, details);
    }

    public void recordAs(String actor, String action, String entityType, Object entityId, Map<String, ?> details) {
        jdbc.sql("""
                insert into audit_log (actor, action, entity_type, entity_id, details, ip)
                values (:actor, :action, :entityType, :entityId, cast(:details as jsonb), :ip)
                """)
                .param("actor", actor)
                .param("action", action)
                .param("entityType", entityType)
                .param("entityId", entityId == null ? null : entityId.toString())
                .param("details", details == null ? null : json.writeValueAsString(details))
                .param("ip", currentIp())
                .update();
    }

    private static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || !auth.isAuthenticated() ? "system" : auth.getName();
    }

    private static String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }
}
