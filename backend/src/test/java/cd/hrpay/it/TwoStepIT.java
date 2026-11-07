package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.security.Totp;
import cd.hrpay.security.TwoStep;
import cd.hrpay.tenancy.Tenants;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

class TwoStepIT extends IntegrationTest {

    @Autowired
    TwoStep twoStep;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Tenants tenants;

    @BeforeEach
    void enforce() {
        twoStep.setEnforced(true);
    }

    @AfterEach
    void relax() {
        twoStep.setEnforced(false);
    }

    /** A new person with the given role and a known password. */
    private String person(String role) throws Exception {
        MockHttpSession admin = adminSignedIn();
        String body = mvc.perform(json(post("/api/users").session(admin), newUser("[\"" + role + "\"]")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(body, "$.id");
        mvc.perform(json(post("/api/users/" + id + "/password").session(admin), "{\"password\":\"%s\"}".formatted(PASSWORD)))
                .andExpect(status().isNoContent());
        return JsonPath.read(body, "$.email");
    }

    private org.springframework.test.web.servlet.ResultActions password(MockHttpSession s, String email) throws Exception {
        return mvc.perform(json(post("/api/auth/login").session(s),
                "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)));
    }

    private static String now(String secret) {
        return Totp.code(secret, Totp.step(System.currentTimeMillis() / 1000));
    }

    /** Time passes, so that the next authenticator code is a new one. */
    private void later(String email) {
        tenants.callAs(1L, () -> jdbc.sql("update user_two_step set last_step = last_step - 5 where user_id = (select id from app_user where email = ?)")
                .param(email).update());
    }

    /** Sets up the authenticator at a first sign-in and returns its secret. */
    private String enrol(String email, MockHttpSession s) throws Exception {
        password(s, email).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.setup").value(true));
        // Nothing is open before the second step.
        mvc.perform(get("/api/auth/me").session(s)).andExpect(status().isUnauthorized());
        String setup = mvc.perform(json(post("/api/auth/two-step/setup").session(s), "{\"method\":\"TOTP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uri").value(org.hamcrest.Matchers.startsWith("otpauth://totp/")))
                .andReturn().getResponse().getContentAsString();
        String secret = JsonPath.read(setup, "$.secret");
        mvc.perform(json(post("/api/auth/two-step/confirm").session(s), "{\"method\":\"TOTP\",\"code\":\"000000x\"}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(post("/api/auth/two-step/confirm").session(s), "{\"method\":\"TOTP\",\"code\":\"%s\"}".formatted(now(secret))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10))
                .andExpect(jsonPath("$.me.email").value(email));
        mvc.perform(get("/api/auth/me").session(s)).andExpect(status().isOk());
        return secret;
    }

    @Test
    void aRoleThatRequiresItSetsItUpAtTheFirstSignInThenAsksEveryTime() throws Exception {
        String email = person("HR");
        String secret = enrol(email, new MockHttpSession());
        later(email);

        MockHttpSession s = new MockHttpSession();
        password(s, email).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.method").value("TOTP"))
                .andExpect(jsonPath("$.setup").value(false));
        mvc.perform(json(post("/api/auth/two-step").session(s), "{\"code\":\"123456\"}")).andExpect(status().isUnauthorized());
        String code = now(secret);
        mvc.perform(json(post("/api/auth/two-step").session(s), "{\"code\":\"%s\"}".formatted(code)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));

        // The same code does not work twice.
        MockHttpSession again = new MockHttpSession();
        password(again, email).andExpect(status().isAccepted());
        mvc.perform(json(post("/api/auth/two-step").session(again), "{\"code\":\"%s\"}".formatted(code)))
                .andExpect(status().isUnauthorized());

        // A role that requires it cannot turn it off.
        mvc.perform(json(post("/api/account/two-step/off").session(s), "{\"password\":\"%s\"}".formatted(PASSWORD)))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(get("/api/account/two-step").session(s))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.required").value(true))
                .andExpect(jsonPath("$.recoveryCodesLeft").value(10));
    }

    @Test
    void fiveWrongCodesEndTheAttempt() throws Exception {
        String email = person("FINANCE");
        enrol(email, new MockHttpSession());
        MockHttpSession s = new MockHttpSession();
        password(s, email).andExpect(status().isAccepted());
        for (int i = 0; i < 5; i++) {
            mvc.perform(json(post("/api/auth/two-step").session(s), "{\"code\":\"000000\"}")).andExpect(status().isUnauthorized());
        }
        mvc.perform(json(post("/api/auth/two-step").session(s), "{\"code\":\"000000\"}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void aRecoveryCodeWorksOnce() throws Exception {
        String email = person("HR");
        MockHttpSession first = new MockHttpSession();
        String secret = enrol(email, first);
        String codes = mvc.perform(json(post("/api/account/two-step/recovery-codes").session(first),
                        "{\"password\":\"%s\"}".formatted(PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> recovery = JsonPath.read(codes, "$.recoveryCodes");
        assertThat(secret).isNotBlank();

        MockHttpSession s = new MockHttpSession();
        password(s, email).andExpect(status().isAccepted());
        mvc.perform(json(post("/api/auth/two-step").session(s), "{\"code\":\"%s\"}".formatted(recovery.get(0).toLowerCase())))
                .andExpect(status().isOk());
        MockHttpSession t = new MockHttpSession();
        password(t, email).andExpect(status().isAccepted());
        mvc.perform(json(post("/api/auth/two-step").session(t), "{\"code\":\"%s\"}".formatted(recovery.get(0))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/account/two-step").session(s)).andExpect(jsonPath("$.recoveryCodesLeft").value(9));
    }

    @Test
    void anEmployeeMayTurnItOnAndOff() throws Exception {
        String email = person("EMPLOYEE");
        MockHttpSession s = new MockHttpSession();
        password(s, email).andExpect(status().isOk());
        String setup = mvc.perform(json(post("/api/auth/two-step/setup").session(s), "{\"method\":\"TOTP\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String secret = JsonPath.read(setup, "$.secret");
        mvc.perform(json(post("/api/auth/two-step/confirm").session(s), "{\"method\":\"TOTP\",\"code\":\"%s\"}".formatted(now(secret))))
                .andExpect(status().isOk());
        password(new MockHttpSession(), email).andExpect(status().isAccepted());

        mvc.perform(json(post("/api/account/two-step/off").session(s), "{\"password\":\"wrong-one-1\"}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(post("/api/account/two-step/off").session(s), "{\"password\":\"%s\"}".formatted(PASSWORD)))
                .andExpect(status().isNoContent());
        password(new MockHttpSession(), email).andExpect(status().isOk());
    }

    @Test
    void anAdministratorResetsALostPhone() throws Exception {
        String email = person("HR");
        MockHttpSession hr = new MockHttpSession();
        enrol(email, hr);
        Number id = tenants.callAs(1L, () -> jdbc.sql("select id from app_user where email = ?").param(email).query(Long.class).single());
        mvc.perform(post("/api/users/" + id + "/two-step/reset").session(adminSignedIn()).with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNoContent());
        // The reset signs the person out, and the next sign-in sets the second step up again.
        mvc.perform(get("/api/auth/me").session(hr)).andExpect(status().isUnauthorized());
        password(new MockHttpSession(), email).andExpect(status().isAccepted()).andExpect(jsonPath("$.setup").value(true));
    }

    @Test
    void aSessionEndedFromAnotherBrowserIsSignedOut() throws Exception {
        twoStep.setEnforced(false);
        String email = person("EMPLOYEE");
        MockHttpSession a = new MockHttpSession();
        MockHttpSession b = new MockHttpSession();
        password(a, email).andExpect(status().isOk());
        password(b, email).andExpect(status().isOk());
        String list = mvc.perform(get("/api/account/sessions").session(a))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        List<String> others = JsonPath.read(list, "$[?(@.current == false)].id");
        assertThat(others).hasSize(1).allMatch(x -> x.length() == 24);
        mvc.perform(delete("/api/account/sessions/" + others.get(0)).session(a).with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(b)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(a)).andExpect(status().isOk());
    }

    @Test
    void theRoleSettingIsEditable() throws Exception {
        MockHttpSession admin = adminSignedIn();
        String roles = mvc.perform(get("/api/roles").session(admin)).andReturn().getResponse().getContentAsString();
        List<Boolean> hr = JsonPath.read(roles, "$[?(@.code == 'HR')].twoStepRequired");
        List<Boolean> employee = JsonPath.read(roles, "$[?(@.code == 'EMPLOYEE')].twoStepRequired");
        assertThat(hr).containsExactly(true);
        assertThat(employee).containsExactly(false);
        Number id = ((List<Number>) JsonPath.read(roles, "$[?(@.code == 'MANAGER')].id")).get(0);
        List<String> permissions = ((List<List<String>>) JsonPath.read(roles, "$[?(@.code == 'MANAGER')].permissions")).get(0);
        String name = ((List<String>) JsonPath.read(roles, "$[?(@.code == 'MANAGER')].name")).get(0);
        String perms = "[" + String.join(",", permissions.stream().map(p -> "\"" + p + "\"").toList()) + "]";
        mvc.perform(json(put("/api/roles/" + id).session(admin),
                        "{\"name\":\"%s\",\"permissions\":%s,\"twoStepRequired\":true}".formatted(name, perms)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.twoStepRequired").value(true));
        mvc.perform(json(put("/api/roles/" + id).session(admin),
                        "{\"name\":\"%s\",\"permissions\":%s,\"twoStepRequired\":false}".formatted(name, perms)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.twoStepRequired").value(false));
    }

    /** The demo administrator, with the requirement off for this one sign-in. */
    private MockHttpSession adminSignedIn() throws Exception {
        boolean was = twoStep.enforced();
        twoStep.setEnforced(false);
        try {
            return login(ADMIN);
        } finally {
            twoStep.setEnforced(was);
        }
    }
}
