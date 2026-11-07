package cd.hrpay.privacy;

import cd.hrpay.audit.AuditService;
import cd.hrpay.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The privacy notice each person reads at their first sign-in, and how long the GPS position of a check-in is kept.
 * Positions older than the retention are erased every night; the check-in itself (time, site, method) stays.
 */
@RestController
@RequestMapping("/api/privacy")
public class Privacy {

    private static final Logger log = LoggerFactory.getLogger(Privacy.class);

    public record Settings(@Min(0) @Max(3650) int gpsRetentionDays) {}

    private final JdbcClient jdbc;
    private final AuditService audit;

    public Privacy(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @PostMapping("/accept")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void accept() {
        int rows = jdbc.sql("update app_user set privacy_accepted_at = now() where id = ? and privacy_accepted_at is null")
                .param(CurrentUser.id()).update();
        if (rows == 1) {
            audit.record("privacy.notice_accepted", "user", CurrentUser.id(), Map.of());
        }
    }

    @GetMapping("/settings")
    @PreAuthorize("isAuthenticated()")
    public Settings settings() {
        return new Settings(retentionDays());
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('site.manage')")
    @Transactional
    public Map<String, Object> save(@Valid @RequestBody Settings b) {
        jdbc.sql("""
                insert into payroll_setting (key, value) values ('GPS_RETENTION_DAYS', ?)
                on conflict (company_id, key) do update set value = excluded.value""").param(String.valueOf(b.gpsRetentionDays())).update();
        audit.record("privacy.gps_retention_changed", "payroll_setting", null, Map.of("days", b.gpsRetentionDays()));
        return Map.of("gpsRetentionDays", b.gpsRetentionDays(), "erased", erase());
    }

    private int retentionDays() {
        return jdbc.sql("select value from payroll_setting where key = 'GPS_RETENTION_DAYS'").query(String.class).optional()
                .map(Integer::parseInt).orElse(0);
    }

    /** Erases the positions of check-ins older than the retention. Returns how many were erased. */
    @Transactional
    public int erase() {
        int days = retentionDays();
        if (days <= 0) {
            return 0;
        }
        return jdbc.sql("""
                update clock_event set lat = null, lng = null, accuracy_m = null
                where occurred_at < now() - make_interval(days => ?) and (lat is not null or lng is not null)""")
                .param(days).update();
    }

    private cd.hrpay.ops.JobLocks locks;

    @org.springframework.beans.factory.annotation.Autowired
    void setLocks(cd.hrpay.ops.JobLocks locks) {
        this.locks = locks;
    }

    @Scheduled(cron = "${app.privacy.cron:0 45 2 * * *}", zone = "Africa/Kinshasa")
    public void nightly() {
        locks.runForEachCompany("gps-erase", java.time.Duration.ofMinutes(30), () -> {
            int erased = erase();
            if (erased > 0) {
                log.info("Erased the GPS position of {} check-in(s) older than the retention.", erased);
            }
        });
    }
}
