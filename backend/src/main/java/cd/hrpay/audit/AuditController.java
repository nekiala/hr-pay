package cd.hrpay.audit;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    public record AuditEntry(long id, OffsetDateTime occurredAt, String actor, String action,
                             String entityType, String entityId, String details, String ip) {}

    public record AuditPage(List<AuditEntry> items, int page, int size, long total) {}

    private final JdbcClient jdbc;

    public AuditController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('audit.read')")
    public AuditPage list(@RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "50") int size,
                          @RequestParam(required = false) String entityType,
                          @RequestParam(required = false) String entityId) {
        int safeSize = Math.clamp(size, 1, 200);
        int safePage = Math.max(page, 0);
        String where = " where (cast(:entityType as varchar) is null or entity_type = :entityType)"
                + " and (cast(:entityId as varchar) is null or entity_id = :entityId)";
        long total = jdbc.sql("select count(*) from audit_log" + where)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .query(Long.class).single();
        List<AuditEntry> items = jdbc.sql("""
                select id, occurred_at, actor, action, entity_type, entity_id, details::text as details, ip
                from audit_log""" + where + " order by id desc limit :limit offset :offset")
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("limit", safeSize)
                .param("offset", (long) safePage * safeSize)
                .query((rs, n) -> new AuditEntry(rs.getLong("id"), rs.getObject("occurred_at", OffsetDateTime.class),
                        rs.getString("actor"), rs.getString("action"), rs.getString("entity_type"),
                        rs.getString("entity_id"), rs.getString("details"), rs.getString("ip")))
                .list();
        return new AuditPage(items, safePage, safeSize, total);
    }
}
