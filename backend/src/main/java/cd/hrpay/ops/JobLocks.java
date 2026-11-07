package cd.hrpay.ops;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Runs a scheduled job on one server only, when several API servers share the database, and reports a failure to
 * the administrators. A server takes the lock by moving locked_until forward; a server that dies frees it when that
 * time passes.
 */
@Service
public class JobLocks {

    private static final Logger log = LoggerFactory.getLogger(JobLocks.class);
    private static final String ME = ManagementFactory.getRuntimeMXBean().getName();

    public record Job(String name, java.time.OffsetDateTime lockedAt, String lockedBy, java.time.OffsetDateTime lastOkAt,
                      String lastError, java.time.OffsetDateTime lastErrorAt) {}

    private final JdbcClient jdbc;
    private final SystemAlerts alerts;

    public JobLocks(JdbcClient jdbc, SystemAlerts alerts) {
        this.jdbc = jdbc;
        this.alerts = alerts;
    }

    /**
     * Runs the job unless another server holds its lock. {@code hold} is the longest the job may take; it also keeps
     * a quick job from running twice when two servers fire at the same minute.
     */
    public boolean run(String name, Duration hold, Runnable job) {
        jdbc.sql("insert into job_lock (name) values (?) on conflict do nothing").param(name).update();
        int taken = jdbc.sql("""
                update job_lock set locked_until = now() + make_interval(secs => :secs), locked_at = now(), locked_by = :me
                where name = :name and locked_until <= now()""")
                .param("secs", hold.toSeconds()).param("me", ME).param("name", name).update();
        if (taken == 0) {
            log.debug("Job {} is running or ran on another server", name);
            return false;
        }
        try {
            job.run();
            jdbc.sql("update job_lock set last_ok_at = now() where name = ?").param(name).update();
            return true;
        } catch (RuntimeException e) {
            log.error("Job {} failed", name, e);
            jdbc.sql("update job_lock set last_error = :error, last_error_at = now() where name = :name")
                    .param("error", SystemAlerts.cut(String.valueOf(e.getMessage()), 1000)).param("name", name).update();
            alerts.raise("JOB_FAILED", name, "The scheduled job \"" + name + "\" failed: " + e.getMessage(), ErrorAlertFilter.trace(e));
            return false;
        }
    }

    public List<Job> jobs() {
        return jdbc.sql("select name, locked_at, locked_by, last_ok_at, last_error, last_error_at from job_lock order by name")
                .query(Job.class).list();
    }
}
