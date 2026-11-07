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
import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** Phase 7: correction requests, leave pay share, half days, documents and seniority, templates per unit. */
class Phase7IT extends IntegrationTest {

    static final String MANAGER = "manager@demo.hrpay.cd";
    static final String EMPLOYEE2 = "employee2@demo.hrpay.cd";

    @Autowired
    UserRepository users;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    cd.hrpay.org.OrgStructure org;

    @Autowired
    cd.hrpay.evaluation.Evaluations evaluations;

    long id(String email) {
        return users.findByEmailIgnoreCase(email).orElseThrow().getId();
    }

    long leaveType(MockHttpSession s, String code) throws Exception {
        String types = mvc.perform(get("/api/leave/types").session(s)).andReturn().getResponse().getContentAsString();
        return JsonPath.<List<Number>>read(types, "$[?(@.code=='" + code + "')].id").getFirst().longValue();
    }

    /** A recent weekday, inside the two months a correction can be asked for. */
    String recentWeekday(int back) {
        var d = org.today().minusDays(back);
        while (d.getDayOfWeek().getValue() > 5) {
            d = d.minusDays(1);
        }
        return d.toString();
    }

    @Test
    void employeeAsksHeadProposesHrApplies() throws Exception {
        MockHttpSession jean = login(EMPLOYEE);
        MockHttpSession head = login(MANAGER);
        MockHttpSession hr = login(HR);
        String day = recentWeekday(3);

        String created = mvc.perform(json(post("/api/corrections").session(jean), """
                        {"workDate":"%s","action":"ADD","type":"IN","time":"07:58","reason":"Badge reader was down"}""".formatted(day)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        long req = JsonPath.<Number>read(created, "$.id").longValue();
        mvc.perform(json(post("/api/corrections").session(jean), """
                        {"workDate":"2030-01-02","action":"ADD","type":"IN","time":"07:58","reason":"x"}"""))
                .andExpect(status().is(422));

        // The head of the Direction sees it; another employee cannot act on it.
        String toDecide = mvc.perform(get("/api/corrections/to-decide").session(head)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Number>>read(toDecide, "$[*].id").stream().map(Number::longValue)).contains(req);
        mvc.perform(json(post("/api/corrections/" + req + "/propose").session(login(EMPLOYEE2)), "{}"))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/corrections/" + req + "/apply").session(head), "{}"))
                .andExpect(status().isForbidden());

        mvc.perform(json(post("/api/corrections/" + req + "/propose").session(head), "{\"note\":\"Confirmed with the guard\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.proposedBy").value(MANAGER));
        mvc.perform(json(post("/api/corrections/" + req + "/cancel").session(jean), "{}"))
                .andExpect(status().isConflict());

        int before = jdbc.sql("select count(*) from clock_event where user_id = ? and work_date = ?::date and voided_at is null")
                .params(id(EMPLOYEE), day).query(Integer.class).single();
        mvc.perform(json(post("/api/corrections/" + req + "/apply").session(hr), "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.decidedBy").value(HR));
        int after = jdbc.sql("select count(*) from clock_event where user_id = ? and work_date = ?::date and voided_at is null")
                .params(id(EMPLOYEE), day).query(Integer.class).single();
        assertThat(after).isEqualTo(before + 1);
        long added = jdbc.sql("select id from clock_event where user_id = ? and work_date = ?::date and voided_at is null order by id desc limit 1")
                .params(id(EMPLOYEE), day).query(Long.class).single();

        // Jean asks for it to be removed again; HR rejects, which needs a reason.
        String voidReq = mvc.perform(json(post("/api/corrections").session(jean), """
                        {"workDate":"%s","action":"VOID","eventId":%d,"reason":"Entered twice"}""".formatted(day, added)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long v = JsonPath.<Number>read(voidReq, "$.id").longValue();
        mvc.perform(json(post("/api/corrections/" + v + "/reject").session(hr), "{}")).andExpect(status().is(422));
        mvc.perform(json(post("/api/corrections/" + v + "/reject").session(hr), "{\"note\":\"It is the only check-in\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));

        // A head proposes directly for someone in the branch, not for someone outside it.
        mvc.perform(json(post("/api/corrections").session(head), """
                        {"userId":%d,"workDate":"%s","action":"ADD","type":"OUT","time":"16:05","reason":"Left with the truck"}"""
                        .formatted(id(EMPLOYEE2), day)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PROPOSED"));
        mvc.perform(json(post("/api/corrections").session(head), """
                        {"userId":%d,"workDate":"%s","action":"ADD","type":"OUT","time":"16:05","reason":"x"}"""
                        .formatted(id(FINANCE), day)))
                .andExpect(status().isForbidden());

        // The entries of a day: one's own, a head's branch, not someone outside it.
        mvc.perform(get("/api/corrections/entries").param("date", day).session(jean)).andExpect(status().isOk());
        mvc.perform(get("/api/corrections/entries").param("userId", String.valueOf(id(EMPLOYEE))).param("date", day).session(head))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").exists());
        mvc.perform(get("/api/corrections/entries").param("userId", String.valueOf(id(EMPLOYEE))).param("date", day).session(login(EMPLOYEE2)))
                .andExpect(status().isForbidden());

        String mine = mvc.perform(get("/api/corrections/mine").session(jean)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(mine, "$[*].status")).contains("APPLIED", "REJECTED");
    }

    @Test
    void headsSeeTheirBranchAndMembersCarryTheirSite() throws Exception {
        String branch = mvc.perform(get("/api/org/my-people").session(login(MANAGER))).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Number>>read(branch, "$[*].userId").stream().map(Number::longValue))
                .contains(id(EMPLOYEE), id(EMPLOYEE2)).doesNotContain(id(FINANCE));
        String nobody = mvc.perform(get("/api/org/my-people").session(login(EMPLOYEE))).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(nobody, "$")).isEmpty();
        String people = mvc.perform(get("/api/org/people").session(login(HR))).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(people, "$[?(@.userId==" + id(EMPLOYEE) + ")].siteName")).hasSize(1);
    }

    @Test
    void sickLeaveKeepsItsShareOfPayAndNeedsADocumentCheckedByHr() throws Exception {
        MockHttpSession me = login(EMPLOYEE2);
        MockHttpSession hr = login(HR);
        long sick = leaveType(me, "SICK");
        String created = mvc.perform(json(post("/api/leave/requests").session(me),
                        "{\"leaveTypeId\":%d,\"startDate\":\"2026-12-14\",\"endDate\":\"2026-12-14\",\"reason\":\"Malaria\"}".formatted(sick)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long req = JsonPath.<Number>read(created, "$.id").longValue();

        // The head approves; it waits for HR, who cannot accept it without a document.
        mvc.perform(json(post("/api/leave/requests/" + req + "/decision").session(login(MANAGER)), "{\"approve\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AWAITING_HR"));
        mvc.perform(json(post("/api/leave/requests/" + req + "/document-check").session(hr), "{\"approve\":true}"))
                .andExpect(status().is(422));

        String pdf = Base64.getEncoder().encodeToString("%PDF-1.4 certificate".getBytes());
        mvc.perform(json(post("/api/leave/requests/" + req + "/documents").session(me),
                        "{\"fileName\":\"certificat.exe\",\"contentType\":\"application/x-msdownload\",\"base64\":\"" + pdf + "\"}"))
                .andExpect(status().is(422));
        String doc = mvc.perform(json(post("/api/leave/requests/" + req + "/documents").session(me),
                        "{\"fileName\":\"certificat.pdf\",\"contentType\":\"application/pdf\",\"base64\":\"" + pdf + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long docId = JsonPath.<Number>read(doc, "$.id").longValue();
        mvc.perform(get("/api/leave/requests/" + req + "/documents/" + docId).session(login(EMPLOYEE)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/leave/requests/" + req + "/documents/" + docId).session(hr))
                .andExpect(status().isOk());

        mvc.perform(json(post("/api/leave/requests/" + req + "/document-check").session(hr), "{\"approve\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.documentCheckedBy").value(HR));

        // 14 December is a Monday: the day is paid at two thirds of 8 hours.
        String month = mvc.perform(get("/api/attendance/month").param("month", "2026-12").session(me))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(month, "$.days[?(@.date=='2026-12-14')].status")).containsExactly("LEAVE");
        assertThat(JsonPath.<List<Integer>>read(month, "$.days[?(@.date=='2026-12-14')].payableMinutes")).containsExactly(320);
    }

    @Test
    void halfDayLeaveTakesHalfADay() throws Exception {
        MockHttpSession me = login(EMPLOYEE2);
        long annual = leaveType(me, "ANNUAL");
        mvc.perform(json(post("/api/leave/requests").session(me),
                        "{\"leaveTypeId\":%d,\"startDate\":\"2026-12-15\",\"endDate\":\"2026-12-16\",\"halfDay\":\"AM\"}".formatted(annual)))
                .andExpect(status().is(422));
        mvc.perform(json(post("/api/leave/requests").session(me),
                        "{\"leaveTypeId\":%d,\"startDate\":\"2026-12-15\",\"endDate\":\"2026-12-15\",\"halfDay\":\"PM\"}".formatted(annual)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workingDays").value(0.5))
                .andExpect(jsonPath("$.halfDay").value("PM"));
        // The pending half day is cancelled so the balances of the other tests stay as they were.
        String mine = mvc.perform(get("/api/leave/mine").session(me)).andReturn().getResponse().getContentAsString();
        Number id = JsonPath.<List<Number>>read(mine, "$.requests[?(@.halfDay=='PM')].id").getFirst();
        mvc.perform(json(post("/api/leave/requests/" + id + "/cancel").session(me), "{}")).andExpect(status().isOk());
    }

    long createUser(MockHttpSession admin, long unit) throws Exception {
        String body = newUser(null).replace("\"orgUnitId\":1", "\"orgUnitId\":" + unit);
        String created = mvc.perform(json(post("/api/users").session(admin), body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(created, "$.id").longValue();
    }

    @Test
    void seniorityAddsAnnualLeaveDays() throws Exception {
        MockHttpSession hr = login(HR);
        mvc.perform(json(put("/api/leave/settings").session(hr), "{\"seniorityYears\":5,\"seniorityDays\":1}"))
                .andExpect(status().isOk());
        long u = createUser(login(ADMIN), 1);
        long category = jdbc.sql("select id from grade_category order by id limit 1").query(Long.class).single();
        BigDecimal base = jdbc.sql("select annual_leave_days from grade_category where id = ?").param(category)
                .query(BigDecimal.class).single();
        jdbc.sql("update app_user set hire_date = '2015-03-01', grade_category_id = ? where id = ?").params(category, u).update();
        mvc.perform(post("/api/leave/grant-annual").param("year", "2026").session(hr).with(csrf())).andExpect(status().isOk());
        BigDecimal granted = jdbc.sql("select days from leave_ledger where user_id = ? and kind = 'GRANT' and year = 2026")
                .param(u).query(BigDecimal.class).single();
        // 10 full years on 1 January 2026: two more days.
        assertThat(granted).isEqualByComparingTo(base.add(BigDecimal.TWO));
    }

    @Test
    void theTemplateOfTheNearestUnitWins() throws Exception {
        MockHttpSession hr = login(HR);
        MockHttpSession admin = login(ADMIN);
        String levels = mvc.perform(get("/api/org/levels").session(hr)).andReturn().getResponse().getContentAsString();
        long service = JsonPath.<List<Number>>read(levels, "$[?(@.code=='SERVICE')].id").getFirst().longValue();
        long section = JsonPath.<List<Number>>read(levels, "$[?(@.code=='SECTION')].id").getFirst().longValue();
        String units = mvc.perform(get("/api/org/units").session(hr)).andReturn().getResponse().getContentAsString();
        long dop = JsonPath.<List<Number>>read(units, "$[?(@.code=='DOP')].id").getFirst().longValue();
        long svc = JsonPath.<Number>read(mvc.perform(json(post("/api/org/units").session(hr), """
                        {"code":"STPL","name":"Service test des modèles","levelId":%d,"parentId":%d,"validFrom":"2026-01-01"}"""
                        .formatted(service, dop))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id").longValue();
        long sec = JsonPath.<Number>read(mvc.perform(json(post("/api/org/units").session(hr), """
                        {"code":"STPLS","name":"Section test des modèles","levelId":%d,"parentId":%d,"validFrom":"2026-01-01"}"""
                        .formatted(section, svc))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id").longValue();
        long inSection = createUser(admin, sec);

        String criteria = mvc.perform(get("/api/evaluation-criteria").session(hr)).andReturn().getResponse().getContentAsString();
        long first = JsonPath.<Number>read(criteria, "$[0].id").longValue();
        String body = """
                {"name":"Service template","frequency":"QUARTERLY","orgUnitId":%d,"active":true,
                 "criteria":[{"criterionId":%d,"weight":100}]}""".formatted(svc, first);
        mvc.perform(json(post("/api/evaluation-templates").session(hr), body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.orgUnitId").value(svc));
        mvc.perform(json(post("/api/evaluation-templates").session(hr), body)).andExpect(status().isConflict());

        // The section has none of its own: the service's applies to the people in it.
        assertThat(evaluations.templateFor(users.findById(inSection).orElseThrow()).name()).isEqualTo("Service template");
        assertThat(evaluations.templateFor(users.findByEmailIgnoreCase(EMPLOYEE).orElseThrow()).name()).isNotEqualTo("Service template");
    }
}
