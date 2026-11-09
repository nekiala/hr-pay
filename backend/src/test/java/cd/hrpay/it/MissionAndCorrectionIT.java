package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.attendance.AutoClose;
import cd.hrpay.attendance.ClockEvent;
import cd.hrpay.attendance.ClockEventRepository;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

class MissionAndCorrectionIT extends IntegrationTest {

    static final ZoneId KIN = ZoneId.of("Africa/Kinshasa");
    static final String EMPLOYEE2 = "employee2@demo.hrpay.cd";
    static final String MANAGER = "manager@demo.hrpay.cd";

    @Autowired
    ClockEventRepository events;
    @Autowired
    UserRepository users;
    @Autowired
    AutoClose autoClose;

    AppUser user(String email) {
        return users.findByEmailIgnoreCase(email).orElseThrow();
    }

    void checkIn(AppUser u, LocalDate date, String time) {
        events.save(new ClockEvent(u.getId(), ClockEvent.Type.IN, date.atTime(LocalTime.parse(time)).atZone(KIN)
                .toOffsetDateTime(), date, ClockEvent.Source.SELF, u.getEmail()));
    }

    @Test
    void approvedMissionEndsTheOpenDayAtTheMissionEnd() throws Exception {
        // The sample manager has no events yet; HR approves missions of anyone.
        AppUser manager = user(MANAGER);
        LocalDate yesterday = LocalDate.now(KIN).minusDays(1);
        checkIn(manager, yesterday, "08:00");
        String created = mvc.perform(json(post("/api/missions").session(login(MANAGER)), """
                        {"workDate":"%s","startTime":"15:00","endTime":"19:00","location":"Ministry of Labour, Gombe",
                         "purpose":"Submit the CNSS file"}""".formatted(yesterday)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(created, "$.id");
        mvc.perform(json(post("/api/missions/" + id + "/decision").session(login(MANAGER)), "{\"approve\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/missions/" + id + "/decision").session(login(HR)), "{\"approve\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        ClockEvent last = events.findLast(manager.getId()).orElseThrow();
        assertThat(last.getType()).isEqualTo(ClockEvent.Type.OUT);
        assertThat(last.getSource()).isEqualTo(ClockEvent.Source.MISSION);
        assertThat(last.getOccurredAt().atZoneSameInstant(KIN).toLocalTime()).isEqualTo(LocalTime.of(19, 0));
    }

    @Test
    void managerDecidesOnlyForTheirTeam() throws Exception {
        MockHttpSession me = login(EMPLOYEE2);
        String body = """
                {"workDate":"%s","startTime":"15:00","endTime":"19:00","location":"Client office",
                 "purpose":"Installation"}""".formatted(LocalDate.now(KIN).plusDays(2));
        String created = mvc.perform(json(post("/api/missions").session(me), body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(created, "$.id");
        mvc.perform(json(post("/api/missions").session(me), body)).andExpect(status().isConflict());
        mvc.perform(json(post("/api/missions/" + id + "/decision").session(login(EMPLOYEE)), "{\"approve\":true}"))
                .andExpect(status().isForbidden());
        MockHttpSession manager = login(MANAGER);
        mvc.perform(get("/api/missions/to-decide").session(manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %s)].employeeName".formatted(id)).value("KALALA Esther"));
        mvc.perform(json(post("/api/missions/" + id + "/decision").session(manager), "{\"approve\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value(MANAGER));
    }

    @Test
    void rejectingNeedsAReason() throws Exception {
        String created = mvc.perform(json(post("/api/missions").session(login(EMPLOYEE2)), """
                        {"workDate":"%s","startTime":"09:00","endTime":"11:00","location":"Client site",
                         "purpose":"Training"}""".formatted(LocalDate.now(KIN).plusDays(3))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(created, "$.id");
        MockHttpSession hr = login(HR);
        mvc.perform(json(post("/api/missions/" + id + "/decision").session(hr), "{\"approve\":false}"))
                .andExpect(status().is(422));
        mvc.perform(json(post("/api/missions/" + id + "/decision").session(hr),
                        "{\"approve\":false,\"note\":\"Not needed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void hrAddsAndVoidsEntriesWithoutDuplicates() throws Exception {
        AppUser employee = user(EMPLOYEE2);
        String url = "/api/attendance/users/" + employee.getId() + "/events";
        MockHttpSession hr = login(HR);
        mvc.perform(json(post(url).session(hr), "{\"type\":\"IN\",\"at\":\"2026-09-01T08:05:00\",\"note\":\"Phone was off\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("HR_MANUAL"));
        mvc.perform(json(post(url).session(hr), "{\"type\":\"IN\",\"at\":\"2026-09-01T08:20:00\",\"note\":\"Again\"}"))
                .andExpect(status().isConflict());
        String out = mvc.perform(json(post(url).session(hr), "{\"type\":\"OUT\",\"at\":\"2026-09-01T17:00:00\",\"note\":\"Left on time\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(json(post(url).session(hr), "{\"type\":\"OUT\",\"at\":\"2026-09-01T12:00:00\",\"note\":\"Lunch\"}"))
                .andExpect(status().is(422));
        mvc.perform(json(post(url).session(hr), "{\"type\":\"IN\",\"at\":\"2026-09-01T09:00:00\"}"))
                .andExpect(status().isBadRequest());

        Number outId = JsonPath.read(out, "$.id");
        mvc.perform(json(post("/api/attendance/events/" + outId + "/void").session(hr), "{\"reason\":\"Wrong time\"}"))
                .andExpect(status().isOk());
        mvc.perform(json(post("/api/attendance/events/" + outId + "/void").session(hr), "{\"reason\":\"Again\"}"))
                .andExpect(status().isConflict());
        mvc.perform(json(post(url).session(login(EMPLOYEE)), "{\"type\":\"OUT\",\"at\":\"2026-09-01T16:00:00\",\"note\":\"x\"}"))
                .andExpect(status().isForbidden());
        String self = "/api/attendance/users/" + user(HR).getId() + "/events";
        mvc.perform(json(post(self).session(hr), "{\"type\":\"IN\",\"at\":\"2026-09-01T08:00:00\",\"note\":\"x\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/attendance/users/" + employee.getId() + "/days").param("from", "2026-09-01")
                        .param("to", "2026-09-01").session(hr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].corrected").value(true))
                .andExpect(jsonPath("$[0].entries.length()").value(1))
                .andExpect(jsonPath("$[0].missingCheckOut").value(true));
    }

    @Test
    void forgottenEndOfDayIsClosedAtScheduleEnd() {
        AppUser finance = user(FINANCE);
        LocalDate wednesday = LocalDate.of(2026, 9, 2);
        checkIn(finance, wednesday, "08:10");
        autoClose.run();
        List<ClockEvent> day = events.findForPeriod(finance.getId(), wednesday, wednesday);
        assertThat(day).hasSize(2);
        assertThat(day.get(1).getSource()).isEqualTo(ClockEvent.Source.AUTO_CLOSE);
        assertThat(day.get(1).getOccurredAt().atZoneSameInstant(KIN).toLocalTime()).isEqualTo(LocalTime.of(17, 0));
        assertThat(autoClose.run()).isZero();
    }
}
