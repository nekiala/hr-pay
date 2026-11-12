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
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

/** Talent review and succession: confidential to HR, successors with a development course, and the 9-box review. */
class TalentIT extends IntegrationTest {

    static final String MANAGER = "manager@demo.hrpay.cd";

    @Autowired
    UserRepository users;

    @Test
    void hrFlagsTheCriticalPostsNamesASuccessorAndPlansTheirTraining() throws Exception {
        MockHttpSession hr = login(HR);
        MockHttpSession employee = login(EMPLOYEE);
        MockHttpSession manager = login(MANAGER);
        long me = users.findByEmailIgnoreCase(EMPLOYEE).orElseThrow().getId();
        long boss = users.findByEmailIgnoreCase(MANAGER).orElseThrow().getId();

        // Nobody but HR (or the head of the Direction générale) sees it.
        mvc.perform(get("/api/talent/posts").session(employee)).andExpect(status().isForbidden());
        mvc.perform(get("/api/talent/posts").session(manager)).andExpect(status().isForbidden());
        mvc.perform(get("/api/talent/access").session(manager)).andExpect(jsonPath("$.talent").value(false));
        mvc.perform(get("/api/talent/people/" + me).session(manager)).andExpect(status().isForbidden());

        // The heads of the top units are suggested; the manager heads a direction.
        mvc.perform(post("/api/talent/posts/suggest").session(hr).with(csrf())).andExpect(status().isOk());
        String posts = mvc.perform(get("/api/talent/posts").session(hr)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.read(posts, "$[?(@.head == true && @.holders[0].userId == %d)].id".formatted(boss));
        assertThat(ids).isNotEmpty();
        long postId = ids.getFirst().longValue();
        List<Number> old = JsonPath.read(posts, "$[?(@.id == %d)].successors[?(@.userId == %d)].id".formatted(postId, me));
        for (Number o : old) {
            mvc.perform(delete("/api/talent/successors/" + o).session(hr).with(csrf())).andExpect(status().isOk());
        }
        mvc.perform(get("/api/talent/posts/" + postId + "/candidates").session(hr)).andExpect(status().isOk());

        // The employee is named successor, ready in one or two years; the holder cannot be.
        String added = mvc.perform(json(post("/api/talent/posts/" + postId + "/successors").session(hr), """
                {"userId":%d,"readiness":"ONE_TWO","note":"Conduit déjà les chantiers"}""".formatted(me)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long successor = JsonPath.<List<Number>>read(added, "$.successors[?(@.userId == %d)].id".formatted(me)).getFirst().longValue();
        mvc.perform(json(post("/api/talent/posts/" + postId + "/successors").session(hr), """
                {"userId":%d,"readiness":"NOW"}""".formatted(me))).andExpect(status().is(422));
        mvc.perform(json(post("/api/talent/posts/" + postId + "/successors").session(hr), """
                {"userId":%d,"readiness":"NOW"}""".formatted(boss))).andExpect(status().is(422));
        mvc.perform(json(post("/api/talent/posts/" + postId + "/successors").session(manager), """
                {"userId":%d,"readiness":"NOW"}""".formatted(me))).andExpect(status().isForbidden());

        // A course to prepare them becomes a training need.
        String course = mvc.perform(json(post("/api/training/courses").session(hr), """
                {"title":"Diriger une direction","providerKind":"EXTERNAL","mode":"CLASSROOM","hours":40,"active":true}"""))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long courseId = JsonPath.<Number>read(course, "$.id").longValue();
        LocalDate due = LocalDate.now().plusMonths(6);
        mvc.perform(json(post("/api/talent/successors/" + successor + "/actions").session(hr), """
                {"kind":"COURSE","courseId":%d,"dueOn":"%s"}""".formatted(courseId, due)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successors[?(@.userId == %d)].actions[*].needId".formatted(me)).value(Matchers.hasItem(Matchers.notNullValue())));
        mvc.perform(get("/api/training/needs").param("year", String.valueOf(due.getYear())).session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.source == 'SUCCESSION' && @.userId == %d)].courseId".formatted(me)).value(Matchers.hasItem((int) courseId)));
        mvc.perform(json(post("/api/talent/successors/" + successor + "/actions").session(hr), """
                {"kind":"MENTOR","title":"Binôme avec le directeur","mentorUserId":%d}""".formatted(me))).andExpect(status().is(422));

        mvc.perform(get("/api/talent/people/" + me).session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.successorOf.length()").value(Matchers.greaterThanOrEqualTo(1)));
        mvc.perform(get("/api/talent/coverage").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.posts").value(Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    void theManagerProposesThePotentialAndHrSettlesTheNineBox() throws Exception {
        MockHttpSession hr = login(HR);
        MockHttpSession employee = login(EMPLOYEE);
        MockHttpSession manager = login(MANAGER);
        long me = users.findByEmailIgnoreCase(EMPLOYEE).orElseThrow().getId();
        String name = "Revue " + System.nanoTime();

        // The thresholds that turn an evaluation into performance 1-2-3 are HR's.
        mvc.perform(get("/api/talent/settings").session(manager)).andExpect(status().isForbidden());
        mvc.perform(get("/api/talent/settings").session(hr)).andExpect(status().isOk()).andExpect(jsonPath("$.performanceHigh").value(80));
        mvc.perform(json(put("/api/talent/settings").session(hr), """
                {"retirementAge":65,"performanceLow":80,"performanceHigh":70}""")).andExpect(status().is(422));

        String opened = mvc.perform(json(post("/api/talent/reviews").session(hr), """
                {"year":%d,"name":"%s"}""".formatted(LocalDate.now().getYear(), name)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.review.status").value("OPEN"))
                .andReturn().getResponse().getContentAsString();
        long review = JsonPath.<Number>read(opened, "$.review.id").longValue();
        long member = JsonPath.<List<Number>>read(opened, "$.members[?(@.userId == %d)].id".formatted(me)).getFirst().longValue();
        mvc.perform(json(post("/api/talent/reviews").session(manager), """
                {"year":2026,"name":"x"}""")).andExpect(status().isForbidden());
        mvc.perform(get("/api/talent/reviews/" + review).session(manager)).andExpect(status().isForbidden());

        // The manager sees only their people and proposes a potential, with a reason.
        mvc.perform(get("/api/talent/proposals").session(manager)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].memberId").value(Matchers.hasItem((int) member)))
                .andExpect(jsonPath("$[0].performance").doesNotExist());
        mvc.perform(json(put("/api/talent/proposals/" + member).session(employee), """
                {"potential":3,"reason":"moi"}""")).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/talent/proposals/" + member).session(manager), """
                {"potential":3,"reason":"Prend des initiatives, apprend vite"}""")).andExpect(status().isOk());

        // HR and the Direction settle: potential 3, performance 2, high flight risk: box 8.
        mvc.perform(get("/api/talent/reviews/" + review).session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.members[?(@.id == %d)].potentialProposed".formatted(member)).value(Matchers.hasItem(3)));
        mvc.perform(json(put("/api/talent/members/" + member).session(hr), """
                {"performance":4,"potential":3}""")).andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/talent/members/" + member).session(hr), """
                {"performance":2,"performanceReason":"Revue en comité","potential":3,"flightRisk":"HIGH","mobility":"LATER"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[?(@.id == %d)].box".formatted(member)).value(Matchers.hasItem(8)))
                .andExpect(jsonPath("$.grid.8").value(Matchers.greaterThanOrEqualTo(1)));
        mvc.perform(get("/api/talent/people/" + me).session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.box").value(8)).andExpect(jsonPath("$.flightRisk").value("HIGH"));

        mvc.perform(post("/api/talent/reviews/" + review + "/close").session(hr).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.review.status").value("CLOSED"));
        mvc.perform(json(put("/api/talent/proposals/" + member).session(manager), """
                {"potential":2,"reason":"Après coup"}""")).andExpect(status().is(422));
    }
}
