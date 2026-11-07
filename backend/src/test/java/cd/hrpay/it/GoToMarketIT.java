package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.tenancy.Tenants;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** Phase 17: the set-up guide, support requests, terms, the status page, trial requests and the dashboards. */
class GoToMarketIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Tenants tenants;

    /** AuthIT locks one employee on purpose; the sign-ins here start clean. */
    @Override
    protected MockHttpSession login(String email) throws Exception {
        jdbc.sql("delete from login_failure where email = ?").param(email).update();
        return super.login(email);
    }

    @Test
    void theSetupGuideReadsTheDataAndKeepsTheReviewsMarkedDone() throws Exception {
        MockHttpSession hr = login(HR);
        mvc.perform(get("/api/setup").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(10))
                .andExpect(jsonPath("$.steps[?(@.key == 'sites')].done").value(true))
                .andExpect(jsonPath("$.steps[?(@.key == 'grades')].done").value(false));
        mvc.perform(post("/api/setup/steps/grades").session(hr).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/setup").session(hr))
                .andExpect(jsonPath("$.steps[?(@.key == 'grades')].done").value(true))
                .andExpect(jsonPath("$.steps[?(@.key == 'grades')].markedBy").value(HR));
        mvc.perform(delete("/api/setup/steps/grades").session(hr).with(csrf())).andExpect(status().isNoContent());
        // A step read from the data cannot be ticked by hand.
        mvc.perform(post("/api/setup/steps/sites").session(hr).with(csrf())).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/setup").session(login(EMPLOYEE))).andExpect(status().isForbidden());
    }

    @Test
    void aSupportRequestGoesToTheProviderAndTheAnswerComesBack() throws Exception {
        MockHttpSession employee = login(EMPLOYEE);
        String created = mvc.perform(json(post("/api/tickets").session(employee), """
                        {"subject":"Payslip PDF is blank","category":"PROBLEM","body":"Since this morning.","page":"/my/payslips","appVersion":"0.17.0"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(org.hamcrest.Matchers.startsWith("S-")))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();

        // Another employee does not see it; the provider does.
        mvc.perform(get("/api/tickets/" + id).session(login("employee2@demo.hrpay.cd"))).andExpect(status().isNotFound());
        MockHttpSession provider = login(ADMIN);
        mvc.perform(get("/api/platform/tickets").session(provider))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].status").value("OPEN"));
        mvc.perform(json(post("/api/platform/tickets/" + id + "/messages").session(provider), "{\"body\":\"Fixed, try again.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket.status").value("ANSWERED"))
                .andExpect(jsonPath("$.messages.length()").value(2));
        assertThat(jdbc.sql("select count(*) from notification where kind = 'support.answered'").query(Integer.class).single()).isPositive();

        mvc.perform(json(post("/api/tickets/" + id + "/messages").session(employee), "{\"body\":\"Works now, thanks.\"}"))
                .andExpect(jsonPath("$.ticket.status").value("OPEN"));
        mvc.perform(post("/api/tickets/" + id + "/close").session(employee).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/tickets").session(employee)).andExpect(jsonPath("$[?(@.id == " + id + ")].status").value("CLOSED"));
        mvc.perform(get("/api/platform/tickets").session(employee)).andExpect(status().isForbidden());
    }

    @Test
    void aNewCompanysAdministratorAcceptsTheTermsAgainAfterEachNewVersion() throws Exception {
        mvc.perform(get("/api/legal/documents/TERMS").param("language", "en")).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Terms of service"))
                .andExpect(jsonPath("$.mustAccept").value(true));
        mvc.perform(get("/api/legal/documents").param("language", "fr")).andExpect(jsonPath("$.length()").value(4));
        // The provider's own company is never asked.
        MockHttpSession provider = login(ADMIN);
        mvc.perform(get("/api/legal/pending").session(provider)).andExpect(jsonPath("$.length()").value(0));

        long n = System.nanoTime() % 1_000_000;
        String email = "terms" + n + "@acme.cd";
        String response = mvc.perform(json(post("/api/platform/companies").session(provider), """
                {"name":"Terms %d","countryCode":"CD","planCode":"UP_TO_50","modules":["ATTENDANCE"],
                 "admin":{"firstName":"Ada","lastName":"Boss","email":"%s","phone":"+2439972%05d"}}"""
                .formatted(n, email, n % 100000))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        MockHttpSession boss = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(boss).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, JsonPath.read(response, "$.temporaryPassword"))))
                .andExpect(status().isOk());
        String pending = mvc.perform(get("/api/legal/pending").session(boss)).andExpect(jsonPath("$.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(json(post("/api/legal/accept").session(boss), "{\"documents\":" + pending + "}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/legal/pending").session(boss)).andExpect(jsonPath("$.length()").value(0));

        mvc.perform(json(post("/api/platform/legal").session(provider), """
                {"kind":"TERMS","mustAccept":true,"fr":{"title":"Conditions v2","body":"Texte."},"en":{"title":"Terms v2","body":"Text."}}"""))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(get("/api/legal/pending").session(boss)).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("TERMS")).andExpect(jsonPath("$[0].version").value(2));
        // Accepting an older version is refused.
        mvc.perform(json(post("/api/legal/accept").session(boss), "{\"documents\":[{\"kind\":\"TERMS\",\"version\":1}]}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void theStatusPageShowsIncidentsWithoutSigningIn() throws Exception {
        mvc.perform(get("/api/version")).andExpect(status().isOk()).andExpect(jsonPath("$.demo").value(false));
        mvc.perform(get("/api/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.checks[?(@.key == 'database')].ok").value(true));
        MockHttpSession provider = login(ADMIN);
        String posted = mvc.perform(json(post("/api/platform/incidents").session(provider),
                        "{\"title\":\"Slow payslips\",\"impact\":\"MAJOR\",\"body\":\"We are looking into it.\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.overall").value("DEGRADED"))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(posted, "$.active[0].id")).longValue();
        mvc.perform(json(post("/api/platform/incidents/" + id + "/updates").session(provider),
                        "{\"body\":\"Fixed.\",\"resolve\":true}"))
                .andExpect(jsonPath("$.overall").value("OPERATIONAL"))
                .andExpect(jsonPath("$.recent[?(@.id == " + id + ")].updates.length()").value(2));
        mvc.perform(json(post("/api/platform/incidents").session(login(HR)),
                "{\"title\":\"x\",\"impact\":\"MINOR\",\"body\":\"x\"}")).andExpect(status().isForbidden());
    }

    @Test
    void aTrialRequestBecomesACompanyOnlyWhenTheProviderApproves() throws Exception {
        long n = System.nanoTime() % 1_000_000;
        String email = "trial" + n + "@prospect.cd";
        String ask = """
                {"companyName":"Prospect %d","countryCode":"CD","employees":"51-300","firstName":"Grace","lastName":"Mbuyi",
                 "email":"%s","phone":"+243990000000","message":"Pilot in January","language":"fr"%s}""";
        // A robot fills the hidden field: nothing is kept.
        mvc.perform(post("/api/trials").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(ask.formatted(n, "bot" + email, ",\"website\":\"spam\""))).andExpect(status().isNoContent());
        mvc.perform(post("/api/trials").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(ask.formatted(n, email, ""))).andExpect(status().isNoContent());
        assertThat(tenants.callAsPlatform(() -> jdbc.sql("select count(*) from trial_request where email like ?")
                .param("%" + email).query(Integer.class).single())).isEqualTo(1);
        assertThat(tenants.callAsPlatform(() -> jdbc.sql("select count(*) from company where name = ?")
                .param("Prospect " + n).query(Integer.class).single())).isZero();

        MockHttpSession provider = login(ADMIN);
        String list = mvc.perform(get("/api/platform/trials").session(provider)).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.<java.util.List<Object>>read(list, "$[?(@.email == '" + email + "')].id").getFirst()).longValue();
        String approved = mvc.perform(json(post("/api/platform/trials/" + id + "/approve").session(provider),
                        "{\"planCode\":\"UP_TO_300\",\"modules\":[\"ATTENDANCE\",\"PAYROLL\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andReturn().getResponse().getContentAsString();
        long company = ((Number) JsonPath.read(approved, "$.companyId")).longValue();
        String welcome = tenants.callAs(company, () -> jdbc.sql("select body from outbox_message where recipient = ? order by id desc limit 1")
                .param(email).query(String.class).single());
        assertThat(welcome).contains("/reset-password?token=");
        mvc.perform(json(post("/api/platform/trials/" + id + "/approve").session(provider),
                "{\"planCode\":\"UP_TO_300\",\"modules\":[\"ATTENDANCE\"]}")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void theDashboardsShowTheMonthsForManagersOnly() throws Exception {
        mvc.perform(get("/api/dashboards").param("months", "6").session(login(HR))).andExpect(status().isOk())
                .andExpect(jsonPath("$.months.length()").value(6))
                .andExpect(jsonPath("$.months[5].headcount").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.directions.length()").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.currency").value("USD"));
        mvc.perform(get("/api/dashboards").session(login(EMPLOYEE))).andExpect(status().isForbidden());
    }
}
