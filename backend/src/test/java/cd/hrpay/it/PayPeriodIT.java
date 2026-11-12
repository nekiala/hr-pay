package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.schedule.WorkCalendar;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** A grade category paid on decades from the 21st: the payslip reads the attendance of the 21st to the 20th. */
class PayPeriodIT extends IntegrationTest {

    @Autowired
    UserRepository users;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    WorkCalendar calendar;

    /** On time every working day from..to, except the days given. */
    private void present(AppUser user, LocalDate from, LocalDate to, List<LocalDate> absent) {
        var zone = java.time.ZoneId.of(user.getSite() == null || user.getSite().getTimeZone() == null ? "Africa/Kinshasa" : user.getSite().getTimeZone());
        for (var day : calendar.plan(user, from, to)) {
            if (!day.expectedAtWork() || day.start() == null || day.end() == null || absent.contains(day.date())) {
                continue;
            }
            for (var e : List.of(Map.entry("IN", day.date().atTime(day.start()).minusMinutes(3)),
                    Map.entry("OUT", day.date().atTime(day.end()).plusMinutes(4)))) {
                jdbc.sql("""
                        insert into clock_event (user_id, type, occurred_at, work_date, site_id, source, note, created_by)
                        values (?, ?, ?, ?, ?, 'SELF', 'test', 'test')""")
                        .params(user.getId(), e.getKey(), e.getValue().atZone(zone).toOffsetDateTime(), day.date(),
                                user.getSite() == null ? null : user.getSite().getId())
                        .update();
            }
        }
    }

    private String slip(MockHttpSession hr, String run, long userId) throws Exception {
        long id = JsonPath.<List<Number>>read(run, "$.payslips[?(@.userId==" + userId + ")].id").getFirst().longValue();
        return mvc.perform(get("/api/payslips/" + id).session(hr)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void anAbsenceAfterThe20thIsTakenOffTheNextMonth() throws Exception {
        usdRate("2026-02-27", 2800);
        usdRate("2026-03-31", 2800);
        MockHttpSession hr = login(HR);
        AppUser employee = users.findByEmailIgnoreCase(EMPLOYEE).orElseThrow();
        AppUser other = users.findByEmailIgnoreCase(HR).orElseThrow();
        long exec = jdbc.sql("select id from grade_category where code = 'EXEC'").query(Long.class).single();
        LocalDate absence = LocalDate.of(2026, 2, 25);
        present(employee, LocalDate.of(2026, 1, 21), LocalDate.of(2026, 3, 31), List.of(absence));
        present(other, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 31), List.of());

        mvc.perform(get("/api/grade-categories/pay-period-preview?kind=DECADES&firstDecadeDay=21&from=2026-11").session(hr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].from").value("2026-10-21"))
                .andExpect(jsonPath("$[0].to").value("2026-11-20"))
                .andExpect(jsonPath("$[0].decades[0].to").value("2026-10-31"));
        mvc.perform(json(put("/api/grade-categories/" + exec + "/pay-period").session(hr), """
                {"kind":"DECADES","firstDecadeDay":15}""")).andExpect(status().is(422));
        mvc.perform(json(put("/api/grade-categories/" + exec + "/pay-period").session(hr), """
                {"kind":"DECADES","firstDecadeDay":21}""")).andExpect(status().isOk());
        try {
            // February: attendance 21 January to 20 February; the absence on the 25th is not counted yet.
            String feb = mvc.perform(post("/api/payroll/runs/2026-02/calculate").session(hr).with(csrf()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.run.projected").value(false))
                    .andReturn().getResponse().getContentAsString();
            String febSlip = slip(hr, feb, employee.getId());
            assertThat(JsonPath.<String>read(febSlip, "$.snapshot.periodFrom")).isEqualTo("2026-01-21");
            assertThat(JsonPath.<String>read(febSlip, "$.snapshot.periodTo")).isEqualTo("2026-02-20");
            assertThat(JsonPath.<List<?>>read(febSlip, "$.snapshot.decades")).hasSize(3);
            int febScheduled = JsonPath.read(febSlip, "$.scheduledMinutes");
            assertThat(JsonPath.<Integer>read(febSlip, "$.paidMinutes")).isEqualTo(febScheduled);
            // The calendar-month categories are as before.
            String hrSlip = slip(hr, feb, other.getId());
            assertThat(JsonPath.<String>read(hrSlip, "$.snapshot.periodFrom")).isEqualTo("2026-02-01");
            assertThat(JsonPath.<String>read(hrSlip, "$.snapshot.periodTo")).isEqualTo("2026-02-28");

            // March: attendance 21 February to 20 March; the absence on 25 February is taken off, at February's rate.
            String mar = mvc.perform(post("/api/payroll/runs/2026-03/calculate").session(hr).with(csrf()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String marSlip = slip(hr, mar, employee.getId());
            assertThat(JsonPath.<String>read(marSlip, "$.snapshot.periodFrom")).isEqualTo("2026-02-21");
            assertThat(JsonPath.<String>read(marSlip, "$.snapshot.periodTo")).isEqualTo("2026-03-20");
            assertThat(JsonPath.<Integer>read(marSlip, "$.snapshot.absentDays")).isEqualTo(1);
            int marScheduled = JsonPath.read(marSlip, "$.scheduledMinutes");
            int marPaid = JsonPath.read(marSlip, "$.paidMinutes");
            int dayMinutes = calendar.plan(employee, absence, absence).getFirst().scheduledMinutes();
            assertThat(marScheduled - marPaid).isEqualTo(Math.round((float) dayMinutes * marScheduled / febScheduled));
            assertThat(jdbc.sql("select var_to from payslip p join payroll_run r on r.id = p.run_id where r.month = ? and p.user_id = ?")
                    .params(LocalDate.of(2026, 3, 1), employee.getId()).query(LocalDate.class).single())
                    .isEqualTo(LocalDate.of(2026, 3, 20));
        } finally {
            jdbc.sql("update grade_category set pay_period = 'MONTH', first_decade_day = 1 where id = ?").param(exec).update();
            for (String m : List.of("2026-02-01", "2026-03-01")) {
                jdbc.sql("delete from payroll_check_acceptance where run_id in (select id from payroll_run where month = ?::date)").param(m).update();
                jdbc.sql("delete from payslip where run_id in (select id from payroll_run where month = ?::date)").param(m).update();
                jdbc.sql("delete from payroll_run where month = ?::date").param(m).update();
            }
            jdbc.sql("delete from clock_event where note = 'test' and created_by = 'test' and work_date between ? and ?")
                    .params(LocalDate.of(2026, 1, 21), LocalDate.of(2026, 3, 31)).update();
        }
    }
}
