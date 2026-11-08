package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/**
 * The quinzaine: off by default, a rate per grade category (10% to 50%), employees who ask not to receive it, HR
 * exclusions, the debt check, approval, closing, payment files, and the month's payslip taking it back.
 */
class QuinzaineIT extends IntegrationTest {

    static final String EMPLOYEE2 = "employee2@demo.hrpay.cd";
    static final String EMPLOYEE3 = "employee3@demo.hrpay.cd";
    static final String MANAGER = "manager@demo.hrpay.cd";

    @Autowired
    JdbcClient jdbc;

    BigDecimal entry(String email, String month) {
        return jdbc.sql("""
                select coalesce(sum(p.amount), 0) from pay_entry p join pay_element e on e.id = p.element_id join app_user u on u.id = p.user_id
                where e.code = 'QUINZAINE' and u.email = ? and p.month = ?::date""")
                .params(email, month).query(BigDecimal.class).single();
    }

    long userId(String email) {
        return jdbc.sql("select id from app_user where email = ?").param(email).query(Long.class).single();
    }

    static BigDecimal number(String json, String path) {
        return new BigDecimal(JsonPath.<List<Object>>read(json, path).getFirst().toString());
    }

    @Test
    void quinzaineIsAnAdvanceWorkedOutLikeThePayrollAndTakenBackInFull() throws Exception {
        jdbc.sql("insert into exchange_rate (currency, rate_date, cdf_per_unit, source) values ('USD', '2026-09-30', 2265, 'Test') on conflict do nothing").update();
        MockHttpSession hr = login(HR);
        MockHttpSession finance = login(FINANCE);
        MockHttpSession me = login(EMPLOYEE);
        MockHttpSession other = login(EMPLOYEE2);

        // Off by default; no category has a rate.
        String overview = mvc.perform(get("/api/quinzaine").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.enabled").value(false))
                .andExpect(jsonPath("$.settings.base").value("NET"))
                .andExpect(jsonPath("$.settings.day").value(15))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/quinzaine").session(me)).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/quinzaine/calculate").session(hr), "{\"month\":\"2028-03\"}")).andExpect(status().is(422));
        List<Number> categories = JsonPath.read(overview, "$.categories[*].id");
        assertThat(JsonPath.<List<Object>>read(overview, "$.categories[*].quinzainePercent")).containsOnlyNulls();

        // The rate is from 10% to 50%.
        long category = categories.getFirst().longValue();
        mvc.perform(json(put("/api/quinzaine/categories/" + category).session(hr), "{\"percent\":60}")).andExpect(status().is(422));
        mvc.perform(json(put("/api/quinzaine/categories/" + category).session(hr), "{\"percent\":5}")).andExpect(status().is(422));
        mvc.perform(json(put("/api/quinzaine/categories/" + category).session(me), "{\"percent\":40}")).andExpect(status().isForbidden());
        for (Number c : categories) {
            mvc.perform(json(put("/api/quinzaine/categories/" + c).session(hr), "{\"percent\":40}")).andExpect(status().isOk());
        }
        mvc.perform(json(put("/api/statutory/settings/QUINZAINE_ENABLED").session(hr), "{\"value\":\"true\"}")).andExpect(status().isOk());
        long loan = 0;
        try {
            mvc.perform(get("/api/quinzaine/mine").session(me)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.refused").value(false));
            // 15 March 2028 is a Wednesday.
            mvc.perform(get("/api/quinzaine/pay-date").param("month", "2028-03").session(hr))
                    .andExpect(status().isOk()).andExpect(jsonPath("$").value("2028-03-15"));
            // 15 April 2028 is a Saturday: paid on Friday the 14th.
            mvc.perform(get("/api/quinzaine/pay-date").param("month", "2028-04").session(hr))
                    .andExpect(jsonPath("$").value("2028-04-14"));

            // Employee 2 asks not to receive it; HR leaves employee 3 out of March; the manager owes a large loan.
            mvc.perform(json(put("/api/quinzaine/mine").session(other), "{\"refused\":true,\"reason\":\"One payment\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.refused").value(true));
            // HR's list shows only the people who asked not to receive it, not every employee.
            mvc.perform(get("/api/quinzaine/employees").session(hr)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].userId").value(userId(EMPLOYEE2)))
                    .andExpect(jsonPath("$[0].reason").value("One payment"));
            mvc.perform(json(post("/api/quinzaine/exclusions").session(hr),
                            "{\"month\":\"2028-03\",\"userId\":%d,\"reason\":\"Disciplinary file\"}".formatted(userId(EMPLOYEE3))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
            loan = jdbc.sql("""
                    insert into loan (user_id, kind, principal, currency, installment, start_month, granted_on, created_by)
                    values (?, 'LOAN', 1000000, 'USD', 100, '2028-01-01', '2026-11-01', 'test') returning id""")
                    .param(userId(MANAGER)).query(Long.class).single();

            mvc.perform(json(post("/api/quinzaine/calculate").session(me), "{\"month\":\"2028-03\"}")).andExpect(status().isForbidden());
            String run = mvc.perform(json(post("/api/quinzaine/calculate").session(hr), "{\"month\":\"2028-03\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CALCULATED"))
                    .andExpect(jsonPath("$.payDate").value("2028-03-15"))
                    .andReturn().getResponse().getContentAsString();
            long id = JsonPath.<Number>read(run, "$.id").longValue();
            assertThat(JsonPath.<List<String>>read(run, "$.lines[*].registrationNo")).contains("EMP-0001")
                    .doesNotContain("EMP-0002", "EMP-0003");
            List<String> skipped = JsonPath.read(run, "$.skipped");
            assertThat(skipped).anyMatch(s -> s.contains("asked not to receive"))
                    .anyMatch(s -> s.contains("Disciplinary file"))
                    .anyMatch(s -> s.contains("in loans and advances"));
            String mine = "$.lines[?(@.registrationNo=='EMP-0001')]";
            BigDecimal net = number(run, mine + ".normalNet");
            BigDecimal amount = number(run, mine + ".amount");
            BigDecimal earned = number(run, mine + ".earnedNet");
            // 40% of a normal month's net, rounded down; never more than the net earned on the pay day.
            assertThat(amount).isEqualByComparingTo(net.multiply(new BigDecimal("0.40")).setScale(0, RoundingMode.DOWN));
            assertThat(amount).isLessThanOrEqualTo(earned);
            mvc.perform(get("/api/quinzaine/receipts/" + number(run, mine + ".id").longValue() + ".pdf").session(me))
                    .andExpect(status().isForbidden());

            // Nothing on the payslip before Finance approves; the one who worked it out cannot approve it.
            assertThat(entry(EMPLOYEE, "2028-03-01")).isEqualByComparingTo(BigDecimal.ZERO);
            mvc.perform(post("/api/quinzaine/" + id + "/approve").session(hr).with(csrf())).andExpect(status().isForbidden());
            mvc.perform(post("/api/quinzaine/" + id + "/approve").session(finance).with(csrf()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
            assertThat(entry(EMPLOYEE, "2028-03-01")).isEqualByComparingTo(amount);
            assertThat(entry(EMPLOYEE2, "2028-03-01")).isEqualByComparingTo(BigDecimal.ZERO);
            mvc.perform(json(post("/api/quinzaine/calculate").session(hr), "{\"month\":\"2028-03\"}")).andExpect(status().is(422));
            mvc.perform(delete("/api/quinzaine/exclusions").param("month", "2028-03").param("userId", String.valueOf(userId(EMPLOYEE3)))
                    .session(hr).with(csrf())).andExpect(status().is(422));

            // HR closes it: receipts are numbered, payment files can be made, the employee sees their receipt.
            mvc.perform(get("/api/payments/2028-03").param("kind", "QUINZAINE").session(finance))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.runStatus").value("APPROVED"));
            mvc.perform(post("/api/quinzaine/" + id + "/close").session(finance).with(csrf())).andExpect(status().isForbidden());
            String closed = mvc.perform(post("/api/quinzaine/" + id + "/close").session(hr).with(csrf()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<List<String>>read(closed, mine + ".number").getFirst()).startsWith("QZ-2028-03-");
            mvc.perform(post("/api/quinzaine/" + id + "/cancel").session(hr).with(csrf())).andExpect(status().is(422));
            mvc.perform(get("/api/quinzaine/receipts/" + number(run, mine + ".id").longValue() + ".pdf").session(me))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/quinzaine/mine").session(me))
                    .andExpect(jsonPath("$.payments[0].month").value("2028-03-01"))
                    .andExpect(jsonPath("$.payments[0].status").value("CLOSED"));

            String pay = mvc.perform(get("/api/payments/2028-03").param("kind", "QUINZAINE").session(finance))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.runStatus").value("CLOSED"))
                    .andReturn().getResponse().getContentAsString();
            List<Map<String, Object>> groups = JsonPath.read(pay, "$.toPay");
            assertThat(groups).isNotEmpty();
            for (Map<String, Object> g : groups) {
                String batch = mvc.perform(json(post("/api/payments/2028-03/batches").param("kind", "QUINZAINE").session(finance),
                                "{\"method\":\"%s\",\"provider\":\"%s\",\"currency\":\"%s\"}".formatted(g.get("method"), g.get("provider"), g.get("currency"))))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("QUINZAINE"))
                        .andReturn().getResponse().getContentAsString();
                long batchId = JsonPath.<Number>read(batch, "$.batch.id").longValue();
                mvc.perform(json(post("/api/payment-batches/" + batchId + "/paid").session(finance),
                        "{\"paidOn\":\"2026-11-08\",\"reference\":\"VIR-15\"}")).andExpect(status().isOk());
            }
            boolean problems = !JsonPath.<List<Object>>read(pay, "$.problems").isEmpty();
            mvc.perform(get("/api/quinzaine").session(hr))
                    .andExpect(jsonPath("$.runs[0].status").value(problems ? "CLOSED" : "PAID"));
            // The month's payroll payment files are separate.
            mvc.perform(get("/api/payments/2028-03").session(finance)).andExpect(status().isNotFound());

            // Withdrawing the request: employee 2 takes it again from the next quinzaine.
            mvc.perform(json(put("/api/quinzaine/mine").session(other), "{\"refused\":false}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.refused").value(false));

            // On the basic salary instead of the net.
            mvc.perform(json(put("/api/statutory/settings/QUINZAINE_BASE").session(hr), "{\"value\":\"BASIC\"}")).andExpect(status().isOk());
            String april = mvc.perform(json(post("/api/quinzaine/calculate").session(hr), "{\"month\":\"2028-04\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.base").value("BASIC"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<List<String>>read(april, "$.lines[*].registrationNo")).contains("EMP-0001", "EMP-0002");
            BigDecimal basic = number(april, mine + ".baseAmount");
            assertThat(number(april, mine + ".amount")).isLessThanOrEqualTo(basic.multiply(new BigDecimal("0.40")));
            long aprilId = JsonPath.<Number>read(april, "$.id").longValue();
            mvc.perform(json(post("/api/quinzaine/" + aprilId + "/cancel").session(hr), "{\"note\":\"Test\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        } finally {
            mvc.perform(json(put("/api/statutory/settings/QUINZAINE_ENABLED").session(hr), "{\"value\":\"false\"}")).andExpect(status().isOk());
            mvc.perform(json(put("/api/statutory/settings/QUINZAINE_BASE").session(hr), "{\"value\":\"NET\"}")).andExpect(status().isOk());
            jdbc.sql("update loan set status = 'CANCELLED' where id = ?").param(loan).update();
        }
    }
}
