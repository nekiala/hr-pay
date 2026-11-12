package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

/** Skills: the job's needs, a self-assessment the manager confirms, the gap matrix, needs from gaps, and the search. */
class SkillsIT extends IntegrationTest {

    static final String MANAGER = "manager@demo.hrpay.cd";

    @Autowired
    UserRepository users;

    @Test
    void theEmployeeRatesThemselvesTheManagerConfirmsAndHrTurnsTheGapIntoATrainingNeed() throws Exception {
        MockHttpSession hr = login(HR);
        MockHttpSession employee = login(EMPLOYEE);
        MockHttpSession manager = login(MANAGER);
        long me = users.findByEmailIgnoreCase(EMPLOYEE).orElseThrow().getId();

        // The sample engineer job needs four skills.
        String mine = mvc.perform(get("/api/skills/me").session(employee)).andExpect(status().isOk())
                .andExpect(jsonPath("$.self").value(true)).andExpect(jsonPath("$.canConfirm").value(false))
                .andReturn().getResponse().getContentAsString();
        long job = JsonPath.<Number>read(mine, "$.occupationId").longValue();
        String view = mvc.perform(get("/api/skills/jobs/" + job).session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.skills.length()").value(Matchers.greaterThanOrEqualTo(4)))
                .andReturn().getResponse().getContentAsString();
        long skill = JsonPath.<Number>read(view, "$.skills[0].skillId").longValue();
        int required = JsonPath.<Number>read(view, "$.skills[0].level").intValue();
        assertThat(required).isGreaterThanOrEqualTo(2);
        mvc.perform(get("/api/skills/jobs/" + job).session(employee)).andExpect(status().isForbidden());

        // The employee rates themselves; the manager sees it waiting and confirms a lower level.
        mvc.perform(json(put("/api/skills/me").session(employee), """
                [{"skillId":%d,"level":%d,"note":"Chantier de Kolwezi %d"}]""".formatted(skill, required, System.nanoTime())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[?(@.skillId == %d)].status".formatted(skill)).value(Matchers.hasItem("SELF")))
                .andExpect(jsonPath("$.skills[?(@.skillId == %d)].selfLevel".formatted(skill)).value(Matchers.hasItem(required)));
        mvc.perform(get("/api/skills/reviews").session(manager)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].userId").value(Matchers.hasItem((int) me)));
        mvc.perform(json(put("/api/skills/people/" + me).session(employee), """
                [{"skillId":%d,"level":4}]""".formatted(skill))).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/skills/people/" + me).session(manager), """
                [{"skillId":%d,"level":%d,"note":"Encore accompagné"}]""".formatted(skill, required - 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[?(@.skillId == %d)].status".formatted(skill)).value(Matchers.hasItem("CONFIRMED")))
                .andExpect(jsonPath("$.skills[?(@.skillId == %d)].gap".formatted(skill)).value(Matchers.hasItem(1)));
        mvc.perform(get("/api/skills/reviews").session(manager)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userId == %d)].waiting".formatted(me)).value(Matchers.not(Matchers.hasItem(4))));
        mvc.perform(json(put("/api/skills/people/" + me).session(manager), """
                [{"skillId":%d,"level":5}]""".formatted(skill))).andExpect(status().isBadRequest());

        // The manager's matrix shows the gap.
        mvc.perform(get("/api/skills/matrix").session(manager)).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.userId == %d)].gaps".formatted(me)).value(Matchers.hasItem(Matchers.greaterThanOrEqualTo(1))));

        // A course builds the skill; HR turns the gap into a need, once.
        String course = mvc.perform(json(post("/api/training/courses").session(hr), """
                {"title":"Compétence %d","providerKind":"INTERNAL","mode":"ON_THE_JOB","hours":8,"active":true}""".formatted(skill)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long courseId = JsonPath.<Number>read(course, "$.id").longValue();
        mvc.perform(json(put("/api/skills/courses/" + courseId).session(employee), "[%d]".formatted(skill))).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/skills/courses/" + courseId).session(hr), "[%d]".formatted(skill))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.courseId == %d)].skillId".formatted(courseId)).value(Matchers.hasItem((int) skill)));
        mvc.perform(json(post("/api/skills/gaps/needs").session(manager), """
                [{"userId":%d,"skillId":%d}]""".formatted(me, skill))).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/skills/gaps/needs").session(hr), """
                [{"userId":%d,"skillId":%d}]""".formatted(me, skill))).andExpect(status().isOk());
        mvc.perform(json(post("/api/skills/gaps/needs").session(hr), """
                [{"userId":%d,"skillId":%d}]""".formatted(me, skill))).andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(0));
        String needs = mvc.perform(get("/api/training/needs").param("year", String.valueOf(LocalDate.now().getYear())).session(hr))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Object> gap = JsonPath.read(needs, "$[?(@.userId == %d && @.source == 'SKILL_GAP')].courseId".formatted(me));
        assertThat(gap).isNotEmpty();
        assertThat(gap.get(0)).isNotNull();

        // HR finds who has the skill.
        mvc.perform(get("/api/skills/search").param("skillId", String.valueOf(skill)).param("minLevel", "1").session(hr))
                .andExpect(status().isOk()).andExpect(jsonPath("$[*].userId").value(Matchers.hasItem((int) me)));
        mvc.perform(get("/api/skills/search").param("skillId", String.valueOf(skill)).session(manager)).andExpect(status().isForbidden());
        mvc.perform(get("/api/skills/certifications").session(hr)).andExpect(status().isOk());
    }

    @Test
    void hrKeepsTheCatalogueAndTheSkillsEachJobNeeds() throws Exception {
        MockHttpSession hr = login(HR);
        MockHttpSession employee = login(EMPLOYEE);
        String name = "Soudure " + System.nanoTime();
        String created = mvc.perform(json(post("/api/skills").session(hr), """
                {"family":"TECHNICAL","name":"%s","level1":"Pointe","level4":"Forme les soudeurs","active":true}""".formatted(name)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.family").value("TECHNICAL"))
                .andReturn().getResponse().getContentAsString();
        long id = JsonPath.<Number>read(created, "$.id").longValue();
        mvc.perform(json(post("/api/skills").session(hr), """
                {"family":"TOOL","name":"%s","active":true}""".formatted(name.toUpperCase()))).andExpect(status().is(422));
        mvc.perform(json(post("/api/skills").session(employee), """
                {"family":"TOOL","name":"x","active":true}""")).andExpect(status().isForbidden());

        String jobs = mvc.perform(get("/api/skills/jobs").session(hr)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long job = JsonPath.<Number>read(jobs, "$[0].id").longValue();
        String before = mvc.perform(get("/api/skills/jobs/" + job).session(hr)).andReturn().getResponse().getContentAsString();
        int count = JsonPath.<List<Object>>read(before, "$.skills").size();
        mvc.perform(json(put("/api/skills/jobs/" + job).session(hr), """
                [{"skillId":%d,"level":2},{"skillId":%d,"level":3}]""".formatted(id, id))).andExpect(status().is(422));
        mvc.perform(json(put("/api/skills/jobs/" + job).session(hr), """
                [{"skillId":%d,"level":5}]""".formatted(id))).andExpect(status().isBadRequest());
        // Add the new skill to what the job already needs.
        StringBuilder body = new StringBuilder("[");
        List<Object> ids = JsonPath.read(before, "$.skills[*].skillId");
        List<Object> levels = JsonPath.read(before, "$.skills[*].level");
        for (int i = 0; i < ids.size(); i++) {
            body.append("{\"skillId\":").append(ids.get(i)).append(",\"level\":").append(levels.get(i)).append("},");
        }
        body.append("{\"skillId\":").append(id).append(",\"level\":2}]");
        mvc.perform(json(put("/api/skills/jobs/" + job).session(hr), body.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.skills.length()").value(count + 1));
        mvc.perform(get("/api/skills").param("all", "true").session(employee)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)].jobs".formatted(id)).value(Matchers.hasItem(1)));
    }
}
