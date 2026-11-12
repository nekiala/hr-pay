package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.recruitment.Recruitment;
import cd.hrpay.tenancy.Tenants;
import cd.hrpay.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** A request to hire, its vacancy and ad check, a candidate through the interview and offer, and the hire. */
class RecruitmentIT extends IntegrationTest {

    static final String MANAGER = "manager@demo.hrpay.cd";
    static final String PDF = "data:application/pdf;base64,"
            + Base64.getEncoder().encodeToString("%PDF-1.4\n% a CV\n".getBytes(StandardCharsets.UTF_8));

    @Autowired
    JdbcClient jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    Recruitment recruitment;

    @Autowired
    Tenants tenants;

    long unit() {
        return jdbc.sql("select id from org_unit where valid_to is null order by id limit 1").query(Long.class).single();
    }

    long job() {
        return jdbc.sql("select id from occupation where active order by id limit 1").query(Long.class).single();
    }

    long grade(MockHttpSession hr) throws Exception {
        String grades = mvc.perform(get("/api/grades").session(hr)).andReturn().getResponse().getContentAsString();
        return JsonPath.<List<Number>>read(grades, "$[?(@.code=='E3A')].id").getFirst().longValue();
    }

    /** An approved request, whose draft vacancy is returned (its id). */
    long vacancy(MockHttpSession hr, int posts) throws Exception {
        String asked = mvc.perform(json(post("/api/recruitment/requests").session(hr), """
                {"unitId":%d,"occupationId":%d,"posts":%d,"reason":"NEW_POST","contractType":"PERMANENT"}"""
                .formatted(unit(), job(), posts))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING")).andReturn().getResponse().getContentAsString();
        long request = JsonPath.<Number>read(asked, "$.id").longValue();
        String decided = mvc.perform(json(post("/api/recruitment/requests/" + request + "/decision").session(hr), """
                {"approve":true}""")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(decided, "$.vacancyId").longValue();
    }

    long candidate(MockHttpSession hr, long vacancy, String email, LocalDate born) throws Exception {
        String added = mvc.perform(json(post("/api/recruitment/vacancies/" + vacancy + "/candidates").session(hr), """
                {"firstName":"Grace","lastName":"MBUYI","email":"%s","phone":"+243 81 %s","birthDate":"%s",
                 "gender":"FEMALE","nationalityCode":"CD","cvName":"cv.pdf","cvType":"application/pdf","cv":"%s","source":"REFERRAL"}"""
                .formatted(email, String.valueOf(1000000 + Math.abs(email.hashCode() % 8999999)), born, PDF))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.row.stage").value("APPLIED")).andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(added, "$.row.id").longValue();
    }

    @Test
    void aRequestBecomesAVacancyAndTheCandidateIsHiredFromTheAcceptedOffer() throws Exception {
        usdRate(LocalDate.now().minusDays(1).toString(), 2800);
        MockHttpSession hr = login(HR);
        MockHttpSession manager = login(MANAGER);
        // An employee who heads nothing cannot ask to hire.
        mvc.perform(json(post("/api/recruitment/requests").session(login(EMPLOYEE)), """
                {"unitId":%d,"occupationId":%d,"posts":1,"reason":"NEW_POST","contractType":"PERMANENT"}"""
                .formatted(unit(), job()))).andExpect(status().isForbidden());

        long vacancy = vacancy(hr, 1);
        mvc.perform(get("/api/recruitment/vacancies/" + vacancy).session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.row.status").value("DRAFT"))
                .andExpect(jsonPath("$.adText").value(Matchers.containsString("##")));

        // The ad check flags a sex and an age criterion.
        mvc.perform(json(post("/api/recruitment/ad-check").session(hr), """
                {"title":"Comptable","adText":"Nous cherchons une femme de moins de 30 ans, célibataire."}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].ground").value(Matchers.hasItems("SEX", "AGE", "MARITAL")));

        mvc.perform(json(post("/api/recruitment/vacancies/" + vacancy + "/status").session(hr), """
                {"status":"OPEN"}""")).andExpect(status().isOk()).andExpect(jsonPath("$.row.status").value("OPEN"));
        String email = "cand-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        long app = candidate(hr, vacancy, email, LocalDate.now().minusYears(30));
        // The same person cannot apply twice to the same vacancy.
        mvc.perform(json(post("/api/recruitment/vacancies/" + vacancy + "/candidates").session(hr), """
                {"firstName":"Grace","lastName":"MBUYI","email":"%s"}""".formatted(email))).andExpect(status().isConflict());
        // A file that is not a PDF or Word document is refused.
        mvc.perform(json(post("/api/recruitment/vacancies/" + vacancy + "/candidates").session(hr), """
                {"firstName":"Jo","lastName":"KABEYA","email":"jo-%s","cvType":"application/pdf","cv":"aGVsbG8="}"""
                .formatted(email))).andExpect(status().is(422));

        // An interview with the manager on the panel; only the panel (or HR) scores it.
        long managerId = users.findByEmailIgnoreCase(MANAGER).orElseThrow().getId();
        String planned = mvc.perform(json(post("/api/recruitment/applications/" + app + "/interviews").session(hr), """
                {"startsAt":"%sT09:00:00Z","place":"Siège","panel":[%d]}""".formatted(LocalDate.now().plusDays(1), managerId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.row.stage").value("INTERVIEW"))
                .andReturn().getResponse().getContentAsString();
        long interview = JsonPath.<Number>read(planned, "$.interviews[0].id").longValue();
        String skill = JsonPath.read(planned, "$.skills[0]");
        mvc.perform(json(put("/api/recruitment/interviews/" + interview + "/scorecard").session(login(EMPLOYEE)), """
                {"scores":[{"skill":"%s","score":4}],"overall":4,"recommendation":"YES"}""".formatted(skill)))
                .andExpect(status().isForbidden());
        mvc.perform(json(put("/api/recruitment/interviews/" + interview + "/scorecard").session(manager), """
                {"scores":[{"skill":"%s","score":4}],"overall":4,"recommendation":"YES","comment":"Solid"}""".formatted(skill)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.interviews[0].scorecards[0].overall").value(4));
        mvc.perform(get("/api/recruitment/interviews/mine").session(manager)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.applicationId==" + app + ")].scored").value(Matchers.contains(true)));

        // The offer, then the hire once accepted.
        long grade = grade(hr);
        LocalDate start = LocalDate.now().plusMonths(1).withDayOfMonth(1);
        mvc.perform(json(put("/api/recruitment/applications/" + app + "/offer").session(hr), """
                {"gradeId":%d,"net":900,"start":"%s","trialMonths":7}""".formatted(grade, start))).andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/recruitment/applications/" + app + "/offer").session(hr), """
                {"gradeId":%d,"net":900,"start":"%s","trialMonths":3}""".formatted(grade, start))).andExpect(status().isOk())
                .andExpect(jsonPath("$.row.stage").value("OFFER"))
                .andExpect(jsonPath("$.offer.body").value(Matchers.containsString("E3A")));
        mvc.perform(get("/api/recruitment/applications/" + app + "/offer.pdf").session(hr)).andExpect(status().isOk());
        mvc.perform(json(post("/api/recruitment/applications/" + app + "/hire").session(hr), "{}")).andExpect(status().isConflict());
        mvc.perform(json(post("/api/recruitment/applications/" + app + "/offer/answer").session(hr), """
                {"answer":"ACCEPTED"}""")).andExpect(status().isOk()).andExpect(jsonPath("$.canHire").value(true));
        String hired = mvc.perform(json(post("/api/recruitment/applications/" + app + "/hire").session(hr), """
                {"registrationNo":"R-%s"}""".formatted(UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long userId = JsonPath.<Number>read(hired, "$.userId").longValue();

        assertThat(jdbc.sql("select email from app_user where id = ?").param(userId).query(String.class).single()).isEqualTo(email);
        assertThat(jdbc.sql("select start_date from employee_contract where user_id = ?").param(userId).query(LocalDate.class).single())
                .isEqualTo(start);
        assertThat(jdbc.sql("""
                select t.trial_months from contract_terms t join employee_contract c on c.id = t.contract_id where c.user_id = ?""")
                .param(userId).query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("select count(*) from employee_checklist where user_id = ? and kind = 'HIRING' and status = 'OPEN'")
                .param(userId).query(Integer.class).single()).isEqualTo(1);
        mvc.perform(get("/api/recruitment/vacancies/" + vacancy).session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.row.status").value("FILLED"))
                .andExpect(jsonPath("$.applications[0].stage").value("HIRED"));
        mvc.perform(get("/api/recruitment/overview").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.hiredThisYear").value(Matchers.greaterThanOrEqualTo(1)));
        mvc.perform(get("/api/recruitment/overview").session(manager)).andExpect(status().isForbidden());
    }

    @Test
    void ageRulesBlockTheHireAndClosedVacanciesTellAndLaterForgetTheirCandidates() throws Exception {
        MockHttpSession hr = login(HR);
        long vacancy = vacancy(hr, 2);
        mvc.perform(json(post("/api/recruitment/vacancies/" + vacancy + "/status").session(hr), """
                {"status":"OPEN"}""")).andExpect(status().isOk());
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long minor = candidate(hr, vacancy, "minor-" + suffix + "@example.com", LocalDate.now().minusYears(16));
        long child = candidate(hr, vacancy, "child-" + suffix + "@example.com", LocalDate.now().minusYears(14));
        long waiting = candidate(hr, vacancy, "wait-" + suffix + "@example.com", LocalDate.now().minusYears(25));

        long grade = grade(hr);
        LocalDate start = LocalDate.now().plusDays(10);
        for (long app : List.of(minor, child)) {
            mvc.perform(json(put("/api/recruitment/applications/" + app + "/offer").session(hr), """
                    {"gradeId":%d,"start":"%s"}""".formatted(grade, start))).andExpect(status().isOk());
            mvc.perform(json(post("/api/recruitment/applications/" + app + "/offer/answer").session(hr), """
                    {"answer":"ACCEPTED"}""")).andExpect(status().isOk()).andExpect(jsonPath("$.canHire").value(false));
        }
        mvc.perform(get("/api/recruitment/applications/" + child).session(hr))
                .andExpect(jsonPath("$.warnings[?(@.code=='UNDER_15')].level").value(Matchers.contains("BLOCK")));
        mvc.perform(json(post("/api/recruitment/applications/" + child + "/hire").session(hr), "{}")).andExpect(status().is(422));
        mvc.perform(json(post("/api/recruitment/applications/" + minor + "/hire").session(hr), "{}")).andExpect(status().is(422));
        // With the waiver of the Président du Tribunal de paix, a 16-year-old can be hired.
        mvc.perform(json(post("/api/recruitment/applications/" + minor + "/hire").session(hr), """
                {"minorWaiverOn":"%s","registrationNo":"R-%s"}""".formatted(LocalDate.now(), suffix))).andExpect(status().isOk());

        // Closing the vacancy turns down those still in progress; their files are kept 12 months.
        mvc.perform(json(post("/api/recruitment/vacancies/" + vacancy + "/status").session(hr), """
                {"status":"CLOSED"}""")).andExpect(status().isOk())
                .andExpect(jsonPath("$.applications[?(@.id==" + waiting + ")].stage").value(Matchers.contains("REJECTED")));
        long waitingCandidate = jdbc.sql("select candidate_id from application where id = ?").param(waiting).query(Long.class).single();
        assertThat(jdbc.sql("select delete_after from candidate where id = ?").param(waitingCandidate).query(LocalDate.class).single())
                .isAfter(LocalDate.now().plusMonths(11));
        long company = jdbc.sql("select company_id from candidate where id = ?").param(waitingCandidate).query(Long.class).single();
        jdbc.sql("update candidate set delete_after = ? where id = ?").params(LocalDate.now().minusDays(1), waitingCandidate).update();
        tenants.runAs(company, recruitment::purge);
        assertThat(jdbc.sql("select count(*) from candidate where id = ?").param(waitingCandidate).query(Integer.class).single()).isZero();
        // The hired person's file stays.
        long hiredCandidate = jdbc.sql("select candidate_id from application where id = ?").param(minor).query(Long.class).single();
        assertThat(jdbc.sql("select count(*) from candidate where id = ?").param(hiredCandidate).query(Integer.class).single()).isEqualTo(1);
    }
}
