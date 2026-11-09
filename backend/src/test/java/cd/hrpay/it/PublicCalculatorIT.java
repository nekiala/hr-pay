package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.tenancy.Tenants;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** The website's salary calculator: no sign-in, the pack's official rates, both ways, and the leads. */
class PublicCalculatorIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Tenants tenants;

    @Override
    protected MockHttpSession login(String email) throws Exception {
        jdbc.sql("delete from login_failure where email = ?").param(email).update();
        return super.login(email);
    }

    private static BigDecimal number(String json, String path) {
        return new BigDecimal(JsonPath.read(json, path).toString());
    }

    @Test
    void aDrcGrossGivesTheNetWithCnssAndIprAndTheEmployerCost() throws Exception {
        String body = mvc.perform(get("/api/public/calculator").param("country", "CD").param("amount", "1000000")
                        .param("currency", "CDF").param("housing", "300000").param("children", "2").param("employees", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gross").value(1300000.0))
                .andExpect(jsonPath("$.lines[?(@.code == 'CNSS_EMPLOYEE')].amount").value(50000.0))
                .andExpect(jsonPath("$.lines[?(@.code == 'INPP')].rate").value(3.0))
                .andExpect(jsonPath("$.minimum.below").value(false))
                .andReturn().getResponse().getContentAsString();
        BigDecimal gross = number(body, "$.gross");
        BigDecimal net = number(body, "$.net");
        BigDecimal deductions = number(body, "$.employeeDeductions");
        assertThat(net).isEqualByComparingTo(gross.subtract(deductions));
        assertThat(number(body, "$.employerCost")).isEqualByComparingTo(gross.add(number(body, "$.employerCharges")));
        // The 2026 IRPP scale, from the pack, not a company's own rates.
        assertThat(JsonPath.<java.util.List<String>>read(body, "$.sources[*].code")).contains("CNSS_EMPLOYEE", "SMIG_DAILY");

        // Net to gross finds back the same basic salary.
        mvc.perform(get("/api/public/calculator").param("country", "CD").param("mode", "NET").param("amount", net.toPlainString())
                        .param("currency", "CDF").param("housing", "300000").param("children", "2").param("employees", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.net").value(net.doubleValue()))
                .andExpect(jsonPath("$.basic").value(org.hamcrest.Matchers.closeTo(1000000.0, 0.02)));
    }

    @Test
    void aBasicBelowTheSmigIsFlaggedAndDollarsUseTheBccRate() throws Exception {
        usdRate("2026-01-02", 2800);
        mvc.perform(get("/api/public/calculator").param("country", "CD").param("amount", "100").param("currency", "USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.usdRate").isNumber())
                .andExpect(jsonPath("$.minimum.below").value(true));
        mvc.perform(get("/api/public/calculator").param("country", "CD").param("amount", "-5").param("currency", "CDF"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(get("/api/public/calculator").param("country", "CD").param("amount", "5").param("currency", "EUR"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void aCongoSalaryPaysItsCnssUnderTheCeilingAndTheTus() throws Exception {
        mvc.perform(get("/api/public/calculator").param("country", "CG").param("amount", "500000").param("currency", "XAF")
                        .param("maritalStatus", "MARRIED").param("children", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[?(@.code == 'TUS')].amount").value(37500.0))
                .andExpect(jsonPath("$.lines[?(@.code == 'CNSS_EMPLOYEE')].amount").value(20000.0))
                .andExpect(jsonPath("$.localCurrency").value("XAF"));
        mvc.perform(get("/api/public/calculator/countries")).andExpect(jsonPath("$.countries.length()").value(2));
    }

    @Test
    void anySiteMayCallItWithoutCookies() throws Exception {
        mvc.perform(options("/api/public/calculator").header("Origin", "https://www.hrpay.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://www.hrpay.example"));
    }

    @Test
    void aVisitorGetsTheResultByEmailAndTheProviderSeesTheLead() throws Exception {
        String email = "visitor" + System.nanoTime() % 1_000_000 + "@example.cd";
        mvc.perform(post("/api/public/calculator/leads").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"%s","companyName":"Kin Logistics","employees":"51-300","language":"fr",
                         "calculation":{"country":"CD","mode":"GROSS","amount":2000000,"currency":"CDF","children":1}}"""
                        .formatted(email)))
                .andExpect(status().isNoContent());
        String outbox = tenants.callAsPlatform(() -> jdbc.sql("select body from outbox_message where recipient = ?")
                .param(email).query(String.class).single());
        assertThat(outbox).contains("Net à payer", "CNSS (part salariale)");

        MockHttpSession provider = login(ADMIN);
        String leads = mvc.perform(get("/api/platform/calculator-leads").session(provider)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int id = JsonPath.<java.util.List<Integer>>read(leads, "$[?(@.email == '" + email + "')].id").getFirst();
        mvc.perform(post("/api/platform/calculator-leads/" + id + "/contacted").session(provider)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/platform/calculator-leads").session(provider))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].status").value("CONTACTED"));
        mvc.perform(get("/api/platform/calculator-leads").session(login(HR))).andExpect(status().isForbidden());

        // Robots fill the hidden field: nothing is kept.
        mvc.perform(post("/api/public/calculator/leads").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"robot@example.cd","website":"x",
                         "calculation":{"country":"CD","mode":"GROSS","amount":1,"currency":"CDF"}}"""))
                .andExpect(status().isNoContent());
        assertThat(tenants.callAsPlatform(() -> jdbc.sql("select count(*) from calculator_lead where email = 'robot@example.cd'")
                .query(Integer.class).single())).isZero();
    }
}
