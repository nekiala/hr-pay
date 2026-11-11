package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** Objectives written by the manager, acknowledged by the person, checked in, and scored in the evaluation. */
class ObjectivesIT extends IntegrationTest {

    static final String MANAGER = "manager@demo.hrpay.cd";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    UserRepository users;

    long freshEmployee() throws Exception {
        String created = mvc.perform(json(post("/api/users").session(login(ADMIN)), newUser("[\"EMPLOYEE\"]")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(created, "$.id").longValue();
    }

    long objective(MockHttpSession who, String body) throws Exception {
        String saved = mvc.perform(json(post("/api/objectives").session(who), body)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(saved, "$.id").longValue();
    }

    /** A person of their own grade category, whose template scores objectives at 60%, reporting to the manager. */
    long personScoredOnObjectives() throws Exception {
        long id = freshEmployee();
        MockHttpSession hr = login(HR);
        String grades = mvc.perform(get("/api/grades").session(hr)).andReturn().getResponse().getContentAsString();
        long grade = JsonPath.<List<Number>>read(grades, "$[?(@.code=='E3A')].id").getFirst().longValue();
        long job = jdbc.sql("select id from occupation where active order by id limit 1").query(Long.class).single();
        mvc.perform(json(post("/api/users/" + id + "/contracts").session(hr), """
                {"contractType":"PERMANENT","startDate":"%s","gradeId":%d,"occupationId":%d,"expatriate":false}"""
                .formatted(LocalDate.now().minusYears(1), grade, job))).andExpect(status().isCreated());
        long company = jdbc.sql("select company_id from app_user where id = ?").param(id).query(Long.class).single();
        long manager = users.findByEmailIgnoreCase(MANAGER).orElseThrow().getId();
        String code = "OBJ" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        long category = jdbc.sql("""
                insert into grade_category (company_id, code, name, annual_leave_days) values (?, ?, ?, 20) returning id""")
                .params(company, code, "Objectives test " + code).query(Long.class).single();
        long template = jdbc.sql("""
                insert into evaluation_template (company_id, name, frequency, grade_category_id) values (?, ?, 'QUARTERLY', ?)
                returning id""").params(company, "Objectives test " + code, category).query(Long.class).single();
        jdbc.sql("""
                insert into evaluation_template_criterion (company_id, template_id, criterion_id, weight)
                select ?, ?, id, case code when 'PRODUCTIVITY' then 40 else 60 end from evaluation_criterion
                where company_id = ? and code in ('PRODUCTIVITY', 'OBJECTIVES')""").params(company, template, company).update();
        jdbc.sql("""
                update app_user set grade_category_id = ?, manager_id = ?,
                       password_hash = (select password_hash from app_user where email = ?) where id = ?""")
                .params(category, manager, EMPLOYEE, id).update();
        return id;
    }

    @Test
    void theManagerWritesObjectivesThePersonAcknowledgesAndTheEvaluationScoresThem() throws Exception {
        MockHttpSession manager = login(MANAGER);
        MockHttpSession hr = login(HR);
        long id = personScoredOnObjectives();
        String email = jdbc.sql("select email from app_user where id = ?").param(id).query(String.class).single();
        MockHttpSession person = login(email);
        int year = LocalDate.now().getYear();

        long first = objective(manager, """
                {"userId":%d,"year":%d,"title":"Close the accounts by the 5th","target":"12 of 12","weight":60}""".formatted(id, year));
        long second = objective(manager, """
                {"userId":%d,"year":%d,"title":"Train two colleagues","weight":30}""".formatted(id, year));
        // Drafts are not shown to the person; another employee sees nothing.
        mvc.perform(get("/api/objectives/mine").session(person)).andExpect(status().isOk())
                .andExpect(jsonPath("$.objectives.length()").value(0));
        mvc.perform(get("/api/objectives/people/" + id).session(login(EMPLOYEE))).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/objectives").session(person), """
                {"userId":%d,"year":%d,"title":"Mine","weight":10}""".formatted(id, year))).andExpect(status().isForbidden());

        // The weights must add up to 100 before sharing.
        mvc.perform(post("/api/objectives/people/" + id + "/share").session(manager).with(csrf())).andExpect(status().is(422));
        mvc.perform(json(put("/api/objectives/" + second).session(manager), """
                {"title":"Train two colleagues","measure":"Colleagues trained","weight":40}""")).andExpect(status().isOk());
        mvc.perform(post("/api/objectives/people/" + id + "/share").session(manager).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.weightTotal").value(100.0));

        mvc.perform(get("/api/objectives/mine").session(person)).andExpect(status().isOk())
                .andExpect(jsonPath("$.objectives.length()").value(2))
                .andExpect(jsonPath("$.objectives[*].status").value(Matchers.everyItem(Matchers.is("SHARED"))));
        mvc.perform(json(post("/api/objectives/mine/acknowledge").session(person), """
                {"year":%d,"comment":"Agreed."}""".formatted(year))).andExpect(status().isOk())
                .andExpect(jsonPath("$.objectives[*].status").value(Matchers.everyItem(Matchers.is("ACKNOWLEDGED"))));

        // A check-in on one objective; the person cannot record one.
        mvc.perform(json(put("/api/objectives/" + first + "/checkin").session(person), """
                {"progress":50}""")).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/objectives/" + first + "/checkin").session(manager), """
                {"progress":50,"comment":"On track","developmentNeed":"Excel training"}""")).andExpect(status().isOk())
                .andExpect(jsonPath("$.progress").value(50));

        // The team list and HR's overview.
        mvc.perform(get("/api/objectives/team").session(manager)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userId==" + id + ")].acknowledged").value(Matchers.contains(2)));

        // The evaluation scores each objective; their weighted average is the objectives line.
        String started = mvc.perform(json(post("/api/evaluations").session(manager), """
                {"userId":%d}""".formatted(id))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.objectives.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        long evaluation = JsonPath.<Number>read(started, "$.id").longValue();
        long productivity = JsonPath.<List<Number>>read(started, "$.scores[?(@.code=='PRODUCTIVITY')].criterionId").getFirst().longValue();
        mvc.perform(json(put("/api/evaluations/" + evaluation + "/scores").session(manager), """
                {"scores":[{"criterionId":%d,"score":80}],"objectiveScores":[{"objectiveId":%d,"score":100}],"submit":true}"""
                .formatted(productivity, first))).andExpect(status().is(422));   // the second objective has no score
        // Objectives: 60 x 100 + 40 x 50 = 80; total 40% x 80 + 60% x 80 = 80.
        mvc.perform(json(put("/api/evaluations/" + evaluation + "/scores").session(manager), """
                {"scores":[{"criterionId":%d,"score":80}],
                 "objectiveScores":[{"objectiveId":%d,"score":100},{"objectiveId":%d,"score":50,"comment":"One trained"}],"submit":true}"""
                .formatted(productivity, first, second))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.scores[?(@.code=='OBJECTIVES')].score").value(Matchers.contains(80.0)))
                .andExpect(jsonPath("$.totalPercent").value(80.0));

        mvc.perform(get("/api/objectives/overview").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.developmentNeeds[?(@.userId==" + id + ")].detail")
                        .value(Matchers.contains("Close the accounts by the 5th: Excel training")));
        mvc.perform(get("/api/objectives/mine").session(person)).andExpect(status().isOk())
                .andExpect(jsonPath("$.objectives[?(@.id==" + first + ")].lastScore").value(Matchers.contains(100.0)));
        mvc.perform(get("/api/objectives/overview").session(manager)).andExpect(status().isForbidden());
    }

    @Test
    void aChangeAsksForANewAcknowledgementAndASharedObjectiveIsCancelledNotDeleted() throws Exception {
        MockHttpSession manager = login(MANAGER);
        MockHttpSession hr = login(HR);
        long id = personScoredOnObjectives();
        String email = jdbc.sql("select email from app_user where id = ?").param(id).query(String.class).single();
        MockHttpSession person = login(email);
        int year = LocalDate.now().getYear() + 1;

        // A unit objective, which a person's objective follows.
        long unit = jdbc.sql("select id from org_unit where valid_to is null order by id limit 1").query(Long.class).single();
        long goal = objective(hr, """
                {"orgUnitId":%d,"year":%d,"title":"Cut costs by 5 %%"}""".formatted(unit, year));
        long only = objective(manager, """
                {"userId":%d,"year":%d,"parentId":%d,"title":"Renegotiate two contracts","weight":100}""".formatted(id, year, goal));
        mvc.perform(post("/api/objectives/people/" + id + "/share?year=" + year).session(manager).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.objectives[0].parentTitle").value("Cut costs by 5 %"));
        mvc.perform(json(post("/api/objectives/mine/acknowledge").session(person), """
                {"year":%d}""".formatted(year))).andExpect(status().isOk());

        mvc.perform(json(put("/api/objectives/" + only).session(manager), """
                {"parentId":%d,"title":"Renegotiate three contracts","weight":100}""".formatted(goal))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHARED"));
        mvc.perform(delete("/api/objectives/" + only).session(manager).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/objectives/people/" + id + "?year=" + year).session(manager)).andExpect(status().isOk())
                .andExpect(jsonPath("$.objectives[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.weightTotal").value(0));
        assertThat(jdbc.sql("select count(*) from objective where id = ?").param(only).query(Integer.class).single()).isEqualTo(1);
    }
}
