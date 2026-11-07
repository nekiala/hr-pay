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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

/** Phase 6: the structure drives who approves, and appears on users, payslips and reports. */
class StructureEverywhereIT extends IntegrationTest {

    @Autowired
    UserRepository users;

    @Autowired
    cd.hrpay.payroll.ExchangeRates rates;

    long id(String email) {
        return users.findByEmailIgnoreCase(email).orElseThrow().getId();
    }

    long unitId(MockHttpSession s, String code) throws Exception {
        String units = mvc.perform(get("/api/org/units").session(s)).andReturn().getResponse().getContentAsString();
        return JsonPath.<List<Number>>read(units, "$[?(@.code=='" + code + "')].id").getFirst().longValue();
    }

    @Test
    void managerComesFromTheStructureUnlessSetByHand() throws Exception {
        MockHttpSession hr = login(HR);
        long jean = id(EMPLOYEE);
        long patrick = id(FINANCE);
        // Nobody is set by hand in the sample: Jean's Service is vacant, so the head of the Direction above is used.
        mvc.perform(get("/api/users/" + jean).session(hr))
                .andExpect(jsonPath("$.managerId").doesNotExist())
                .andExpect(jsonPath("$.unitName").value("Service logistique"));
        mvc.perform(get("/api/org/users/" + jean + "/assignments").session(hr))
                .andExpect(jsonPath("$.manager.userName").value("Sarah Tshibangu"))
                .andExpect(jsonPath("$.manager.manual").value(false));

        // A manager set by hand wins for "reports to"; the head of the branch still sees Jean's requests.
        mvc.perform(json(put("/api/users/" + jean + "/manager").session(login(ADMIN)), "{\"managerId\":" + patrick + "}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/org/users/" + jean + "/assignments").session(hr))
                .andExpect(jsonPath("$.manager.userName").value("Patrick Ilunga"))
                .andExpect(jsonPath("$.manager.manual").value(true));
        String people = mvc.perform(get("/api/org/people").session(hr)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(people, "$[?(@.userId==" + jean + ")].reportsToName")).containsExactly("Patrick Ilunga");
        mvc.perform(json(put("/api/users/" + jean + "/manager").session(login(ADMIN)), "{\"managerId\":null}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/org/users/" + jean + "/assignments").session(hr))
                .andExpect(jsonPath("$.manager.userName").value("Sarah Tshibangu"));
    }

    @Autowired
    cd.hrpay.org.OrgStructure org;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    long createUser(MockHttpSession admin, long unit) throws Exception {
        String body = newUser(null).replace("\"orgUnitId\":1", "\"orgUnitId\":" + unit);
        String created = mvc.perform(json(post("/api/users").session(admin), body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(created, "$.id").longValue();
    }

    @Test
    void deputyCoversTheUnitOnlyWhileTheHeadIsAwayOrMissing() throws Exception {
        MockHttpSession hr = login(HR);
        MockHttpSession admin = login(ADMIN);
        String levels = mvc.perform(get("/api/org/levels").session(hr)).andReturn().getResponse().getContentAsString();
        long service = JsonPath.<List<Number>>read(levels, "$[?(@.code=='SERVICE')].id").getFirst().longValue();
        String unit = mvc.perform(json(post("/api/org/units").session(hr), """
                        {"code":"SDEPUTY","name":"Service test des adjoints","levelId":%d,"parentId":%d,"validFrom":"2026-01-01"}"""
                        .formatted(service, unitId(hr, "DOP"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long sd = JsonPath.<Number>read(unit, "$.id").longValue();
        long head = createUser(admin, sd);
        long deputy = createUser(admin, sd);
        long member = createUser(admin, sd);
        mvc.perform(json(put("/api/org/units/" + sd + "/head").session(hr), "{\"userId\":" + head + ",\"role\":\"HEAD\",\"from\":\"2026-01-01\"}"))
                .andExpect(status().isOk());
        mvc.perform(json(put("/api/org/units/" + sd + "/head").session(hr), "{\"userId\":" + deputy + ",\"role\":\"DEPUTY\",\"from\":\"2026-01-01\"}"))
                .andExpect(status().isOk());

        // The head is there: the head covers the member (and the deputy); the deputy covers nobody.
        assertThat(org.teamOf(head)).contains(member, deputy);
        assertThat(org.teamOf(deputy)).doesNotContain(member);
        // The head of the Direction above covers the whole branch.
        assertThat(org.teamOf(id("manager@demo.hrpay.cd"))).contains(head, deputy, member);

        // The head is on approved leave today: the deputy steps in.
        long annual = jdbc.sql("select id from leave_type where code = 'ANNUAL'").query(Long.class).single();
        jdbc.sql("""
                insert into leave_request (user_id, leave_type_id, start_date, end_date, working_days, status, requested_by)
                values (?, ?, ?, ?, 1, 'APPROVED', 'test')""").params(head, annual, org.today(), org.today()).update();
        assertThat(org.teamOf(deputy)).contains(member).doesNotContain(deputy);
        jdbc.sql("delete from leave_request where user_id = ?").param(head).update();
        assertThat(org.teamOf(deputy)).doesNotContain(member);

        // The unit has no head: the deputy covers it.
        mvc.perform(json(post("/api/org/units/" + sd + "/head/end").session(hr), "{\"role\":\"HEAD\"}")).andExpect(status().isOk());
        assertThat(org.teamOf(deputy)).contains(member);
    }

    @Test
    void usersFilterByUnitIncludesTheUnitsBelow() throws Exception {
        MockHttpSession hr = login(HR);
        String dop = mvc.perform(get("/api/users").param("unitId", String.valueOf(unitId(hr, "DOP"))).param("size", "100").session(hr))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(dop, "$.items[*].email")).contains("manager@demo.hrpay.cd", EMPLOYEE, "employee2@demo.hrpay.cd")
                .doesNotContain(HR, FINANCE);
        assertThat(JsonPath.<List<String>>read(dop, "$.items[?(@.email=='" + EMPLOYEE + "')].unitName")).containsExactly("Service logistique");
        String drh = mvc.perform(get("/api/users").param("unitId", String.valueOf(unitId(hr, "DRH"))).param("size", "100").session(hr))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(drh, "$.items[*].email")).contains(HR).doesNotContain(EMPLOYEE);
    }

    @Test
    void payslipsKeepTheirUnitAndCostIsReportedByDirection() throws Exception {
        rates.store("""
                {"data":[{"currency_code":"USD","rate_date":"2026-07-31","indicative_rate":"2100"}]}""");
        MockHttpSession hr = login(HR);
        String run = mvc.perform(post("/api/payroll/runs/2026-07/calculate").session(hr).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long jean = id(EMPLOYEE);
        assertThat(JsonPath.<List<String>>read(run, "$.payslips[?(@.userId==" + jean + ")].directionName"))
                .containsExactly("Direction des opérations");
        Number slip = JsonPath.<List<Number>>read(run, "$.payslips[?(@.userId==" + jean + ")].id").getFirst();
        mvc.perform(get("/api/payslips/" + slip).session(hr))
                .andExpect(jsonPath("$.snapshot.unit").value("Service logistique"))
                .andExpect(jsonPath("$.snapshot.direction").value("Direction des opérations"));

        String cost = mvc.perform(get("/api/reports/structure").param("month", "2026-07").session(login(FINANCE)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(cost, "$.total.headcount")).isGreaterThanOrEqualTo(7);
        int payslips = JsonPath.read(cost, "$.total.payslips");
        assertThat(payslips).isEqualTo(JsonPath.<List<Object>>read(run, "$.payslips").size());
        List<Integer> dopPayslips = JsonPath.read(cost, "$.directions[?(@.name=='Direction des opérations')].payslips");
        assertThat(dopPayslips.getFirst()).isGreaterThanOrEqualTo(3);
        mvc.perform(get("/api/reports/structure.csv").param("month", "2026-07").session(hr))
                .andExpect(status().isOk());
        mvc.perform(get("/api/reports/structure").param("month", "2026-07").session(login(EMPLOYEE)))
                .andExpect(status().isForbidden());
    }
}
