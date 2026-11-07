package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.ops.BackupWatch;
import cd.hrpay.ops.ErrorAlertFilter;
import cd.hrpay.ops.JobLocks;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Phase 9: scheduled jobs on one server, alerts to administrators, the backup watch and the System page. */
class OpsIT extends IntegrationTest {

    @Autowired
    JobLocks locks;

    @Autowired
    BackupWatch backups;

    @Autowired
    ErrorAlertFilter errors;

    @Autowired
    JdbcClient jdbc;

    int alerts(String kind) {
        return jdbc.sql("select coalesce(sum(occurrences), 0) from system_alert where kind = ?").param(kind).query(Integer.class).single();
    }

    int alertEmails() {
        return jdbc.sql("select count(*) from outbox_message where subject like 'HR Pay alert:%'").query(Integer.class).single();
    }

    @Test
    void aJobRunsOnceWhileItsLockIsHeldAndAFailureIsReported() {
        AtomicInteger runs = new AtomicInteger();
        assertThat(locks.run("test-job", Duration.ofMinutes(5), runs::incrementAndGet)).isTrue();
        // A second server firing at the same time finds the lock taken.
        assertThat(locks.run("test-job", Duration.ofMinutes(5), runs::incrementAndGet)).isFalse();
        assertThat(runs).hasValue(1);

        int emails = alertEmails();
        assertThat(locks.run("failing-job", Duration.ofSeconds(0), () -> {
            throw new IllegalStateException("disk full");
        })).isFalse();
        assertThat(jdbc.sql("select last_error from job_lock where name = 'failing-job'").query(String.class).single()).isEqualTo("disk full");
        assertThat(alerts("JOB_FAILED")).isGreaterThanOrEqualTo(1);
        assertThat(alertEmails()).isGreaterThan(emails);

        // The same failure again is counted, not emailed again within the hour.
        int afterFirst = alertEmails();
        locks.run("failing-job", Duration.ofSeconds(0), () -> {
            throw new IllegalStateException("disk full");
        });
        assertThat(alertEmails()).isEqualTo(afterFirst);
        assertThat(jdbc.sql("select occurrences from system_alert where kind = 'JOB_FAILED' and fingerprint = 'failing-job'")
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void theBackupWatchReportsFailedAndMissingBackups() {
        jdbc.sql("delete from backup_run").update();
        backups.check();
        assertThat(alerts("BACKUP_MISSING")).isGreaterThanOrEqualTo(1);

        jdbc.sql("insert into backup_run (file_name, finished_at, status, message) values ('hrpay-x.dump', now(), 'FAILED', 'pg_dump failed: no space left')").update();
        backups.check();
        assertThat(jdbc.sql("select count(*) from system_alert where kind = 'BACKUP_FAILED' and detail like '%no space left%'")
                .query(Integer.class).single()).isEqualTo(1);

        // A restore test is not a backup: it does not count as the last good backup.
        jdbc.sql("delete from system_alert where kind = 'BACKUP_MISSING'").update();
        jdbc.sql("insert into backup_run (file_name, finished_at, status) values ('restore-test: hrpay-x.dump', now(), 'OK')").update();
        backups.check();
        assertThat(alerts("BACKUP_MISSING")).isEqualTo(1);

        jdbc.sql("delete from system_alert where kind = 'BACKUP_MISSING'").update();
        jdbc.sql("insert into backup_run (file_name, finished_at, size_bytes, status) values ('hrpay-y.dump', now(), 1000, 'OK')").update();
        backups.check();
        assertThat(alerts("BACKUP_MISSING")).isZero();
    }

    @Test
    void anUnhandledErrorAlertsTheAdministrators() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/payroll/runs/2026-01/calculate");
        assertThatThrownBy(() -> errors.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            throw new IllegalArgumentException("Unexpected null rate");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.sql("select summary from system_alert where kind = 'ERROR' order by last_at desc limit 1")
                .query(String.class).single()).contains("/api/payroll/runs/2026-01/calculate").contains("Unexpected null rate");
    }

    @Test
    void systemPageForAdministratorsAndHealthProbesForEveryone() throws Exception {
        mvc.perform(get("/api/system/status").session(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").exists())
                .andExpect(jsonPath("$.databaseBytes").isNumber())
                .andExpect(jsonPath("$.jobs").isArray());
        mvc.perform(get("/api/system/status").session(login(HR))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        int code = mvc.perform(get("/actuator/info")).andReturn().getResponse().getStatus();
        assertThat(code).isNotEqualTo(200);
    }
}
