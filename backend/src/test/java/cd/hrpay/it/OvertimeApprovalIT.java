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
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

class OvertimeApprovalIT extends IntegrationTest {

    static final ZoneId KIN = ZoneId.of("Africa/Kinshasa");
    static final String EMPLOYEE2 = "employee2@demo.hrpay.cd";
    static final String MANAGER = "manager@demo.hrpay.cd";

    @Autowired
    ClockEventRepository events;
    @Autowired
    UserRepository users;

    void day(AppUser u, LocalDate date, String in, String out) {
        events.save(new ClockEvent(u.getId(), ClockEvent.Type.IN, date.atTime(LocalTime.parse(in)).atZone(KIN)
                .toOffsetDateTime(), date, ClockEvent.Source.SELF, "t"));
        events.save(new ClockEvent(u.getId(), ClockEvent.Type.OUT, date.atTime(LocalTime.parse(out)).atZone(KIN)
                .toOffsetDateTime(), date, ClockEvent.Source.SELF, "t"));
    }

    @Test
    void approvedOvertimeIsPaidWithTheWeeklyTiers() throws Exception {
        AppUser employee = users.findByEmailIgnoreCase(EMPLOYEE2).orElseThrow();
        day(employee, LocalDate.of(2026, 8, 3), "08:00", "19:00");   // Monday: 2 h overtime
        day(employee, LocalDate.of(2026, 8, 8), "08:00", "12:00");   // Saturday: 4 h on a rest day
        String decision = "{\"userId\":%d,\"workDate\":\"%s\",\"approve\":%s,\"minutes\":%s,\"note\":%s}";

        MockHttpSession manager = login(MANAGER);
        mvc.perform(get("/api/overtime/pending").param("from", "2026-08-01").param("to", "2026-08-31").session(manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].minutes").value(120))
                .andExpect(jsonPath("$[1].kind").value("REST_DAY"));

        mvc.perform(json(post("/api/overtime/decisions").session(login(EMPLOYEE)),
                        decision.formatted(employee.getId(), "2026-08-03", true, "null", "null")))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/overtime/decisions").session(manager),
                        decision.formatted(employee.getId(), "2026-08-03", true, 200, "null")))
                .andExpect(status().is(422));
        mvc.perform(json(post("/api/overtime/decisions").session(manager),
                        decision.formatted(employee.getId(), "2026-08-03", true, 90, "\"Only 90 minutes were requested\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedMinutes").value(90));
        mvc.perform(json(post("/api/overtime/decisions").session(manager),
                        decision.formatted(employee.getId(), "2026-08-03", true, "null", "null")))
                .andExpect(status().isConflict());
        mvc.perform(json(post("/api/overtime/decisions").session(manager),
                        decision.formatted(employee.getId(), "2026-08-08", false, "null", "null")))
                .andExpect(status().is(422));
        mvc.perform(json(post("/api/overtime/decisions").session(manager),
                        decision.formatted(employee.getId(), "2026-08-08", false, "null", "\"Not authorised\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        // August 2026: 21 workdays = 10,080 minutes for 1,200 USD. 90 min x 1.30 = 117 weighted minutes = 13.93.
        mvc.perform(get("/api/attendance/month").param("month", "2026-08").session(login(EMPLOYEE2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overtimeApprovedMinutes").value(90))
                .andExpect(jsonPath("$.overtimePendingMinutes").value(0))
                .andExpect(jsonPath("$.overtime[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.overtime[1].status").value("REJECTED"))
                .andExpect(jsonPath("$.earnings.overtimeEarned").value(13.93));
    }
}
