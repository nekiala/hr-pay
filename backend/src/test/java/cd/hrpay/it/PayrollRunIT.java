package cd.hrpay.it;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.payroll.ExchangeRates;
import cd.hrpay.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

class PayrollRunIT extends IntegrationTest {

    @Autowired
    UserRepository users;

    @Autowired
    ExchangeRates rates;

    long id(String email) {
        return users.findByEmailIgnoreCase(email).orElseThrow().getId();
    }

    @Test
    void calculateApproveAndCloseAMonth() throws Exception {
        rates.store("""
                {"data":[{"currency_code":"USD","rate_date":"2026-10-30","indicative_rate":"2250"}]}""");
        MockHttpSession hr = login(HR);
        MockHttpSession finance = login(FINANCE);
        long employee = id("employee1@demo.hrpay.cd");

        String loan = mvc.perform(json(post("/api/loans").session(hr), """
                        {"userId":%d,"kind":"LOAN","principal":100,"currency":"USD","installment":50,
                         "startMonth":"2026-10","grantedOn":"2026-09-20","note":"Medical"}""".formatted(employee)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(100))
                .andReturn().getResponse().getContentAsString();
        long loanId = JsonPath.<Number>read(loan, "$.id").longValue();

        String elements = mvc.perform(get("/api/pay-elements").session(hr)).andReturn().getResponse().getContentAsString();
        long bonus = JsonPath.<List<Number>>read(elements, "$[?(@.code=='PERF_BONUS')].id").getFirst().longValue();
        mvc.perform(json(post("/api/pay-entries").session(hr), """
                        {"userId":%d,"elementId":%d,"month":"2026-10","amount":400,"currency":"USD"}""".formatted(employee, bonus)))
                .andExpect(status().isCreated());

        String run = mvc.perform(post("/api/payroll/runs/2026-10/calculate").session(hr).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.status").value("CALCULATED"))
                .andExpect(jsonPath("$.run.usdCdf").value(2250))
                .andExpect(jsonPath("$.run.projected").value(false))
                .andReturn().getResponse().getContentAsString();
        int payslips = JsonPath.<Integer>read(run, "$.run.employeeCount");
        assertThat(payslips).isGreaterThanOrEqualTo(5);
        long slip = JsonPath.<List<Number>>read(run, "$.payslips[?(@.userId==" + employee + ")].id").getFirst().longValue();

        String detail = mvc.perform(get("/api/payslips/" + slip).session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.grade").value("G1B · Grade 1B (sample)"))
                .andExpect(jsonPath("$.snapshot.childrenInCharge").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(detail, "$.lines[*].elementCode"))
                .contains("BASIC", "PERF_BONUS", "FAMILY_ALLOWANCE", "CNSS_EMPLOYEE", "IPR", "LOAN", "CNSS_EMPLOYER", "INPP");
        // The employee cannot see it before the month is closed.
        mvc.perform(get("/api/payslips/" + slip).session(login(EMPLOYEE))).andExpect(status().isForbidden());

        // HR calculated, so HR cannot approve; Finance can. Once approved, pay entries for the month are locked.
        mvc.perform(post("/api/payroll/runs/2026-10/approve").session(hr).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/payroll/runs/2026-10/approve").session(finance).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.run.status").value("APPROVED"));
        mvc.perform(json(post("/api/pay-entries").session(hr), """
                        {"userId":%d,"elementId":%d,"month":"2026-10","amount":10,"currency":"USD"}"""
                        .formatted(id("employee2@demo.hrpay.cd"), bonus)))
                .andExpect(status().is(422));
        mvc.perform(post("/api/payroll/runs/2026-10/calculate").session(hr).with(csrf())).andExpect(status().is(422));

        mvc.perform(post("/api/payroll/runs/2026-10/close").session(finance).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/payroll/runs/2026-10/close").session(hr).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.status").value("CLOSED"))
                .andExpect(jsonPath("$.payslips[0].number").value("PS-2026-10-0001"));

        mvc.perform(get("/api/loans/" + loanId).session(hr))
                .andExpect(jsonPath("$.loan.repaid").value(50))
                .andExpect(jsonPath("$.loan.balance").value(50));
        mvc.perform(get("/api/my/payslips").session(login(EMPLOYEE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(slip));
        mvc.perform(get("/api/payslips/" + slip).session(login(EMPLOYEE))).andExpect(status().isOk());
        mvc.perform(get("/api/payroll/runs").session(login(EMPLOYEE))).andExpect(status().isForbidden());
    }

    @Test
    void aFutureMonthCannotBeCalculated() throws Exception {
        mvc.perform(post("/api/payroll/runs/2099-01/calculate").session(login(HR)).with(csrf()))
                .andExpect(status().is(422));
    }
}
