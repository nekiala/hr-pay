package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.career.ContractFollowup;
import cd.hrpay.representation.StaffRepresentation;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** Trials and fixed-term contracts coming to their end, day workers, and the staff representation. */
class ContractFollowupIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ContractFollowup followup;

    long grade(MockHttpSession s, String code) throws Exception {
        String grades = mvc.perform(get("/api/grades").session(s)).andReturn().getResponse().getContentAsString();
        return JsonPath.<List<Number>>read(grades, "$[?(@.code=='" + code + "')].id").getFirst().longValue();
    }

    long freshEmployee() throws Exception {
        String created = mvc.perform(json(post("/api/users").session(login(ADMIN)), newUser("[\"EMPLOYEE\"]")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(created, "$.id").longValue();
    }

    long contract(MockHttpSession hr, long userId, String type, LocalDate start, LocalDate end) throws Exception {
        String body = end == null
                ? """
                  {"contractType":"%s","startDate":"%s","gradeId":%d,"expatriate":false}""".formatted(type, start, grade(hr, "E4A"))
                : """
                  {"contractType":"%s","startDate":"%s","endDate":"%s","gradeId":%d,"expatriate":false}"""
                        .formatted(type, start, end, grade(hr, "E4A"));
        String saved = mvc.perform(json(post("/api/users/" + userId + "/contracts").session(hr), body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(saved, "$.id").longValue();
    }

    void trial(long contractId, int months) {
        jdbc.sql("insert into contract_terms (contract_id, trial_months) values (?, ?)").params(contractId, months).update();
    }

    @Test
    void aTrialComingToItsEndIsRemindedThenConfirmedOrEnded() throws Exception {
        MockHttpSession hr = login(HR);
        LocalDate today = LocalDate.now();
        long id = freshEmployee();
        LocalDate start = today.minusMonths(3).plusDays(10);
        long c = contract(hr, id, "PERMANENT", start, null);
        trial(c, 3);
        LocalDate due = start.plusMonths(3).minusDays(1);

        String overview = mvc.perform(get("/api/contract-followup").session(hr)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(overview, "$.items[?(@.userId==" + id + ")].kind")).containsExactly("TRIAL");
        assertThat(JsonPath.<List<String>>read(overview, "$.items[?(@.userId==" + id + ")].dueOn")).containsExactly(due.toString());

        // The morning job tells HR once.
        followup.run();
        followup.run();
        assertThat(jdbc.sql("select count(*) from reminder_sent where user_id = ? and kind like 'followup.trial.%'").param(id)
                .query(Integer.class).single()).isEqualTo(1);
        long hrId = jdbc.sql("select id from app_user where email = ?").param(HR).query(Long.class).single();
        assertThat(jdbc.sql("select count(*) from notification where user_id = ? and kind = 'followup' and body like ?")
                .params(hrId, "%" + due.getYear() + "%").query(Integer.class).single()).isPositive();

        String base = "/api/users/" + id + "/contract-followup/" + c;
        // The last day falls within the trial.
        mvc.perform(json(post(base + "/end-trial").session(hr), """
                {"lastDay":"%s","givenBy":"EMPLOYER"}""".formatted(due.plusDays(1))))
                .andExpect(status().is(422));
        mvc.perform(json(post(base + "/confirm").session(hr), "{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.item.decision").value("CONFIRMED"))
                .andExpect(jsonPath("$.letterId").value(Matchers.notNullValue()));
        mvc.perform(json(post(base + "/confirm").session(hr), "{}")).andExpect(status().isConflict());
        mvc.perform(delete(base).param("kind", "TRIAL").session(hr).with(csrf())).andExpect(status().isNoContent());

        // Ended instead: a draft departure, 3 working days' notice after the first 3 days (art. 71).
        LocalDate last = today.plusDays(1);
        mvc.perform(json(post(base + "/end-trial").session(hr), """
                {"lastDay":"%s","givenBy":"EMPLOYER","note":"Not conclusive"}""".formatted(last)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.item.decision").value("ENDED"));
        mvc.perform(get("/api/users/" + id + "/departure").session(hr))
                .andExpect(jsonPath("$.reason").value("END_OF_TRIAL"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.severanceAmount").value(0.0))
                .andExpect(jsonPath("$.noticeEnd").value(Matchers.notNullValue()))
                .andExpect(jsonPath("$.notice").value("PAID"));

        mvc.perform(get("/api/contract-followup").session(login(EMPLOYEE))).andExpect(status().isForbidden());
    }

    @Test
    void aTrialPastItsEndWithNoDecisionIsConfirmedByLaw() throws Exception {
        MockHttpSession hr = login(HR);
        long id = freshEmployee();
        LocalDate start = LocalDate.now().minusMonths(2).minusDays(5);
        long c = contract(hr, id, "PERMANENT", start, null);
        trial(c, 2);
        followup.run();
        mvc.perform(get("/api/users/" + id + "/contract-followup").session(hr))
                .andExpect(jsonPath("$[0].decision").value("CONFIRMED_BY_LAW"))
                .andExpect(jsonPath("$[0].decidedBy").value("system"));
    }

    @Test
    void aFixedTermContractIsRenewedMadePermanentOrLetEnd() throws Exception {
        MockHttpSession hr = login(HR);
        LocalDate today = LocalDate.now();
        LocalDate start = YearMonth.now().minusMonths(6).atDay(1);
        LocalDate end = today.plusDays(20);

        long renewed = freshEmployee();
        long c1 = contract(hr, renewed, "FIXED_TERM", start, end);
        String r = mvc.perform(json(post("/api/users/" + renewed + "/contract-followup/" + c1 + "/renew").session(hr), """
                {"endDate":"%s"}""".formatted(end.plusMonths(6))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.item.decision").value("RENEWED"))
                .andReturn().getResponse().getContentAsString();
        long next = JsonPath.<Number>read(r, "$.contractId").longValue();
        assertThat(jdbc.sql("select contract_type || ' ' || start_date || ' ' || end_date from employee_contract where id = ?")
                .param(next).query(String.class).single())
                .isEqualTo("FIXED_TERM " + end.plusDays(1) + " " + end.plusMonths(6));

        long permanent = freshEmployee();
        long c2 = contract(hr, permanent, "FIXED_TERM", start, end);
        mvc.perform(json(post("/api/users/" + permanent + "/contract-followup/" + c2 + "/permanent").session(hr), "{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.item.decision").value("MADE_PERMANENT"));
        assertThat(jdbc.sql("select contract_type from employee_contract where user_id = ? order by start_date desc limit 1")
                .param(permanent).query(String.class).single()).isEqualTo("PERMANENT");
        // Seniority stays: the hire date is the first contract's start.
        assertThat(jdbc.sql("select hire_date from app_user where id = ?").param(permanent).query(LocalDate.class).single())
                .isEqualTo(start);

        long ending = freshEmployee();
        long c3 = contract(hr, ending, "FIXED_TERM", start, end);
        mvc.perform(json(post("/api/users/" + ending + "/contract-followup/" + c3 + "/let-end").session(hr), "{}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/users/" + ending + "/departure").session(hr))
                .andExpect(jsonPath("$.reason").value("END_OF_CONTRACT"))
                .andExpect(jsonPath("$.lastDay").value(end.toString()));
    }

    @Test
    void aDayWorkerWithTwentyTwoDaysInTwoMonthsIsHiredOnAPermanentContract() throws Exception {
        MockHttpSession hr = login(HR);
        LocalDate today = LocalDate.now();
        long id = freshEmployee();
        contract(hr, id, "DAILY", today.minusDays(50), today.minusDays(1));
        for (int d = 2; d <= 23; d++) {
            jdbc.sql("""
                    insert into clock_event (user_id, type, occurred_at, work_date, source, created_by) values (?, 'IN', now(), ?, 'HR_MANUAL', 'test')""")
                    .params(id, today.minusDays(d)).update();
        }
        mvc.perform(json(post("/api/users/" + id + "/contracts").session(hr), """
                        {"contractType":"DAILY","startDate":"%s","gradeId":%d,"expatriate":false}""".formatted(today, grade(hr, "E4A"))))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("art. 40")));
        contract(hr, id, "PERMANENT", today, null);
    }

    @Test
    void aStaffDelegateIsNotDismissedOrTransferredWithoutTheInspector() throws Exception {
        MockHttpSession hr = login(HR);
        LocalDate today = LocalDate.now();
        long id = freshEmployee();
        contract(hr, id, "PERMANENT", YearMonth.now().minusMonths(14).atDay(1), null);

        mvc.perform(json(post("/api/representation/delegates").session(hr), """
                {"userId":%d,"seat":"TITULAR","unionName":"UNTC","electedOn":"%s"}""".formatted(id, today.minusYears(1))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.item.active").value(true))
                .andExpect(jsonPath("$.item.termEnd").value(today.minusYears(1).plusYears(3).minusDays(1).toString()));
        mvc.perform(get("/api/representation").session(hr))
                .andExpect(jsonPath("$.titulars").value(Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.alerts[*]").value(Matchers.hasItem(Matchers.containsString("art. 157"))));
        mvc.perform(get("/api/representation").session(login(EMPLOYEE))).andExpect(status().isForbidden());

        // A transfer needs the inspector's decision.
        long unit = jdbc.sql("select id from org_unit where code = 'DAF'").query(Long.class).single();
        mvc.perform(json(post("/api/users/" + id + "/career").session(hr), """
                        {"kind":"TRANSFER","effectiveOn":"%s","unitId":%d}""".formatted(YearMonth.now().plusMonths(1).atDay(1), unit)))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("036/95")));

        // A dismissal too, when it is confirmed.
        mvc.perform(json(put("/api/users/" + id + "/departure").session(hr), """
                {"lastDay":"%s","reason":"DISMISSAL","noticeDate":"%s"}""".formatted(today.plusDays(20), today)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/users/" + id + "/departure/confirm").session(hr).with(csrf()))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("inspector")));
        mvc.perform(json(post("/api/representation/authorisations").session(hr), """
                {"userId":%d,"action":"DISMISSAL","decidedOn":"%s","reference":"IT/KIN/2026/114"}""".formatted(id, today)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/users/" + id + "/departure/confirm").session(hr).with(csrf())).andExpect(status().isOk());

        // Hours of the mandate: above 35 in a month is flagged.
        mvc.perform(json(post("/api/representation/hours").session(hr), """
                {"userId":%d,"onDay":"%s","hours":12}""".formatted(id, today.withDayOfMonth(1)))).andExpect(status().isCreated());
        mvc.perform(json(post("/api/representation/hours").session(hr), """
                {"userId":%d,"onDay":"%s","hours":12}""".formatted(id, today.withDayOfMonth(2)))).andExpect(status().isCreated());
        mvc.perform(json(post("/api/representation/hours").session(hr), """
                {"userId":%d,"onDay":"%s","hours":12}""".formatted(id, today.withDayOfMonth(3))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warnings[0]").value(Matchers.containsString("35")));

        // A meeting's minutes go to the inspector within eight days.
        mvc.perform(json(post("/api/representation/events").session(hr), """
                {"kind":"MEETING","heldOn":"%s","note":"Quarterly meeting"}""".formatted(today)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warnings[0]").value(Matchers.containsString("art. 37")));
        mvc.perform(json(post("/api/representation/events").session(hr), """
                {"kind":"ELECTION","heldOn":"%s","announcedOn":"%s"}""".formatted(today, today.minusDays(10))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warnings[0]").value(Matchers.containsString("3 weeks")));
    }

    @Test
    void theLawAsksForDelegatesByHeadcount() {
        assertThat(StaffRepresentation.requiredSeats("CD", 9, 10)).isZero();
        assertThat(StaffRepresentation.requiredSeats("CD", 12, 10)).isEqualTo(1);
        assertThat(StaffRepresentation.requiredSeats("CD", 120, 10)).isEqualTo(5);
        assertThat(StaffRepresentation.requiredSeats("CD", 2500, 10)).isEqualTo(10);
        assertThat(StaffRepresentation.requiredSeats("CG", 50, 7)).isNull();
    }
}
