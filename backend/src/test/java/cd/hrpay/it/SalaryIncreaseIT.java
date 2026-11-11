package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.payroll.SalaryIncreases;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** A general increase of the grade scale: simulated without saving, approved by someone else, back pay, undone. */
class SalaryIncreaseIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SalaryIncreases increases;

    BigDecimal base(long grade) {
        return jdbc.sql("select base_salary from grade where id = ?").param(grade).query(BigDecimal.class).single();
    }

    int count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Integer.class).single();
    }

    @Test
    void anIncreaseIsSimulatedApprovedByAnotherPersonPaysBackPayAndCanBeUndone() throws Exception {
        LocalDate today = LocalDate.now();
        YearMonth paid = YearMonth.from(today).minusMonths(2);
        usdRate(today.toString(), 2800);
        usdRate(paid.atEndOfMonth().toString(), 2800);
        MockHttpSession hr = login(HR);
        MockHttpSession finance = login(FINANCE);

        // A grade of its own (1,000 USD) and one person on it since well before the paid month.
        String grades = mvc.perform(get("/api/grades").session(hr)).andReturn().getResponse().getContentAsString();
        long category = JsonPath.<List<Number>>read(grades, "$[?(@.code=='M1')].categoryId").getFirst().longValue();
        String g = mvc.perform(json(post("/api/grades").session(hr), """
                        {"categoryId":%d,"code":"GI%d","name":"General increase test","baseSalary":1000,"currency":"USD","active":true}"""
                        .formatted(category, System.nanoTime() % 100000)))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        long grade = JsonPath.<Number>read(g, "$.id").longValue();
        String created = mvc.perform(json(post("/api/users").session(login(ADMIN)), newUser("[\"EMPLOYEE\"]")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long user = JsonPath.<Number>read(created, "$.id").longValue();
        mvc.perform(json(post("/api/users/" + user + "/contracts").session(hr), """
                        {"contractType":"PERMANENT","startDate":"2025-01-06","gradeId":%d,"expatriate":false}""".formatted(grade)))
                .andExpect(status().isCreated());
        jdbc.sql("delete from user_schedule where user_id = ?").param(user).update();
        jdbc.sql("""
                insert into user_schedule (user_id, schedule_id, effective_from)
                select ?, (select schedule_id from user_schedule order by id limit 1), date '2025-01-01'""").param(user).update();
        // Worked every weekday of the paid month, then that month's payroll calculated and approved.
        jdbc.sql("""
                insert into clock_event (user_id, type, occurred_at, work_date, source, created_by)
                select ?, t.type, (d + t.at) at time zone 'Africa/Kinshasa', d, 'HR_MANUAL', 'test'
                from generate_series(cast(? as date), cast(? as date), interval '1 day') as s(day)
                cross join lateral (select cast(s.day as date) as d) x
                cross join (values ('IN', time '08:00'), ('OUT', time '17:00')) as t(type, at)
                where extract(isodow from d) <= 5""").params(user, paid.atDay(1), paid.atEndOfMonth()).update();
        mvc.perform(post("/api/payroll/runs/" + paid + "/calculate").session(hr).with(csrf())).andExpect(status().isOk());
        jdbc.sql("update payroll_run set status = 'APPROVED' where month = ?").param(paid.atDay(1)).update();
        try {
            String input = """
                    {"name":"5%% test","percent":5,"startMonth":"%s","gradeIds":[%d]}""".formatted(paid, grade);

            // 1. The simulation: the grade at 1,050, back pay for the approved month, and nothing saved.
            int salariesBefore = count("select count(*) from employee_salary");
            int entriesBefore = count("select count(*) from pay_entry");
            String sim = mvc.perform(json(post("/api/payroll/increases/simulate").session(hr), input))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.grades[0].newBase").value(1050.0))
                    .andReturn().getResponse().getContentAsString();
            // Back pay goes on the month after the last approved payroll (other tests may have approved later months).
            YearMonth arrearsMonth = YearMonth.parse(JsonPath.<String>read(sim, "$.arrearsMonth"));
            assertThat(arrearsMonth.isAfter(paid)).isTrue();
            BigDecimal arrears = new BigDecimal(JsonPath.<List<Object>>read(sim, "$.people[?(@.userId==" + user + ")].arrears")
                    .getFirst().toString());
            assertThat(arrears).isPositive();
            assertThat(new BigDecimal(JsonPath.<List<Object>>read(sim, "$.people[?(@.userId==" + user + ")].newBasic").getFirst()
                    .toString())).isEqualByComparingTo("1050");
            assertThat(JsonPath.<List<Object>>read(sim, "$.totals")).hasSize(1);
            assertThat(base(grade)).isEqualByComparingTo("1000");
            assertThat(count("select count(*) from employee_salary")).isEqualTo(salariesBefore);
            assertThat(count("select count(*) from pay_entry")).isEqualTo(entriesBefore);
            mvc.perform(json(post("/api/payroll/increases/simulate").session(finance), input)).andExpect(status().isForbidden());
            mvc.perform(json(post("/api/payroll/increases/simulate").param("format", "xlsx").session(hr), input))
                    .andExpect(status().is2xxSuccessful());

            // 2. Saved, sent, refused to its author, approved by Finance.
            String saved = mvc.perform(json(post("/api/payroll/increases").session(hr), input))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            long id = JsonPath.<Number>read(saved, "$.id").longValue();
            mvc.perform(post("/api/payroll/increases/" + id + "/preview").session(finance).with(csrf())).andExpect(status().isOk());
            mvc.perform(get("/api/payroll/increases/" + id).session(hr)).andExpect(jsonPath("$.summary[0].people").value(1));
            mvc.perform(post("/api/payroll/increases/" + id + "/approve").session(finance).with(csrf())).andExpect(status().isConflict());
            mvc.perform(post("/api/payroll/increases/" + id + "/submit").session(hr).with(csrf()))
                    .andExpect(jsonPath("$.status").value("SUBMITTED"));
            mvc.perform(post("/api/payroll/increases/" + id + "/approve").session(hr).with(csrf())).andExpect(status().isForbidden());
            mvc.perform(post("/api/payroll/increases/" + id + "/approve").session(finance).with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"))
                    .andExpect(jsonPath("$.canUndo").value(true));
            assertThat(base(grade)).isEqualByComparingTo("1050");
            assertThat(jdbc.sql("select gross_monthly from employee_salary where user_id = ? and effective_from = ?")
                    .params(user, paid.atDay(1)).query(BigDecimal.class).single()).isEqualByComparingTo("1050");
            assertThat(jdbc.sql("""
                    select e.amount from pay_entry e join pay_element p on p.id = e.element_id
                    where e.user_id = ? and p.code = 'SALARY_ARREARS' and e.month = ?""")
                    .params(user, arrearsMonth.atDay(1)).query(BigDecimal.class).single()).isEqualByComparingTo(arrears);

            // 3. Undone while the next payroll is not approved: everything as before.
            mvc.perform(post("/api/payroll/increases/" + id + "/undo").session(finance).with(csrf()))
                    .andExpect(jsonPath("$.status").value("UNDONE"));
            assertThat(base(grade)).isEqualByComparingTo("1000");
            assertThat(count("select count(*) from employee_salary")).isEqualTo(salariesBefore);
            assertThat(count("select count(*) from pay_entry")).isEqualTo(entriesBefore);

            // 4. An increase to come: the salaries from its month at once, the grade base on that month.
            YearMonth later = YearMonth.from(today).plusMonths(2);
            String next = mvc.perform(json(post("/api/payroll/increases").session(hr), """
                            {"name":"Later","percent":3,"minimumAmount":40,"minimumCurrency":"USD","rounding":5,"startMonth":"%s","gradeIds":[%d]}"""
                            .formatted(later, grade)))
                    .andReturn().getResponse().getContentAsString();
            long id2 = JsonPath.<Number>read(next, "$.id").longValue();
            mvc.perform(post("/api/payroll/increases/" + id2 + "/submit").session(hr).with(csrf())).andExpect(status().isOk());
            mvc.perform(post("/api/payroll/increases/" + id2 + "/approve").session(login(ADMIN)).with(csrf()))
                    .andExpect(jsonPath("$.status").value("APPROVED"));
            assertThat(base(grade)).isEqualByComparingTo("1000");
            assertThat(jdbc.sql("select gross_monthly from employee_salary where user_id = ? and effective_from = ?")
                    .params(user, later.atDay(1)).query(BigDecimal.class).single()).isEqualByComparingTo("1040");
            increases.applyDueGrades(later.atDay(1));
            assertThat(base(grade)).isEqualByComparingTo("1040");
            mvc.perform(post("/api/payroll/increases/" + id2 + "/undo").session(finance).with(csrf())).andExpect(status().isOk());
            assertThat(base(grade)).isEqualByComparingTo("1000");
        } finally {
            jdbc.sql("delete from payroll_run where month = ?").param(paid.atDay(1)).update();
            jdbc.sql("update app_user set status = 'DISABLED' where id = ?").param(user).update();
            jdbc.sql("update grade set active = false where id = ?").param(grade).update();
        }
    }
}
