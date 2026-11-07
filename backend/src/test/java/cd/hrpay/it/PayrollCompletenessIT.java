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

/** Phase 14: final settlement, corrections of a closed month, back pay, accounting entries, parallel run, sign-off. */
class PayrollCompletenessIT extends IntegrationTest {

    @Autowired
    UserRepository users;

    @Autowired
    ExchangeRates rates;

    @Autowired
    JdbcClient jdbc;

    long id(String email) {
        return users.findByEmailIgnoreCase(email).orElseThrow().getId();
    }

    long element(MockHttpSession s, String code) throws Exception {
        String elements = mvc.perform(get("/api/pay-elements").session(s)).andReturn().getResponse().getContentAsString();
        return JsonPath.<List<Number>>read(elements, "$[?(@.code=='" + code + "')].id").getFirst().longValue();
    }

    @Test
    void theNoticeFollowsTheGradeCategoryAndWhoGaveIt() throws Exception {
        MockHttpSession hr = login(HR);
        long esther = id("employee2@demo.hrpay.cd");
        record Before(Long category, java.time.LocalDate hired) {}
        Before before = jdbc.sql("select grade_category_id as category, hire_date as hired from app_user where id = ?").param(esther).query(Before.class).single();
        long cat1 = jdbc.sql("select id from grade_category where code = 'EXEC'").query(Long.class).single();
        // The categories carry their notice; only master-data managers change it.
        mvc.perform(get("/api/leave/categories").session(hr))
                .andExpect(jsonPath("$[?(@.code=='MAITRISE')].noticeBaseUnit").value("MONTHS"))
                .andExpect(jsonPath("$[?(@.code=='CADRE_COLLAB')].noticeDaysPerYear").value(16.0));
        mvc.perform(json(put("/api/grade-categories/" + cat1 + "/notice").session(login(EMPLOYEE)),
                "{\"base\":14,\"baseUnit\":\"DAYS\",\"daysPerYear\":7,\"dayKind\":\"WORKING\",\"resignationShare\":0.5}"))
                .andExpect(status().isForbidden());
        jdbc.sql("update app_user set grade_category_id = ?, hire_date = '2026-01-05' where id = ?").params(cat1, esther).update();
        try {
            // Resigning in the first year: half of 14 working days, counted from the day after, Sundays left out.
            mvc.perform(json(post("/api/users/" + esther + "/departure/propose").session(hr), """
                            {"lastDay":"2026-06-05","reason":"RESIGNATION","noticeDate":"2026-06-01"}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.noticeGivenBy").value("EMPLOYEE"))
                    .andExpect(jsonPath("$.noticeEnd").value("2026-06-09"))
                    .andExpect(jsonPath("$.notice").value("OWED"))
                    .andExpect(jsonPath("$.noticeDays").value(3))
                    .andExpect(jsonPath("$.noticeOwed").value(org.hamcrest.Matchers.greaterThan(0.0)))
                    .andExpect(jsonPath("$.noticeAmount").value(0.0));
            // Worked to its end: nothing is owed.
            mvc.perform(json(post("/api/users/" + esther + "/departure/propose").session(hr), """
                            {"lastDay":"2026-06-09","reason":"RESIGNATION","noticeDate":"2026-06-01"}"""))
                    .andExpect(jsonPath("$.notice").value("WORKED")).andExpect(jsonPath("$.noticeOwed").value(0.0));
            // The employer's notice is the whole 14 days; leaving earlier, the rest is paid.
            mvc.perform(json(post("/api/users/" + esther + "/departure/propose").session(hr), """
                            {"lastDay":"2026-06-09","reason":"DISMISSAL","noticeDate":"2026-06-01"}"""))
                    .andExpect(jsonPath("$.noticeGivenBy").value("EMPLOYER"))
                    .andExpect(jsonPath("$.noticeEnd").value("2026-06-17"))
                    .andExpect(jsonPath("$.notice").value("PAID"))
                    .andExpect(jsonPath("$.noticeDays").value(7));
            // Saved and confirmed, what is owed is a deduction on the last payslip.
            mvc.perform(json(put("/api/users/" + esther + "/departure").session(hr), """
                    {"lastDay":"2026-06-05","reason":"RESIGNATION","noticeDate":"2026-06-01","leaveDays":0}""")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.noticeOwed").value(org.hamcrest.Matchers.greaterThan(0.0)));
            mvc.perform(post("/api/users/" + esther + "/departure/confirm").session(hr).with(csrf())).andExpect(status().isOk());
            assertThat(jdbc.sql("""
                    select count(*) from pay_entry e join pay_element p on p.id = e.element_id
                    where e.user_id = ? and p.code = 'NOTICE_OWED' and p.kind = 'DEDUCTION'""").param(esther).query(Integer.class).single())
                    .isEqualTo(1);
            mvc.perform(delete("/api/users/" + esther + "/departure").session(hr).with(csrf())).andExpect(status().isNoContent());
        } finally {
            jdbc.sql("update app_user set grade_category_id = ?, hire_date = ? where id = ?")
                    .params(before.category(), before.hired(), esther).update();
        }
    }

    @Test
    void aDepartureEndsTheContractAndItsSettlementIsOnTheLastPayslip() throws Exception {
        rates.store("""
                {"data":[{"currency_code":"USD","rate_date":"2026-04-30","indicative_rate":"2300"}]}""");
        MockHttpSession hr = login(HR);
        long esther = id("employee2@demo.hrpay.cd");

        // The proposal: dismissal, so the notice is paid; nothing is saved yet.
        mvc.perform(json(post("/api/users/" + esther + "/departure/propose").session(hr), """
                        {"lastDay":"2026-04-15","reason":"DISMISSAL"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notice").value("PAID"))
                .andExpect(jsonPath("$.noticeAmount").isNumber())
                .andExpect(jsonPath("$.explanation").isNotEmpty());
        mvc.perform(get("/api/users/" + esther + "/departure").session(hr)).andExpect(status().isNoContent());

        mvc.perform(json(put("/api/users/" + esther + "/departure").session(hr), """
                        {"lastDay":"2026-04-15","reason":"DISMISSAL","leaveDays":6,"note":"Restructuring"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.leaveDays").value(6));
        // Not confirmed: no certificate yet; an employee cannot see departures.
        mvc.perform(get("/api/users/" + esther + "/departure/certificate.pdf").session(hr)).andExpect(status().is(422));
        mvc.perform(get("/api/departures").session(login(EMPLOYEE))).andExpect(status().isForbidden());

        String confirmed = mvc.perform(post("/api/users/" + esther + "/departure/confirm").session(hr).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andReturn().getResponse().getContentAsString();
        BigDecimal notice = new BigDecimal(JsonPath.read(confirmed, "$.noticeAmount").toString());
        // The departure checklist starts by itself.
        assertThat(jdbc.sql("select count(*) from employee_checklist where user_id = ? and kind = 'DEPARTURE' and status = 'OPEN'")
                .param(esther).query(Integer.class).single()).isEqualTo(1);
        assertThat(notice).isPositive();
        assertThat(jdbc.sql("select end_date from employee_contract where user_id = ? and end_date = '2026-04-15'")
                .param(esther).query(java.time.LocalDate.class).optional()).isPresent();
        try {
            String run = mvc.perform(post("/api/payroll/runs/2026-04/calculate").session(hr).with(csrf()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            long slip = JsonPath.<List<Number>>read(run, "$.payslips[?(@.userId==" + esther + ")].id").getFirst().longValue();
            String detail = mvc.perform(get("/api/payslips/" + slip).session(hr)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.snapshot.lastDay").value("2026-04-15"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<List<String>>read(detail, "$.lines[*].elementCode")).contains("NOTICE_PAY", "LEAVE_PAYOUT");

            byte[] pdf = mvc.perform(get("/api/users/" + esther + "/departure/certificate.pdf").session(hr))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
            mvc.perform(get("/api/departures").session(hr)).andExpect(jsonPath("$[?(@.userId==" + esther + ")].status").value("CONFIRMED"));
        } finally {
            // The month is still open: the departure can be taken back, and the contract runs on.
            mvc.perform(delete("/api/users/" + esther + "/departure").session(hr).with(csrf())).andExpect(status().isNoContent());
        }
        assertThat(jdbc.sql("select count(*) from employee_contract where user_id = ? and end_date = '2026-04-15'")
                .param(esther).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from employee_checklist where user_id = ? and kind = 'DEPARTURE' and status = 'OPEN'")
                .param(esther).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from pay_entry e join pay_element p on p.id = e.element_id where e.user_id = ? and p.code = 'NOTICE_PAY'")
                .param(esther).query(Integer.class).single()).isZero();
    }

    @Test
    void aClosedMonthIsCorrectedNeverEdited() throws Exception {
        rates.store("""
                {"data":[{"currency_code":"USD","rate_date":"2026-03-31","indicative_rate":"2280"}]}""");
        MockHttpSession hr = login(HR);
        MockHttpSession finance = login(FINANCE);
        long jean = id(EMPLOYEE);
        long david = id("employee3@demo.hrpay.cd");
        long bonus = element(hr, "OTHER_BONUS");
        for (long u : List.of(jean, david)) {
            mvc.perform(json(post("/api/pay-entries").session(hr), """
                            {"userId":%d,"elementId":%d,"month":"2026-03","amount":500,"currency":"USD"}""".formatted(u, bonus)))
                    .andExpect(status().isCreated());
        }
        String run = mvc.perform(post("/api/payroll/runs/2026-03/calculate").session(hr).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/payroll/runs/2026-03/approve").session(finance).with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/payroll/runs/2026-03/close").session(hr).with(csrf())).andExpect(status().isOk());
        long jeanSlip = JsonPath.<List<Number>>read(run, "$.payslips[?(@.userId==" + jean + ")].id").getFirst().longValue();
        long davidSlip = JsonPath.<List<Number>>read(run, "$.payslips[?(@.userId==" + david + ")].id").getFirst().longValue();

        // A bonus forgotten in March: the preview shows the difference; nothing is stored.
        mvc.perform(json(post("/api/payslips/" + jeanSlip + "/correction/preview").session(hr), """
                        {"extra":[{"elementId":%d,"amount":200,"note":"forgotten"}]}""".formatted(bonus)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[?(@.code=='OTHER_BONUS')].change").value(200.0))
                .andExpect(jsonPath("$.lines[?(@.code=='CNSS_EMPLOYEE')].change").isNotEmpty());

        // As a complementary payslip of March: it waits for Finance, who must not be the one who made it.
        String c = mvc.perform(json(post("/api/payslips/" + jeanSlip + "/corrections").session(hr), """
                        {"mode":"COMPLEMENTARY","reason":"Bonus forgotten","extra":[{"elementId":%d,"amount":200}]}""".formatted(bonus)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andReturn().getResponse().getContentAsString();
        long correction = JsonPath.<Number>read(c, "$.id").longValue();
        long complementary = JsonPath.<Number>read(c, "$.complementaryId").longValue();
        // The employee does not see it before approval.
        mvc.perform(get("/api/payslips/" + complementary).session(login(EMPLOYEE))).andExpect(status().isForbidden());
        mvc.perform(post("/api/payroll/corrections/" + correction + "/approve").session(hr).with(csrf())).andExpect(status().isForbidden());
        String number = JsonPath.read(mvc.perform(get("/api/payslips/" + jeanSlip).session(hr)).andReturn().getResponse().getContentAsString(), "$.number");
        mvc.perform(post("/api/payroll/corrections/" + correction + "/approve").session(finance).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.complementaryNumber").value(number + "-C1"));
        mvc.perform(get("/api/payslips/" + complementary).session(login(EMPLOYEE))).andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("COMPLEMENTARY"))
                .andExpect(jsonPath("$.correctsNumber").value(number));
        byte[] pdf = mvc.perform(get("/api/payslips/" + complementary + ".pdf").session(hr)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        // The original stays as it was; the declaration adds both, one row per person.
        mvc.perform(get("/api/payslips/" + jeanSlip).session(hr))
                .andExpect(jsonPath("$.lines[?(@.elementCode=='OTHER_BONUS')].amount").value(500.0));
        String cnss = mvc.perform(get("/api/declarations/2026-03/CNSS").session(finance)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(cnss, "$.rows[?(@.userId==" + jean + ")]")).hasSize(1);

        // Carried into the next payroll instead: a back pay entry appears in the open month; it can be taken back.
        String carried = mvc.perform(json(post("/api/payslips/" + davidSlip + "/corrections").session(hr), """
                        {"mode":"NEXT_PAYROLL","reason":"Bonus forgotten","extra":[{"elementId":%d,"amount":100}]}""".formatted(bonus)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CARRIED"))
                .andExpect(jsonPath("$.grossChange").value(100.0))
                .andReturn().getResponse().getContentAsString();
        String target = JsonPath.read(carried, "$.targetMonth");
        assertThat(jdbc.sql("""
                select count(*) from pay_entry e join pay_element p on p.id = e.element_id
                where e.user_id = ? and p.code = 'BACK_PAY' and e.month = cast(? as date)""").params(david, target)
                .query(Integer.class).single()).isEqualTo(1);
        // A complementary payslip after a carry is refused: one way per payslip.
        mvc.perform(json(post("/api/payslips/" + davidSlip + "/corrections").session(hr), """
                        {"mode":"COMPLEMENTARY","reason":"Again","extra":[{"elementId":%d,"amount":50}]}""".formatted(bonus)))
                .andExpect(status().is(422));
        mvc.perform(delete("/api/payroll/corrections/" + JsonPath.<Number>read(carried, "$.id")).session(hr).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.sql("""
                select count(*) from pay_entry e join pay_element p on p.id = e.element_id
                where e.user_id = ? and p.code = 'BACK_PAY'""").param(david).query(Integer.class).single()).isZero();

        // A reversal cancels the whole payslip: its net is the opposite of what was paid.
        BigDecimal net = new BigDecimal(JsonPath.read(mvc.perform(get("/api/payslips/" + davidSlip).session(hr))
                .andReturn().getResponse().getContentAsString(), "$.net").toString());
        String reversal = mvc.perform(json(post("/api/payslips/" + davidSlip + "/corrections").session(hr), """
                        {"mode":"REVERSAL","reason":"Paid by mistake"}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WAITING"))
                .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(JsonPath.read(reversal, "$.net").toString())).isEqualByComparingTo(net.negate());
        mvc.perform(delete("/api/payroll/corrections/" + JsonPath.<Number>read(reversal, "$.id")).session(finance).with(csrf()))
                .andExpect(status().isNoContent());

        // A raise dated back into March shows as back pay due.
        var salary = jdbc.sql("select gross_monthly, currency from employee_salary where user_id = ? and effective_from <= '2026-03-31' order by effective_from desc limit 1")
                .param(jean).query((rs, i) -> new Object[] {rs.getBigDecimal(1), rs.getString(2)}).single();
        mvc.perform(json(post("/api/users/" + jean + "/salaries").session(hr), """
                        {"effectiveFrom":"2026-03-02","grossMonthly":%s,"currency":"%s","note":"Raise dated back"}"""
                        .formatted(((BigDecimal) salary[0]).add(BigDecimal.valueOf(100)).toPlainString(), salary[1])))
                .andExpect(status().isCreated());
        mvc.perform(json(post("/api/users/" + jean + "/salaries").session(hr), """
                        {"effectiveFrom":"2026-04-02","grossMonthly":%s,"currency":"%s"}""".formatted(((BigDecimal) salary[0]).toPlainString(), salary[1])))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/payroll/back-pay").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.payslipId==" + jeanSlip + ")].basicNow").isNotEmpty());

        // Accounting entries of March: they balance, in the local currency.
        String journal = mvc.perform(get("/api/accounting/2026-03").session(finance)).andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("CDF"))
                .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(JsonPath.read(journal, "$.debit").toString()))
                .isEqualByComparingTo(new BigDecimal(JsonPath.read(journal, "$.credit").toString()));
        assertThat(JsonPath.<List<String>>read(journal, "$.entries[*].account")).contains("6612", "4310", "4220", "6641");
        mvc.perform(get("/api/accounting/2026-03.csv").session(finance)).andExpect(status().isOk());
        mvc.perform(get("/api/accounting/2026-03").session(login(EMPLOYEE))).andExpect(status().isForbidden());

        // The parallel run against the old payroll's figures.
        String jeanNet = JsonPath.read(mvc.perform(get("/api/payslips/" + jeanSlip).session(hr)).andReturn().getResponse()
                .getContentAsString(), "$.net").toString();
        BigDecimal both = new BigDecimal(jeanNet).add(new BigDecimal(JsonPath.read(mvc.perform(get("/api/payslips/" + complementary)
                .session(hr)).andReturn().getResponse().getContentAsString(), "$.net").toString()));
        mvc.perform(json(post("/api/payroll/runs/2026-03/compare").session(hr), """
                        {"csv":"Matricule;Net à payer\\nEMP-0001;%s\\nOLD-9999;100,00\\n"}""".formatted(both.toPlainString().replace('.', ','))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.registrationNo=='EMP-0001')].status").value("MATCH"))
                .andExpect(jsonPath("$.rows[?(@.registrationNo=='OLD-9999')].status").value("ONLY_THEIRS"));
    }

    @Test
    void ratesAndFormulasAreSignedOff() throws Exception {
        MockHttpSession hr = login(HR);
        mvc.perform(get("/api/payroll/certification.xlsx").session(hr)).andExpect(status().isOk());
        mvc.perform(json(post("/api/payroll/certification").session(hr), """
                        {"certifiedBy":"Albert Mbodo, expert-comptable","certifiedOn":"2026-11-01"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.last.certifiedBy").value("Albert Mbodo, expert-comptable"))
                .andExpect(jsonPath("$.ratesChangedSince").value(0));
        mvc.perform(json(post("/api/payroll/certification").session(hr), """
                        {"certifiedBy":"Someone","certifiedOn":"2099-01-01"}"""))
                .andExpect(status().is(422));
        mvc.perform(get("/api/accounting/accounts").session(login(FINANCE))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='BASIC')].defaultExpense").value("6611"));
    }
}
