package cd.hrpay.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

class EmployeeFileIT extends IntegrationTest {

    @Autowired
    UserRepository users;

    long id(String email) {
        return users.findByEmailIgnoreCase(email).orElseThrow().getId();
    }

    long grade(MockHttpSession s, String code) throws Exception {
        String grades = mvc.perform(get("/api/grades").session(s)).andReturn().getResponse().getContentAsString();
        return JsonPath.<List<Number>>read(grades, "$[?(@.code=='" + code + "')].id").getFirst().longValue();
    }

    @Test
    void newContractEndsThePreviousOneAndSetsTheBasic() throws Exception {
        long employee = id("employee3@demo.hrpay.cd");
        MockHttpSession hr = login(HR);
        mvc.perform(get("/api/users/" + employee + "/file").session(hr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contracts.length()").value(1))
                .andExpect(jsonPath("$.contracts[0].gradeCode").value("E4A"));

        mvc.perform(json(post("/api/users/" + employee + "/contracts").session(hr), """
                        {"contractType":"PERMANENT","startDate":"2026-12-01","gradeId":%d,"expatriate":false,
                         "negotiatedBasic":900,"note":"Promotion"}""".formatted(grade(hr, "M1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gradeCode").value("M1"));
        mvc.perform(get("/api/users/" + employee + "/file").session(hr))
                .andExpect(jsonPath("$.contracts[1].endDate").value("2026-11-30"));
        mvc.perform(get("/api/users/" + employee + "/salaries").session(hr))
                .andExpect(jsonPath("$[0].effectiveFrom").value("2026-12-01"))
                .andExpect(jsonPath("$[0].grossMonthly").value(731));    // the M1 basic; 169 is the complement
        mvc.perform(get("/api/users/" + employee + "/pay-elements").session(hr))
                .andExpect(jsonPath("$[?(@.elementCode=='COMPLEMENT' && @.startMonth=='2026-12-01')].amount").value(169.0));
        mvc.perform(get("/api/users/" + employee).session(hr))
                .andExpect(jsonPath("$.gradeCategoryId").value(2));   // MAITRISE, the category of M1

        mvc.perform(json(post("/api/users/" + employee + "/contracts").session(hr), """
                        {"contractType":"FIXED_TERM","startDate":"2027-01-01","gradeId":%d,"expatriate":false}""".formatted(grade(hr, "M1"))))
                .andExpect(status().is(422));
        mvc.perform(get("/api/users/" + employee + "/file").session(login(EMPLOYEE))).andExpect(status().isForbidden());
    }

    @Test
    void profileAndDependantsAreValidated() throws Exception {
        long employee = id("employee2@demo.hrpay.cd");
        MockHttpSession hr = login(HR);
        String base = "/api/users/" + employee;
        mvc.perform(json(put(base + "/profile").session(hr), "{\"maritalStatus\":\"MARRIED\",\"paymentMethod\":\"MOBILE_MONEY\"}"))
                .andExpect(status().is(422));
        mvc.perform(json(put(base + "/profile").session(hr), """
                        {"maritalStatus":"MARRIED","paymentMethod":"MOBILE_MONEY","mobileMoneyOperator":"M-Pesa",
                         "mobileMoneyNumber":"+243810000006"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mobileMoneyOperator").value("M-Pesa"));
        mvc.perform(json(post(base + "/dependants").session(hr), "{\"fullName\":\"Kid\",\"relationship\":\"CHILD\",\"inCharge\":true}"))
                .andExpect(status().is(422));
        mvc.perform(json(post(base + "/dependants").session(hr), "{\"fullName\":\"Partner\",\"relationship\":\"SPOUSE\",\"inCharge\":true}"))
                .andExpect(status().isCreated());
        mvc.perform(json(post(base + "/dependants").session(hr), "{\"fullName\":\"Other\",\"relationship\":\"SPOUSE\",\"inCharge\":true}"))
                .andExpect(status().isConflict());
        mvc.perform(json(put(base + "/profile").session(login(EMPLOYEE)), "{\"maritalStatus\":\"SINGLE\",\"paymentMethod\":\"CASH\"}"))
                .andExpect(status().isForbidden());
    }

    /** A new employee, so that other tests' payrolls do not change. */
    long freshEmployee() throws Exception {
        String created = mvc.perform(json(post("/api/users").session(login(ADMIN)), newUser("[\"EMPLOYEE\"]")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(created, "$.id").longValue();
    }

    @Test
    void dependantsCanBeEditedAndChildrenFollowTheAgeLimits() throws Exception {
        MockHttpSession hr = login(HR);
        String base = "/api/users/" + freshEmployee();
        java.time.LocalDate today = java.time.LocalDate.now();
        String adult = today.minusYears(20).toString();
        String body = mvc.perform(json(post(base + "/dependants").session(hr), """
                        {"fullName":"Grown","relationship":"CHILD","birthDate":"%s","inCharge":true}""".formatted(adult)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long child = JsonPath.<Number>read(body, "$.id").longValue();
        mvc.perform(get(base + "/file").session(hr))
                .andExpect(jsonPath("$.dependants[?(@.id==" + child + ")].counts").value(false))
                .andExpect(jsonPath("$.dependants[?(@.id==" + child + ")].age").value(20));
        mvc.perform(json(put(base + "/dependants/" + child).session(hr), """
                        {"fullName":"Grown Up","relationship":"CHILD","birthDate":"%s","inCharge":true,"student":true,
                         "note":"University"}""".formatted(adult)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + child + ")].fullName").value("Grown Up"))
                .andExpect(jsonPath("$[?(@.id==" + child + ")].student").value(true))
                .andExpect(jsonPath("$[?(@.id==" + child + ")].counts").value(true))
                .andExpect(jsonPath("$[?(@.id==" + child + ")].countsUntil").value(today.minusYears(20).plusYears(25).minusDays(1).toString()));
        mvc.perform(json(put(base + "/dependants/" + child).session(hr), """
                        {"fullName":"Grown Up","relationship":"CHILD","inCharge":true}"""))
                .andExpect(status().is(422));
    }

    @Test
    void theGradeChangesFromADateOrIsCorrected() throws Exception {
        MockHttpSession hr = login(HR);
        String base = "/api/users/" + freshEmployee();
        mvc.perform(json(post(base + "/contracts").session(hr), """
                        {"contractType":"PERMANENT","startDate":"%s","gradeId":%d,"expatriate":false}"""
                        .formatted(java.time.YearMonth.now().minusMonths(1).atDay(1), grade(hr, "E4A"))))
                .andExpect(status().isCreated());
        String first = mvc.perform(get(base + "/file").session(hr)).andReturn().getResponse().getContentAsString();
        long oldContract = JsonPath.<Number>read(first, "$.contracts[0].id").longValue();
        String from = java.time.YearMonth.now().plusMonths(2).atDay(1).toString();
        String created = mvc.perform(json(post(base + "/grade-change").session(hr), """
                        {"gradeId":%d,"from":"%s","keepNet":false}""".formatted(grade(hr, "M1"), from)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gradeCode").value("M1"))
                .andReturn().getResponse().getContentAsString();
        long newContract = JsonPath.<Number>read(created, "$.id").longValue();
        mvc.perform(get(base + "/salaries").session(hr))
                .andExpect(jsonPath("$[0].effectiveFrom").value(from))
                .andExpect(jsonPath("$[0].grossMonthly").value(731));
        mvc.perform(get(base).session(hr)).andExpect(jsonPath("$.gradeCategoryId").value(2));
        // The category follows the grade: it cannot be set apart.
        mvc.perform(json(put(base + "/employment").session(hr), "{\"gradeCategoryId\":1}")).andExpect(status().is(422));

        // A wrong grade on the latest contract is corrected, its basic following; the older one cannot be.
        mvc.perform(json(put(base + "/contracts/" + newContract + "/grade").session(hr), "{\"gradeId\":%d}".formatted(grade(hr, "M2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gradeCode").value("M2"));
        mvc.perform(get(base + "/salaries").session(hr))
                .andExpect(jsonPath("$[0].grossMonthly").value(org.hamcrest.Matchers.not(731)));
        mvc.perform(json(put(base + "/contracts/" + oldContract + "/grade").session(hr), "{\"gradeId\":%d}".formatted(grade(hr, "M2"))))
                .andExpect(status().is(422));
        mvc.perform(json(post(base + "/grade-change").session(hr), """
                        {"gradeId":%d,"from":"%s","keepNet":false}""".formatted(grade(hr, "M2"), from)))
                .andExpect(status().is(422));
        // Out of the way of the other tests (yearly leave grants).
        mvc.perform(post(base + "/disable").session(login(ADMIN)).with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());
    }
}
