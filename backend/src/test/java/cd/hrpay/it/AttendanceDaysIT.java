package cd.hrpay.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.attendance.ClockEvent;
import cd.hrpay.attendance.ClockEventRepository;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

class AttendanceDaysIT extends IntegrationTest {

    static final String EMPLOYEE2 = "employee2@demo.hrpay.cd";

    @Autowired
    ClockEventRepository events;
    @Autowired
    UserRepository users;

    void clock(AppUser user, LocalDate date, String in, String out) {
        ZoneId kin = ZoneId.of("Africa/Kinshasa");
        events.save(new ClockEvent(user.getId(), ClockEvent.Type.IN,
                date.atTime(java.time.LocalTime.parse(in)).atZone(kin).toOffsetDateTime(), date, ClockEvent.Source.SELF, "t"));
        events.save(new ClockEvent(user.getId(), ClockEvent.Type.OUT,
                date.atTime(java.time.LocalTime.parse(out)).atZone(kin).toOffsetDateTime(), date, ClockEvent.Source.SELF, "t"));
    }

    @Test
    void monthSummaryProratesGrossSalaryByPayableMinutes() throws Exception {
        AppUser employee = users.findByEmailIgnoreCase(EMPLOYEE2).orElseThrow();
        clock(employee, LocalDate.of(2026, 10, 5), "08:00", "17:00");      // full day
        clock(employee, LocalDate.of(2026, 10, 6), "08:30", "18:00");      // 30 min late, 60 min overtime
        clock(employee, LocalDate.of(2026, 10, 10), "09:00", "11:00");     // Saturday

        // October 2026: 22 workdays of 8 hours = 10,560 minutes. Payable 480 + 450 = 930 minutes of 1,200 USD.
        mvc.perform(get("/api/attendance/month").param("month", "2026-10").session(login(EMPLOYEE2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduledMinutesMonth").value(10560))
                .andExpect(jsonPath("$.payableMinutesToDate").value(930))
                .andExpect(jsonPath("$.overtimeMinutesToDate").value(180))
                .andExpect(jsonPath("$.lateDays").value(1))
                .andExpect(jsonPath("$.absentDays").value(20))
                .andExpect(jsonPath("$.earnings.grossMonthly").value(1200.00))
                .andExpect(jsonPath("$.earnings.currency").value("USD"))
                .andExpect(jsonPath("$.earnings.earnedToDate").value(105.68))
                .andExpect(jsonPath("$.days[9].status").value("REST_DAY"))
                .andExpect(jsonPath("$.days[9].overtimeKind").value("REST_DAY"));
    }

    @Test
    void employeesSeeOnlyTheirOwnAttendanceAndHrSeesEveryone() throws Exception {
        AppUser employee = users.findByEmailIgnoreCase(EMPLOYEE2).orElseThrow();
        mvc.perform(get("/api/attendance/users/" + employee.getId() + "/month").session(login(EMPLOYEE)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/attendance/users/" + employee.getId() + "/month").param("month", "2026-10")
                        .session(login(HR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.earnings.grossMonthly").value(1200.00));
        mvc.perform(get("/api/attendance/days").param("from", "2026-01-01").param("to", "2026-12-31")
                        .session(login(EMPLOYEE2)))
                .andExpect(status().is(422));
    }

    @Test
    void hrAddsSalariesWithoutDuplicatesAndEmployeesCannot() throws Exception {
        AppUser employee = users.findByEmailIgnoreCase(EMPLOYEE2).orElseThrow();
        MockHttpSession hr = login(HR);
        String body = "{\"effectiveFrom\":\"2026-12-01\",\"grossMonthly\":1350,\"currency\":\"USD\",\"note\":\"Promotion\"}";
        mvc.perform(json(post("/api/users/" + employee.getId() + "/salaries").session(hr), body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.grossMonthly").value(473))      // the E4A basic; 877 is the complement
                .andExpect(jsonPath("$.createdBy").value(HR));
        mvc.perform(json(post("/api/users/" + employee.getId() + "/salaries").session(hr), body))
                .andExpect(status().isConflict());
        mvc.perform(json(post("/api/users/" + employee.getId() + "/salaries").session(login(EMPLOYEE2)), body))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/users/" + employee.getId() + "/salaries").session(hr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].effectiveFrom").value("2026-12-01"))
                .andExpect(jsonPath("$[1].grossMonthly").value(1200.00));
    }
}
