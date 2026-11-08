package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.attendance.ClockEvent;
import cd.hrpay.attendance.ClockEventRepository;
import cd.hrpay.payroll.PayslipCalculator;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/**
 * Salary advance on demand: off by default, the share per grade category, what is available and why, the receipt signed
 * with a one-time code, automatic and HR approval, the payslip deduction, Finance's list and the payment files.
 */
class SalaryAdvanceIT extends IntegrationTest {

    static final String EMPLOYEE2 = "employee2@demo.hrpay.cd";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ClockEventRepository events;

    long userId(String email) {
        return jdbc.sql("select id from app_user where email = ?").param(email).query(Long.class).single();
    }

    String name(String email) {
        return jdbc.sql("select first_name || ' ' || last_name from app_user where email = ?").param(email).query(String.class).single();
    }

    String latestCode(String email) {
        String body = jdbc.sql("select body from outbox_message where recipient = ? order by id desc limit 1")
                .param(email).query(String.class).single();
        Matcher m = Pattern.compile("(?:code is|est) (\\d{6})").matcher(body);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    BigDecimal entry(String email, LocalDate month) {
        return jdbc.sql("""
                select coalesce(sum(p.amount), 0) from pay_entry p join pay_element e on e.id = p.element_id join app_user u on u.id = p.user_id
                where e.code = 'EARNED_ADVANCE' and u.email = ? and p.month = ?""")
                .params(email, month).query(BigDecimal.class).single();
    }

    /** A full working day for each weekday of the month so far, so that some net is earned. */
    void worked(String email, LocalDate from, LocalDate to) {
        ZoneId kin = ZoneId.of("Africa/Kinshasa");
        long user = userId(email);
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            events.save(new ClockEvent(user, ClockEvent.Type.IN, d.atTime(LocalTime.of(7, 55)).atZone(kin).toOffsetDateTime(), d,
                    ClockEvent.Source.SELF, "advance test"));
            events.save(new ClockEvent(user, ClockEvent.Type.OUT, d.atTime(LocalTime.of(17, 5)).atZone(kin).toOffsetDateTime(), d,
                    ClockEvent.Source.SELF, "advance test"));
        }
    }

    String settings(boolean enabled, String approval) {
        return "{\"enabled\":%s,\"approval\":\"%s\",\"maxPerMonth\":2,\"closingDays\":0,\"minServiceDays\":0,\"maxDebtMonths\":3}"
                .formatted(enabled, approval);
    }

    @Test
    void art114KeepsOneFifthUpToFiveSmigAndOneThirdAbove() {
        BigDecimal ceiling = new BigDecimal("1000");
        assertThat(PayslipCalculator.assignable(new BigDecimal("500"), ceiling)).isEqualByComparingTo("100");
        assertThat(PayslipCalculator.assignable(new BigDecimal("1000"), ceiling)).isEqualByComparingTo("200");
        // 200 on the first 1000, then a third of the 600 above.
        assertThat(PayslipCalculator.assignable(new BigDecimal("1600"), ceiling)).isEqualByComparingTo("400");
    }

    @Test
    void anEmployeeTakesPartOfTheNetEarnedAndThePayslipTakesItBack() throws Exception {
        MockHttpSession hr = login(HR);
        MockHttpSession finance = login(FINANCE);
        MockHttpSession me = login(EMPLOYEE);
        MockHttpSession other = login(EMPLOYEE2);
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Kinshasa"));
        LocalDate month = YearMonth.from(today).atDay(1);
        usdRate(month.minusDays(1).toString(), 2800);

        // Off by default: the menu hides it, nobody can ask, HR sees the rules.
        mvc.perform(get("/api/advances/offered").session(me)).andExpect(status().isOk()).andExpect(jsonPath("$").value(false));
        String overview = mvc.perform(get("/api/advances").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.enabled").value(false))
                .andExpect(jsonPath("$.settings.approval").value("AUTO"))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Number>>read(overview, "$.categories[*].advancePercent"))
                .allSatisfy(p -> assertThat(p.doubleValue()).isEqualTo(50.0));
        mvc.perform(get("/api/advances").session(me)).andExpect(status().isForbidden());
        mvc.perform(get("/api/advances/mine").session(me)).andExpect(status().isOk())
                .andExpect(jsonPath("$.availability.open").value(false))
                .andExpect(jsonPath("$.availability.reason").value("Salary advances are not offered by the company."));
        mvc.perform(json(post("/api/advances/mine/code").session(me), "{\"amount\":10}")).andExpect(status().is(422));

        // The rules and the share are checked, and only HR sets them.
        mvc.perform(json(put("/api/advances/settings").session(me), settings(true, "AUTO"))).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/advances/settings").session(hr), settings(true, "BANK"))).andExpect(status().is(422));
        long category = JsonPath.<List<Number>>read(overview, "$.categories[*].id").getFirst().longValue();
        mvc.perform(json(put("/api/advances/categories/" + category).session(hr), "{\"percent\":60}")).andExpect(status().is(422));
        mvc.perform(json(put("/api/advances/categories/" + category).session(hr), "{\"percent\":5}")).andExpect(status().is(422));

        worked(EMPLOYEE, month, today);
        worked(EMPLOYEE2, month, today);
        mvc.perform(json(put("/api/advances/settings").session(hr), settings(true, "AUTO"))).andExpect(status().isOk());
        try {
            mvc.perform(get("/api/advances/offered").session(me)).andExpect(jsonPath("$").value(true));
            String mine = mvc.perform(get("/api/advances/mine").session(me)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            BigDecimal available = new BigDecimal(JsonPath.read(mine, "$.availability.available").toString());
            // Nothing is earned yet on the first working day of a month.
            Assumptions.assumeTrue(available.compareTo(BigDecimal.TEN) >= 0, "nothing earned yet this month");
            BigDecimal earned = new BigDecimal(JsonPath.read(mine, "$.availability.earnedNet").toString());
            BigDecimal net = new BigDecimal(JsonPath.read(mine, "$.availability.normalNet").toString());
            assertThat(earned).isLessThanOrEqualTo(net);
            // Half of what is earned, rounded down; nothing taken yet.
            assertThat(available).isLessThanOrEqualTo(earned.multiply(new BigDecimal("0.5")));
            assertThat(JsonPath.<List<String>>read(mine, "$.availability.explanation")).anyMatch(l -> l.contains("50%"));
            String currency = JsonPath.read(mine, "$.availability.currency");

            // Not more than available; the code is for one amount; the typed name must be the person's.
            mvc.perform(json(post("/api/advances/mine/code").session(me), "{\"amount\":%s}".formatted(available.add(BigDecimal.ONE))))
                    .andExpect(status().is(422));
            BigDecimal amount = available.divide(BigDecimal.TWO, 0, java.math.RoundingMode.DOWN);
            String sent = mvc.perform(json(post("/api/advances/mine/code").session(me), "{\"amount\":%s}".formatted(amount)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.channels[0]").value("EMAIL"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<String>read(sent, "$.receipt")).contains(name(EMPLOYEE)).contains("no interest");
            String challenge = JsonPath.read(sent, "$.challengeId");
            String code = latestCode(EMPLOYEE);
            mvc.perform(json(post("/api/advances/mine").session(me), "{\"challengeId\":\"%s\",\"code\":\"%s\",\"amount\":%s,\"signedName\":\"Someone Else\"}"
                    .formatted(challenge, code, amount))).andExpect(status().is(422));
            mvc.perform(json(post("/api/advances/mine").session(me), "{\"challengeId\":\"%s\",\"code\":\"%s\",\"amount\":%s,\"signedName\":\"%s\"}"
                    .formatted(challenge, code, amount.add(BigDecimal.ONE), name(EMPLOYEE)))).andExpect(status().is(422));
            String sent2 = mvc.perform(json(post("/api/advances/mine/code").session(me), "{\"amount\":%s}".formatted(amount)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String advance = mvc.perform(json(post("/api/advances/mine").session(me), "{\"challengeId\":\"%s\",\"code\":\"%s\",\"amount\":%s,\"signedName\":\"%s\"}"
                            .formatted(JsonPath.read(sent2, "$.challengeId"), latestCode(EMPLOYEE), amount, name(EMPLOYEE).toUpperCase())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"))
                    .andExpect(jsonPath("$.signedChannels").value("EMAIL"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<String>read(advance, "$.number")).startsWith("AV-" + YearMonth.from(today) + "-");
            // On the month's payslip at once, and less is available now.
            assertThat(entry(EMPLOYEE, month)).isEqualByComparingTo(amount);
            mvc.perform(get("/api/advances/mine").session(me))
                    .andExpect(jsonPath("$.availability.requestsLeft").value(1))
                    .andExpect(jsonPath("$.advances[0].status").value("APPROVED"));

            // With HR approval: the request waits; nobody decides on their own; a refusal needs a reason.
            mvc.perform(json(put("/api/advances/settings").session(hr), settings(true, "HR"))).andExpect(status().isOk());
            String sent3 = mvc.perform(json(post("/api/advances/mine/code").session(other), "{\"amount\":5}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String waiting = mvc.perform(json(post("/api/advances/mine").session(other), "{\"challengeId\":\"%s\",\"code\":\"%s\",\"amount\":5,\"signedName\":\"%s\"}"
                            .formatted(JsonPath.read(sent3, "$.challengeId"), latestCode(EMPLOYEE2), name(EMPLOYEE2))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REQUESTED"))
                    .andReturn().getResponse().getContentAsString();
            long waitingId = JsonPath.<Number>read(waiting, "$.id").longValue();
            assertThat(entry(EMPLOYEE2, month)).isEqualByComparingTo(BigDecimal.ZERO);
            mvc.perform(get("/api/advances").session(hr)).andExpect(jsonPath("$.waiting[?(@.id == %d)]".formatted(waitingId)).isNotEmpty());
            mvc.perform(post("/api/advances/" + waitingId + "/approve").session(other).with(csrf())).andExpect(status().isForbidden());
            mvc.perform(json(post("/api/advances/" + waitingId + "/reject").session(hr), "{\"note\":\" \"}")).andExpect(status().is(422));
            mvc.perform(post("/api/advances/" + waitingId + "/approve").session(hr).with(csrf()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
            assertThat(entry(EMPLOYEE2, month)).isEqualByComparingTo("5");

            // Finance's morning list, then the payment files: paid advances are marked so.
            mvc.perform(get("/api/payments/" + YearMonth.from(today)).param("kind", "ADVANCE").session(me)).andExpect(status().isForbidden());
            String pay = mvc.perform(get("/api/payments/" + YearMonth.from(today)).param("kind", "ADVANCE").session(finance))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.runStatus").value("CLOSED"))
                    .andReturn().getResponse().getContentAsString();
            List<Map<String, Object>> groups = JsonPath.read(pay, "$.toPay");
            for (Map<String, Object> g : groups) {
                String batch = mvc.perform(json(post("/api/payments/" + YearMonth.from(today) + "/batches").param("kind", "ADVANCE").session(finance),
                                "{\"method\":\"%s\",\"provider\":\"%s\",\"currency\":\"%s\"}".formatted(g.get("method"), g.get("provider"), g.get("currency"))))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("ADVANCE"))
                        .andReturn().getResponse().getContentAsString();
                long batchId = JsonPath.<Number>read(batch, "$.batch.id").longValue();
                mvc.perform(json(post("/api/payment-batches/" + batchId + "/paid").session(finance),
                        "{\"paidOn\":\"%s\",\"reference\":\"AV-1\"}".formatted(today))).andExpect(status().isOk());
            }
            List<Object> problems = JsonPath.read(pay, "$.problems");
            if (!groups.isEmpty() && problems.isEmpty()) {
                mvc.perform(get("/api/advances/mine").session(me)).andExpect(jsonPath("$.advances[0].status").value("PAID"));
            }
            assertThat(currency).isNotBlank();
        } finally {
            mvc.perform(json(put("/api/advances/settings").session(hr), settings(false, "AUTO"))).andExpect(status().isOk());
            // Leave the month as it was for other tests.
            jdbc.sql("delete from pay_entry where element_id in (select id from pay_element where code = 'EARNED_ADVANCE')").update();
            jdbc.sql("delete from payment_line where advance_id is not null").update();
            jdbc.sql("delete from payment_batch where advance_run_id is not null").update();
            jdbc.sql("delete from salary_advance").update();
            jdbc.sql("delete from clock_event where created_by = 'advance test'").update();
        }
    }
}
