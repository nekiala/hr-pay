package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.health.HealthJobs;
import cd.hrpay.health.SafetyCommittee;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** Medical visits, the work accident register and the health and safety committee. */
class HealthSafetyIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    HealthJobs jobs;

    long freshEmployee() throws Exception {
        String created = mvc.perform(json(post("/api/users").session(login(ADMIN)), newUser("[\"EMPLOYEE\"]")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(created, "$.id").longValue();
    }

    long contract(MockHttpSession hr, long userId, LocalDate start) throws Exception {
        String grades = mvc.perform(get("/api/grades").session(hr)).andReturn().getResponse().getContentAsString();
        long grade = JsonPath.<List<Number>>read(grades, "$[?(@.code=='E4A')].id").getFirst().longValue();
        String saved = mvc.perform(json(post("/api/users/" + userId + "/contracts").session(hr), """
                {"contractType":"PERMANENT","startDate":"%s","gradeId":%d,"expatriate":false}""".formatted(start, grade)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(saved, "$.id").longValue();
    }

    long userId(String email) {
        return jdbc.sql("select id from app_user where email = ?").param(email).query(Long.class).single();
    }

    @Test
    void aHiringVisitIsDuePlannedAndRecordedWithItsResultOnly() throws Exception {
        MockHttpSession hr = login(HR);
        LocalDate today = LocalDate.now();
        long id = freshEmployee();
        long c = contract(hr, id, today.minusDays(10));

        String page = mvc.perform(get("/api/health/visits").session(hr)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(page, "$.due[?(@.userId==" + id + ")].kind")).containsExactly("HIRING");
        assertThat(JsonPath.<List<String>>read(page, "$.due[?(@.userId==" + id + ")].dueOn"))
                .containsExactly(today.minusDays(10).toString());

        // Planned: the employee is told.
        String planned = mvc.perform(json(post("/api/health/visits").session(hr), """
                {"userId":%d,"kind":"HIRING","dueOn":"%s","note":"Clinique Ngaliema, 8 h"}""".formatted(id, today.plusDays(2))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long visit = JsonPath.<Number>read(planned, "$.item.id").longValue();
        assertThat(jdbc.sql("select count(*) from notification where user_id = ? and kind = 'medical.planned'").param(id)
                .query(Integer.class).single()).isEqualTo(1);

        // Restrictions are written when the result has some; the date of a visit that took place is not in the future.
        mvc.perform(json(post("/api/health/visits/" + visit + "/result").session(hr), """
                {"visitedOn":"%s","result":"FIT_RESTRICTED"}""".formatted(today))).andExpect(status().is(422));
        mvc.perform(json(post("/api/health/visits/" + visit + "/result").session(hr), """
                {"visitedOn":"%s","result":"FIT"}""".formatted(today.plusDays(1)))).andExpect(status().is(422));
        mvc.perform(json(post("/api/health/visits/" + visit + "/result").session(hr), """
                {"visitedOn":"%s","doctor":"Dr Mbuyi","result":"FIT"}""".formatted(today)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.result").value("FIT"))
                .andExpect(jsonPath("$.item.validUntil").value(today.plusMonths(12).minusDays(1).toString()));
        // The contract's medical fitness is filled.
        assertThat(jdbc.sql("select medical_fit_on from contract_terms where contract_id = ?").param(c).query(LocalDate.class)
                .single()).isEqualTo(today);
        // Next: the yearly visit when the certificate ends.
        page = mvc.perform(get("/api/health/visits").param("days", "400").session(hr)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(page, "$.due[?(@.userId==" + id + ")].kind")).containsExactly("YEARLY");
        assertThat(JsonPath.<List<String>>read(page, "$.due[?(@.userId==" + id + ")].dueOn"))
                .containsExactly(today.plusMonths(12).toString());

        // The clinic's list prints.
        mvc.perform(get("/api/health/visits/due-list").param("format", "pdf").session(hr))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF));
        // An employee does not see the register.
        mvc.perform(get("/api/health/visits").session(login(EMPLOYEE))).andExpect(status().isForbidden());
    }

    @Test
    void aLongSickLeavePlansTheReturnToWorkVisit() throws Exception {
        MockHttpSession hr = login(HR);
        LocalDate today = LocalDate.now();
        long id = freshEmployee();
        contract(hr, id, today.minusYears(1));
        long sick = jdbc.sql("select id from leave_type where code = 'SICK'").query(Long.class).single();
        long leave = jdbc.sql("""
                insert into leave_request (user_id, leave_type_id, start_date, end_date, working_days, status, requested_by)
                values (?, ?, ?, ?, 25, 'APPROVED', 'test') returning id""")
                .params(id, sick, today.minusDays(33), today.plusDays(2)).query(Long.class).single();
        jobs.run();
        jobs.run();
        assertThat(jdbc.sql("select count(*) from medical_visit where leave_request_id = ? and kind = 'RETURN' and due_on = ?")
                .params(leave, today.plusDays(3)).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void anAccidentIsReportedRecordedWithItsLeaveAndDeclared() throws Exception {
        MockHttpSession hr = login(HR);
        LocalDate today = LocalDate.now();
        long id = freshEmployee();
        contract(hr, id, today.minusYears(2));
        // The employee's unit head (set by hand) reports it; an employee cannot report for someone outside their team.
        jdbc.sql("""
                insert into user_schedule (user_id, schedule_id, effective_from)
                select ?, id, date '2020-01-01' from work_schedule where code = 'STANDARD'""").param(id).update();
        long head = userId(EMPLOYEE);
        jdbc.sql("update app_user set manager_id = ? where id = ?").params(head, id).update();
        long stranger = freshEmployee();
        OffsetDateTime at = today.minusDays(5).atTime(10, 30).atOffset(ZoneOffset.ofHours(1));
        MockHttpSession employee = login(EMPLOYEE);
        mvc.perform(json(post("/api/health/accidents/report").session(employee), """
                {"userId":%d,"kind":"WORK","occurredAt":"%s","description":"Fell from a ladder"}""".formatted(stranger, at)))
                .andExpect(status().isForbidden());
        String reported = mvc.perform(json(post("/api/health/accidents/report").session(employee), """
                {"userId":%d,"kind":"WORK","occurredAt":"%s","place":"Warehouse","description":"Fell from a ladder",
                 "injury":"Left wrist"}""".formatted(id, at)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REPORTED"))
                .andExpect(jsonPath("$.declareBy").value(today.minusDays(5).plusDays(60).toString()))
                .andReturn().getResponse().getContentAsString();
        long accident = JsonPath.<Number>read(reported, "$.id").longValue();
        assertThat(JsonPath.<List<Number>>read(mvc.perform(get("/api/health/accidents/reportable").session(employee))
                .andReturn().getResponse().getContentAsString(), "$[*].userId").stream().map(Number::longValue).toList())
                .contains(id, head).doesNotContain(stranger);

        // On the deadlines until declared.
        String deadlines = mvc.perform(get("/api/health/deadlines").session(hr)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(deadlines, "$[?(@.kind=='ACCIDENT')].due"))
                .contains(today.plusDays(55).toString());

        // HR completes it: 40 days off, recorded as work accident leave, and a return-to-work visit.
        LocalDate offFrom = today.minusDays(4);
        LocalDate offTo = offFrom.plusDays(39);
        mvc.perform(json(put("/api/health/accidents/" + accident).session(hr), """
                {"kind":"WORK","occurredAt":"%s","place":"Warehouse","description":"Fell from a ladder","injury":"Left wrist",
                 "offFrom":"%s","offTo":"%s"}""".formatted(at, offFrom, offTo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.status").value("RECORDED"))
                .andExpect(jsonPath("$.item.daysOff").value(40))
                .andExpect(jsonPath("$.item.leaveRequestId").value(Matchers.notNullValue()));
        assertThat(jdbc.sql("""
                select t.code || ' ' || t.pay_percent || ' ' || l.status from leave_request l join leave_type t on t.id = l.leave_type_id
                where l.user_id = ? and l.start_date = ?""").params(id, offFrom).query(String.class).single())
                .isEqualTo("WORK_ACCIDENT 66.67 APPROVED");
        assertThat(jdbc.sql("select count(*) from medical_visit where accident_id = ? and kind = 'RETURN' and due_on = ?")
                .params(accident, offTo.plusDays(1)).query(Integer.class).single()).isEqualTo(1);

        // The declaration letter is drafted with the facts.
        String letter = mvc.perform(post("/api/health/accidents/" + accident + "/letter").session(hr).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long letterId = JsonPath.<Number>read(letter, "$.item.letterId").longValue();
        assertThat(jdbc.sql("select body from employee_letter where id = ?").param(letterId).query(String.class).single())
                .contains("Fell from a ladder").contains("Warehouse");

        mvc.perform(json(put("/api/health/accidents/" + accident + "/declaration").session(hr), """
                {"declaredOn":"%s","reference":"CNSS/AT/123","inspectionCopyOn":"%s"}""".formatted(today, today)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.declarationRef").value("CNSS/AT/123"));
        String undeclared = mvc.perform(get("/api/health/accidents").session(hr)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Number>>read(undeclared, "$.undeclared[*].id")).noneMatch(n -> n.longValue() == accident);
        mvc.perform(get("/api/health/accidents").param("year", String.valueOf(today.minusDays(5).getYear())).session(hr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.figures.accidents").value(Matchers.greaterThanOrEqualTo(1)));
        // The employee sees their own accident; deleting it cancels the leave.
        mvc.perform(delete("/api/health/accidents/" + accident).session(hr).with(csrf())).andExpect(status().isNoContent());
        assertThat(jdbc.sql("select status from leave_request where user_id = ? and start_date = ?").params(id, offFrom)
                .query(String.class).single()).isEqualTo("CANCELLED");
        // Other tests expect the sample employee to have no team.
        jdbc.sql("update app_user set manager_id = null where id = ?").param(id).update();
    }

    @Test
    void theCommitteeMeetsInspectsAndPrintsItsYearlyReport() throws Exception {
        MockHttpSession hr = login(HR);
        LocalDate today = LocalDate.now();
        jdbc.sql("update payroll_setting set value = '1' where key = 'SAFETY_COMMITTEE_FROM'").update();
        String o = mvc.perform(get("/api/health/committee").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.required").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(o, "$.alerts")).anyMatch(a -> a.contains("Record its members"));

        mvc.perform(json(post("/api/health/committee/members").session(hr), """
                {"userId":%d,"role":"PRESIDENT","fromOn":"%s"}""".formatted(userId(ADMIN), today.minusMonths(5))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.termEnd").value(today.minusMonths(5).plusYears(2).minusDays(1).toString()));
        mvc.perform(json(post("/api/health/committee/members").session(hr), """
                {"name":"Dr Kalala","role":"DOCTOR","fromOn":"%s"}""".formatted(today.minusMonths(5))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Dr Kalala"));
        o = mvc.perform(get("/api/health/committee").session(hr)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(o, "$.alerts")).anyMatch(a -> a.contains("meeting is overdue"));

        String meeting = mvc.perform(json(post("/api/health/committee/events").session(hr), """
                {"kind":"MEETING","heldOn":"%s","note":"Ladders, protective shoes"}""".formatted(today.minusDays(3))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long event = JsonPath.<Number>read(meeting, "$.id").longValue();
        mvc.perform(json(post("/api/health/committee/actions").session(hr), """
                {"risk":"Worn ladders","action":"Buy two new ladders","dueOn":"%s","eventId":%d}""".formatted(today.minusDays(1), event)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.late").value(true));
        o = mvc.perform(get("/api/health/committee").session(hr)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(o, "$.alerts")).noneMatch(a -> a.contains("meeting is overdue"))
                .anyMatch(a -> a.contains("past their due date"));

        mvc.perform(get("/api/health/committee/report/" + today.getYear()).param("format", "pdf").session(hr))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF));
        mvc.perform(get("/api/health/committee/report/" + today.getYear()).param("format", "json").session(hr))
                .andExpect(jsonPath("$.tables[0].rows.length()").value(2));
        jdbc.sql("update payroll_setting set value = '20' where key = 'SAFETY_COMMITTEE_FROM'").update();
    }

    @Test
    void workerSeatsFollowTheHeadcount() {
        assertThat(SafetyCommittee.workerSeats("CD", 19, 20)).isZero();
        assertThat(SafetyCommittee.workerSeats("CD", 20, 20)).isEqualTo(2);
        assertThat(SafetyCommittee.workerSeats("CD", 51, 20)).isEqualTo(4);
        assertThat(SafetyCommittee.workerSeats("CD", 300, 20)).isEqualTo(6);
        assertThat(SafetyCommittee.workerSeats("CD", 800, 20)).isEqualTo(8);
        assertThat(SafetyCommittee.workerSeats("CD", 1500, 20)).isEqualTo(10);
        assertThat(SafetyCommittee.workerSeats("CG", 300, 0)).isZero();
        assertThat(SafetyCommittee.workerSeats("CG", 300, 20)).isNull();
    }
}
