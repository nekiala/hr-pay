package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.security.ResetPasswordCommand;
import cd.hrpay.tenancy.Tenants;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** "Forgot your password?", the provider's reset of a locked-out administrator, and the on-premise server command. */
class PasswordRecoveryIT extends IntegrationTest {

    private static final String NEW_PASSWORD = "Fresh-start-2026";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Tenants tenants;

    @Value("${spring.datasource.url}")
    String dbUrl;

    @Value("${spring.datasource.username}")
    String dbUser;

    @Value("${spring.datasource.password}")
    String dbPassword;

    private String newEmployee() throws Exception {
        String created = mvc.perform(json(post("/api/users").session(login(ADMIN)),
                        "{\"initialPassword\":\"" + PASSWORD + "\"," + newUser(null).substring(1)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.email");
    }

    private void ask(String email) throws Exception {
        mvc.perform(post("/api/auth/password-reset").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email))).andExpect(status().isNoContent());
    }

    private String token(String email) {
        String body = tenants.callAsPlatform(() -> jdbc.sql("select body from outbox_message where recipient = ? order by id desc limit 1")
                .param(email).query(String.class).single());
        Matcher m = Pattern.compile("/reset-password\\?token=([A-Za-z0-9_-]+)").matcher(body);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private org.springframework.test.web.servlet.ResultActions confirm(String token, String password) throws Exception {
        return mvc.perform(post("/api/auth/password-reset/confirm").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\",\"password\":\"%s\"}".formatted(token, password)));
    }

    private org.springframework.test.web.servlet.ResultActions signIn(String email, String password, MockHttpSession session) throws Exception {
        return mvc.perform(post("/api/auth/login").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    @Test
    void aLinkByEmailSetsANewPasswordOnceAndSignsOutEverywhere() throws Exception {
        String email = newEmployee();
        MockHttpSession before = login(email);
        mvc.perform(get("/api/auth/me").session(before)).andExpect(status().isOk());

        // The same answer for an unknown email: the form does not tell who has an account.
        ask("nobody-" + System.nanoTime() + "@nowhere.cd");
        ask(email.toUpperCase());
        String token = token(email);
        assertThat(jdbc.sql("select token_hash from password_reset where token_hash = ?")
                .param(token).query(String.class).optional()).isEmpty(); // only the hash is kept

        confirm(token, "short").andExpect(status().isUnprocessableEntity());
        confirm("not-a-real-token", NEW_PASSWORD).andExpect(status().isUnprocessableEntity());
        confirm(token, NEW_PASSWORD).andExpect(status().isNoContent());
        confirm(token, "Another-one-2026").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("This link no longer works. Ask for a new one from the sign-in page."));

        signIn(email, PASSWORD, new MockHttpSession()).andExpect(status().isUnauthorized());
        signIn(email, NEW_PASSWORD, new MockHttpSession()).andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false));
        // The browser signed in before is signed out.
        mvc.perform(get("/api/auth/me").session(before)).andExpect(status().isUnauthorized());
    }

    @Test
    void anOldLinkDoesNotWorkAndAskingTooOftenSendsNoMore() throws Exception {
        String email = newEmployee();
        ask(email);
        String token = token(email);
        jdbc.sql("update password_reset set expires_at = now() - interval '1 minute' where user_id = (select id from app_user where email = ?)")
                .param(email).update();
        confirm(token, NEW_PASSWORD).andExpect(status().isUnprocessableEntity());

        ask(email);
        ask(email);
        ask(email);
        assertThat(jdbc.sql("select count(*) from password_reset r join app_user u on u.id = r.user_id where u.email = ?")
                .param(email).query(Integer.class).single()).isEqualTo(3);
        // A new link replaces none of the old ones until one is used; using one ends the others.
        String latest = token(email);
        confirm(latest, NEW_PASSWORD).andExpect(status().isNoContent());
        assertThat(jdbc.sql("select count(*) from password_reset r join app_user u on u.id = r.user_id where u.email = ? and r.used_at is null")
                .param(email).query(Integer.class).single()).isZero();
    }

    private record Company(long id, String adminEmail) {}

    private Company newCompany() throws Exception {
        long n = System.nanoTime() % 1_000_000;
        String email = "boss" + n + "@recovery.cd";
        String response = mvc.perform(json(post("/api/platform/companies").session(login(ADMIN)), """
                {"name":"Recovery %d","countryCode":"CD","planCode":"UP_TO_50","modules":["ATTENDANCE"],
                 "admin":{"firstName":"Ada","lastName":"Boss","email":"%s","phone":"+2439971%05d"}}"""
                .formatted(n, email, n % 100000))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Company(((Number) JsonPath.read(response, "$.company.id")).longValue(), email);
    }

    @Test
    void theProviderResetsALockedOutAdministratorAndTheCompanySeesIt() throws Exception {
        Company acme = newCompany();
        MockHttpSession provider = login(ADMIN);
        String admins = mvc.perform(get("/api/platform/companies/" + acme.id() + "/admins").session(provider))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].email").value(acme.adminEmail()))
                .andReturn().getResponse().getContentAsString();
        long userId = ((Number) JsonPath.read(admins, "$[0].id")).longValue();

        // A reason is required, and the company's own people cannot use it.
        mvc.perform(json(post("/api/platform/companies/" + acme.id() + "/admins/" + userId + "/reset").session(provider),
                "{\"mode\":\"PASSWORD\",\"resetTwoStep\":true,\"reason\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/platform/companies/" + acme.id() + "/admins/" + userId + "/reset").session(login(HR)),
                "{\"mode\":\"PASSWORD\",\"resetTwoStep\":true,\"reason\":\"x\"}")).andExpect(status().isForbidden());

        String done = mvc.perform(json(post("/api/platform/companies/" + acme.id() + "/admins/" + userId + "/reset").session(provider),
                        "{\"mode\":\"PASSWORD\",\"resetTwoStep\":true,\"reason\":\"Call from the director, ticket 42\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String temporary = JsonPath.read(done, "$.temporaryPassword");
        assertThat(temporary).isNotBlank();
        signIn(acme.adminEmail(), temporary, new MockHttpSession()).andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));

        assertThat(tenants.callAs(acme.id(), () -> jdbc.sql("select count(*) from audit_log where action = 'user.recovered_by_provider'")
                .query(Integer.class).single())).isEqualTo(1);
        assertThat(tenants.callAs(acme.id(), () -> jdbc.sql("select count(*) from outbox_message where recipient = ?")
                .param(acme.adminEmail()).query(Integer.class).single())).isPositive();

        // The link instead: the administrator chooses the password.
        mvc.perform(json(post("/api/platform/companies/" + acme.id() + "/admins/" + userId + "/reset").session(provider),
                        "{\"mode\":\"LINK\",\"resetTwoStep\":false,\"reason\":\"Asked again\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.temporaryPassword").doesNotExist());
        confirm(token(acme.adminEmail()), NEW_PASSWORD).andExpect(status().isNoContent());
        signIn(acme.adminEmail(), NEW_PASSWORD, new MockHttpSession()).andExpect(status().isOk());
    }

    @Test
    void theServerCommandSetsATemporaryPassword() throws Exception {
        String email = newEmployee();
        for (int i = 0; i < 6; i++) {
            signIn(email, "Wrong-password-1", new MockHttpSession());
        }
        signIn(email, PASSWORD, new MockHttpSession()).andExpect(status().isTooManyRequests());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code;
        try (Connection c = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            code = ResetPasswordCommand.run(new String[] {email, "--second-step"}, c, new PrintStream(out, true, StandardCharsets.UTF_8));
        }
        assertThat(code).isZero();
        Matcher m = Pattern.compile("password for \\S+: (\\S+)").matcher(out.toString(StandardCharsets.UTF_8));
        assertThat(m.find()).isTrue();
        signIn(email, m.group(1), new MockHttpSession()).andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'user.password_reset_on_server'")
                .query(Integer.class).single()).isPositive();

        try (Connection c = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            assertThat(ResetPasswordCommand.run(new String[] {"nobody@nowhere.cd"}, c, new PrintStream(out))).isEqualTo(1);
        }
    }
}
