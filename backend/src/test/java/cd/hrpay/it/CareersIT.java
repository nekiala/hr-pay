package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** The public careers page (apply, withdraw) and the job offers staff apply to from the app. */
class CareersIT extends IntegrationTest {

    static final String MANAGER = "manager@demo.hrpay.cd";
    static final String PDF = "data:application/pdf;base64,"
            + Base64.getEncoder().encodeToString("%PDF-1.4\n% a CV\n".getBytes(StandardCharsets.UTF_8));

    @Autowired
    JdbcClient jdbc;

    @Autowired
    UserRepository users;

    long job() {
        return jdbc.sql("select id from occupation where active order by id limit 1").query(Long.class).single();
    }

    /** An open vacancy in a unit, seen by whom the visibility says. */
    long vacancy(MockHttpSession hr, long unit, String visibility) throws Exception {
        String saved = mvc.perform(json(post("/api/recruitment/vacancies").session(hr), """
                {"unitId":%d,"occupationId":%d,"posts":1,"title":"Comptable %s","adText":"## Mission\\nTenir les comptes.",
                 "contractType":"PERMANENT","visibility":"%s","internalDays":5}"""
                .formatted(unit, job(), visibility, visibility))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = JsonPath.<Number>read(saved, "$.row.id").longValue();
        mvc.perform(json(post("/api/recruitment/vacancies/" + id + "/status").session(hr), """
                {"status":"OPEN"}""")).andExpect(status().isOk());
        return id;
    }

    long unit() {
        return jdbc.sql("select id from org_unit where valid_to is null order by id limit 1").query(Long.class).single();
    }

    String apply(String slug, long vacancy, String email, boolean consent, String website) {
        return """
                {"firstName":"Aline","lastName":"KASONGO","email":"%s","phone":"+243 99 %s","coverLetter":"Motivée",
                 "consent":%s,"cvName":"cv.pdf","cvType":"application/pdf","cv":"%s","channel":"WhatsApp!","website":"%s"}"""
                .formatted(email, String.valueOf(1000000 + Math.abs(email.hashCode() % 8999999)), consent, PDF, website);
    }

    @Test
    void thePublicPageListsPublicOffersAndTakesApplicationsThatCanBeWithdrawn() throws Exception {
        MockHttpSession hr = login(HR);
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(json(put("/api/recruitment/careers").session(hr), """
                {"slug":"Bad Name!","enabled":true}""")).andExpect(status().is(422));
        mvc.perform(json(put("/api/recruitment/careers").session(hr), """
                {"enabled":true}""")).andExpect(status().is(422));
        mvc.perform(json(put("/api/recruitment/careers").session(login(EMPLOYEE)), """
                {"slug":"%s","enabled":true}""".formatted(slug))).andExpect(status().isForbidden());
        // Off: nobody sees the page.
        mvc.perform(json(put("/api/recruitment/careers").session(hr), """
                {"slug":"%s","enabled":false}""".formatted(slug))).andExpect(status().isOk());
        mvc.perform(get("/api/public/careers/" + slug)).andExpect(status().isNotFound());
        mvc.perform(json(put("/api/recruitment/careers").session(hr), """
                {"slug":"%s","enabled":true}""".formatted(slug))).andExpect(status().isOk())
                .andExpect(jsonPath("$.link").value(Matchers.endsWith("/careers/" + slug)));

        long open = vacancy(hr, unit(), "PUBLIC");
        long internal = vacancy(hr, unit(), "INTERNAL");
        long first = vacancy(hr, unit(), "INTERNAL_FIRST");
        mvc.perform(get("/api/public/careers/" + slug.toUpperCase())).andExpect(status().isOk())
                .andExpect(jsonPath("$.offers[*].id").value(Matchers.hasItem((int) open)))
                .andExpect(jsonPath("$.offers[*].id").value(Matchers.not(Matchers.hasItem((int) internal))))
                .andExpect(jsonPath("$.offers[*].id").value(Matchers.not(Matchers.hasItem((int) first))));
        mvc.perform(get("/api/public/careers/" + slug + "/offers/" + open)).andExpect(status().isOk())
                .andExpect(jsonPath("$.adText").value(Matchers.containsString("Tenir les comptes")));
        mvc.perform(get("/api/public/careers/" + slug + "/offers/" + internal)).andExpect(status().isNotFound());
        // Internal first: public once its internal days have passed.
        jdbc.sql("update vacancy set opened_on = opened_on - 5 where id = ?").param(first).update();
        mvc.perform(get("/api/public/careers/" + slug + "/offers/" + first)).andExpect(status().isOk());

        String email = "pub-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String url = "/api/public/careers/" + slug + "/offers/" + open + "/apply";
        mvc.perform(json(post(url), apply(slug, open, email, false, ""))).andExpect(status().is(422));
        // A robot filling the hidden field is thanked, and nothing is saved.
        mvc.perform(json(post(url), apply(slug, open, "bot-" + email, true, "http://spam"))).andExpect(status().isNoContent());
        assertThat(jdbc.sql("select count(*) from candidate where email = ?").param("bot-" + email).query(Integer.class).single()).isZero();
        mvc.perform(json(post(url), apply(slug, open, email, true, ""))).andExpect(status().isNoContent());
        mvc.perform(json(post(url), apply(slug, open, email, true, ""))).andExpect(status().isConflict());

        long app = jdbc.sql("select a.id from application a join candidate c on c.id = a.candidate_id where c.email = ?")
                .param(email).query(Long.class).single();
        mvc.perform(get("/api/recruitment/applications/" + app).session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.row.source").value("CAREERS"))
                .andExpect(jsonPath("$.channel").value("whatsapp"))
                .andExpect(jsonPath("$.coverLetter").value("Motivée"))
                .andExpect(jsonPath("$.row.hasCv").value(true));

        String token = jdbc.sql("select withdraw_token::text from application where id = ?").param(app).query(String.class).single();
        mvc.perform(get("/api/public/careers/withdraw/" + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Comptable PUBLIC"));
        mvc.perform(post("/api/public/careers/withdraw/" + token)).andExpect(status().isNoContent());
        assertThat(jdbc.sql("select count(*) from candidate where email = ?").param(email).query(Integer.class).single()).isZero();
        mvc.perform(get("/api/public/careers/withdraw/" + token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/careers/withdraw/not-a-token")).andExpect(status().isNotFound());
    }

    @Test
    void staffApplyInsideTheAppAndWinningMovesThemInsteadOfHiringThemAgain() throws Exception {
        usdRate(LocalDate.now().minusDays(1).toString(), 2800);
        MockHttpSession hr = login(HR);
        MockHttpSession employee = login(EMPLOYEE);
        long me = users.findByEmailIgnoreCase(EMPLOYEE).orElseThrow().getId();
        Long myUnit = jdbc.sql("select unit_id from org_assignment where user_id = ? and valid_to is null").param(me)
                .query(Long.class).optional().orElse(null);
        long other = jdbc.sql("select id from org_unit where valid_to is null and id <> coalesce(?, 0) order by id desc limit 1")
                .param(myUnit).query(Long.class).single();
        long vacancy = vacancy(hr, other, "INTERNAL");

        mvc.perform(get("/api/job-offers").session(employee)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + vacancy + ")].applicationId").value(Matchers.contains(Matchers.nullValue())));
        mvc.perform(json(post("/api/job-offers/" + vacancy + "/apply").session(employee), """
                {"note":"Je connais le poste."}""")).andExpect(status().isOk())
                .andExpect(jsonPath("$.offer.stage").value("APPLIED"));
        mvc.perform(json(post("/api/job-offers/" + vacancy + "/apply").session(employee), "{}")).andExpect(status().isConflict());
        mvc.perform(post("/api/job-offers/" + vacancy + "/withdraw").session(employee).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.offer.stage").value("WITHDRAWN"));
        String again = mvc.perform(json(post("/api/job-offers/" + vacancy + "/apply").session(employee), "{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.offer.stage").value("APPLIED"))
                .andReturn().getResponse().getContentAsString();
        long app = JsonPath.<Number>read(again, "$.offer.applicationId").longValue();

        // HR sees where the employee stands; the manager hears of it only at the shortlist.
        mvc.perform(get("/api/recruitment/applications/" + app).session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.row.source").value("INTERNAL"))
                .andExpect(jsonPath("$.internal.userId").value((int) me))
                .andExpect(jsonPath("$.internal.managerToldAt").value(Matchers.nullValue()));
        mvc.perform(json(post("/api/recruitment/applications/" + app + "/stage").session(hr), """
                {"stage":"SCREENED"}""")).andExpect(status().isOk())
                .andExpect(jsonPath("$.internal.managerToldAt").value(Matchers.notNullValue()));

        // The same grade: the move is a transfer, with a new job if the vacancy's differs.
        long grade = jdbc.sql("select grade_id from employee_contract where user_id = ? order by start_date desc limit 1")
                .param(me).query(Long.class).single();
        LocalDate start = LocalDate.now().plusMonths(1).withDayOfMonth(1);
        mvc.perform(json(put("/api/recruitment/applications/" + app + "/offer").session(hr), """
                {"gradeId":%d,"start":"%s"}""".formatted(grade, start))).andExpect(status().isOk());
        mvc.perform(json(post("/api/recruitment/applications/" + app + "/offer/answer").session(hr), """
                {"answer":"ACCEPTED"}""")).andExpect(status().isOk());
        mvc.perform(json(post("/api/recruitment/applications/" + app + "/hire").session(hr), "{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value((int) me));

        Long move = jdbc.sql("select career_move_id from application where id = ?").param(app).query(Long.class).single();
        assertThat(move).isNotNull();
        assertThat(jdbc.sql("select to_unit_id from career_move where id = ?").param(move).query(Long.class).single()).isEqualTo(other);
        assertThat(jdbc.sql("select count(*) from app_user where email = ?").param(EMPLOYEE).query(Integer.class).single()).isEqualTo(1);
        mvc.perform(get("/api/recruitment/vacancies/" + vacancy).session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.row.status").value("FILLED"));
        mvc.perform(get("/api/job-offers").session(employee)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + vacancy + ")]").isEmpty());
    }
}
