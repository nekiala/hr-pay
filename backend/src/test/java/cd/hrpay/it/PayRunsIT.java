package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/**
 * The Pay runs page: the types a company can run, every run in one list, and the 13th month as its own run that the
 * month-end payroll of its month pays.
 */
class PayRunsIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void thirteenthMonthIsItsOwnRunPaidByTheMonthEndPayroll() throws Exception {
        usdRate("2025-12-31", 2800);
        MockHttpSession hr = login(HR);
        MockHttpSession finance = login(FINANCE);
        MockHttpSession me = login(EMPLOYEE);
        try {
            mvc.perform(get("/api/pay-runs/types").session(me)).andExpect(status().isForbidden());
            // A company that does not pay a 13th month cannot run one.
            mvc.perform(json(put("/api/statutory/settings/THIRTEENTH_ENABLED").session(hr), "{\"value\":\"false\"}")).andExpect(status().isOk());
            mvc.perform(get("/api/pay-runs/types").session(hr)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.type=='THIRTEENTH')].available").value(false))
                    .andExpect(jsonPath("$[?(@.type=='MONTHLY')].available").value(true));
            mvc.perform(json(post("/api/thirteenth-runs/calculate").session(hr), "{\"year\":2025,\"month\":\"2025-12\"}"))
                    .andExpect(status().is(422));
            mvc.perform(json(put("/api/statutory/settings/THIRTEENTH_ENABLED").session(hr), "{\"value\":\"true\"}")).andExpect(status().isOk());

            // Paid with a payroll of the year or the next one only.
            mvc.perform(json(post("/api/thirteenth-runs/calculate").session(hr), "{\"year\":2025,\"month\":\"2027-01\"}"))
                    .andExpect(status().is(422));
            mvc.perform(json(post("/api/thirteenth-runs/calculate").session(me), "{\"year\":2025,\"month\":\"2025-12\"}"))
                    .andExpect(status().isForbidden());
            String run = mvc.perform(json(post("/api/thirteenth-runs/calculate").session(hr), "{\"year\":2025,\"month\":\"2025-12\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CALCULATED"))
                    .andReturn().getResponse().getContentAsString();
            long id = JsonPath.<Number>read(run, "$.id").longValue();
            List<Object> amounts = JsonPath.read(run, "$.lines[?(@.registrationNo=='EMP-0001')].amount");
            assertThat(amounts).hasSize(1);
            BigDecimal amount = new BigDecimal(amounts.getFirst().toString());
            assertThat(amount).isPositive();

            // HR leaves someone out; working it out again keeps them out.
            long other = JsonPath.<List<Number>>read(run, "$.lines[?(@.registrationNo!='EMP-0001')].userId").getFirst().longValue();
            mvc.perform(json(post("/api/thirteenth-runs/" + id + "/exclusions").session(hr), "{\"userId\":%d,\"reason\":\"Left in March\"}".formatted(other)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.excluded.length()").value(1));
            String again = mvc.perform(json(post("/api/thirteenth-runs/calculate").session(hr), "{\"year\":2025,\"month\":\"2025-12\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                    .andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<List<Number>>read(again, "$.lines[*].userId").stream().map(Number::longValue)).doesNotContain(other);
            assertThat(JsonPath.<List<String>>read(again, "$.skipped")).anyMatch(s -> s.contains("Left in March"));

            // The payroll calculated before the approval has no 13th month, and cannot close while it waits.
            mvc.perform(post("/api/payroll/runs/2025-12/calculate").session(hr).with(csrf())).andExpect(status().isOk());
            mvc.perform(post("/api/thirteenth-runs/" + id + "/approve").session(hr).with(csrf())).andExpect(status().isForbidden());   // Finance approves
            mvc.perform(post("/api/thirteenth-runs/" + id + "/approve").session(finance).with(csrf()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
            mvc.perform(get("/api/pay-runs").param("from", "2025-12").param("to", "2025-12").session(hr)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.type=='THIRTEENTH')].stage").value("TO_PAY"))
                    .andExpect(jsonPath("$[?(@.type=='MONTHLY')].stage").value("TO_APPROVE"));
            mvc.perform(get("/api/pay-runs/month/2025-12").session(hr)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.notices[?(@.type=='MONTHLY')].level").value("WARN"));
            mvc.perform(post("/api/payroll/runs/2025-12/approve").session(finance).with(csrf())).andExpect(status().isOk());
            mvc.perform(post("/api/payroll/runs/2025-12/close").session(hr).with(csrf())).andExpect(status().is(422));
            mvc.perform(json(post("/api/payroll/runs/2025-12/send-back").session(finance), "{\"reason\":\"13th month\"}")).andExpect(status().isOk());

            // Calculated again: the payslip carries it, taxed with the month.
            String view = mvc.perform(post("/api/payroll/runs/2025-12/calculate").session(hr).with(csrf())).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            long slip = JsonPath.<List<Number>>read(view, "$.payslips[?(@.registrationNo=='EMP-0001')].id").getFirst().longValue();
            String detail = mvc.perform(get("/api/payslips/" + slip).session(hr)).andReturn().getResponse().getContentAsString();
            List<Object> line = JsonPath.read(detail, "$.lines[?(@.elementCode=='THIRTEENTH_MONTH')].amount");
            assertThat(line).hasSize(1);
            String currency = JsonPath.read(detail, "$.currency");
            String lineCurrency = JsonPath.<List<String>>read(again, "$.lines[?(@.registrationNo=='EMP-0001')].currency").getFirst();
            if (currency.equals(lineCurrency)) {
                assertThat(new BigDecimal(line.getFirst().toString())).isEqualByComparingTo(amount);
            }
            mvc.perform(post("/api/payroll/runs/2025-12/approve").session(finance).with(csrf())).andExpect(status().isOk());
            mvc.perform(post("/api/payroll/runs/2025-12/close").session(hr).with(csrf())).andExpect(status().isOk());
            mvc.perform(get("/api/thirteenth-runs/" + id).session(hr)).andExpect(jsonPath("$.status").value("PAID"));
            mvc.perform(post("/api/thirteenth-runs/" + id + "/cancel").session(hr).with(csrf())).andExpect(status().is(422));
        } finally {
            jdbc.sql("delete from loan_repayment where run_id in (select id from payroll_run where month = '2025-12-01')").update();
            jdbc.sql("delete from leave_ledger where kind = 'EARNED' and entry_date = '2025-12-31'").update();
            jdbc.sql("delete from payroll_run where month = '2025-12-01'").update();
            jdbc.sql("delete from thirteenth_run where year = 2025").update();
        }
    }
}
