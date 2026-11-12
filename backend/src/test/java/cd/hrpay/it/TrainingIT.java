package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.payroll.PayElements;
import cd.hrpay.tenancy.Tenants;
import cd.hrpay.training.Training;
import cd.hrpay.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** Training: a request, the plan with the representatives' opinion, a session, the effect check, and apprentices. */
class TrainingIT extends IntegrationTest {

    static final String MANAGER = "manager@demo.hrpay.cd";
    static final String PDF = "data:application/pdf;base64,"
            + Base64.getEncoder().encodeToString("%PDF-1.4\n% a certificate\n".getBytes(StandardCharsets.UTF_8));

    @Autowired
    JdbcClient jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    Training training;

    @Autowired
    PayElements pay;

    @Autowired
    Tenants tenants;

    @Test
    void anEmployeeAsksTheManagerSupportsHrPlansTrainsAndTheManagerJudgesTheEffect() throws Exception {
        MockHttpSession hr = login(HR);
        MockHttpSession employee = login(EMPLOYEE);
        MockHttpSession manager = login(MANAGER);
        long me = users.findByEmailIgnoreCase(EMPLOYEE).orElseThrow().getId();
        int year = LocalDate.now().getYear() + 1;   // a year no other test touches

        String course = mvc.perform(json(post("/api/training/courses").session(hr), """
                {"title":"Excel avancé","providerKind":"INPP","mode":"CLASSROOM","hours":16,"costPerPerson":120,"currency":"USD","active":true}"""))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long courseId = JsonPath.<Number>read(course, "$.id").longValue();
        mvc.perform(json(post("/api/training/courses").session(employee), """
                {"title":"x","providerKind":"INPP","mode":"CLASSROOM","hours":1,"active":true}""")).andExpect(status().isForbidden());

        // The employee asks; their manager supports; HR accepts.
        String asked = mvc.perform(json(post("/api/training/needs").session(employee), """
                {"topic":"Tableaux croisés","courseId":%d,"reason":"Rapports mensuels","year":%d}""".formatted(courseId, year)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.source").value("EMPLOYEE")).andReturn().getResponse().getContentAsString();
        long need = JsonPath.<Number>read(asked, "$.id").longValue();
        mvc.perform(json(post("/api/training/needs/" + need + "/decision").session(employee), """
                {"accept":true}""")).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/training/needs/" + need + "/opinion").session(manager), """
                {"opinion":"SUPPORT","comment":"Utile"}""")).andExpect(status().isOk())
                .andExpect(jsonPath("$.managerOpinion").value("SUPPORT"));
        mvc.perform(get("/api/training/needs").param("year", String.valueOf(year)).session(manager)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(Matchers.hasItem((int) need)));
        mvc.perform(json(post("/api/training/needs/" + need + "/decision").session(hr), """
                {"accept":false}""")).andExpect(status().is(422));
        mvc.perform(json(post("/api/training/needs/" + need + "/decision").session(hr), """
                {"accept":true,"priority":"HIGH"}""")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"));

        // The plan: a line for every category, the representatives consulted, approved.
        mvc.perform(json(put("/api/training/plans/" + year).session(hr), """
                {"budget":5000,"currency":"USD","note":"Plan annuel"}""")).andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.status").value("DRAFT"))
                .andExpect(jsonPath("$.warnings", Matchers.hasItem(Matchers.containsString("art. 19"))));
        mvc.perform(post("/api/training/plans/" + year + "/approve").session(hr).with(csrf())).andExpect(status().is(422));
        String plan = mvc.perform(json(post("/api/training/plans/" + year + "/lines").session(hr), """
                {"courseId":%d,"quarter":2,"people":10,"budget":1200,"nationalManagers":true}""".formatted(courseId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].topic").value("Excel avancé"))
                .andExpect(jsonPath("$.plannedBudget").value(1200)).andReturn().getResponse().getContentAsString();
        long line = JsonPath.<Number>read(plan, "$.lines[0].id").longValue();
        mvc.perform(json(post("/api/training/plans/" + year + "/consult").session(hr), """
                {"consultedOn":"%s","opinion":"Favorable"}""".formatted(LocalDate.now().plusDays(3))))
                .andExpect(status().is(422));
        mvc.perform(json(post("/api/training/plans/" + year + "/consult").session(hr), """
                {"consultedOn":"%s","opinion":"Favorable"}""".formatted(LocalDate.now()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.status").value("CONSULTED"));
        mvc.perform(post("/api/training/plans/" + year + "/approve").session(hr).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.status").value("APPROVED"))
                .andExpect(jsonPath("$.warnings", Matchers.not(Matchers.hasItem(Matchers.containsString("art. 17")))));
        mvc.perform(json(post("/api/training/needs/" + need + "/change").session(hr), """
                {"planLineId":%d}""".formatted(line))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PLANNED"));

        // A session: the need brings the employee in; it ends; who came; the cost.
        LocalDate start = LocalDate.of(year, 4, 6);
        String session = mvc.perform(json(post("/api/training/sessions").session(hr), """
                {"courseId":%d,"planLineId":%d,"startsOn":"%s","endsOn":"%s","place":"Kinshasa","trainer":"INPP"}"""
                .formatted(courseId, line, start, start.plusDays(1)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.openNeeds[*].id").value(Matchers.hasItem((int) need)))
                .andReturn().getResponse().getContentAsString();
        long sessionId = JsonPath.<Number>read(session, "$.session.id").longValue();
        mvc.perform(json(post("/api/training/sessions/" + sessionId + "/attendees").session(hr), """
                {"needIds":[%d]}""".formatted(need))).andExpect(status().isOk())
                .andExpect(jsonPath("$.attendees[0].userId").value((int) me));
        // Not over yet.
        mvc.perform(json(post("/api/training/sessions/" + sessionId + "/done").session(hr), """
                {"present":[%d]}""".formatted(me))).andExpect(status().is(422));
        jdbc.sql("update training_session set starts_on = current_date - 100, ends_on = current_date - 99 where id = ?")
                .param(sessionId).update();
        String done = mvc.perform(json(post("/api/training/sessions/" + sessionId + "/done").session(hr), """
                {"present":[%d],"actualCost":150}""".formatted(me))).andExpect(status().isOk())
                .andExpect(jsonPath("$.session.status").value("DONE"))
                .andExpect(jsonPath("$.attendees[0].attended").value(true)).andReturn().getResponse().getContentAsString();
        long attendee = JsonPath.<Number>read(done, "$.attendees[0].id").longValue();
        assertThat(jdbc.sql("select status from training_need where id = ?").param(need).query(String.class).single()).isEqualTo("DONE");

        // The certificate goes to the employee file.
        mvc.perform(json(post("/api/training/attendees/" + attendee + "/certificate").session(hr), """
                {"fileName":"attestation.pdf","data":"%s"}""".formatted(PDF))).andExpect(status().isOk())
                .andExpect(jsonPath("$.attendees[0].documentId").isNumber());

        // The employee rates it and sees it in their history; another person cannot.
        mvc.perform(json(post("/api/training/attendees/" + attendee + "/rating").session(hr), """
                {"rating":5}""")).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/training/attendees/" + attendee + "/rating").session(employee), """
                {"rating":4,"comment":"Très pratique"}""")).andExpect(status().isOk())
                .andExpect(jsonPath("$.history[?(@.attendeeId==" + attendee + ")].rating").value(Matchers.contains(4)))
                .andExpect(jsonPath("$.requests[?(@.id==" + need + ")].status").value(Matchers.contains("DONE")));

        // Three months on, the manager is asked once, and answers.
        long company = jdbc.sql("select company_id from app_user where id = ?").param(me).query(Long.class).single();
        assertThat(tenants.callAs(company, training::remind)).isPositive();
        assertThat(tenants.callAs(company, training::remind)).isZero();
        mvc.perform(get("/api/training/effects").session(manager)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].attendeeId").value(Matchers.hasItem((int) attendee)));
        mvc.perform(json(post("/api/training/attendees/" + attendee + "/effect").session(employee), """
                {"effect":"YES"}""")).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/training/attendees/" + attendee + "/effect").session(manager), """
                {"effect":"PARTLY","comment":"Rapports plus rapides"}""")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].attendeeId").value(Matchers.not(Matchers.hasItem((int) attendee))));

        // The report of the year the session took place.
        int sessionYear = LocalDate.now().minusDays(100).getYear();
        mvc.perform(get("/api/training/report").param("year", String.valueOf(sessionYear)).session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.byPerson[*].label").value(Matchers.hasItem(Matchers.containsString("EMP"))))
                .andExpect(jsonPath("$.levy.localCurrency").isNotEmpty());
        mvc.perform(get("/api/training/report").param("year", String.valueOf(year)).session(employee)).andExpect(status().isForbidden());
        mvc.perform(get("/api/training/people/" + me).session(manager)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].courseTitle").value("Excel avancé"));
    }

    @Test
    void anApprenticeHasAtMostFourYearsAndTheFollowUpTheCodeAsks() throws Exception {
        MockHttpSession hr = login(HR);
        String created = mvc.perform(json(post("/api/users").session(login(ADMIN)), newUser("[\"EMPLOYEE\"]")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long me = JsonPath.<Number>read(created, "$.id").longValue();
        String grades = mvc.perform(get("/api/grades").session(hr)).andReturn().getResponse().getContentAsString();
        long grade = JsonPath.<List<Number>>read(grades, "$[?(@.code=='E1A')].id").getFirst().longValue();
        usdRate(LocalDate.now().minusDays(1).toString(), 2800);
        LocalDate start = LocalDate.now().minusMonths(2);
        jdbc.sql("insert into employee_profile (user_id, birth_date) values (?, ?) on conflict (user_id) do update set birth_date = excluded.birth_date")
                .params(me, start.minusYears(16)).update();

        mvc.perform(json(post("/api/users/" + me + "/contracts").session(hr), """
                {"contractType":"APPRENTICE","startDate":"%s","endDate":"%s","gradeId":%d,"expatriate":false}"""
                .formatted(start, start.plusYears(5), grade))).andExpect(status().is(422));
        mvc.perform(json(post("/api/users/" + me + "/contracts").session(hr), """
                {"contractType":"APPRENTICE","startDate":"%s","gradeId":%d,"expatriate":false}"""
                .formatted(start, grade))).andExpect(status().is(422));
        mvc.perform(json(post("/api/users/" + me + "/contracts").session(hr), """
                {"contractType":"APPRENTICE","startDate":"%s","endDate":"%s","gradeId":%d,"expatriate":false}"""
                .formatted(start, start.plusYears(2), grade))).andExpect(status().isCreated());
        long contract = jdbc.sql("select id from employee_contract where user_id = ? and contract_type = 'APPRENTICE'").param(me)
                .query(Long.class).single();

        mvc.perform(get("/api/apprentices/" + contract).session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.ageAtStart").value(16))
                .andExpect(jsonPath("$.warnings", Matchers.hasItem(Matchers.containsString("art. 21"))))
                .andExpect(jsonPath("$.warnings", Matchers.hasItem(Matchers.containsString("art. 6"))));
        long master = users.findByEmailIgnoreCase(HR).orElseThrow().getId();
        mvc.perform(json(put("/api/apprentices/" + contract).session(hr), """
                {"trade":"Électricité","masterUserId":%d,"onemVisaOn":"%s","onemVisaNo":"ONEM/123","medicalOn":"%s","waiverOn":"%s","stayMonths":30}"""
                .formatted(master, start, start.minusMonths(1), start.minusDays(5)))).andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/apprentices/" + contract).session(hr), """
                {"trade":"Électricité","masterUserId":%d,"onemVisaOn":"%s","onemVisaNo":"ONEM/123","medicalOn":"%s","waiverOn":"%s","stayMonths":12}"""
                .formatted(master, start, start.minusMonths(1), start.minusDays(5)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings").isEmpty())
                .andExpect(jsonPath("$.stayUntil").value(start.plusYears(2).plusMonths(12).toString()));
        mvc.perform(get("/api/apprentices").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].contractId").value(Matchers.hasItem((int) contract)));
        mvc.perform(get("/api/apprentices").session(login(EMPLOYEE))).andExpect(status().isForbidden());

        // By default the allowance owes no CNSS and no tax.
        long company = jdbc.sql("select company_id from app_user where id = ?").param(me).query(Long.class).single();
        assertThat(tenants.callAs(company, () -> pay.forContract(pay.elements(), "APPRENTICE"))
                .stream().noneMatch(e -> e.subjectToCnss() || e.taxableIpr())).isTrue();
        assertThat(tenants.callAs(company, () -> pay.forContract(pay.elements(), "PERMANENT"))
                .stream().anyMatch(PayElements.Element::subjectToCnss)).isTrue();
    }

    @Test
    void workersEducationLeaveIsAskedThirtyDaysAhead() throws Exception {
        MockHttpSession employee = login(EMPLOYEE);
        Long type = jdbc.sql("select id from leave_type where code = 'WORKER_EDUCATION' and company_id = (select company_id from app_user where email = ?)")
                .param(EMPLOYEE).query(Long.class).optional().orElse(null);
        assertThat(type).isNotNull();
        jdbc.sql("update leave_type set active = true where id = ?").param(type).update();
        LocalDate soon = LocalDate.now().plusDays(10);
        while (soon.getDayOfWeek().getValue() > 5) {
            soon = soon.plusDays(1);
        }
        mvc.perform(json(post("/api/leave/requests").session(employee), """
                {"leaveTypeId":%d,"startDate":"%s","endDate":"%s"}""".formatted(type, soon, soon)))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("30")));
    }
}
