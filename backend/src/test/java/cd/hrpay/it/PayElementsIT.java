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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

class PayElementsIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

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
        long user = JsonPath.<Number>read(mvc.perform(json(post("/api/users").session(login(ADMIN)), newUser("[\"EMPLOYEE\"]")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id").longValue();
        mvc.perform(json(post("/api/users/" + user + "/pay-elements").session(hr), """
                        {"elementId":%d,"amount":125.97,"currency":"USD","excluded":false,"startMonth":"2028-01",
                         "note":"Agreed at hiring"}""".formatted(element(hr, "COMPLEMENT"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(125.97));
    }

    @Test
    void theAgreedPayKeepsTheGradesBasicAndTheRestIsTheComplement() throws Exception {
        MockHttpSession hr = login(HR);
        jdbc.sql("insert into exchange_rate (currency, rate_date, cdf_per_unit, source) values ('USD', '2026-09-30', 2265, 'Test') on conflict do nothing").update();
        String grades = mvc.perform(get("/api/grades").session(hr)).andReturn().getResponse().getContentAsString();
        long e3a = JsonPath.<List<Number>>read(grades, "$[?(@.code=='E3A')].id").getFirst().longValue();
        long user = JsonPath.<Number>read(mvc.perform(json(post("/api/users").session(login(ADMIN)), newUser("[\"EMPLOYEE\"]")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id").longValue();

        // Below the grade's basic: refused.
        mvc.perform(json(post("/api/users/" + user + "/contracts").session(hr), """
                        {"contractType":"PERMANENT","startDate":"2026-09-01","gradeId":%d,"expatriate":false,
                         "negotiatedBasic":250}""".formatted(e3a)))
                .andExpect(status().is(422));
        // A net that the grade's basic alone already exceeds: refused, choose a lower grade.
        mvc.perform(json(post("/api/users/" + user + "/contracts").session(hr), """
                        {"contractType":"PERMANENT","startDate":"2026-09-01","gradeId":%d,"expatriate":false,
                         "agreedNet":100}""".formatted(e3a)))
                .andExpect(status().is(422));
        // An agreed net: the E3A basic, and the complement that gives exactly that net in a normal month.
        mvc.perform(json(post("/api/users/" + user + "/contracts").session(hr), """
                        {"contractType":"PERMANENT","startDate":"2026-09-01","gradeId":%d,"expatriate":false,
                         "agreedNet":600}""".formatted(e3a)))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/users/" + user + "/salaries").session(hr))
                .andExpect(jsonPath("$[0].grossMonthly").value(307.0));
        String items = mvc.perform(get("/api/users/" + user + "/pay-elements").session(hr))
                .andReturn().getResponse().getContentAsString();
        java.math.BigDecimal complement = new java.math.BigDecimal(JsonPath.<List<Object>>read(items,
                "$[?(@.elementCode=='COMPLEMENT')].amount").getFirst().toString());
        assertThat(complement).isPositive();
        String plan = mvc.perform(json(post("/api/users/" + user + "/salaries/net/preview").session(hr), """
                        {"effectiveFrom":"2026-09-01","net":600}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gradeBasic").value(307.0))
                .andReturn().getResponse().getContentAsString();
        assertThat(new java.math.BigDecimal(JsonPath.<Object>read(plan, "$.net").toString()))
                .isCloseTo(new java.math.BigDecimal("600"), org.assertj.core.data.Offset.offset(new java.math.BigDecimal("0.01")));
        assertThat(new java.math.BigDecimal(JsonPath.<Object>read(plan, "$.complement").toString())).isEqualByComparingTo(complement);

        // A later agreed salary above the basic: the basic stays, the complement is replaced from that month.
        mvc.perform(json(post("/api/users/" + user + "/salaries").session(hr), """
                        {"effectiveFrom":"2026-11-01","grossMonthly":500,"currency":"USD"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.grossMonthly").value(307.0));
        mvc.perform(get("/api/users/" + user + "/pay-elements").session(hr))
                .andExpect(jsonPath("$[?(@.elementCode=='COMPLEMENT' && @.startMonth=='2026-11-01')].amount").value(193.0))
                .andExpect(jsonPath("$[?(@.elementCode=='COMPLEMENT' && @.startMonth=='2026-09-01')].endMonth").value("2026-10-01"));
        mvc.perform(json(post("/api/users/" + user + "/salaries").session(hr), """
                        {"effectiveFrom":"2026-12-01","grossMonthly":300,"currency":"USD"}"""))
                .andExpect(status().is(422));
        // Other tests count the active employees with a grade category (yearly leave grants): take this one out.
        jdbc.sql("update app_user set status = 'DISABLED' where id = ?").param(user).update();
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
