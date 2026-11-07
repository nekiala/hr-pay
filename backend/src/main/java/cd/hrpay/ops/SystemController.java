package cd.hrpay.ops;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What an administrator checks: version, uptime, database size, backups, scheduled jobs and recent alerts. */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    public record Status(String version, Instant builtAt, String javaVersion, Instant startedAt, long uptimeSeconds,
                         long databaseBytes, long activeSessions, long employees, boolean backupsExpected,
                         List<BackupWatch.Run> backups, List<JobLocks.Job> jobs, List<SystemAlerts.Alert> alerts) {}

    private final JdbcClient jdbc;
    private final BackupWatch backups;
    private final JobLocks jobs;
    private final SystemAlerts alerts;
    private final ObjectProvider<BuildProperties> build;

    public SystemController(JdbcClient jdbc, BackupWatch backups, JobLocks jobs, SystemAlerts alerts,
                            ObjectProvider<BuildProperties> build) {
        this.jdbc = jdbc;
        this.backups = backups;
        this.jobs = jobs;
        this.alerts = alerts;
        this.build = build;
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('system.monitor')")
    public Status status() {
        BuildProperties b = build.getIfAvailable();
        Instant started = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
        long sessions = jdbc.sql("select count(*) from spring_session where expiry_time > (extract(epoch from now()) * 1000)")
                .query(Long.class).single();
        return new Status(b == null ? "dev" : b.getVersion(), b == null ? null : b.getTime(), System.getProperty("java.version"),
                started, Duration.between(started, Instant.now()).toSeconds(),
                jdbc.sql("select pg_database_size(current_database())").query(Long.class).single(), sessions,
                jdbc.sql("select count(*) from app_user where status = 'ACTIVE'").query(Long.class).single(),
                backups.expected(), backups.recent(10), jobs.jobs(), alerts.recent(20));
    }
}
