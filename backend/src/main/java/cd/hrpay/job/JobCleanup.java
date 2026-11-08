package cd.hrpay.job;

import cd.hrpay.ops.JobLocks;
import cd.hrpay.tenancy.Tenants;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Files made by jobs are kept 7 days; the jobs themselves 90 days. What the jobs did (a payroll) stays. */
@Component
class JobCleanup {

    private static final Logger log = LoggerFactory.getLogger(JobCleanup.class);

    private final JdbcClient jdbc;
    private final JobLocks locks;
    private final Tenants tenants;

    JobCleanup(JdbcClient jdbc, JobLocks locks, Tenants tenants) {
        this.jdbc = jdbc;
        this.locks = locks;
        this.tenants = tenants;
    }

    @Scheduled(cron = "0 20 2 * * *", zone = "Africa/Kinshasa")
    void scheduled() {
        locks.run("jobs.cleanup", Duration.ofMinutes(30), this::clean);
    }

    void clean() {
        tenants.callAsPlatform(() -> {
            int files = jdbc.sql("""
                    delete from background_job_file f using background_job j
                    where j.id = f.job_id and j.status not in ('WAITING', 'RUNNING') and f.created_at < now() - interval '7 days'""")
                    .update();
            int old = jdbc.sql("delete from background_job where status not in ('WAITING', 'RUNNING') and created_at < now() - interval '90 days'")
                    .update();
            if (files + old > 0) {
                log.info("Background jobs: {} file(s) and {} old job(s) deleted", files, old);
            }
            return null;
        });
    }
}
