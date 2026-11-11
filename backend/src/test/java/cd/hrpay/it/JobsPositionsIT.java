package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** Job descriptions in versions, the checks they bring to contracts, career ladders and approved positions. */
class JobsPositionsIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    long freshEmployee() throws Exception {
        String created = mvc.perform(json(post("/api/users").session(login(ADMIN)), newUser("[\"EMPLOYEE\"]")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(created, "$.id").longValue();
    }

    long grade(MockHttpSession hr, String code) throws Exception {
        String grades = mvc.perform(get("/api/grades").session(hr)).andReturn().getResponse().getContentAsString();
        return JsonPath.<List<Number>>read(grades, "$[?(@.code=='" + code + "')].id").getFirst().longValue();
    }

    long contract(MockHttpSession hr, long userId, LocalDate start, long grade, long job) throws Exception {
        String saved = mvc.perform(json(post("/api/users/" + userId + "/contracts").session(hr), """
                {"contractType":"PERMANENT","startDate":"%s","gradeId":%d,"occupationId":%d,"expatriate":false}"""
                .formatted(start, grade, job)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(saved, "$.id").longValue();
    }

    long job(MockHttpSession hr, String body) throws Exception {
        String saved = mvc.perform(json(post("/api/job-catalogue").session(hr), body)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(saved, "$.id").longValue();
    }

    static String code() {
        return "J" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    @Test
    void aJobDescriptionIsKeptInVersionsAndAContractKeepsItsVersion() throws Exception {
        MockHttpSession hr = login(HR);
        long low = grade(hr, "E3A");
        long high = grade(hr, "E4B");
        String code = code();
        long job = job(hr, """
                {"code":"%s","name":"Storekeeper","active":true,"purpose":"Keeps the store","duties":"Receives goods\\nCounts the stock",
                 "education":"State diploma","minGradeId":%d,"maxGradeId":%d,"unskilled":false}""".formatted(code, low, high));
        mvc.perform(get("/api/job-catalogue").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + job + ")].version").value(Matchers.contains(1)));

        long id = freshEmployee();
        long c = contract(hr, id, LocalDate.now().minusDays(5), low, job);
        assertThat(jdbc.sql("select occupation_version from employee_contract where id = ?").param(c).query(Integer.class).single())
                .isEqualTo(1);

        // A new version: the contract keeps version 1.
        mvc.perform(json(put("/api/job-catalogue/" + job).session(hr), """
                {"code":"%s","name":"Storekeeper","active":true,"purpose":"Keeps the store and the tools","duties":"Receives goods",
                 "minGradeId":%d,"maxGradeId":%d,"unskilled":false}""".formatted(code, low, high)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(get("/api/job-catalogue/" + job + "/versions").session(hr)).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].purpose").value("Keeps the store"));
        assertThat(jdbc.sql("select occupation_version from employee_contract where id = ?").param(c).query(Integer.class).single())
                .isEqualTo(1);

        mvc.perform(get("/api/job-catalogue/" + job + "/pdf").session(hr)).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
        mvc.perform(get("/api/job-catalogue/people/" + id + "/pdf").session(hr)).andExpect(status().isOk());
        // Someone else's job description is not for an employee; the page is HR's.
        MockHttpSession employee = login(EMPLOYEE);
        mvc.perform(get("/api/job-catalogue/people/" + id + "/pdf").session(employee)).andExpect(status().isForbidden());
        mvc.perform(get("/api/job-catalogue").session(employee)).andExpect(status().isForbidden());

        // The grade must pay no more at the bottom than at the top; a job does not report to itself.
        mvc.perform(json(put("/api/job-catalogue/" + job).session(hr), """
                {"code":"%s","name":"Storekeeper","active":true,"minGradeId":%d,"maxGradeId":%d,"unskilled":false}"""
                .formatted(code, high, low))).andExpect(status().is(422));
        mvc.perform(json(put("/api/job-catalogue/" + job).session(hr), """
                {"code":"%s","name":"Storekeeper","active":true,"reportsToId":%d,"unskilled":false}""".formatted(code, job)))
                .andExpect(status().is(422));
    }

    @Test
    void anUnskilledJobRefusesALongTrialAndAGradeOutsideItsRangeIsFlagged() throws Exception {
        MockHttpSession hr = login(HR);
        long low = grade(hr, "E1A");
        long outside = grade(hr, "M1");
        long job = job(hr, """
                {"code":"%s","name":"Labourer","active":true,"duties":"Loads trucks","minGradeId":%d,"maxGradeId":%d,"unskilled":true}"""
                .formatted(code(), low, low));
        long id = freshEmployee();
        long c = contract(hr, id, LocalDate.now(), outside, job);
        mvc.perform(json(put("/api/lifecycle/people/" + id + "/contracts/" + c + "/terms").session(hr), """
                {"trialMonths":3}""")).andExpect(status().is(422))
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("art. 43")));
        mvc.perform(get("/api/lifecycle/people/" + id + "/contracts/" + c + "/terms").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.issues[?(@.code=='GRADE_RANGE')].level").value(Matchers.contains("WARN")));
    }

    @Test
    void theLadderShowsWhoMeetsTheConditionsOfTheNextJob() throws Exception {
        MockHttpSession hr = login(HR);
        long g = grade(hr, "E3A");
        long clerk = job(hr, """
                {"code":"%s","name":"Clerk","active":true,"unskilled":false}""".formatted(code()));
        long head = job(hr, """
                {"code":"%s","name":"Head clerk","active":true,"unskilled":false}""".formatted(code()));
        long id = freshEmployee();
        contract(hr, id, LocalDate.now().minusYears(3), g, clerk);

        String path = mvc.perform(json(post("/api/job-catalogue/paths").session(hr), """
                {"fromId":%d,"toId":%d,"minYears":2}""".formatted(clerk, head)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long pathId = JsonPath.<Number>read(path, "$.id").longValue();
        mvc.perform(json(post("/api/job-catalogue/paths").session(hr), """
                {"fromId":%d,"toId":%d,"minYears":1}""".formatted(clerk, head))).andExpect(status().isConflict());

        mvc.perform(get("/api/job-catalogue/people/" + id + "/next-steps").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.occupationName").value("Clerk"))
                .andExpect(jsonPath("$.steps[0].toName").value("Head clerk"))
                .andExpect(jsonPath("$.steps[0].yearsMet").value(true))
                .andExpect(jsonPath("$.steps[0].eligible").value(true));
        mvc.perform(get("/api/job-catalogue/ready").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userId==" + id + ")].toName").value(Matchers.contains("Head clerk")));

        // With an evaluation average required and none approved, the person is not ready.
        mvc.perform(json(put("/api/job-catalogue/paths/" + pathId).session(hr), """
                {"fromId":%d,"toId":%d,"minYears":2,"minScore":70}""".formatted(clerk, head))).andExpect(status().isOk());
        mvc.perform(get("/api/job-catalogue/people/" + id + "/next-steps").session(hr))
                .andExpect(jsonPath("$.steps[0].scoreMet").value(false)).andExpect(jsonPath("$.steps[0].eligible").value(false));
        mvc.perform(get("/api/job-catalogue/ready").session(hr))
                .andExpect(jsonPath("$[?(@.userId==" + id + ")]").isEmpty());
        mvc.perform(delete("/api/job-catalogue/paths/" + pathId).session(hr).with(csrf())).andExpect(status().isOk());
    }

    @Test
    void positionsCountWhoIsInPlaceAndWarnWhenNoPostIsFree() throws Exception {
        MockHttpSession hr = login(HR);
        int year = LocalDate.now().getYear();
        long g = grade(hr, "E3A");
        long job = job(hr, """
                {"code":"%s","name":"Cashier","active":true,"unskilled":false}""".formatted(code()));
        long unit = jdbc.sql("select id from org_unit where parent_id is null order by id limit 1").query(Long.class).single();
        try {
            mvc.perform(json(post("/api/positions").session(hr), """
                    {"year":%d,"unitId":%d,"occupationId":%d,"posts":1,"budgetCost":12000}""".formatted(year, unit, job)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.posts").value(1));

            long first = freshEmployee();
            jdbc.sql("update org_assignment set valid_to = current_date - 1 where user_id = ? and valid_to is null and valid_from < current_date")
                    .param(first).update();
            jdbc.sql("delete from org_assignment where user_id = ? and valid_to is null").param(first).update();
            jdbc.sql("insert into org_assignment (user_id, unit_id, valid_from, reason, created_by) values (?, ?, current_date - 30, 'HIRE', 'test')")
                    .params(first, unit).update();
            // The post is free: no warning; then it is taken.
            mvc.perform(get("/api/positions/check").session(hr).param("userId", String.valueOf(first)).param("occupationId", String.valueOf(job)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.warning").doesNotExist());
            contract(hr, first, LocalDate.now().minusDays(20), g, job);

            String headcount = mvc.perform(get("/api/positions").session(hr).param("year", String.valueOf(year)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<List<Integer>>read(headcount, "$.units[?(@.unitId==" + unit + ")].jobs[?(@.occupationId==" + job + ")].filled"))
                    .containsExactly(1);
            assertThat(JsonPath.<List<Integer>>read(headcount, "$.units[?(@.unitId==" + unit + ")].jobs[?(@.occupationId==" + job + ")].vacant"))
                    .containsExactly(0);

            // A second person in the same unit and job: no free post, a warning to go on with.
            long second = freshEmployee();
            jdbc.sql("delete from org_assignment where user_id = ?").param(second).update();
            jdbc.sql("insert into org_assignment (user_id, unit_id, valid_from, reason, created_by) values (?, ?, current_date - 30, 'HIRE', 'test')")
                    .params(second, unit).update();
            mvc.perform(get("/api/positions/check").session(hr).param("userId", String.valueOf(second)).param("occupationId", String.valueOf(job)))
                    .andExpect(jsonPath("$.warning").value(Matchers.containsString("1 approved, 1 filled")));
            // The one in place is not warned about the post they hold.
            mvc.perform(get("/api/positions/check").session(hr).param("userId", String.valueOf(first)).param("occupationId", String.valueOf(job)))
                    .andExpect(jsonPath("$.warning").doesNotExist());

            // The next year starts from the people in place.
            mvc.perform(json(post("/api/positions/copy").session(hr), """
                    {"toYear":%d,"fromFilled":true}""".formatted(year + 1))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.lines").value(Matchers.greaterThan(0)));
            assertThat(jdbc.sql("select posts from position_budget where year = ? and unit_id = ? and occupation_id = ?")
                    .params(year + 1, unit, job).query(Integer.class).single()).isEqualTo(1);
            mvc.perform(json(post("/api/positions/copy").session(hr), """
                    {"toYear":%d,"fromFilled":false}""".formatted(year + 1))).andExpect(status().is(422));
            mvc.perform(get("/api/positions/units").session(login(EMPLOYEE)).param("year", String.valueOf(year))).andExpect(status().isOk());
            mvc.perform(get("/api/positions").session(login(EMPLOYEE)).param("year", String.valueOf(year))).andExpect(status().isForbidden());
        } finally {
            jdbc.sql("delete from position_budget where year in (?, ?)").params(year, year + 1).update();
        }
    }
}
