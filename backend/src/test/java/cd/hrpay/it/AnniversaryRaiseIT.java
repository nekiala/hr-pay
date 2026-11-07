package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.payroll.AnniversaryRaises;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

class AnniversaryRaiseIT extends IntegrationTest {

    @Autowired
    AnniversaryRaises raises;

    @Autowired
    JdbcClient jdbc;

    @Test
    void appliesOncePerAnniversaryFromTheNextMonthAndEmailsHr() throws Exception {
        MockHttpSession hr = login(HR);
        String created = mvc.perform(json(post("/api/users").session(login(ADMIN)), newUser("[\"EMPLOYEE\"]")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long user = JsonPath.<Number>read(created, "$.id").longValue();
        String grades = mvc.perform(get("/api/grades").session(hr)).andReturn().getResponse().getContentAsString();
        long g2a = JsonPath.<List<Number>>read(grades, "$[?(@.code=='M1')].id").getFirst().longValue();
        // Hired on a permanent contract on 10 March 2025 at a negotiated 800 (grade M1 base 731, MAITRISE raise 2.5%).
        mvc.perform(json(post("/api/users/" + user + "/contracts").session(hr), """
                        {"contractType":"PERMANENT","startDate":"2025-03-10","gradeId":%d,"expatriate":false,
                         "negotiatedBasic":800}""".formatted(g2a)))
                .andExpect(status().isCreated());
        jdbc.sql("update payroll_setting set value = '2026-01-01' where key = 'RAISES_FROM'").update();
        mvc.perform(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/grade-categories/3/raise").session(hr),
                        """
                        {"percent":3,"timing":"ANNIVERSARY_MONTH"}"""))
                .andExpect(status().isOk());
        mvc.perform(get("/api/leave/categories").session(hr)).andExpect(jsonPath("$[?(@.id==3)].raiseTiming").value("ANNIVERSARY_MONTH"));

        var before = raises.applyDue(LocalDate.parse("2026-03-09"), user);
        assertThat(before.applied()).isEmpty();
        var on = raises.applyDue(LocalDate.parse("2026-03-10"), user);
        assertThat(on.applied()).hasSize(1);
        var r = on.applied().getFirst();
        assertThat(r.raiseAmount()).isEqualByComparingTo("18.28");      // 2.5% of 731
        assertThat(r.newBasic()).isEqualByComparingTo("818.28");
        assertThat(r.effectiveFrom()).isEqualTo(LocalDate.parse("2026-04-01"));
        assertThat(raises.applyDue(LocalDate.parse("2026-03-20"), user).applied()).isEmpty();

        mvc.perform(get("/api/users/" + user + "/salaries").session(hr))
                .andExpect(jsonPath("$[0].effectiveFrom").value("2026-04-01"))
                .andExpect(jsonPath("$[0].grossMonthly").value(818.28));
        mvc.perform(get("/api/raises").session(hr)).andExpect(status().isOk());

        int listed = raises.sendSummary();
        assertThat(listed).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("select count(*) from outbox_message where recipient = 'hr@demo.hrpay.cd' and subject like '%augmentation(s) d''ancienneté%'")
                .query(Integer.class).single()).isPositive();
        assertThat(raises.sendSummary()).isZero();
        jdbc.sql("update payroll_setting set value = '2026-11-01' where key = 'RAISES_FROM'").update();
        // Other tests count the active employees with a grade category (yearly leave grants): take this one out.
        jdbc.sql("update app_user set status = 'DISABLED' where id = ?").param(user).update();
    }
}
