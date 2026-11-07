package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.payroll.ExchangeRates;
import cd.hrpay.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

class PaymentsIT extends IntegrationTest {

    @Autowired
    UserRepository users;

    @Autowired
    ExchangeRates rates;

    @Autowired
    JdbcClient jdbc;

    long id(String email) {
        return users.findByEmailIgnoreCase(email).orElseThrow().getId();
    }

    @Test
    void payDeclareAndSummariseAClosedMonth() throws Exception {
        rates.store("""
                {"data":[{"currency_code":"USD","rate_date":"2026-08-31","indicative_rate":"2000"}]}""");
        MockHttpSession hr = login(HR);
        MockHttpSession finance = login(FINANCE);
        long david = id("employee3@demo.hrpay.cd");
        long patrick = id("finance@demo.hrpay.cd");
        // David is paid by M-Pesa; Patrick's bank account is missing.
        jdbc.sql("update employee_profile set payment_method = 'MOBILE_MONEY', mobile_money_operator = 'M-Pesa ', mobile_money_number = '081 000 0007' where user_id = ?")
                .param(david).update();
        jdbc.sql("update employee_profile set bank_account = null where user_id = ?").param(patrick).update();
        try {
            // Nobody clocked in during August in the test data, so a bonus gives these three a net pay.
            String elements = mvc.perform(get("/api/pay-elements").session(hr)).andReturn().getResponse().getContentAsString();
            long bonus = JsonPath.<List<Number>>read(elements, "$[?(@.code=='OTHER_BONUS')].id").getFirst().longValue();
            for (long u : List.of(id(EMPLOYEE), david, patrick)) {
                mvc.perform(json(post("/api/pay-entries").session(hr), """
                                {"userId":%d,"elementId":%d,"month":"2026-08","amount":300,"currency":"USD"}""".formatted(u, bonus)))
                        .andExpect(status().isCreated());
            }
            mvc.perform(post("/api/payroll/runs/2026-08/calculate").session(hr).with(csrf())).andExpect(status().isOk());

            // Nothing can be paid or filed before the month is closed.
            mvc.perform(json(post("/api/payments/2026-08/batches").session(finance), """
                            {"method":"BANK","provider":"RAWBANK (SAMPLE)","currency":"USD"}"""))
                    .andExpect(status().is(422));
            mvc.perform(get("/api/declarations/2026-08/CNSS").session(finance))
                    .andExpect(jsonPath("$.warnings[0]").value(org.hamcrest.Matchers.containsString("not closed")));

            mvc.perform(post("/api/payroll/runs/2026-08/approve").session(finance).with(csrf())).andExpect(status().isOk());
            mvc.perform(post("/api/payroll/runs/2026-08/close").session(hr).with(csrf())).andExpect(status().isOk());

            String overview = mvc.perform(get("/api/payments/2026-08").session(finance)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.runStatus").value("CLOSED"))
                    .andExpect(jsonPath("$.problems[?(@.userId==" + patrick + ")].problem").isNotEmpty())
                    .andExpect(jsonPath("$.toPay[?(@.provider=='M-PESA')].count").isNotEmpty())
                    .andReturn().getResponse().getContentAsString();
            int bankCount = JsonPath.<List<Integer>>read(overview, "$.toPay[?(@.provider=='RAWBANK (SAMPLE)')].count").getFirst();
            assertThat(bankCount).isGreaterThanOrEqualTo(1);

            // HR cannot make payment files; Finance can.
            mvc.perform(json(post("/api/payments/2026-08/batches").session(hr), """
                            {"method":"MOBILE_MONEY","provider":"M-PESA","currency":"USD"}"""))
                    .andExpect(status().isForbidden());
            String mobile = mvc.perform(json(post("/api/payments/2026-08/batches").session(finance), """
                            {"method":"MOBILE_MONEY","provider":"M-PESA","currency":"USD"}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.batch.status").value("PREPARED"))
                    .andExpect(jsonPath("$.lines[?(@.userId==" + david + ")].account").value(org.hamcrest.Matchers.contains("243810000007")))
                    .andReturn().getResponse().getContentAsString();
            long mobileId = JsonPath.<Number>read(mobile, "$.batch.id").longValue();
            String csv = mvc.perform(get("/api/payment-batches/" + mobileId + "/file.csv").session(finance))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(csv).contains("No,MSISDN,Beneficiary,Amount,Currency,Reference").contains("243810000007,David Lukusa");
            // The same people cannot be put in a second file.
            mvc.perform(json(post("/api/payments/2026-08/batches").session(finance), """
                            {"method":"MOBILE_MONEY","provider":"M-PESA","currency":"USD"}"""))
                    .andExpect(status().is(422));

            String bank = mvc.perform(json(post("/api/payments/2026-08/batches").session(finance), """
                            {"method":"BANK","provider":"RAWBANK (SAMPLE)","currency":"USD"}"""))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            long bankId = JsonPath.<Number>read(bank, "$.batch.id").longValue();
            // Cancelled, the people are free again and a new file can be made.
            mvc.perform(json(post("/api/payment-batches/" + bankId + "/cancel").session(finance), "{\"reason\":\"Wrong date\"}"))
                    .andExpect(jsonPath("$.batch.status").value("CANCELLED")).andExpect(jsonPath("$.lines.length()").value(0));
            bank = mvc.perform(json(post("/api/payments/2026-08/batches").session(finance), """
                            {"method":"BANK","provider":"RAWBANK (SAMPLE)","currency":"USD"}"""))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            bankId = JsonPath.<Number>read(bank, "$.batch.id").longValue();

            // A bank payment needs its reference; it then cannot be cancelled.
            mvc.perform(json(post("/api/payment-batches/" + bankId + "/paid").session(finance), "{\"paidOn\":\"2026-09-02\"}"))
                    .andExpect(status().is(422));
            mvc.perform(json(post("/api/payment-batches/" + bankId + "/paid").session(finance),
                            "{\"paidOn\":\"2026-09-02\",\"reference\":\"OP-7781\"}"))
                    .andExpect(jsonPath("$.batch.status").value("PAID"))
                    .andExpect(jsonPath("$.batch.reference").value("OP-7781"));
            mvc.perform(json(post("/api/payment-batches/" + bankId + "/cancel").session(finance), "{\"reason\":\"x\"}"))
                    .andExpect(status().isConflict());
            mvc.perform(get("/api/payments/2026-08").session(finance))
                    .andExpect(jsonPath("$.paid").value(bankCount));

            // Declarations in CDF at the payroll's rate.
            String cnss = mvc.perform(get("/api/declarations/2026-08/cnss").session(finance)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.usdCdf").value(2000))
                    .andExpect(jsonPath("$.warnings.length()").value(0))
                    .andReturn().getResponse().getContentAsString();
            String employeeSlip = jdbc.sql("""
                    select p.cnss_base::text from payslip p join payroll_run r on r.id = p.run_id
                    where r.month = '2026-08-01' and p.user_id = ?""").param(id(EMPLOYEE)).query(String.class).single();
            BigDecimal baseCdf = new BigDecimal(JsonPath.<Object>read(cnss, "$.rows[?(@.userId==" + id(EMPLOYEE) + ")].baseCdf")
                    .toString().replaceAll("[\\[\\]]", ""));
            assertThat(baseCdf).isEqualByComparingTo(new BigDecimal(employeeSlip).multiply(BigDecimal.valueOf(2000)));
            mvc.perform(get("/api/declarations/2026-08/IERE").session(finance)).andExpect(jsonPath("$.rows.length()").value(0));
            mvc.perform(get("/api/declarations/2026-08/IPR/file.csv").session(finance)).andExpect(status().isOk());

            mvc.perform(json(post("/api/declarations/2026-08/CNSS/filed").session(finance),
                            "{\"filedOn\":\"2026-09-10\",\"reference\":\"CNSS-0908\"}"))
                    .andExpect(jsonPath("$.filed.reference").value("CNSS-0908"));
            mvc.perform(json(post("/api/declarations/2026-08/CNSS/filed").session(finance), "{\"filedOn\":\"2026-09-10\"}"))
                    .andExpect(status().isConflict());
            mvc.perform(get("/api/declarations/2026-08").session(hr))
                    .andExpect(jsonPath("$.declarations[0].kind").value("CNSS"))
                    .andExpect(jsonPath("$.declarations[0].filed.filedOn").value("2026-09-10"))
                    .andExpect(jsonPath("$.declarations[1].filed").doesNotExist());
            mvc.perform(delete("/api/declarations/2026-08/CNSS/filed").session(hr).with(csrf()))
                    .andExpect(jsonPath("$.filed").doesNotExist());
            mvc.perform(get("/api/declarations/2026-08").session(login(EMPLOYEE))).andExpect(status().isForbidden());

            // Year-end: the employee sees their own year, with the paid date; HR sees everyone.
            mvc.perform(get("/api/my/year-summary?year=2026").session(login(EMPLOYEE))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.months[?(@.month=='2026-08')].paidOn").value(org.hamcrest.Matchers.contains("2026-09-02")))
                    .andExpect(jsonPath("$.details.taxNumber").value("NIF-EMP-0001"));
            mvc.perform(get("/api/year-summaries?year=2026").session(hr)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.userId==" + david + ")].months").isNotEmpty());
            mvc.perform(get("/api/year-summaries?year=2026").session(login(EMPLOYEE))).andExpect(status().isForbidden());
        } finally {
            jdbc.sql("update employee_profile set payment_method = 'BANK', mobile_money_operator = null, mobile_money_number = null where user_id = ?")
                    .param(david).update();
            jdbc.sql("update employee_profile set bank_account = '0001-FIN-0001' where user_id = ?").param(patrick).update();
        }
    }

    @Test
    void companyDetailsAreKeptByHr() throws Exception {
        mvc.perform(json(put("/api/company").session(login(HR)), """
                        {"name":"Demo SARL","cnssNumber":"CNSS-EMP-1","taxNumber":"A1234567B","bankAccount":"00011-0001"}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.taxNumber").value("A1234567B"));
        mvc.perform(json(put("/api/company").session(login(FINANCE)), "{\"name\":\"Other\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/company").session(login(FINANCE))).andExpect(jsonPath("$.name").value("Demo SARL"));
    }
}
