package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** Annual leave is earned month by month (DRC art. 141): the closed monthly payroll credits it, the payslip shows it. */
class LeaveAccrualIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    /** Approved leave in a past month (beyond the 60 days a request can go back), written as HR would have approved it. */
    long approvedLeave(long userId, String code, String from, String to, int days) {
        return jdbc.sql("""
                insert into leave_request (company_id, user_id, leave_type_id, start_date, end_date, working_days, status,
                                           requested_by, decided_by, decided_at)
                values (1, ?, (select id from leave_type where code = ? and company_id = 1), cast(? as date), cast(? as date), ?,
                        'APPROVED', 'test', 'test', now()) returning id""")
                .params(userId, code, from, to, days).query(Long.class).single();
    }

    @Test
    void theClosedMonthlyPayrollCreditsTheAnnualLeaveEarnedThatMonth() throws Exception {
        MockHttpSession me = login(EMPLOYEE);
        usdRate("2026-05-29", 2250);
        MockHttpSession hr = login(HR);
        MockHttpSession finance = login(FINANCE);
        long userId = jdbc.sql("select id from app_user where email = ?").param(EMPLOYEE).query(Long.class).single();
        // This year's leave was granted in one go: for this test it is earned month by month instead.
        jdbc.sql("""
                update leave_ledger set kind = 'ADJUSTMENT' where user_id = ? and kind = 'GRANT' and year = 2026
                and leave_type_id = (select id from leave_type where code = 'ANNUAL' and company_id = 1)""").param(userId).update();
        try {
            approvedLeave(userId, "ANNUAL", "2026-05-04", "2026-05-08", 5);
            approvedLeave(userId, "UNPAID", "2026-05-11", "2026-05-15", 5);
            String run = mvc.perform(post("/api/payroll/runs/2026-05/calculate").session(hr).with(csrf()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            long slip = JsonPath.<List<Number>>read(run, "$.payslips[?(@.userId==" + userId + ")].id").getFirst().longValue();
            String detail = mvc.perform(get("/api/payslips/" + slip).session(hr)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            // Category EXEC: 20 days a year, 1.6667 a month. Only the paid leave week is service: no attendance was
            // recorded for the rest of May, and unpaid leave and unjustified absence do not earn leave.
            BigDecimal earned = new BigDecimal(JsonPath.read(detail, "$.snapshot.leaveEarned").toString());
            assertThat(earned).isPositive().isLessThan(new BigDecimal("0.5"));
            BigDecimal before = jdbc.sql("""
                    select coalesce(sum(days), 0) from leave_ledger where user_id = ?
                    and leave_type_id = (select id from leave_type where code = 'ANNUAL' and company_id = 1)""")
                    .param(userId).query(BigDecimal.class).single();
            assertThat(new BigDecimal(JsonPath.read(detail, "$.snapshot.leaveBalance").toString()))
                    .isEqualByComparingTo(before.add(earned));

            mvc.perform(post("/api/payroll/runs/2026-05/approve").session(finance).with(csrf())).andExpect(status().isOk());
            mvc.perform(post("/api/payroll/runs/2026-05/close").session(hr).with(csrf())).andExpect(status().isOk());
            assertThat(jdbc.sql("select days from leave_ledger where user_id = ? and kind = 'EARNED' and entry_date = '2026-05-31'")
                    .param(userId).query(BigDecimal.class).single()).isEqualByComparingTo(earned);
            mvc.perform(get("/api/payslips/" + slip + ".pdf").session(me)).andExpect(status().isOk());
        } finally {
            jdbc.sql("delete from leave_ledger where user_id = ? and kind = 'EARNED'").param(userId).update();
            jdbc.sql("delete from leave_request where user_id = ? and requested_by = 'test'").param(userId).update();
            jdbc.sql("""
                    update leave_ledger set kind = 'GRANT' where user_id = ? and kind = 'ADJUSTMENT' and year = 2026
                    and note like 'Annual leave 2026%'""").param(userId).update();
        }
    }
}
