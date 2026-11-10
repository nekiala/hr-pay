package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.security.GoogleDomains;
import cd.hrpay.tenancy.Tenants;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;

/** Google sign-in is accepted only from the company's own Google Workspace domains, and can replace the password. */
@TestPropertySource(properties = {"app.oidc.google.client-id=test-client.apps.googleusercontent.com",
        "app.oidc.google.client-secret=test-secret"})
class GoogleSignInIT extends IntegrationTest {

    @Autowired
    GoogleDomains domains;

    @Autowired
    Tenants tenants;

    private int passwordLogin(String email) throws Exception {
        return mvc.perform(post("/api/auth/login").session(new MockHttpSession()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void googleAsksWhichAccountToUse() throws Exception {
        mvc.perform(get("/api/auth/providers")).andExpect(jsonPath("$.google").value(true));
        String location = mvc.perform(get("/oauth2/authorization/google")).andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(location).startsWith("https://accounts.google.com/").contains("prompt=select_account");
    }

    @Test
    void onlyWorkspaceAccountsOfTheCompanyDomainsAreAccepted() throws Exception {
        MockHttpSession admin = login(ADMIN);
        mvc.perform(json(put("/api/company/sign-in/google").session(admin), "{\"domains\":[\"gmail.com\"]}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(put("/api/company/sign-in/google").session(admin), "{\"domains\":[\"not a domain\"]}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(put("/api/company/sign-in/google").session(admin), "{\"domains\":[],\"googleOnly\":true}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(put("/api/company/sign-in/google").session(admin),
                        "{\"domains\":[\"@Demo.HRPay.cd\",\"ntoprog.org\",\"ntoprog.org\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.googleDomains[0]").value("demo.hrpay.cd"))
                .andExpect(jsonPath("$.googleDomains[1]").value("ntoprog.org"))
                .andExpect(jsonPath("$.googleDomains.length()").value(2))
                .andExpect(jsonPath("$.googleOnly").value(false));
        mvc.perform(get("/api/company/google-domains").session(login(HR))).andExpect(jsonPath("$.domains[0]").value("demo.hrpay.cd"));
        mvc.perform(get("/api/company/google-domains").session(login(EMPLOYEE))).andExpect(status().isForbidden());

        tenants.callAs(1L, () -> {
            assertThat(domains.refusal(EMPLOYEE, null)).isEqualTo("personal_account");
            assertThat(domains.refusal(EMPLOYEE, "demo.hrpay.cd")).isNull();
            assertThat(domains.refusal(EMPLOYEE, "other.com")).isEqualTo("domain_not_allowed");
            assertThat(domains.refusal("someone@other.com", "demo.hrpay.cd")).isEqualTo("domain_not_allowed");
            return null;
        });

        // Another company cannot take a domain already used.
        String body = """
                {"name":"Other Co","countryCode":"CD","planCode":"UP_TO_50","modules":["ATTENDANCE"],
                 "admin":{"firstName":"Olga","lastName":"Other","email":"olga%d@other.cd","phone":"+2438160%05d"}}"""
                .formatted(System.nanoTime() % 100000, (int) (System.nanoTime() % 100000));
        String created = mvc.perform(json(post("/api/platform/companies").session(admin), body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long other = ((Number) JsonPath.read(created, "$.company.id")).longValue();
        assertThatThrownBy(() -> tenants.callAs(other, () -> {
            domains.replace(List.of("demo.hrpay.cd"), false, "test");
            return null;
        })).isInstanceOf(BusinessRuleException.class).hasMessageContaining("already used");

        mvc.perform(json(put("/api/company/sign-in/google").session(admin), "{\"domains\":[]}")).andExpect(status().isOk());
    }

    @Test
    void googleOnlyRefusesThePasswordOfPeopleOnTheDomains() throws Exception {
        MockHttpSession admin = login(ADMIN);
        mvc.perform(json(put("/api/company/sign-in/google").session(admin), "{\"domains\":[\"demo.hrpay.cd\"],\"googleOnly\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.googleOnly").value(true));
        try {
            assertThat(passwordLogin(EMPLOYEE)).isEqualTo(401);
        } finally {
            tenants.callAs(1L, () -> {
                domains.replace(List.of(), false, "test");
                return null;
            });
        }
        assertThat(passwordLogin(EMPLOYEE)).isEqualTo(200);
    }
}
