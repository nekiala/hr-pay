package cd.hrpay.it;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

class PayElementsIT extends IntegrationTest {

    @Autowired
    UserRepository users;

    long id(String email) {
        return users.findByEmailIgnoreCase(email).orElseThrow().getId();
    }

    long element(MockHttpSession s, String code) throws Exception {
        String all = mvc.perform(get("/api/pay-elements").session(s)).andReturn().getResponse().getContentAsString();
        return JsonPath.<List<Number>>read(all, "$[?(@.code=='" + code + "')].id").getFirst().longValue();
    }

    @Test
    void catalogueHasBuiltInElementsThatOnlyTakeANewName() throws Exception {
        MockHttpSession hr = login(HR);
        mvc.perform(get("/api/pay-elements").session(hr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("BASIC"))
                .andExpect(jsonPath("$[0].system").value(true));
        long ipr = element(hr, "IPR");
        mvc.perform(json(put("/api/pay-elements/" + ipr).session(hr), """
                        {"code":"IPR","name":"Impôt professionnel sur les rémunérations","kind":"EARNING",
                         "method":"FIXED","subjectToCnss":true,"taxableIpr":true,"prorated":false,
                         "defaultForAll":true,"displayOrder":120,"active":true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Impôt professionnel sur les rémunérations"))
                .andExpect(jsonPath("$.kind").value("DEDUCTION"))
                .andExpect(jsonPath("$.method").value("COMPUTED"));

        mvc.perform(json(post("/api/pay-elements").session(hr), """
                        {"code":"MEAL","name":"Meal allowance","kind":"EARNING","method":"PER_DAY_WORKED",
                         "defaultAmount":1.5,"defaultCurrency":"USD","subjectToCnss":false,"taxableIpr":false,
                         "prorated":false,"defaultForAll":false,"displayOrder":45,"active":true}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("MEAL"));
        mvc.perform(json(post("/api/pay-elements").session(hr), """
                        {"code":"MEAL","name":"Again","kind":"EARNING","method":"MANUAL","subjectToCnss":false,
                         "taxableIpr":false,"prorated":false,"defaultForAll":false,"displayOrder":45,"active":true}"""))
                .andExpect(status().isConflict());
        mvc.perform(json(post("/api/pay-elements").session(hr), """
                        {"code":"FUEL","name":"Fuel","kind":"EARNING","method":"FIXED","subjectToCnss":false,
                         "taxableIpr":false,"prorated":false,"defaultForAll":false,"displayOrder":45,"active":true}"""))
                .andExpect(status().is(422));
        mvc.perform(get("/api/pay-elements").session(login(EMPLOYEE))).andExpect(status().isForbidden());
    }

    @Test
    void standingItemsCannotOverlapAndCanBeEnded() throws Exception {
        MockHttpSession hr = login(HR);
        long employee = id("employee1@demo.hrpay.cd");
        long transport = element(hr, "TRANSPORT");
        String body = mvc.perform(json(post("/api/users/" + employee + "/pay-elements").session(hr), """
                        {"elementId":%d,"amount":3,"currency":"USD","excluded":false,"startMonth":"2027-01",
                         "note":"Lives far"}""".formatted(transport)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.startMonth").value("2027-01-01"))
                .andReturn().getResponse().getContentAsString();
        long assignment = JsonPath.<Number>read(body, "$.id").longValue();
        mvc.perform(json(post("/api/users/" + employee + "/pay-elements").session(hr), """
                        {"elementId":%d,"excluded":true,"startMonth":"2027-06"}""".formatted(transport)))
                .andExpect(status().isConflict());
        mvc.perform(json(put("/api/users/" + employee + "/pay-elements/" + assignment + "/end").session(hr),
                        """
                        {"lastMonth":"2027-05"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].endMonth").value("2027-05-01"));
        mvc.perform(json(post("/api/users/" + employee + "/pay-elements").session(hr), """
                        {"elementId":%d,"excluded":true,"startMonth":"2027-06"}""".formatted(transport)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.excluded").value(true));

        mvc.perform(json(post("/api/users/" + employee + "/pay-elements").session(hr), """
                        {"elementId":%d,"amount":50,"currency":"USD","excluded":false,"startMonth":"2027-01"}"""
                        .formatted(element(hr, "PERF_BONUS"))))
                .andExpect(status().is(422));
        mvc.perform(json(post("/api/users/" + id("hr@demo.hrpay.cd") + "/pay-elements").session(hr), """
                        {"elementId":%d,"amount":9,"currency":"USD","excluded":false,"startMonth":"2027-01"}"""
                        .formatted(transport)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aNegotiatedComplementIsSalaryGivenPerPerson() throws Exception {
        MockHttpSession hr = login(HR);
        mvc.perform(get("/api/pay-elements").session(hr))
                .andExpect(jsonPath("$[?(@.code=='COMPLEMENT')].method").value("FIXED"))
                .andExpect(jsonPath("$[?(@.code=='COMPLEMENT')].subjectToCnss").value(true))
                .andExpect(jsonPath("$[?(@.code=='COMPLEMENT')].taxableIpr").value(true))
                .andExpect(jsonPath("$[?(@.code=='COMPLEMENT')].defaultForAll").value(false));
        mvc.perform(json(post("/api/users/" + id("employee3@demo.hrpay.cd") + "/pay-elements").session(hr), """
                        {"elementId":%d,"amount":125.97,"currency":"USD","excluded":false,"startMonth":"2028-01",
                         "note":"Agreed at hiring"}""".formatted(element(hr, "COMPLEMENT"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(125.97));
    }

    @Test
    void oneOffEntriesAreOnePerElementAndMonth() throws Exception {
        MockHttpSession hr = login(HR);
        long employee = id("employee2@demo.hrpay.cd");
        long bonus = element(hr, "PERF_BONUS");
        String body = mvc.perform(json(post("/api/pay-entries").session(hr), """
                        {"userId":%d,"elementId":%d,"month":"2027-03","amount":120,"currency":"USD","note":"Q1"}"""
                        .formatted(employee, bonus)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.elementCode").value("PERF_BONUS"))
                .andReturn().getResponse().getContentAsString();
        long entry = JsonPath.<Number>read(body, "$.id").longValue();
        mvc.perform(json(post("/api/pay-entries").session(hr), """
                        {"userId":%d,"elementId":%d,"month":"2027-03","amount":10,"currency":"USD"}"""
                        .formatted(employee, bonus)))
                .andExpect(status().isConflict());
        mvc.perform(json(post("/api/pay-entries").session(hr), """
                        {"userId":%d,"elementId":%d,"month":"2027-03","amount":10,"currency":"USD"}"""
                        .formatted(employee, element(hr, "IPR"))))
                .andExpect(status().is(422));
        mvc.perform(json(put("/api/pay-entries/" + entry).session(hr), """
                        {"amount":150,"currency":"USD","note":"Q1 revised"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(150));
        mvc.perform(get("/api/pay-entries").param("month", "2027-03").session(login(FINANCE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(delete("/api/pay-entries/" + entry).session(hr).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/pay-entries").param("month", "2027-03").session(hr))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
