package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.payroll.AnniversaryRaises;
import cd.hrpay.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** Simulate pay and the year-end projection: same figures as the real run, nothing saved, HR only, never one's own. */
class PaySimulationIT extends IntegrationTest {

    @Autowired
    UserRepository users;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AnniversaryRaises raises;

    long id(String email) {
        return users.findByEmailIgnoreCase(email).orElseThrow().getId();
    }

    static final List<String> TABLES = List.of("payslip", "payslip_line", "pay_entry", "leave_allowance", "leave_ledger",
            "quinzaine_line", "quinzaine_run", "departure", "loan_repayment", "employee_salary", "anniversary_raise",
            "payroll_run", "thirteenth_run");

    Map<String, Integer> counts() {
        Map<String, Integer> out = new java.util.TreeMap<>();
        for (String t : TABLES) {
            out.put(t, jdbc.sql("select count(*) from " + t).query(Integer.class).single());
        }
        return out;
    }

    BigDecimal number(String body, String path) {
        return new BigDecimal(JsonPath.read(body, path).toString());
    }

    String simulate(MockHttpSession s, String body) throws Exception {
        return mvc.perform(json(post("/api/payroll/simulate").session(s), body)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void aSimulationGivesTheRunsFiguresAndSavesNothing() throws Exception {
        usdRate("2025-11-28", 2800);
        MockHttpSession hr = login(HR);
        long employee = id(EMPLOYEE);
        String run = mvc.perform(post("/api/payroll/runs/2025-11/calculate").session(hr).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long slip = JsonPath.<List<Number>>read(run, "$.payslips[?(@.userId==" + employee + ")].id").getFirst().longValue();
        String real = mvc.perform(get("/api/payslips/" + slip).session(hr)).andReturn().getResponse().getContentAsString();

        Map<String, Integer> before = counts();
        String sim = simulate(hr, "{\"userId\":%d,\"type\":\"MONTH\",\"month\":\"2025-11\"}".formatted(employee));
        assertThat(number(sim, "$.gross")).isEqualByComparingTo(number(real, "$.gross"));
        assertThat(number(sim, "$.net")).isEqualByComparingTo(number(real, "$.net"));
        assertThat(number(sim, "$.employerCharges")).isEqualByComparingTo(number(real, "$.employerCharges"));
        // No check-in in the test data: the same lines as the payslip (days not worked are not paid).
        assertThat(JsonPath.<List<String>>read(sim, "$.lines[*].elementCode"))
                .containsExactlyInAnyOrderElementsOf(JsonPath.<List<String>>read(real, "$.lines[?(@.amount != 0)].elementCode"));
        // A month to come counts its days as worked.
        String later = simulate(hr, "{\"userId\":%d,\"type\":\"MONTH\",\"month\":\"2027-03\"}".formatted(employee));
        assertThat(JsonPath.<List<String>>read(later, "$.lines[*].elementCode")).contains("BASIC", "CNSS_EMPLOYEE", "IPR");
        assertThat(JsonPath.<Boolean>read(sim, "$.changed")).isFalse();

        // What if: a one-off bonus of 100 and two more children.
        String whatIf = simulate(hr, """
                {"userId":%d,"type":"MONTH","month":"2025-11","whatIf":{"bonus":100,"children":4}}""".formatted(employee));
        assertThat(number(whatIf, "$.gross")).isGreaterThan(number(sim, "$.gross"));
        assertThat(JsonPath.<List<String>>read(whatIf, "$.lines[*].elementCode")).contains("OTHER_BONUS");
        assertThat(JsonPath.<Boolean>read(whatIf, "$.changed")).isTrue();

        // Every pay type, none of them saved.
        simulate(hr, "{\"userId\":%d,\"type\":\"MONTH\",\"month\":\"2027-03\",\"whatIf\":{\"overtimeHours\":10,\"unpaidDays\":2}}"
                .formatted(employee));
        String leave = simulate(hr, """
                {"userId":%d,"type":"LEAVE_PAY","leaveFrom":"2027-04-05","leaveTo":"2027-04-16"}""".formatted(employee));
        assertThat(number(leave, "$.gross")).isPositive();
        assertThat(JsonPath.<String>read(leave, "$.period")).isEqualTo("2027-04-05/2027-04-16");
        String settlement = simulate(hr, """
                {"userId":%d,"type":"SETTLEMENT","lastDay":"2027-01-15","reason":"DISMISSAL"}""".formatted(employee));
        assertThat(JsonPath.<List<String>>read(settlement, "$.explanation")).isNotEmpty();
        assertThat(JsonPath.<List<String>>read(settlement, "$.warnings")).anyMatch(w -> w.contains("15"));
        String thirteenth = simulate(hr, "{\"userId\":%d,\"type\":\"THIRTEENTH\",\"year\":2026}".formatted(employee));
        assertThat(JsonPath.<List<String>>read(thirteenth, "$.lines[*].elementCode")).contains("THIRTEENTH_MONTH");
        simulate(hr, "{\"userId\":%d,\"type\":\"QUINZAINE\",\"month\":\"2027-02\"}".formatted(employee));
        mvc.perform(get("/api/payroll/simulate.pdf").param("userId", String.valueOf(employee)).param("month", "2025-11")
                        .param("bonus", "50").session(hr))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("application/pdf"));
        assertThat(counts()).isEqualTo(before);
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'payroll.simulated' and entity_id = ?")
                .param(String.valueOf(employee)).query(Integer.class).single()).isGreaterThanOrEqualTo(7);

        jdbc.sql("delete from payroll_run where month = '2025-11-01'").update();
    }

    @Test
    void onlyThePermissionAndNeverOnesOwnPay() throws Exception {
        long employee = id(EMPLOYEE);
        String body = "{\"userId\":%d,\"type\":\"MONTH\",\"month\":\"2026-11\"}".formatted(employee);
        mvc.perform(json(post("/api/payroll/simulate").session(login(FINANCE)), body)).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/payroll/simulate").session(login("employee2@demo.hrpay.cd")), body)).andExpect(status().isForbidden());
        mvc.perform(get("/api/payroll/projection").param("userId", String.valueOf(employee)).session(login(EMPLOYEE)))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/payroll/simulate").session(login(HR)),
                "{\"userId\":%d,\"type\":\"MONTH\",\"month\":\"2026-11\"}".formatted(id(HR)))).andExpect(status().isForbidden());
    }

    @Test
    void theProjectionCountsTheAnniversaryRaiseAndLeave() throws Exception {
        usdRate(LocalDate.now().toString(), 2800);
        MockHttpSession hr = login(HR);
        String created = mvc.perform(json(post("/api/users").session(login(ADMIN)), newUser("[\"EMPLOYEE\"]")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long user = JsonPath.<Number>read(created, "$.id").longValue();
        String grades = mvc.perform(get("/api/grades").session(hr)).andReturn().getResponse().getContentAsString();
        long m1 = JsonPath.<List<Number>>read(grades, "$[?(@.code=='M1')].id").getFirst().longValue();
        mvc.perform(json(post("/api/users/" + user + "/contracts").session(hr), """
                        {"contractType":"PERMANENT","startDate":"2025-03-10","gradeId":%d,"expatriate":false}""".formatted(m1)))
                .andExpect(status().isCreated());
        try {
            var planned = raises.upcoming(user, LocalDate.parse("2027-12-31"));
            Map<String, Integer> before = counts();
            String p = mvc.perform(get("/api/payroll/projection").param("userId", String.valueOf(user)).param("year", "2027")
                            .session(hr))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.months.length()").value(12))
                    .andExpect(jsonPath("$.months[0].status").value("PROJECTED"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(counts()).isEqualTo(before);
            List<Number> basics = JsonPath.read(p, "$.months[*].basic");
            assertThat(planned).isNotEmpty();
            {
                var r = planned.stream().filter(x -> x.effectiveFrom().getYear() == 2027).findFirst().orElseThrow();
                int at = r.effectiveFrom().getMonthValue() - 1;
                assertThat(new BigDecimal(basics.get(at).toString()).subtract(new BigDecimal(basics.get(at - 1).toString())))
                        .isEqualByComparingTo(r.raiseAmount());
                assertThat(JsonPath.<List<String>>read(p, "$.months[" + at + "].events").toString()).contains(r.raiseAmount().toPlainString());
                assertThat(JsonPath.<Integer>read(p, "$.raises")).isEqualTo(1);
            }
            assertThat(JsonPath.<List<Object>>read(p, "$.totals")).hasSize(1);
            mvc.perform(get("/api/payroll/projection").param("userId", String.valueOf(user)).param("year", "2027")
                    .param("format", "xlsx").session(hr)).andExpect(status().isOk());
            mvc.perform(get("/api/payroll/projection").param("userId", String.valueOf(user)).param("year", "2027")
                    .param("format", "pdf").session(hr)).andExpect(status().isOk());

            String group = mvc.perform(json(post("/api/payroll/projection/group").session(hr), "{\"year\":2027,\"variable\":\"NONE\"}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<Integer>read(group, "$.people")).isGreaterThanOrEqualTo(2);
            assertThat(JsonPath.<List<Object>>read(group, "$.months")).isNotEmpty();
        } finally {
            jdbc.sql("update app_user set status = 'DISABLED' where id = ?").param(user).update();
        }
    }
}
