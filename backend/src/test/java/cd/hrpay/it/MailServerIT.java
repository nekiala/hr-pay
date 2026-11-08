package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.notify.MailServers;
import cd.hrpay.notify.NotificationService;
import cd.hrpay.tenancy.Tenants;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import java.net.ServerSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/**
 * Each company's own email server (set by whoever holds company.manage, HR by default), the platform's, and the
 * Messages page when there is none.
 */
class MailServerIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Tenants tenants;

    @Autowired
    NotificationService messages;

    @Autowired
    MailServers servers;

    @Autowired
    UserRepository users;

    FakeSmtp platformSmtp;
    FakeSmtp companySmtp;

    @BeforeEach
    void start() throws Exception {
        platformSmtp = new FakeSmtp();
        companySmtp = new FakeSmtp();
    }

    @AfterEach
    void stop() throws Exception {
        platformSmtp.close();
        companySmtp.close();
        // Other tests read codes on the Messages page: leave no server behind.
        tenants.callAsPlatform(() -> {
            jdbc.sql("delete from company_mail_server").update();
            return jdbc.sql("delete from platform_mail_server").update();
        });
    }

    private static String server(int port, String extra) {
        return """
                {"enabled":true,"protocol":"SMTP","host":"127.0.0.1","port":%d,"encryption":"NONE",
                 "fromAddress":"no-reply@hrpay.test","fromName":"HR Pay","bcc":"archive@hrpay.test"%s}"""
                .formatted(port, extra);
    }

    private AppUser admin() {
        return users.findByEmailIgnoreCase(ADMIN).orElseThrow();
    }

    private String lastVia(String email) {
        return tenants.callAsPlatform(() -> jdbc.sql(
                "select status || ' ' || coalesce(via, '') from outbox_message where recipient = ? order by id desc limit 1")
                .param(email).query(String.class).single());
    }

    @Test
    void withoutAnyServerEmailsAreOnlyWrittenOnTheMessagesPage() throws Exception {
        MockHttpSession admin = login(ADMIN);
        MockHttpSession hr = login(HR);
        mvc.perform(get("/api/outbox/server").session(admin)).andExpect(jsonPath("$.inUse").value("NONE"));
        assertThat(messages.sendEmail(admin(), "Hello", "Body").status()).isEqualTo("LOGGED");
        assertThat(lastVia(ADMIN)).isEqualTo("LOGGED NONE");
        mvc.perform(get("/api/company/mail-server").session(hr))
                .andExpect(jsonPath("$.inUse").value("NONE")).andExpect(jsonPath("$.settings").doesNotExist());
    }

    @Test
    void thePlatformServerSendsAndKeepsSecurityEmailsOutOfTheHiddenCopy() throws Exception {
        MockHttpSession admin = login(ADMIN);
        MockHttpSession hr = login(HR);
        mvc.perform(json(put("/api/platform/mail-server").session(admin), server(platformSmtp.port(), "")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.settings.enabled").value(true));

        assertThat(messages.sendEmail(admin(), "Raises", "Two raises").status()).isEqualTo("SENT");
        assertThat(lastVia(ADMIN)).isEqualTo("SENT PLATFORM");
        assertThat(platformSmtp.received).hasSize(1);
        assertThat(platformSmtp.received.getFirst().recipients()).containsExactlyInAnyOrder(ADMIN, "archive@hrpay.test");

        assertThat(messages.securityEmail(admin(), () -> "Choose a new password", () -> "link").status()).isEqualTo("SENT");
        assertThat(platformSmtp.received).hasSize(2);
        assertThat(platformSmtp.received.get(1).recipients()).containsExactly(ADMIN);
        assertThat(platformSmtp.received.get(1).data()).doesNotContain("archive@hrpay.test");

        mvc.perform(get("/api/outbox/server").session(admin)).andExpect(jsonPath("$.inUse").value("PLATFORM"));
    }

    @Test
    void theCompanyServerComesFirstAndItsPasswordNeverLeaves() throws Exception {
        MockHttpSession admin = login(ADMIN);
        MockHttpSession hr = login(HR);
        mvc.perform(json(put("/api/platform/mail-server").session(admin), server(platformSmtp.port(), "")))
                .andExpect(status().isOk());
        mvc.perform(json(put("/api/company/mail-server").session(hr), server(companySmtp.port(),
                        ",\"username\":\"mailer@company.cd\",\"password\":\"s3cret\",\"ehloDomain\":\"vps.company.cd\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inUse").value("COMPANY"))
                .andExpect(jsonPath("$.settings.passwordSet").value(true))
                .andExpect(jsonPath("$.settings.password").doesNotExist());
        String stored = tenants.callAs(1L, () -> jdbc.sql("select password from company_mail_server").query(String.class).single());
        assertThat(stored).isNotEqualTo("s3cret");

        assertThat(messages.sendEmail(admin(), "Hello", "Body").status()).isEqualTo("SENT");
        assertThat(lastVia(ADMIN)).isEqualTo("SENT COMPANY");
        assertThat(platformSmtp.received).isEmpty();
        FakeSmtp.Mail mail = companySmtp.received.getFirst();
        assertThat(mail.ehlo()).isEqualTo("vps.company.cd");
        assertThat(mail.user()).isEqualTo("mailer@company.cd");

        // Saving again with an empty password keeps the one saved.
        mvc.perform(json(put("/api/company/mail-server").session(hr), server(companySmtp.port(),
                        ",\"username\":\"mailer@company.cd\",\"password\":\"\"")))
                .andExpect(jsonPath("$.settings.passwordSet").value(true));
        assertThat(tenants.callAs(1L, () -> jdbc.sql("select password from company_mail_server").query(String.class).single()))
                .isEqualTo(stored);

        mvc.perform(json(post("/api/company/mail-server/test").session(hr), server(companySmtp.port(), "")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Connected to 127.0.0.1:" + companySmtp.port() + "."));

        // Turned off, the platform's server takes over again.
        mvc.perform(json(put("/api/company/mail-server").session(hr), server(companySmtp.port(), "")
                .replace("\"enabled\":true", "\"enabled\":false"))).andExpect(jsonPath("$.inUse").value("PLATFORM"));
    }

    @Test
    void aFailingCompanyServerDoesNotFallBackAndTellsTheAdministrators() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        MockHttpSession admin = login(ADMIN);
        MockHttpSession hr = login(HR);
        mvc.perform(json(put("/api/platform/mail-server").session(admin), server(platformSmtp.port(), "")))
                .andExpect(status().isOk());
        mvc.perform(json(put("/api/company/mail-server").session(hr), server(closedPort, "")))
                .andExpect(status().isOk());

        assertThat(messages.sendEmail(admin(), "Hello", "Body").status()).isEqualTo("FAILED");
        assertThat(lastVia(ADMIN)).isEqualTo("FAILED COMPANY");
        assertThat(platformSmtp.received).isEmpty();
        long notices = tenants.callAs(1L, () -> jdbc.sql("select count(*) from notification where kind = 'MAIL_FAILED'")
                .query(Long.class).single());
        assertThat(notices).isPositive();

        // A second failure within the hour tells nobody again.
        messages.sendEmail(admin(), "Hello again", "Body");
        assertThat(tenants.callAs(1L, () -> jdbc.sql("select count(*) from notification where kind = 'MAIL_FAILED'")
                .query(Long.class).single())).isEqualTo(notices);

        mvc.perform(json(post("/api/company/mail-server/test").session(hr), server(closedPort, "")))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void onlyAdministratorsSeeTheSettingsAndBadValuesAreRefused() throws Exception {
        mvc.perform(get("/api/company/mail-server").session(login(EMPLOYEE))).andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/mail-server").session(login(HR))).andExpect(status().isForbidden());
        mvc.perform(get("/api/company/mail-server").session(login(HR))).andExpect(status().isOk());
        MockHttpSession admin = login(ADMIN);
        MockHttpSession hr = login(HR);
        mvc.perform(json(put("/api/company/mail-server").session(hr), server(25, "").replace("127.0.0.1", "bad host!")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(put("/api/company/mail-server").session(hr), server(25, "").replace("archive@hrpay.test", "nope")))
                .andExpect(status().isUnprocessableContent());
        assertThat(servers.available(1L)).isFalse();
    }
}
