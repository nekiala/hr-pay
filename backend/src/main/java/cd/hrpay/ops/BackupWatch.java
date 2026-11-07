package cd.hrpay.ops;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Reads what the backup job (deploy/backup) wrote in backup_run, and alerts the administrators when a backup failed
 * or none succeeded for too long. Off unless BACKUPS_EXPECTED=true, which the production set-up sets.
 */
@Service
public class BackupWatch {

    public record Run(long id, OffsetDateTime startedAt, OffsetDateTime finishedAt, String fileName, Long sizeBytes,
                      String status, boolean offsite, String message) {}

    private final JdbcClient jdbc;
    private final SystemAlerts alerts;
    private final JobLocks locks;
    private final boolean expected;
    private final Duration maxAge;

    public BackupWatch(JdbcClient jdbc, SystemAlerts alerts, JobLocks locks,
                       @Value("${app.backups.expected:false}") boolean expected,
                       @Value("${app.backups.max-age:PT26H}") Duration maxAge) {
        this.jdbc = jdbc;
        this.alerts = alerts;
        this.locks = locks;
        this.expected = expected;
        this.maxAge = maxAge;
    }

    public boolean expected() {
        return expected;
    }

    public List<Run> recent(int limit) {
        return jdbc.sql("""
                select id, started_at, finished_at, file_name, size_bytes, status, offsite, message
                from backup_run order by started_at desc limit ?""").param(limit).query(Run.class).list();
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT1H")
    public void hourly() {
        if (expected) {
            locks.run("backup-watch", Duration.ofMinutes(30), this::check);
        }
    }

    /** Raises an alert for each failed run not yet reported, and when the last good backup is too old. */
    public void check() {
        for (Run r : jdbc.sql("""
                select id, started_at, finished_at, file_name, size_bytes, status, offsite, message from backup_run
                where status = 'FAILED' and started_at > now() - interval '2 days' order by started_at""").query(Run.class).list()) {
            alerts.raise("BACKUP_FAILED", "run-" + r.id(), "The database backup of " + r.startedAt().toLocalDate() + " failed",
                    r.message());
        }
        // A run stuck in RUNNING for 6 hours died without saying so.
        jdbc.sql("""
                update backup_run set status = 'FAILED', finished_at = now(), message = 'Stopped without finishing'
                where status = 'RUNNING' and started_at < now() - interval '6 hours'""").update();
        OffsetDateTime last = jdbc.sql("select max(finished_at) from backup_run where status = 'OK' and file_name not like 'restore-test:%' and file_name not like 'restore:%'")
                .query(OffsetDateTime.class).optional().orElse(null);
        if (last == null || last.isBefore(OffsetDateTime.now().minus(maxAge))) {
            alerts.raise("BACKUP_MISSING", "no-recent-backup", last == null
                    ? "No database backup has succeeded yet"
                    : "No database backup has succeeded since " + last.toLocalDate() + " " + last.toLocalTime().withNano(0) + " UTC",
                    "Check the backup container (docker compose logs backup) and the free disk space.");
        }
    }
}
