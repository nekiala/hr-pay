package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

class EvaluationIT extends IntegrationTest {

    static final String MANAGER = "manager@demo.hrpay.cd";

    @Autowired
    UserRepository users;

    long id(String email) {
        return users.findByEmailIgnoreCase(email).orElseThrow().getId();
    }

    static String scores(String body, int productivity, int quality, Integer teamwork, boolean submit) {
        List<Map<String, Object>> s = JsonPath.read(body, "$.scores");
        StringBuilder out = new StringBuilder("{\"scores\":[");
        for (Map<String, Object> x : s) {
            Object value = switch ((String) x.get("code")) {
                case "PRODUCTIVITY" -> productivity;
                case "QUALITY" -> quality;
                case "TEAMWORK" -> teamwork;
                default -> null;
            };
            out.append("{\"criterionId\":").append(x.get("criterionId")).append(",\"score\":").append(value).append("},");
        }
        out.setLength(out.length() - 1);
        return out.append("],\"managerComment\":\"Q3 review\",\"submit\":").append(submit).append("}").toString();
    }

    @Test
    void managerScoresEmployeeContestsHrDecidesAndTheYearScoreFeedsTheThirteenthMonth() throws Exception {
        MockHttpSession manager = login(MANAGER);
        MockHttpSession employee = login("employee2@demo.hrpay.cd");
        MockHttpSession hr = login(HR);
        long esther = id("employee2@demo.hrpay.cd");

        String started = mvc.perform(json(post("/api/evaluations").session(manager), """
                        {"userId":%d,"inPeriod":"2026-08-15"}""".formatted(esther)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.periodStart").value("2026-07-01"))
                .andExpect(jsonPath("$.periodEnd").value("2026-09-30"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        long evaluation = JsonPath.<Number>read(started, "$.id").longValue();
        mvc.perform(json(post("/api/evaluations").session(manager), """
                        {"userId":%d,"inPeriod":"2026-09-01"}""".formatted(esther)))
                .andExpect(status().isConflict());
        // A draft is not shown to the employee; another employee never sees it.
        mvc.perform(get("/api/evaluations/" + evaluation).session(employee)).andExpect(status().isForbidden());
        mvc.perform(get("/api/evaluations/" + evaluation).session(login(EMPLOYEE))).andExpect(status().isForbidden());

        mvc.perform(json(put("/api/evaluations/" + evaluation + "/scores").session(manager), scores(started, 80, 90, null, true)))
                .andExpect(status().is(422));   // teamwork missing
        // No clock events in the test data, so punctuality from attendance is 0:
        // 35% x 80 + 35% x 90 + 15% x 70 + 15% x 0 = 70.
        mvc.perform(json(put("/api/evaluations/" + evaluation + "/scores").session(manager), scores(started, 80, 90, 70, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.totalPercent").value(70.0));

        mvc.perform(json(post("/api/evaluations/" + evaluation + "/answer").session(employee), """
                        {"acknowledge":false}"""))
                .andExpect(status().is(422));
        mvc.perform(json(post("/api/evaluations/" + evaluation + "/answer").session(employee), """
                        {"acknowledge":false,"comment":"I led the September audit."}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONTESTED"));

        mvc.perform(json(post("/api/evaluations/" + evaluation + "/approve").session(manager), "{}"))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/evaluations/" + evaluation + "/approve").session(hr), "{}"))
                .andExpect(status().is(422));   // a contest needs HR's explanation
        // The manager revises after the contest: 35% x 80 + 35% x 100 + 15% x 70 = 73.5.
        mvc.perform(json(put("/api/evaluations/" + evaluation + "/scores").session(manager), scores(started, 80, 100, 70, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.totalPercent").value(73.5))
                .andExpect(jsonPath("$.employeeComment").value("I led the September audit."));
        mvc.perform(json(post("/api/evaluations/" + evaluation + "/answer").session(employee), """
                        {"acknowledge":true}"""))
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
        mvc.perform(json(post("/api/evaluations/" + evaluation + "/approve").session(hr), """
                        {"hrComment":"Agreed with the revision."}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(json(put("/api/evaluations/" + evaluation + "/scores").session(manager), scores(started, 10, 10, 10, true)))
                .andExpect(status().is(422));   // locked once approved

        mvc.perform(get("/api/evaluations").param("scope", "mine").session(employee))
                .andExpect(jsonPath("$[0].id").value(evaluation));
        mvc.perform(get("/api/evaluations").param("scope", "all").session(manager)).andExpect(status().isForbidden());

        // 13th month for 2026: one approved evaluation at 73.5%, twelve months under contract.
        String preview = mvc.perform(get("/api/thirteenth-month").param("year", "2026").session(hr))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<String, Object> row = JsonPath.<List<Map<String, Object>>>read(preview, "$.rows[?(@.userId==" + esther + ")]").getFirst();
        assertThat(row.get("scoreSource")).isEqualTo("EVALUATIONS");
        assertThat(new BigDecimal(row.get("scorePercent").toString())).isEqualByComparingTo("73.5");
        assertThat(row.get("monthsWorked")).isEqualTo(12);
        BigDecimal base = new BigDecimal(row.get("base").toString());
        assertThat(new BigDecimal(row.get("amount").toString()))
                .isEqualByComparingTo(base.multiply(new BigDecimal("0.735")).setScale(2, RoundingMode.HALF_UP));
        // Someone without an evaluation gets the fallback (50%).
        Map<String, Object> other = JsonPath.<List<Map<String, Object>>>read(preview,
                "$.rows[?(@.userId==" + id("employee3@demo.hrpay.cd") + ")]").getFirst();
        assertThat(other.get("scoreSource")).isEqualTo("FALLBACK");
        assertThat(new BigDecimal(other.get("scorePercent").toString())).isEqualByComparingTo("50");
    }

    @Test
    void templateWeightsMustAddUpTo100() throws Exception {
        MockHttpSession hr = login(HR);
        String criteria = mvc.perform(get("/api/evaluation-criteria").session(hr)).andReturn().getResponse().getContentAsString();
        long first = JsonPath.<Number>read(criteria, "$[0].id").longValue();
        mvc.perform(json(post("/api/evaluation-templates").session(hr), """
                        {"name":"Managers","frequency":"SEMI_ANNUAL","gradeCategoryId":3,"active":true,
                         "criteria":[{"criterionId":%d,"weight":60}]}""".formatted(first)))
                .andExpect(status().is(422));
        mvc.perform(json(post("/api/evaluation-templates").session(hr), """
                        {"name":"Everyone again","frequency":"MONTHLY","active":true,
                         "criteria":[{"criterionId":%d,"weight":100}]}""".formatted(first)))
                .andExpect(status().isConflict());   // the sample template already applies to everyone
        mvc.perform(json(post("/api/evaluation-templates").session(login(EMPLOYEE)), """
                        {"name":"X","frequency":"MONTHLY","active":false,"criteria":[{"criterionId":%d,"weight":100}]}""".formatted(first)))
                .andExpect(status().isForbidden());
    }
}
