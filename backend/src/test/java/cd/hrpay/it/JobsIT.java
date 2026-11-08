package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.job.JobWorkers;
import cd.hrpay.tenancy.Tenants;
import cd.hrpay.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** Background jobs: the 202 answer and its progress, one job per month, restart recovery and who sees the file. */
class JobsIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Tenants tenants;

    @Autowired
    UserRepository users;

    @Autowired
    JobWorkers workers;

    private long id(String email) {
        return users.findByEmailIgnoreCase(email).orElseThrow().getId();
    }

    /** Follows a job as the page does until it ends. */
    private String await(MockHttpSession s, long job) throws Exception {
        for (int i = 0; i < 600; i++) {
            String j = mvc.perform(get("/api/jobs/" + job).session(s)).andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString();
            String st = JsonPath.read(j, "$.status");
            if (!st.equals("WAITING") && !st.equals("RUNNING")) {
                return j;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("job " + job + " did not end");
    }

    @Test
    void aLongJobAnswers202AndIsFollowedToItsResult() throws Exception {
        usdRate("2026-02-27", 2300);
        MockHttpSession hr = login(HR);
        String started = mvc.perform(post("/api/payroll/runs/2026-02/calculate").session(hr).with(csrf())
                        .header("Prefer", "respond-async"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.job.type").value("payroll.calculate"))
                .andReturn().getResponse().getContentAsString();
        long job = JsonPath.<Number>read(started, "$.job.id").longValue();

        String done = await(hr, job);
        assertThat(JsonPath.<String>read(done, "$.status")).isEqualTo("DONE");
        assertThat(JsonPath.<Integer>read(done, "$.progress")).isEqualTo(100);
        assertThat(JsonPath.<String>read(done, "$.result.run.status")).isEqualTo("CALCULATED");
        assertThat(JsonPath.<String>read(done, "$.requestedBy")).isEqualTo(HR);

        // Listed on the person's My jobs page, not on someone else's; others cannot open it.
        mvc.perform(get("/api/jobs").session(hr)).andExpect(jsonPath("$[?(@.id==" + job + ")]").isNotEmpty());
        MockHttpSession employee = login(EMPLOYEE);
        mvc.perform(get("/api/jobs").session(employee)).andExpect(jsonPath("$[?(@.id==" + job + ")]").isEmpty());
        mvc.perform(get("/api/jobs/" + job).session(employee)).andExpect(status().isNotFound());

        // Every payslip of the month in one zip: only for those who may see all payslips; the download is audited.
        String zip = mvc.perform(post("/api/payroll/runs/2026-02/payslips.zip").session(hr).with(csrf()))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        long zipJob = JsonPath.<Number>read(zip, "$.job.id").longValue();
        String zipDone = await(hr, zipJob);
        assertThat(JsonPath.<String>read(zipDone, "$.status")).isEqualTo("DONE");
        assertThat(JsonPath.<String>read(zipDone, "$.fileName")).isEqualTo("payslips-2026-02.zip");
        byte[] file = mvc.perform(get("/api/jobs/" + zipJob + "/file").session(hr)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(file, 0, 2)).isEqualTo("PK");
        mvc.perform(get("/api/jobs/" + zipJob + "/file").session(employee)).andExpect(status().isNotFound());
        mvc.perform(post("/api/payroll/runs/2026-02/payslips.zip").session(employee).with(csrf())).andExpect(status().isForbidden());
        // Finance may see all payslips too, so may download the file HR made.
        mvc.perform(get("/api/jobs/" + zipJob + "/file").session(login(FINANCE))).andExpect(status().isOk());
        int audited = jdbc.sql("select count(*) from audit_log where action = 'job.file_downloaded' and entity_id = ?")
                .param(String.valueOf(zipJob)).query(Integer.class).single();
        assertThat(audited).isEqualTo(2);
    }

    @Test
    void oneJobPerMonthAndNothingChangesUnderIt() throws Exception {
        usdRate("2025-06-30", 2800);
        long hrId = id(HR);
        // A calculation already waits for this month (another server will take it).
        long waiting = jdbc.sql("""
                insert into background_job (type, job_key, params, permission, language, requested_by, requested_by_email)
                values ('payroll.calculate', 'payroll:2025-06', '{"month":"2025-06"}', 'payroll.run', 'en', ?, ?) returning id""")
                .params(hrId, HR).query(Long.class).single();
        MockHttpSession hr = login(HR);
        mvc.perform(post("/api/payroll/runs/2025-06/calculate").session(hr).with(csrf()).header("Prefer", "respond-async"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.job.id").value(waiting))
                .andExpect(jsonPath("$.job.existing").value(true));
        mvc.perform(post("/api/payroll/runs/2025-06/close").session(hr).with(csrf()).header("Prefer", "respond-async"))
                .andExpect(jsonPath("$.job.id").value(waiting));
        // The payroll cannot be approved or sent back while it is being worked on.
        mvc.perform(post("/api/payroll/runs/2025-06/approve").session(login(FINANCE)).with(csrf())).andExpect(status().is(422));

        // A waiting job can be cancelled, then started again as a new job.
        mvc.perform(post("/api/jobs/" + waiting + "/cancel").session(login(EMPLOYEE)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/jobs/" + waiting + "/cancel").session(hr).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/jobs/" + waiting + "/cancel").session(hr).with(csrf())).andExpect(status().is(422));
        String again = mvc.perform(post("/api/jobs/" + waiting + "/retry").session(hr).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long retried = JsonPath.<Number>read(again, "$.id").longValue();
        assertThat(retried).isNotEqualTo(waiting);
        assertThat(JsonPath.<String>read(await(hr, retried), "$.status")).isEqualTo("DONE");
    }

    @Test
    void aJobWhoseServerStoppedRunsAgainOnceAndTheRequesterIsTold() throws Exception {
        usdRate("2025-07-31", 2800);
        long hrId = id(HR);
        // Taken by a server that stopped 10 minutes ago, in the middle of the job.
        long job = jdbc.sql("""
                insert into background_job (type, job_key, params, permission, language, requested_by, requested_by_email,
                    status, started_at, heartbeat_at, worker, attempts, created_at)
                values ('payroll.calculate', 'payroll:2025-07', '{"month":"2025-07"}', 'payroll.run', 'en', ?, ?,
                    'RUNNING', now() - interval '11 minutes', now() - interval '10 minutes', 'gone', 1, now() - interval '12 minutes')
                returning id""").params(hrId, HR).query(Long.class).single();
        tenants.callAsPlatform(workers::recoverStale);
        MockHttpSession hr = login(HR);
        String done = await(hr, job);
        assertThat(JsonPath.<String>read(done, "$.status")).isEqualTo("DONE");
        assertThat(JsonPath.<Integer>read(done, "$.attempts")).isEqualTo(2);
        // It ended long after the page stopped waiting: the bell says so.
        List<String> kinds = jdbc.sql("select kind from notification where user_id = ? and link = '/jobs'").param(hrId)
                .query(String.class).list();
        assertThat(kinds).contains("JOB_DONE");

        // A job that keeps losing its server fails after three tries.
        long lost = jdbc.sql("""
                insert into background_job (type, params, language, requested_by, requested_by_email, status, heartbeat_at, attempts)
                values ('payroll.calculate', '{"month":"2025-08"}', 'en', ?, ?, 'RUNNING', now() - interval '10 minutes', 3)
                returning id""").params(hrId, HR).query(Long.class).single();
        tenants.callAsPlatform(workers::recoverStale);
        assertThat(jdbc.sql("select status from background_job where id = ?").param(lost).query(String.class).single())
                .isEqualTo("FAILED");
    }

    @Test
    void aBusinessErrorInTheJobIsThePagesError() throws Exception {
        MockHttpSession hr = login(HR);
        String started = mvc.perform(post("/api/payroll/runs/2099-02/calculate").session(hr).with(csrf()).header("Prefer", "respond-async"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String failed = await(hr, JsonPath.<Number>read(started, "$.job.id").longValue());
        assertThat(JsonPath.<String>read(failed, "$.status")).isEqualTo("FAILED");
        assertThat(JsonPath.<Integer>read(failed, "$.errorStatus")).isEqualTo(422);
        assertThat(JsonPath.<String>read(failed, "$.error")).contains("before the month starts");
        // Waiting for it (the default), the page gets the 422 as before.
        mvc.perform(post("/api/payroll/runs/2099-02/calculate").session(hr).with(csrf())).andExpect(status().is(422));
        // The installation's queue on the System page.
        mvc.perform(get("/api/system/jobs").session(login(ADMIN))).andExpect(status().isOk())
                .andExpect(jsonPath("$.failedToday").isNumber());
        mvc.perform(get("/api/system/jobs").session(hr)).andExpect(status().isForbidden());
    }
}
