package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.lifecycle.Onboarding;
import cd.hrpay.tenancy.Tenants;
import cd.hrpay.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** Onboarding: the pre-start link, HR's check, the buddy, the welcome page and the trial reviews. */
class OnboardingIT extends IntegrationTest {

    static final String PDF = "data:application/pdf;base64,"
            + Base64.getEncoder().encodeToString("%PDF-1.4\n% an ID\n".getBytes(StandardCharsets.UTF_8));

    @Autowired
    JdbcClient jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    Onboarding onboarding;

    @Autowired
    Tenants tenants;

    @Test
    void aNewHireFillsTheirFileFromTheLinkAndHrFollowsTheStart() throws Exception {
        MockHttpSession hr = login(HR);
        // A new hire: an account, a permanent contract starting in ten days with a three-month trial.
        String created = mvc.perform(json(post("/api/users").session(login(ADMIN)), newUser("[\"EMPLOYEE\"]")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long me = JsonPath.<Number>read(created, "$.id").longValue();
        String email = JsonPath.read(created, "$.email");
        jdbc.sql("""
                update app_user set password_hash = (select password_hash from app_user where email = ?), must_change_password = false
                where id = ?""").params(EMPLOYEE, me).update();
        long company = jdbc.sql("select company_id from app_user where id = ?").param(me).query(Long.class).single();
        LocalDate start = LocalDate.now().plusDays(10);
        String grades = mvc.perform(get("/api/grades").session(hr)).andReturn().getResponse().getContentAsString();
        long grade = JsonPath.<java.util.List<Number>>read(grades, "$[?(@.code=='M1')].id").getFirst().longValue();
        usdRate(LocalDate.now().minusDays(1).toString(), 2800);
        mvc.perform(json(post("/api/users/" + me + "/contracts").session(hr), """
                {"contractType":"PERMANENT","startDate":"%s","gradeId":%d,"expatriate":false,"negotiatedBasic":800}"""
                .formatted(start, grade))).andExpect(status().isCreated());
        long contract = jdbc.sql("select id from employee_contract where user_id = ? order by start_date desc limit 1").param(me)
                .query(Long.class).single();
        jdbc.sql("""
                insert into contract_terms (contract_id, trial_months) values (?, 3)
                on conflict (contract_id) do update set trial_months = 3""").param(contract).update();

        mvc.perform(json(post("/api/onboarding/" + me).session(login(EMPLOYEE)), """
                {"startOn":"%s"}""".formatted(start))).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/onboarding/" + me).session(hr), """
                {"startOn":"%s"}""".formatted(start))).andExpect(status().isOk())
                .andExpect(jsonPath("$.row.status").value("OPEN"))
                .andExpect(jsonPath("$.reviews[*].kind").value(Matchers.contains("MID", "END")))
                .andExpect(jsonPath("$.reviews[1].dueOn").value(start.plusMonths(3).minusDays(15).toString()))
                .andExpect(jsonPath("$.checklist.items[*].owner").value(Matchers.hasItem("NEW_HIRE")));

        // The link, by email; it works without signing in.
        mvc.perform(post("/api/onboarding/" + me + "/link").session(hr).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.link").value(Matchers.containsString("/welcome-form/")))
                .andExpect(jsonPath("$.linkExpiresOn").value(start.toString()));
        String token = jdbc.sql("select token::text from onboarding where user_id = ?").param(me).query(String.class).single();
        String page = mvc.perform(get("/api/public/onboarding/" + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.startOn").value(start.toString()))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/public/onboarding/not-a-token")).andExpect(status().isNotFound());

        mvc.perform(json(post("/api/public/onboarding/" + token), """
                {"answers":{"maritalStatus":"MARRIED","cnssNumber":"123-NEW","idDocumentNumber":"OP-998877",
                 "family":[{"name":"Bébé KASONGO","relationship":"CHILD","birthDate":"2023-04-01"}]},"submit":true}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.submittedAt").isNotEmpty())
                .andExpect(jsonPath("$.answers.idDocumentNumber").value("OP-998877"));
        // Stored encrypted.
        assertThat(jdbc.sql("select answers from onboarding where user_id = ?").param(me).query(String.class).single())
                .doesNotContain("OP-998877");

        java.util.List<Integer> wanted = JsonPath.read(page, "$.documents[*].id");
        if (!wanted.isEmpty()) {
            long type = wanted.get(0);
            mvc.perform(json(post("/api/public/onboarding/" + token + "/documents"), """
                    {"documentTypeId":%d,"fileName":"id.exe","contentType":"application/x-msdownload","data":"%s"}"""
                    .formatted(type, PDF))).andExpect(status().is(422));
            String sent = mvc.perform(json(post("/api/public/onboarding/" + token + "/documents"), """
                    {"documentTypeId":%d,"fileName":"id.pdf","contentType":"application/pdf","data":"%s"}"""
                    .formatted(type, PDF))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.sent[0].status").value("PENDING"))
                    .andReturn().getResponse().getContentAsString();
            long doc = JsonPath.<Number>read(sent, "$.sent[0].id").longValue();
            mvc.perform(get("/api/onboarding/documents/" + doc + "/file").session(hr)).andExpect(status().isOk());
            mvc.perform(json(post("/api/onboarding/documents/" + doc + "/check").session(hr), """
                    {"accept":true,"expiresOn":"%s"}""".formatted(LocalDate.now().plusYears(5)))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.documents[0].status").value("ACCEPTED"));
            assertThat(jdbc.sql("select count(*) from employee_document where user_id = ? and type_id = ? and deleted_at is null")
                    .params(me, type).query(Integer.class).single()).isPositive();
            mvc.perform(post("/api/public/onboarding/" + token + "/documents/" + doc + "/remove")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.sent[0].status").value("ACCEPTED"));
        }

        mvc.perform(post("/api/onboarding/" + me + "/apply-details").session(hr).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.row.detailsAppliedAt").isNotEmpty());
        assertThat(jdbc.sql("select marital_status from employee_profile where user_id = ?").param(me).query(String.class).single())
                .isEqualTo("MARRIED");
        assertThat(jdbc.sql("select count(*) from dependant where user_id = ? and full_name = 'Bébé KASONGO'").param(me)
                .query(Integer.class).single()).isEqualTo(1);

        // A buddy, from the colleagues of the unit; the new hire cannot be their own.
        mvc.perform(get("/api/onboarding/" + me + "/colleagues").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(Matchers.not(Matchers.hasItem((int) me))));
        long buddy = users.findByEmailIgnoreCase(HR).orElseThrow().getId();
        mvc.perform(json(put("/api/onboarding/" + me + "/buddy").session(hr), """
                {"buddyUserId":%d}""".formatted(me))).andExpect(status().is(422));
        mvc.perform(json(put("/api/onboarding/" + me + "/buddy").session(hr), """
                {"buddyUserId":%d}""".formatted(buddy))).andExpect(status().isOk())
                .andExpect(jsonPath("$.buddyUserId").value((int) buddy));

        // Before day one the welcome page is not there; on day one it is.
        MockHttpSession employee = login(email);
        mvc.perform(get("/api/onboarding/me").session(employee)).andExpect(status().isNoContent());
        jdbc.sql("update onboarding set start_on = current_date where user_id = ?").param(me).update();
        mvc.perform(get("/api/onboarding/me").session(employee)).andExpect(status().isOk())
                .andExpect(jsonPath("$.buddy").isNotEmpty()).andExpect(jsonPath("$.seen").value(false));
        mvc.perform(post("/api/onboarding/me/seen").session(employee).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/onboarding/me").session(employee)).andExpect(jsonPath("$.seen").value(true));
        // After the start date the link no longer works.
        jdbc.sql("update onboarding set token_expires_on = current_date - 1 where user_id = ?").param(me).update();
        mvc.perform(get("/api/public/onboarding/" + token)).andExpect(status().isNotFound());

        // The trial reviews: the END one needs a recommendation, which reaches HR.
        long end = jdbc.sql("select id from trial_review where contract_id = ? and kind = 'END'").param(contract)
                .query(Long.class).single();
        mvc.perform(json(put("/api/trial-reviews/" + end).session(employee), """
                {"rating":5,"recommendation":"CONFIRM"}""")).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/trial-reviews/" + end).session(hr), """
                {"rating":4}""")).andExpect(status().is(422));
        mvc.perform(json(put("/api/trial-reviews/" + end).session(hr), """
                {"rating":4,"strengths":"Rigoureuse","recommendation":"CONFIRM"}""")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
        mvc.perform(get("/api/trial-reviews/mine").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + end + ")].status").value(Matchers.contains("DONE")));

        // Reminders: a step due tomorrow, a review due today, each once.
        jdbc.sql("""
                update employee_checklist_item set due_on = current_date + 1, reminded_on = null, done_at = null
                where checklist_id = (select id from employee_checklist where user_id = ? and kind = 'HIRING' and status = 'OPEN')""")
                .param(me).update();
        jdbc.sql("update trial_review set due_on = current_date, reminded_on = null where contract_id = ? and kind = 'MID'")
                .param(contract).update();
        int first = tenants.callAs(company, onboarding::remind);
        assertThat(first).isPositive();
        assertThat(tenants.callAs(company, onboarding::remind)).isZero();

        mvc.perform(get("/api/onboarding/overview").session(hr)).andExpect(status().isOk());
        mvc.perform(get("/api/onboarding").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].userId").value(Matchers.hasItem((int) me)));
        mvc.perform(post("/api/onboarding/" + me + "/close").session(hr).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.row.status").value("DONE"));
    }
}
