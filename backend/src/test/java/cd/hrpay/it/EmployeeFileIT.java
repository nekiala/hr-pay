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
                .andExpect(jsonPath("$[0].grossMonthly").value(900));
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
}
