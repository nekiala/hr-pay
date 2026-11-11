package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.attendance.AttendanceDays;
import cd.hrpay.attendance.DayCalculator;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

class TravelMissionIT extends IntegrationTest {

    static final ZoneId KIN = ZoneId.of("Africa/Kinshasa");
    static final String EMPLOYEE2 = "employee2@demo.hrpay.cd";
    static final String MANAGER = "manager@demo.hrpay.cd";

    @Autowired
    UserRepository users;
    @Autowired
    AttendanceDays attendance;
    @Autowired
    JdbcClient jdbc;

    AppUser user(String email) {
        return users.findByEmailIgnoreCase(email).orElseThrow();
    }

    String request(MockHttpSession who, String country, String city, LocalDate from, LocalDate to, String extra) throws Exception {
        return mvc.perform(json(post("/api/travel-missions").session(who), """
                        {"countryCode":"%s","city":"%s","startDate":"%s","endDate":"%s","purpose":"Deploy the new release at the client"%s}"""
                        .formatted(country, city, from, to, extra)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void domesticMissionIsPaidAsWorkedAndCarriesACheckableOrder() throws Exception {
        LocalDate monday = LocalDate.now(KIN).plusDays(200).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        LocalDate friday = monday.plusDays(4);
        MockHttpSession me = login(EMPLOYEE);
        Number id = JsonPath.read(request(me, "CD", "Lubumbashi", monday, friday, ""), "$[0].id");
        mvc.perform(get("/api/travel-missions/" + id).session(me))
                .andExpect(jsonPath("$.mission.status").value("PENDING"))
                .andExpect(jsonPath("$.mission.scope").value("DOMESTIC"))
                .andExpect(jsonPath("$.mission.step").value("MANAGER"))
                .andExpect(jsonPath("$.mission.days").value(5));
        // The same dates again clash; the employee cannot approve their own mission.
        mvc.perform(json(post("/api/travel-missions").session(me), """
                {"countryCode":"CD","city":"Kolwezi","startDate":"%s","endDate":"%s","purpose":"Another trip there"}"""
                .formatted(friday, friday.plusDays(1)))).andExpect(status().isConflict());
        mvc.perform(json(post("/api/travel-missions/" + id + "/decision").session(me), "{\"approve\":true}"))
                .andExpect(status().isForbidden());
        // No order before approval.
        mvc.perform(get("/api/travel-missions/" + id + "/order.pdf").session(me)).andExpect(status().isConflict());

        // The unit head approves a domestic mission alone.
        mvc.perform(json(post("/api/travel-missions/" + id + "/decision").session(login(MANAGER)), "{\"approve\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.number").value(org.hamcrest.Matchers.startsWith("OM-" + monday.getYear() + "-")));

        byte[] pdf = mvc.perform(get("/api/travel-missions/" + id + "/order.pdf").session(me))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");

        // Every scheduled day of the trip is worked and paid in full, without a check-in.
        List<DayCalculator.DayResult> days = attendance.days(user(EMPLOYEE), monday, friday);
        assertThat(days).allSatisfy(d -> {
            if (d.scheduledMinutes() > 0) {
                assertThat(d.status()).isEqualTo(DayCalculator.Status.MISSION);
                assertThat(d.payableMinutes()).isEqualTo(d.scheduledMinutes());
                assertThat(d.leaveType()).isEqualTo("Mission: Lubumbashi");
            }
        });

        // The QR code's page answers anyone, with the minimum.
        String token = jdbc.sql("select verify_token::text from mission where id = ?").param(id.longValue()).query(String.class).single();
        mvc.perform(get("/api/public/mission-check/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.destination").value(org.hamcrest.Matchers.startsWith("Lubumbashi")))
                .andExpect(jsonPath("$.registrationNo").doesNotExist());
        mvc.perform(get("/api/public/mission-check/" + java.util.UUID.randomUUID())).andExpect(status().isNotFound());

        // Cancelled by HR: the order no longer checks out and the days are no longer a mission.
        mvc.perform(json(post("/api/travel-missions/" + id + "/cancel").session(me), "{}"))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/travel-missions/" + id + "/cancel").session(login(HR)), "{\"reason\":\"Client postponed\"}"))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(get("/api/public/mission-check/" + token)).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(attendance.days(user(EMPLOYEE), monday, monday).getFirst().status()).isNotEqualTo(DayCalculator.Status.MISSION);
    }

    @Test
    void internationalMissionNeedsTheUnitHeadThenHr() throws Exception {
        LocalDate from = LocalDate.now(KIN).plusDays(230);
        MockHttpSession me = login(EMPLOYEE);
        Number id = JsonPath.read(request(me, "JP", "Tokyo", from, from.plusDays(9), ",\"transport\":\"PLANE\""), "$[0].id");
        mvc.perform(json(post("/api/travel-missions/" + id + "/decision").session(login(MANAGER)), "{\"approve\":true}"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.step").value("HR"))
                .andExpect(jsonPath("$.zone").value("WORLD"));
        // The unit head cannot give the second approval.
        mvc.perform(json(post("/api/travel-missions/" + id + "/decision").session(login(MANAGER)), "{\"approve\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/travel-missions/to-decide").session(login(HR)))
                .andExpect(jsonPath("$[?(@.id == %s)]".formatted(id)).exists());
        mvc.perform(json(post("/api/travel-missions/" + id + "/decision").session(login(HR)), "{\"approve\":true}"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.firstApprovedBy").value(MANAGER));
        byte[] pdf = mvc.perform(get("/api/travel-missions/" + id + "/order.pdf").session(me))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        // French page, then the same in English.
        assertThat(new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1).split("/Type\\s*/Page[^s]", -1).length - 1).isEqualTo(2);
    }

    @Test
    void missionMoneyAdvanceExpensesReportAndSettlement() throws Exception {
        MockHttpSession hr = login(HR);
        mvc.perform(json(post("/api/travel-missions/per-diem").session(hr),
                        "{\"zone\":\"DOMESTIC\",\"amount\":50,\"lodgingAmount\":30,\"currency\":\"USD\"}"))
                .andExpect(status().isCreated());
        LocalDate from = LocalDate.now(KIN).minusDays(40);
        LocalDate to = from.plusDays(4);
        // HR sends employee 2 at a daily rate above the scale; the hotel is not booked by the company.
        String created = request(hr, "CD", "Kisangani", from, to,
                ",\"userIds\":[%d],\"perDiemRate\":60,\"lodgingByCompany\":false".formatted(user(EMPLOYEE2).getId()));
        Number id = JsonPath.read(created, "$[0].id");
        assertThat(JsonPath.<Number>read(created, "$[0].perDiemScale").doubleValue()).isEqualTo(50.0);
        assertThat(JsonPath.<Number>read(created, "$[0].lodgingRate").doubleValue()).isEqualTo(30.0);
        // An employee cannot set a rate.
        mvc.perform(json(post("/api/travel-missions").session(login(EMPLOYEE2)), """
                {"countryCode":"CD","city":"Goma","startDate":"%s","endDate":"%s","purpose":"Training session","perDiemRate":500}"""
                .formatted(from.plusDays(300), from.plusDays(301)))).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/travel-missions/" + id + "/decision").session(hr), "{\"approve\":true}"))
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // The advance goes to the payment files of the month.
        mvc.perform(json(post("/api/travel-missions/" + id + "/advance").session(hr), "{\"amount\":200}"))
                .andExpect(jsonPath("$.money.paid").value(200.0));
        mvc.perform(json(post("/api/travel-missions/" + id + "/advance").session(hr), "{\"amount\":5000}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(get("/api/payments/" + YearMonth.now(KIN) + "?kind=MISSION").session(login(FINANCE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payslips").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        // The employee adds a cost with its receipt and files the report.
        MockHttpSession emp = login(EMPLOYEE2);
        String detail = mvc.perform(json(post("/api/travel-missions/" + id + "/expenses").session(emp), """
                        {"spentOn":"%s","category":"TRANSPORT","description":"Taxi from the airport","amount":25,
                         "fileName":"taxi.png","contentType":"image/png","base64":"iVBORw0KGgo="}""".formatted(from)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Number expense = JsonPath.read(detail, "$.expenses[0].id");
        mvc.perform(get("/api/travel-missions/" + id + "/expenses/" + expense + "/receipt").session(emp))
                .andExpect(status().isOk());
        mvc.perform(post("/api/travel-missions/" + id + "/close").session(hr).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isUnprocessableContent()); // no report yet
        mvc.perform(json(post("/api/travel-missions/" + id + "/report").session(emp),
                        "{\"report\":\"Installed the release at the Kisangani branch and trained four users.\"}"))
                .andExpect(jsonPath("$.mission.reportFiled").value(true));
        mvc.perform(json(post("/api/travel-missions/" + id + "/expenses/" + expense + "/decision").session(hr), "{\"approve\":true}"))
                .andExpect(jsonPath("$.money.expensesAccepted").value(25.0));

        // Due: 5 days x 60 + 4 nights x 30 + 25 = 445; advance 200; balance 245 to pay. Excess 5 x 10 = 50 on the payslip.
        mvc.perform(post("/api/travel-missions/" + id + "/close").session(hr).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mission.status").value("CLOSED"))
                .andExpect(jsonPath("$.money.due").value(445.0))
                .andExpect(jsonPath("$.money.excess").value(50.0))
                .andExpect(jsonPath("$.money.balance").value(0.0))
                .andExpect(jsonPath("$.payments[1].kind").value("BALANCE"))
                .andExpect(jsonPath("$.payments[1].amount").value(245.0));
        BigDecimal excess = jdbc.sql("""
                select e.amount from pay_entry e join pay_element p on p.id = e.element_id
                where e.user_id = ? and p.code = 'MISSION_EXCESS'""").param(user(EMPLOYEE2).getId()).query(BigDecimal.class).single();
        assertThat(excess).isEqualByComparingTo("50");
    }
}
