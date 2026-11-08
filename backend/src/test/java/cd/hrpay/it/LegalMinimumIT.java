package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

class LegalMinimumIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    long userId(String email) {
        return jdbc.sql("select id from app_user where email = ?").param(email).query(Long.class).single();
    }

    String gradeOn(long user) {
        return jdbc.sql("""
                select g.code from employee_contract c join grade g on g.id = c.grade_id where c.user_id = ?
                and c.start_date <= '2027-01-01' and (c.end_date is null or c.end_date >= '2027-01-01')
                order by c.start_date desc limit 1""").param(user).query(String.class).single();
    }

    BigDecimal base(String grade) {
        return jdbc.sql("select base_salary from grade where code = ?").param(grade).query(BigDecimal.class).single();
    }

    BigDecimal coefficient(String grade) {
        return jdbc.sql("select smig_coefficient from grade where code = ?").param(grade).query(BigDecimal.class).single();
    }

    static BigDecimal num(String json, String path) {
        Object v = JsonPath.read(json, path);
        if (v instanceof List<?> l) {
            v = l.getFirst();
        }
        return new BigDecimal(v.toString());
    }

    @Test
    void gradesAreRaisedToTheLegalMinimumAndPeopleKeepTheirNet() throws Exception {
        usdRate(java.time.LocalDate.now().toString(), 2800);
        MockHttpSession hr = login(HR);
        long two = userId("employee2@demo.hrpay.cd");
        long three = userId("employee3@demo.hrpay.cd");
        Map<String, BigDecimal> bases = jdbc.sql("select code, base_salary from grade").query((rs, n) -> Map.entry(
                rs.getString(1), rs.getBigDecimal(2))).list().stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, Map.Entry::getValue));
        Map<Long, java.time.LocalDate> ends = new java.util.HashMap<>();
        jdbc.sql("select id, end_month from employee_pay_element where user_id in (?, ?)").params(two, three)
                .query((rs, n) -> ends.put(rs.getLong(1), rs.getObject(2, java.time.LocalDate.class))).list();
        long lastElement = jdbc.sql("select coalesce(max(id), 0) from employee_pay_element").query(Long.class).single();
        try {
            // Whatever grade each one is on in 2027 (other tests may have moved them), its base is put 50 below its
            // legal minimum: one is paid 427 above that base, the other exactly the base.
            String g2 = gradeOn(two);
            String g3 = gradeOn(three);
            for (String g : new java.util.HashSet<>(List.of(g2, g3))) {
                jdbc.sql("update grade set base_salary = base_salary - 50 where code = ?").param(g).update();
            }
            BigDecimal base2 = base(g2);
            BigDecimal base3 = base(g3);
            mvc.perform(json(post("/api/users/" + two + "/salaries").session(hr),
                    "{\"effectiveFrom\":\"2027-01-01\",\"grossMonthly\":%s,\"currency\":\"USD\"}".formatted(base2.add(new BigDecimal("427")))))
                    .andExpect(status().isCreated());
            mvc.perform(json(post("/api/users/" + three + "/salaries").session(hr),
                    "{\"effectiveFrom\":\"2027-01-01\",\"grossMonthly\":%s,\"currency\":\"USD\"}".formatted(base3)))
                    .andExpect(status().isCreated());

            String preview = mvc.perform(get("/api/grades/legal-minimum").param("from", "2027-03-01").session(hr))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            BigDecimal rate = num(preview, "$.usdLocal");
            // 21,500 FC x coefficient / 100 x 26 days, in whole dollars at the day's rate.
            BigDecimal new2 = new BigDecimal("21500").multiply(coefficient(g2)).multiply(new BigDecimal("26"))
                    .divide(new BigDecimal("100").multiply(rate), 0, java.math.RoundingMode.CEILING);
            BigDecimal new3 = new BigDecimal("21500").multiply(coefficient(g3)).multiply(new BigDecimal("26"))
                    .divide(new BigDecimal("100").multiply(rate), 0, java.math.RoundingMode.CEILING);
            assertThat(num(preview, "$.grades[?(@.code=='" + g2 + "')].newBase")).isEqualByComparingTo(new2);
            assertThat(base(g2)).isEqualByComparingTo(base2);   // only shown

            String p2 = "$.people[?(@.userId==" + two + ")]";
            assertThat(JsonPath.<List<String>>read(preview, p2 + ".newGrade")).containsExactly(g2);
            assertThat(num(preview, p2 + ".newBasic")).isEqualByComparingTo(new2);
            assertThat(num(preview, p2 + ".newComplement")).isLessThan(new BigDecimal("427"));
            assertThat(num(preview, p2 + ".newNet").subtract(num(preview, p2 + ".net")).abs()).isLessThanOrEqualTo(new BigDecimal("0.01"));
            // At the base already: the raised base would pay more than the net, so the nearest lower grade takes over.
            String p3 = "$.people[?(@.userId==" + three + ")]";
            assertThat(JsonPath.<List<String>>read(preview, p3 + ".newGrade")).doesNotContain(g3);
            assertThat(num(preview, p3 + ".newBasic")).isLessThan(new3);
            assertThat(num(preview, p3 + ".newNet").subtract(num(preview, p3 + ".net")).abs()).isLessThanOrEqualTo(new BigDecimal("0.01"));

            mvc.perform(post("/api/grades/legal-minimum").param("from", "2027-03-01").session(hr).with(csrf()))
                    .andExpect(status().isOk());
            assertThat(base(g2)).isEqualByComparingTo(new2);
            assertThat(jdbc.sql("select gross_monthly from employee_salary where user_id = ? and effective_from = '2027-03-01'")
                    .param(two).query(BigDecimal.class).single()).isEqualByComparingTo(new2);
            assertThat(jdbc.sql("""
                    select g.code from employee_contract c join grade g on g.id = c.grade_id
                    where c.user_id = ? and c.start_date = '2027-03-01'""").param(three).query(String.class).single())
                    .isEqualTo(JsonPath.<List<String>>read(preview, p3 + ".newGrade").getFirst());
        } finally {
            bases.forEach((code, base) -> jdbc.sql("update grade set base_salary = ? where code = ?").params(base, code).update());
            jdbc.sql("delete from employee_pay_element where id > ? and user_id in (?, ?)").params(lastElement, two, three).update();
            ends.forEach((id, end) -> jdbc.sql("update employee_pay_element set end_month = ? where id = ?").params(end, id).update());
            jdbc.sql("delete from employee_salary where user_id in (?, ?) and effective_from >= '2027-01-01'").params(two, three).update();
            jdbc.sql("delete from employee_contract where user_id in (?, ?) and start_date = '2027-03-01'").params(two, three).update();
            jdbc.sql("update employee_contract set end_date = null where user_id in (?, ?) and end_date = '2027-02-28'")
                    .params(two, three).update();
            for (long u : List.of(two, three)) {
                jdbc.sql("""
                        update app_user set grade_category_id = (select g.category_id from employee_contract c join grade g
                        on g.id = c.grade_id where c.user_id = ? and c.end_date is null order by c.start_date desc limit 1)
                        where id = ?""").params(u, u).update();
            }
        }
    }
}
