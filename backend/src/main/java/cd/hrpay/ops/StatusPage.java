package cd.hrpay.ops;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.tenancy.TenantContext;
import cd.hrpay.tenancy.Tenants;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public status page: whether the app and its database answer, the incidents and planned maintenance the provider
 * posts, and the version running. Readable without signing in; shows nothing of any company.
 */
@RestController
public class StatusPage {

    /** demo: this server is the public demo, wiped every night. */
    public record Version(String version, Instant builtAt, boolean demo) {}

    public record Update(long id, String body, OffsetDateTime createdAt) {}

    public record Incident(long id, String title, String impact, OffsetDateTime startedAt, OffsetDateTime resolvedAt,
                           List<Update> updates) {}

    public record Check(String key, boolean ok) {}

    /** overall: OPERATIONAL, MAINTENANCE, DEGRADED or OUTAGE. */
    public record Status(String overall, Version version, List<Check> checks, List<Incident> active, List<Incident> recent,
                         OffsetDateTime checkedAt) {}

    public record NewIncident(@NotBlank @Size(max = 150) String title, @NotBlank @Pattern(regexp = "MINOR|MAJOR|OUTAGE|PLANNED") String impact,
                              @NotBlank @Size(max = 1000) String body, OffsetDateTime startedAt) {}

    public record NewUpdate(@NotBlank @Size(max = 1000) String body, boolean resolve) {}

    private final JdbcClient jdbc;
    private final Tenants tenants;
    private final TransactionTemplate tx;
    private final AuditService audit;
    private final ObjectProvider<BuildProperties> build;
    private final boolean demo;

    public StatusPage(JdbcClient jdbc, Tenants tenants, TransactionTemplate tx, AuditService audit,
                      ObjectProvider<BuildProperties> build,
                      @org.springframework.beans.factory.annotation.Value("${app.demo.enabled:false}") boolean demo) {
        this.demo = demo;
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.tx = tx;
        this.audit = audit;
        this.build = build;
    }

    @GetMapping("/api/version")
    public Version version() {
        BuildProperties b = build.getIfAvailable();
        return new Version(b == null ? "dev" : b.getVersion(), b == null ? null : b.getTime(), demo);
    }

    @GetMapping("/api/status")
    public Status status() {
        List<Check> checks = new ArrayList<>();
        checks.add(new Check("app", true));
        boolean database;
        List<Incident> incidents = List.of();
        try {
            incidents = tenants.callAsPlatform(this::incidents);
            database = true;
        } catch (RuntimeException e) {
            database = false;
        }
        checks.add(new Check("database", database));
        List<Incident> active = incidents.stream().filter(i -> i.resolvedAt() == null
                && !i.startedAt().isAfter(OffsetDateTime.now())).toList();
        List<Incident> recent = incidents.stream().filter(i -> !active.contains(i)).toList();
        String overall = !database || active.stream().anyMatch(i -> i.impact().equals("OUTAGE")) ? "OUTAGE"
                : active.stream().anyMatch(i -> i.impact().equals("MAJOR") || i.impact().equals("MINOR")) ? "DEGRADED"
                : active.stream().anyMatch(i -> i.impact().equals("PLANNED")) ? "MAINTENANCE" : "OPERATIONAL";
        return new Status(overall, version(), checks, active, recent, OffsetDateTime.now());
    }

    /** Open incidents, planned maintenance to come, and the last 30 days. */
    private List<Incident> incidents() {
        List<Incident> list = jdbc.sql("""
                select id, title, impact, started_at, resolved_at from status_incident
                where resolved_at is null or resolved_at > now() - interval '30 days'
                order by coalesce(resolved_at, 'infinity') desc, started_at desc limit 50""")
                .query((rs, n) -> new Incident(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, OffsetDateTime.class), rs.getObject(5, OffsetDateTime.class), List.of()))
                .list();
        return list.stream().map(i -> new Incident(i.id(), i.title(), i.impact(), i.startedAt(), i.resolvedAt(),
                jdbc.sql("select id, body, created_at from status_update where incident_id = ? order by id desc")
                        .param(i.id()).query(Update.class).list())).toList();
    }

    // ---- the provider ----

    @PostMapping("/api/platform/incidents")
    @PreAuthorize("hasAuthority('platform.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Status post(@Valid @RequestBody NewIncident in) {
        requireProvider();
        String by = CurrentUser.email();
        long id = tenants.callAsPlatform(() -> tx.execute(s -> {
            long incident = jdbc.sql("""
                    insert into status_incident (title, impact, started_at, created_by) values (?, ?, ?, ?) returning id""")
                    .params(in.title().trim(), in.impact(), in.startedAt() == null ? OffsetDateTime.now() : in.startedAt(), by)
                    .query(Long.class).single();
            jdbc.sql("insert into status_update (incident_id, body, created_by) values (?, ?, ?)")
                    .params(incident, in.body().trim(), by).update();
            return incident;
        }));
        audit.record("status.incident_posted", "status_incident", id, Map.of("impact", in.impact(), "title", in.title()));
        return status();
    }

    @PostMapping("/api/platform/incidents/{id}/updates")
    @PreAuthorize("hasAuthority('platform.manage')")
    public Status update(@PathVariable long id, @Valid @RequestBody NewUpdate in) {
        requireProvider();
        String by = CurrentUser.email();
        tenants.callAsPlatform(() -> tx.execute(s -> {
            Boolean open = jdbc.sql("select resolved_at is null from status_incident where id = ?").param(id)
                    .query(Boolean.class).optional().orElseThrow(() -> new NotFoundException("Incident not found."));
            if (!open) {
                throw new BusinessRuleException("This incident is already resolved.");
            }
            jdbc.sql("insert into status_update (incident_id, body, created_by) values (?, ?, ?)").params(id, in.body().trim(), by).update();
            if (in.resolve()) {
                jdbc.sql("update status_incident set resolved_at = now() where id = ?").param(id).update();
            }
            return null;
        }));
        audit.record(in.resolve() ? "status.incident_resolved" : "status.incident_updated", "status_incident", id, null);
        return status();
    }

    private static void requireProvider() {
        if (!TenantContext.isProvider(TenantContext.companyId())) {
            throw new AccessDeniedException("Only the provider manages companies.");
        }
    }
}
