package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.attendance.ClockEvent;
import cd.hrpay.attendance.ClockEventRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

class RankingsDisciplineIT extends IntegrationTest {

    static final String MANAGER = "manager@demo.hrpay.cd";

    @Autowired
    ClockEventRepository events;
    @Autowired
    JdbcClient jdbc;

    private void clock(long userId, LocalDate date, String in, String out) {
        ZoneId kin = ZoneId.of("Africa/Kinshasa");
        events.save(new ClockEvent(userId, ClockEvent.Type.IN, date.atTime(LocalTime.parse(in)).atZone(kin).toOffsetDateTime(),
                date, ClockEvent.Source.SELF, "t"));
        events.save(new ClockEvent(userId, ClockEvent.Type.OUT, date.atTime(LocalTime.parse(out)).atZone(kin).toOffsetDateTime(),
                date, ClockEvent.Source.SELF, "t"));
    }

    /** A new employee of the Service logistique (under the manager's Direction), on contract since 2025. */
    private long employee() throws Exception {
        String body = newUser("[\"EMPLOYEE\"]");
        body = body.substring(0, body.length() - 1) + ",\"initialPassword\":\"" + PASSWORD + "\"}";
        String created = mvc.perform(json(post("/api/users").session(login(ADMIN)), body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long user = JsonPath.<Number>read(created, "$.id").longValue();
        long slog = jdbc.sql("select id from org_unit where code = 'SLOG'").query(Long.class).single();
        jdbc.sql("update org_assignment set unit_id = ?, valid_from = date '2025-01-01' where user_id = ? and valid_to is null")
                .params(slog, user).update();
        jdbc.sql("""
                insert into user_schedule (user_id, schedule_id, effective_from)
                select ?, id, date '2025-01-01' from work_schedule where code = 'STANDARD'""").param(user).update();
        MockHttpSession hr = login(HR);
        String grades = mvc.perform(get("/api/grades").session(hr)).andReturn().getResponse().getContentAsString();
        long grade = JsonPath.<List<Number>>read(grades, "$[?(@.code=='M1')].id").getFirst().longValue();
        mvc.perform(json(post("/api/users/" + user + "/contracts").session(hr), """
                        {"contractType":"PERMANENT","startDate":"2025-01-02","gradeId":%d,"expatriate":false}""".formatted(grade)))
                .andExpect(status().isCreated());
        return user;
    }

    @Test
    void rankingsShowTheMostLateAndKeepEachPersonToTheirScope() throws Exception {
        long user = employee();
        // March 2026, schedule 08:00-17:00 with 10 minutes of grace: 4 late days, one within the grace.
        clock(user, LocalDate.of(2026, 3, 2), "08:30", "17:00");
        clock(user, LocalDate.of(2026, 3, 3), "08:20", "17:00");
        clock(user, LocalDate.of(2026, 3, 4), "08:05", "17:00");
        clock(user, LocalDate.of(2026, 3, 5), "08:40", "16:00");
        clock(user, LocalDate.of(2026, 3, 9), "08:15", "17:00");
        MockHttpSession hr = login(HR);
        String body = mvc.perform(get("/api/rankings/lateness").param("from", "2026-03-01").param("to", "2026-03-31")
                        .param("top", "0").session(hr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyWide").value(true))
                .andExpect(jsonPath("$.previousFrom").value("2026-02-01"))
                .andReturn().getResponse().getContentAsString();
        List<Number> score = JsonPath.read(body, "$.rows[?(@.userId==" + user + ")].score");
        List<Number> minutes = JsonPath.read(body, "$.rows[?(@.userId==" + user + ")].values.lateMinutes");
        assertThat(score.getFirst().intValue()).isEqualTo(4);
        assertThat(minutes.getFirst().intValue()).isEqualTo(105);

        String early = mvc.perform(get("/api/rankings/early").param("from", "2026-03-01").param("to", "2026-03-31")
                .param("top", "0").session(hr)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Number>>read(early, "$.rows[?(@.userId==" + user + ")].score").getFirst().intValue()).isEqualTo(1);

        // Late on 4 days in March, no case yet: HR's follow-up list.
        String follow = mvc.perform(get("/api/rankings/follow-up").param("from", "2026-03-01").param("to", "2026-03-31")
                .param("top", "0").session(hr)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(follow, "$.rows[?(@.userId==" + user + ")].values.months").getFirst())
                .isEqualTo("2026-03: 4 days");

        mvc.perform(get("/api/rankings/lateness/people/" + user).param("from", "2026-03-01").param("to", "2026-03-31")
                        .session(hr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].date").value("2026-03-09"));

        // The head sees their team only, and not the HR-only rankings.
        MockHttpSession manager = login(MANAGER);
        mvc.perform(get("/api/rankings").session(manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.kind=='SICK')]").isEmpty())
                .andExpect(jsonPath("$[?(@.kind=='LATENESS')]").isNotEmpty());
        String team = mvc.perform(get("/api/rankings/absence").param("from", "2026-03-01").param("to", "2026-03-31")
                        .param("top", "0").session(manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyWide").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(team, "$.rows[?(@.registrationNo=='HR-0001')]")).isEmpty();
        mvc.perform(get("/api/rankings/sick").param("from", "2026-03-01").param("to", "2026-03-31").session(manager))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/rankings/lateness").param("from", "2026-03-01").param("to", "2026-03-31")
                .session(login(EMPLOYEE))).andExpect(status().isForbidden());

        mvc.perform(get("/api/rankings/lateness").param("from", "2025-01-01").param("to", "2026-03-31").session(hr))
                .andExpect(status().is(422));
        mvc.perform(get("/api/rankings/units").param("from", "2026-03-01").param("to", "2026-03-31").param("byUnit", "true")
                        .session(hr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.name=='Service logistique')]").isNotEmpty());
        mvc.perform(get("/api/rankings/turnover").param("from", "2026-01-01").param("to", "2026-03-31").session(hr))
                .andExpect(status().isOk());
        mvc.perform(get("/api/rankings/leave").session(hr)).andExpect(status().isOk());
        mvc.perform(get("/api/rankings/missions").param("from", "2026-03-01").param("to", "2026-03-31").session(hr))
                .andExpect(status().isOk());
        mvc.perform(get("/api/rankings/overtime").param("from", "2026-03-01").param("to", "2026-03-31").session(hr))
                .andExpect(status().isOk());
        mvc.perform(get("/api/rankings/clocking").param("from", "2026-03-01").param("to", "2026-03-31").session(hr))
                .andExpect(status().isOk());
        mvc.perform(get("/api/rankings/lateness").param("from", "2026-03-01").param("to", "2026-03-31")
                        .param("format", "xlsx").session(hr))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        mvc.perform(get("/api/rankings/lateness").param("from", "2026-03-01").param("to", "2026-03-31")
                        .param("format", "pdf").session(hr))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"));
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'report.ranking'").query(Integer.class).single())
                .isPositive();
    }

    @Test
    void aCaseGoesFromProposalToHearingDecisionAndContestAndSanctionsCountInTheRanking() throws Exception {
        long user = employee();
        MockHttpSession hr = login(HR);
        MockHttpSession manager = login(MANAGER);
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Kinshasa"));

        // A head proposes for their team only; an employee cannot.
        long hrUser = jdbc.sql("select id from app_user where email = ?").param(HR).query(Long.class).single();
        mvc.perform(json(post("/api/discipline/cases").session(manager), """
                {"userId":%d,"factsDate":"%s","facts":"Late"}""".formatted(hrUser, today)))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/discipline/cases").session(login(EMPLOYEE)), """
                {"userId":%d,"factsDate":"%s","facts":"Late"}""".formatted(user, today)))
                .andExpect(status().isForbidden());
        String proposed = mvc.perform(json(post("/api/discipline/cases").session(manager), """
                        {"userId":%d,"factsDate":"%s","facts":"Arrived late four times in March after a verbal reminder.",
                         "ruleBroken":"Company rules art. 12: working hours","latenessMonth":"2026-03-01"}""".formatted(user, today)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.origin").value("HEAD"))
                .andReturn().getResponse().getContentAsString();
        long id = JsonPath.<Number>read(proposed, "$.id").longValue();
        mvc.perform(json(post("/api/discipline/cases/" + id + "/review").session(manager), "{\"accept\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/discipline/cases/" + id + "/review").session(hr), "{\"accept\":true}"))
                .andExpect(jsonPath("$.status").value("OPEN"));

        // No decision before the hearing (art. 62).
        String types = mvc.perform(get("/api/discipline/types").session(hr)).andReturn().getResponse().getContentAsString();
        long suspension = JsonPath.<List<Number>>read(types, "$[?(@.code=='SUSPENSION')].id").getFirst().longValue();
        long blame = JsonPath.<List<Number>>read(types, "$[?(@.code=='BLAME')].id").getFirst().longValue();
        mvc.perform(json(post("/api/discipline/cases/" + id + "/decision").session(hr), "{\"sanctionTypeId\":%d}".formatted(blame)))
                .andExpect(status().is(422));

        String hearing = "%sT10:00:00".formatted(today.plusDays(1));
        mvc.perform(json(post("/api/discipline/cases/" + id + "/hearing").session(hr), """
                        {"at":"%s","place":"the HR office"}""".formatted(hearing)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HEARING"))
                .andExpect(jsonPath("$.hearingLetterStatus").value("DRAFT"));
        // The employee sees nothing until the invitation is sent.
        MockHttpSession employee = login(jdbc.sql("select email from app_user where id = ?").param(user).query(String.class).single());
        mvc.perform(get("/api/my/discipline").session(employee)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(json(post("/api/discipline/cases/" + id + "/heard").session(hr), "{\"on\":\"%s\",\"attended\":false}"
                .formatted(today))).andExpect(status().is(422));
        mvc.perform(post("/api/discipline/cases/" + id + "/letters/hearing/send").session(hr).with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(jsonPath("$.hearingLetterStatus").value("SENT"));
        long letter = jdbc.sql("select hearing_letter_id from discipline_case where id = ?").param(id).query(Long.class).single();
        assertThat(jdbc.sql("select body from employee_letter where id = ?").param(letter).query(String.class).single())
                .contains("Arrived late four times").contains("the HR office").contains("Company rules art. 12");
        mvc.perform(get("/api/my/discipline").session(employee)).andExpect(jsonPath("$[0].status").value("HEARING"));

        mvc.perform(json(post("/api/discipline/cases/" + id + "/heard").session(hr), """
                        {"on":"%s","attended":true,"statement":"Bus strikes in March.","note":"Admits the facts."}""".formatted(today)))
                .andExpect(jsonPath("$.status").value("HEARD"));
        // The head does not read the employee's words nor HR's notes.
        mvc.perform(get("/api/discipline/cases/" + id).session(manager))
                .andExpect(jsonPath("$.statement").doesNotExist())
                .andExpect(jsonPath("$.hearingNote").doesNotExist());

        // A mise à pied needs company rules that provide it, and stays within 15 days.
        LocalDate monday = today.plusWeeks(5).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        String suspend = """
                {"sanctionTypeId":%d,"note":"Repeated lateness","suspensionFrom":"%s","suspensionDays":%d}""";
        mvc.perform(json(post("/api/discipline/cases/" + id + "/decision").session(hr), suspend.formatted(suspension, monday, 3)))
                .andExpect(status().is(422));
        mvc.perform(json(put("/api/discipline/settings").session(hr), """
                        {"rankingMonths":24,"followUpDays":4,"suspensionAllowed":true,"suspensionMaxDays":15,"suspensionYearDays":30}"""))
                .andExpect(status().isOk());
        mvc.perform(json(post("/api/discipline/cases/" + id + "/decision").session(hr), suspend.formatted(suspension, monday, 16)))
                .andExpect(status().is(422));
        mvc.perform(json(post("/api/discipline/cases/" + id + "/decision").session(hr), suspend.formatted(suspension, monday, 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECIDED"))
                .andExpect(jsonPath("$.suspensionTo").value(monday.plusDays(2).toString()))
                .andExpect(jsonPath("$.letterStatus").value("DRAFT"));
        Long leave = jdbc.sql("select leave_request_id from discipline_case where id = ?").param(id).query(Long.class).single();
        assertThat(jdbc.sql("""
                select r.status || ' ' || t.code || ' ' || r.working_days from leave_request r join leave_type t on t.id = r.leave_type_id
                where r.id = ?""").param(leave).query(String.class).single()).isEqualTo("APPROVED SUSPENSION 3.00");
        // The employee cannot ask for this kind of leave.
        long typeId = jdbc.sql("select id from leave_type where code = 'SUSPENSION'").query(Long.class).single();
        mvc.perform(json(post("/api/leave/requests").session(employee), """
                {"leaveTypeId":%d,"startDate":"%s","endDate":"%s"}""".formatted(typeId, monday.plusWeeks(2), monday.plusWeeks(2))))
                .andExpect(status().is(422));

        mvc.perform(post("/api/discipline/cases/" + id + "/letters/decision/send").session(hr).with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(jsonPath("$.status").value("NOTIFIED"));
        mvc.perform(get("/api/my/discipline").session(employee))
                .andExpect(jsonPath("$[0].sanctionCode").value("SUSPENSION"))
                .andExpect(jsonPath("$[0].hearingNote").doesNotExist());
        mvc.perform(json(post("/api/my/discipline/" + id + "/contest").session(employee), "{\"text\":\"The buses were on strike.\"}"))
                .andExpect(jsonPath("$.contest").value("The buses were on strike."));
        mvc.perform(json(post("/api/my/discipline/" + id + "/contest").session(employee), "{\"text\":\"Again\"}"))
                .andExpect(status().is(422));

        String ranking = mvc.perform(get("/api/rankings/sanctions").param("from", today.minusMonths(1).toString())
                        .param("to", today.toString()).param("top", "0").session(hr))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Number>>read(ranking, "$.rows[?(@.userId==" + user + ")].score").getFirst().intValue()).isEqualTo(3);
        assertThat(JsonPath.<List<Number>>read(ranking, "$.rows[?(@.userId==" + user + ")].values.type_SUSPENSION").getFirst()
                .intValue()).isEqualTo(1);

        // Withdrawn: it no longer counts and the unpaid days go.
        mvc.perform(json(post("/api/discipline/cases/" + id + "/cancel").session(hr), "{\"reason\":\"Strike confirmed\"}"))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(jdbc.sql("select status from leave_request where id = ?").param(leave).query(String.class).single())
                .isEqualTo("CANCELLED");
        String after = mvc.perform(get("/api/rankings/sanctions").param("from", today.minusMonths(1).toString())
                        .param("to", today.toString()).param("top", "0").session(hr))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(after, "$.rows[?(@.userId==" + user + ")]")).isEmpty();
        mvc.perform(get("/api/discipline/cases").session(login(EMPLOYEE))).andExpect(status().isForbidden());
    }
}
