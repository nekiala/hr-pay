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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.annotation.Transactional;

class CalendarIT extends IntegrationTest {

    @Autowired
    WorkCalendar calendar;
    @Autowired
    UserRepository users;

    static String week(String extra) {
        StringBuilder days = new StringBuilder();
        for (int d = 1; d <= 7; d++) {
            boolean work = d <= 5;
            days.append(d > 1 ? "," : "").append("""
                    {"weekday":%d,"workday":%s,"startTime":%s,"endTime":%s,"lunchEnabled":%s,"lunchStart":%s,
                     "lunchMinutes":%s,"lunchPaid":false}""".formatted(d, work, work ? "\"07:30\"" : "null",
                    work ? "\"16:00\"" : "null", work, work ? "\"12:00\"" : "null", work ? "30" : "null"));
        }
        return """
                {"code":"%s","name":"Early shift","graceMinutes":5,"deductLateArrival":true,
                 "deductEarlyDeparture":true,"active":true,"days":[%s]}""".formatted(extra, days);
    }

    @Test
    @Transactional
    void november2026HasTwentyOneWorkdaysOf168Hours() {
        AppUser employee = users.findByEmailIgnoreCase(EMPLOYEE).orElseThrow();
        List<WorkCalendar.DayPlan> plans = calendar.plan(employee, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
        assertThat(plans.stream().filter(WorkCalendar.DayPlan::workday).count()).isEqualTo(21);
        assertThat(WorkCalendar.scheduledMinutes(plans)).isEqualTo(168 * 60);
        assertThat(plans.getFirst().workday()).isFalse(); // Sunday 1 November
    }

    @Test
    @Transactional
    void independenceDayIsAPaidHolidayOnAWorkday() {
        AppUser employee = users.findByEmailIgnoreCase(EMPLOYEE).orElseThrow();
        WorkCalendar.DayPlan june30 = calendar.plan(employee, LocalDate.of(2026, 6, 30));
        assertThat(june30.workday()).isTrue();
        assertThat(june30.holiday()).isTrue();
        assertThat(june30.expectedAtWork()).isFalse();
        assertThat(june30.scheduledMinutes()).isEqualTo(480);
    }

    @Test
    void hrMovesAHolidayToTheAnnouncedDate() throws Exception {
        MockHttpSession hr = login(HR);
        String year = mvc.perform(get("/api/holidays").param("year", "2024").session(hr))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Number id = JsonPath.<List<Number>>read(year, "$[?(@.name==\"Fête de l'indépendance\")].id").getFirst();
        // 30 June 2024 was a Sunday; suppose the government moved it to Monday 1 July.
        mvc.perform(json(put("/api/holidays/occurrences/" + id).session(hr),
                        "{\"observedDate\":\"2024-07-01\",\"announcementRef\":\"Communiqué 2024/06\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observedDate").value("2024-07-01"))
                .andExpect(jsonPath("$.officialDate").value("2024-06-30"))
                .andExpect(jsonPath("$.moved").value(true))
                .andExpect(jsonPath("$.setBy").value(HR));
        mvc.perform(json(put("/api/holidays/occurrences/" + id).session(login(EMPLOYEE)),
                        "{\"observedDate\":\"2024-07-02\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void schedulesAreValidatedAndAssignedFromADate() throws Exception {
        MockHttpSession hr = login(HR);
        String created = mvc.perform(json(post("/api/schedules").session(hr), week("EARLY")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.weeklyMinutes").value(5 * 480))
                .andReturn().getResponse().getContentAsString();
        long scheduleId = ((Number) JsonPath.read(created, "$.id")).longValue();

        String bad = week("BADLUNCH").replace("\"lunchStart\":\"12:00\"", "\"lunchStart\":\"15:50\"");
        mvc.perform(json(post("/api/schedules").session(hr), bad)).andExpect(status().isUnprocessableContent());

        long userId = users.findByEmailIgnoreCase("employee2@demo.hrpay.cd").orElseThrow().getId();
        mvc.perform(json(post("/api/users/" + userId + "/schedules").session(hr),
                        "{\"scheduleId\":%d,\"effectiveFrom\":\"2026-12-01\"}".formatted(scheduleId)))
                .andExpect(status().isOk());
        AppUser user = users.findById(userId).orElseThrow();
        assertThat(calendar.scheduleOn(userId, LocalDate.of(2026, 11, 30)).orElseThrow().getCode()).isEqualTo("STANDARD");
        assertThat(calendar.scheduleOn(userId, LocalDate.of(2026, 12, 1)).orElseThrow().getCode()).isEqualTo("EARLY");
        assertThat(user).isNotNull();
    }

    @Test
    void overtimeRatesMustCoverEveryHourWithoutGaps() throws Exception {
        MockHttpSession admin = login(ADMIN);
        String gap = """
                {"effectiveFrom":"2027-01-01","allowed":true,"requiresApproval":true,"roundingMinutes":15,
                 "tiers":[{"kind":"WEEKDAY","fromHour":0,"toHour":6,"multiplier":1.3},
                          {"kind":"WEEKDAY","fromHour":8,"toHour":null,"multiplier":1.6},
                          {"kind":"REST_DAY","fromHour":0,"toHour":null,"multiplier":2},
                          {"kind":"HOLIDAY","fromHour":0,"toHour":null,"multiplier":2}]}""";
        mvc.perform(json(put("/api/overtime-policies").session(admin), gap)).andExpect(status().isUnprocessableContent());
        mvc.perform(json(put("/api/overtime-policies").session(admin), gap.replace("\"fromHour\":8", "\"fromHour\":6")))
                .andExpect(status().isOk());
        mvc.perform(json(put("/api/overtime-policies").session(login(HR)), gap)).andExpect(status().isForbidden());
        mvc.perform(get("/api/overtime-policies").session(admin))
                .andExpect(jsonPath("$[0].effectiveFrom").value("2027-01-01"))
                .andExpect(jsonPath("$[1].effectiveFrom").value("2020-01-01"));
    }
}
