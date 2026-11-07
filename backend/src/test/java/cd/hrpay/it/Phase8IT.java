package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** Phase 8: presence and exceptions, Excel exports, PDF payslips, holidays moved in a closed month, privacy. */
class Phase8IT extends IntegrationTest {

    static final String MANAGER = "manager@demo.hrpay.cd";

    @Autowired
    UserRepository users;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    cd.hrpay.payroll.ExchangeRates rates;

    long id(String email) {
        return users.findByEmailIgnoreCase(email).orElseThrow().getId();
    }

    @Test
    void presenceShowsTheBranchOrEveryone() throws Exception {
        String all = mvc.perform(get("/api/reports/presence").param("date", "2026-11-05").session(login(HR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.expected").exists())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(all, "$.people")).hasSizeGreaterThanOrEqualTo(7);
        String team = mvc.perform(get("/api/reports/presence").param("date", "2026-11-05").session(login(MANAGER)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Number>>read(team, "$.people[*].userId").stream().map(Number::longValue))
                .contains(id(EMPLOYEE)).doesNotContain(id(FINANCE));
        mvc.perform(get("/api/reports/presence").session(login(EMPLOYEE))).andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/presence").param("date", "2030-01-01").session(login(HR))).andExpect(status().is(422));
    }

    @Test
    void exceptionsAsListAndAsExcel() throws Exception {
        MockHttpSession hr = login(HR);
        mvc.perform(get("/api/reports/exceptions").param("from", "2026-10-01").param("to", "2026-10-31").session(hr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.ABSENT").exists());
        mvc.perform(get("/api/reports/exceptions").param("from", "2026-01-01").param("to", "2026-10-31").session(hr))
                .andExpect(status().is(422));
        byte[] xlsx = mvc.perform(get("/api/reports/exceptions.xlsx").param("from", "2026-10-01").param("to", "2026-10-31").session(hr))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/vnd.openxmlformats")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(sheetXml(xlsx)).contains("Registration no").contains("<sheetData>");
        mvc.perform(get("/api/reports/exceptions.csv").param("from", "2026-10-01").param("to", "2026-10-31").session(hr))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/csv"));

        // The monthly attendance export as a workbook: hours are number cells.
        byte[] month = mvc.perform(get("/api/reports/attendance.xlsx").param("month", "2026-10").session(hr))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(sheetXml(month)).contains("<c r=\"F2\"><v>");
    }

    static String sheetXml(byte[] xlsx) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if (e.getName().equals("xl/worksheets/sheet1.xml")) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("No worksheet in the workbook");
    }

    @Test
    void payslipAsPdfWithYearToDateAndAHolidayMovedInAClosedMonth() throws Exception {
        rates.store("""
                {"data":[{"currency_code":"USD","rate_date":"2026-06-30","indicative_rate":"2200"}]}""");
        MockHttpSession hr = login(HR);
        long jean = id(EMPLOYEE);
        String run = mvc.perform(post("/api/payroll/runs/2026-06/calculate").session(hr).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long slip = JsonPath.<List<Number>>read(run, "$.payslips[?(@.userId==" + jean + ")].id").getFirst().longValue();

        byte[] pdf = mvc.perform(get("/api/payslips/" + slip + ".pdf").session(hr))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        mvc.perform(get("/api/payslips/" + slip + ".pdf").session(login(EMPLOYEE))).andExpect(status().isForbidden());
        mvc.perform(get("/api/payslips/" + slip + "/year-to-date").session(hr))
                .andExpect(status().isOk()).andExpect(jsonPath("$.year").value(2026));

        // Close June; then Independence Day (30 June) is moved to 1 July: June gains a working day.
        mvc.perform(post("/api/payroll/runs/2026-06/approve").session(login(FINANCE)).with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/payroll/runs/2026-06/close").session(hr).with(csrf())).andExpect(status().isOk());
        String holidays = mvc.perform(get("/api/holidays").param("year", "2026").session(hr)).andReturn().getResponse().getContentAsString();
        Number june30 = JsonPath.<List<Number>>read(holidays, "$[?(@.officialDate=='2026-06-30')].id").getFirst();
        mvc.perform(json(put("/api/holidays/occurrences/" + june30).session(hr), "{\"observedDate\":\"2026-07-01\"}"))
                .andExpect(status().isOk());

        BigDecimal settled = jdbc.sql("select coalesce(sum(amount), 0) from holiday_adjustment where payslip_id = ?")
                .param(slip).query(BigDecimal.class).single();
        assertThat(settled.signum()).isNotZero();
        String adjustments = mvc.perform(get("/api/payroll/holiday-adjustments").session(hr))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Number>>read(adjustments, "$[?(@.payslipId==" + slip + ")].amount")).isNotEmpty();
        int entries = jdbc.sql("""
                select count(*) from pay_entry e join pay_element p on p.id = e.element_id
                where e.user_id = ? and p.code in ('HOLIDAY_ADJ', 'HOLIDAY_ADJ_LESS')""").param(jean).query(Integer.class).single();
        assertThat(entries).isEqualTo(1);

        // Moving it back settles to nothing, and the entry goes away.
        mvc.perform(json(put("/api/holidays/occurrences/" + june30).session(hr), "{\"observedDate\":\"2026-06-30\"}"))
                .andExpect(status().isOk());
        BigDecimal back = jdbc.sql("select coalesce(sum(amount), 0) from holiday_adjustment where payslip_id = ?")
                .param(slip).query(BigDecimal.class).single();
        assertThat(back).isEqualByComparingTo("0");
        int left = jdbc.sql("""
                select count(*) from pay_entry e join pay_element p on p.id = e.element_id
                where e.user_id = ? and p.code in ('HOLIDAY_ADJ', 'HOLIDAY_ADJ_LESS')""").param(jean).query(Integer.class).single();
        assertThat(left).isZero();
    }

    @Test
    void privacyNoticeAndGpsRetention() throws Exception {
        MockHttpSession me = login("employee2@demo.hrpay.cd");
        mvc.perform(get("/api/auth/me").session(me)).andExpect(jsonPath("$.privacyAccepted").value(false));
        mvc.perform(post("/api/privacy/accept").session(me).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(me)).andExpect(jsonPath("$.privacyAccepted").value(true));

        long jean = id(EMPLOYEE);
        jdbc.sql("""
                insert into clock_event (user_id, type, occurred_at, work_date, source, lat, lng, accuracy_m, created_by)
                values (?, 'OUT', now() - interval '400 days', current_date - 400, 'SELF', -4.3, 15.3, 20, 'test')""").param(jean).update();
        mvc.perform(json(put("/api/privacy/settings").session(me), "{\"gpsRetentionDays\":30}")).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/privacy/settings").session(login(ADMIN)), "{\"gpsRetentionDays\":365}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erased").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        int withPosition = jdbc.sql("select count(*) from clock_event where occurred_at < now() - interval '365 days' and lat is not null")
                .query(Integer.class).single();
        assertThat(withPosition).isZero();
        mvc.perform(get("/api/privacy/settings").session(me)).andExpect(jsonPath("$.gpsRetentionDays").value(365));
    }
}
