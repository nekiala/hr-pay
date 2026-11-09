package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.Notifications;
import cd.hrpay.notify.WhatsAppConsent;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/**
 * WhatsApp: the platform's account, templates submitted to Meta, a company turning it on, an employee confirming their
 * number with a code sent by WhatsApp, notifications going out as templates, Meta's delivery reports and a STOP reply.
 * A small HTTP server answers as Meta.
 */
class WhatsAppIT extends IntegrationTest {

    static final String SECRET = "app-secret-123";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Notifications notifications;

    @Autowired
    WhatsAppConsent consent;

    HttpServer meta;
    final List<String> requests = new CopyOnWriteArrayList<>();
    final AtomicInteger ids = new AtomicInteger();

    @BeforeEach
    void startMeta() throws Exception {
        meta = HttpServer.create(new InetSocketAddress("127.0.0.1", 18099), 0);
        meta.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = ex.getRequestURI().getPath();
            requests.add(ex.getRequestMethod() + " " + path + " " + body);
            String answer;
            if (path.endsWith("/messages")) {
                answer = "{\"messages\":[{\"id\":\"wamid.%d\"}]}".formatted(ids.incrementAndGet());
            } else if (ex.getRequestMethod().equals("POST")) {
                answer = "{\"id\":\"%d\",\"status\":\"PENDING\",\"category\":\"UTILITY\"}".formatted(ids.incrementAndGet());
            } else {
                StringBuilder data = new StringBuilder();
                for (String name : List.of("hrpay_otp", "hrpay_payslip_ready", "hrpay_request_update", "hrpay_action_needed",
                        "hrpay_checkout_reminder", "someone_elses")) {
                    for (String lang : List.of("fr", "en")) {
                        data.append(data.isEmpty() ? "" : ",").append(
                                "{\"name\":\"%s\",\"language\":\"%s\",\"status\":\"APPROVED\",\"id\":\"1\"}".formatted(name, lang));
                    }
                }
                answer = "{\"data\":[" + data + "]}";
            }
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/javascript; charset=UTF-8");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        meta.start();
    }

    @AfterEach
    void stopMeta() {
        meta.stop(0);
        jdbc.sql("delete from platform_whatsapp").update();
        jdbc.sql("delete from company_whatsapp").update();
        jdbc.sql("update app_user set whatsapp_phone = null, whatsapp_opt_in_at = null").update();
    }

    long userId(String email) {
        return jdbc.sql("select id from app_user where email = ?").param(email).query(Long.class).single();
    }

    String phone(String email) {
        return jdbc.sql("select phone_e164 from app_user where email = ?").param(email).query(String.class).single();
    }

    static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    List<String> sent(String template) {
        return requests.stream().filter(r -> r.contains("/messages") && r.contains("\"" + template + "\"")).toList();
    }

    @Test
    void employeesWhoAgreeGetTheirNotificationsOnWhatsApp() throws Exception {
        MockHttpSession admin = login(ADMIN);
        MockHttpSession hr = login(HR);
        MockHttpSession me = login(EMPLOYEE);
        long myId = userId(EMPLOYEE);
        String myPhone = phone(EMPLOYEE);

        // Nothing is offered before the company turns it on.
        mvc.perform(get("/api/my/whatsapp").session(me)).andExpect(status().isOk()).andExpect(jsonPath("$.offered").value(false));
        mvc.perform(post("/api/my/whatsapp/code").session(me).with(csrf())).andExpect(status().is(422));
        mvc.perform(json(put("/api/company/whatsapp").session(hr), "{\"enabled\":true,\"kinds\":[\"DECISIONS\"],\"ownNumber\":false}"))
                .andExpect(status().is(422));

        // The provider sets up the platform's number; the secrets never come back.
        mvc.perform(json(put("/api/platform/whatsapp").session(hr), "{\"enabled\":true}")).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/platform/whatsapp").session(admin),
                        "{\"enabled\":true,\"phoneNumberId\":\"1234567\",\"businessId\":\"7654321\",\"displayNumber\":\"+243 81 000 0000\"}"))
                .andExpect(status().is(422));
        String saved = mvc.perform(json(put("/api/platform/whatsapp").session(admin),
                        "{\"enabled\":true,\"phoneNumberId\":\"1234567\",\"businessId\":\"7654321\",\"displayNumber\":\"+243 81 000 0000\","
                                + "\"accessToken\":\"EAAG-token\",\"appSecret\":\"%s\"}".formatted(SECRET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.tokenSet").value(true))
                .andExpect(jsonPath("$.settings.templates[0].status").value("NOT_SUBMITTED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(saved).doesNotContain("EAAG-token").doesNotContain(SECRET);
        String verifyToken = JsonPath.read(saved, "$.settings.verifyToken");

        // Templates go to Meta in French and English; their status is read back.
        mvc.perform(post("/api/platform/whatsapp/templates/submit").session(admin).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.templates[?(@.status != 'APPROVED')]").isEmpty());
        assertThat(requests.stream().filter(r -> r.startsWith("POST /v21.0/7654321/message_templates"))).hasSize(10)
                .anyMatch(r -> r.contains("\"AUTHENTICATION\"")).anyMatch(r -> r.contains("Voir le bulletin"));

        // The company turns it on for decisions only.
        mvc.perform(json(put("/api/company/whatsapp").session(hr), "{\"enabled\":true,\"kinds\":[\"DECISIONS\"],\"ownNumber\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inUse").value("PLATFORM"))
                .andExpect(jsonPath("$.inUseNumber").value("+243 81 000 0000"));

        // The employee confirms their number with a code sent by WhatsApp only.
        mvc.perform(get("/api/my/whatsapp").session(me)).andExpect(jsonPath("$.offered").value(true))
                .andExpect(jsonPath("$.optedIn").value(false));
        int emails = jdbc.sql("select count(*) from outbox_message where channel = 'EMAIL' and user_id = ?").param(myId)
                .query(Integer.class).single();
        String code = mvc.perform(post("/api/my/whatsapp/code").session(me).with(csrf())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.regex.Matcher found = java.util.regex.Pattern.compile("\"text\":\"(\\d{6})\"").matcher(sent("hrpay_otp").getLast());
        assertThat(found.find()).as(sent("hrpay_otp").getLast()).isTrue();
        String otp = found.group(1);
        assertThat(otp).matches("\\d{6}");
        assertThat(jdbc.sql("select count(*) from outbox_message where channel = 'EMAIL' and user_id = ?").param(myId)
                .query(Integer.class).single()).isEqualTo(emails);
        mvc.perform(json(post("/api/my/whatsapp/confirm").session(me),
                        "{\"challengeId\":\"%s\",\"code\":\"%s\"}".formatted(JsonPath.read(code, "$.challengeId"), otp)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.optedIn").value(true))
                .andExpect(jsonPath("$.history[0].action").value("IN"));

        // Earlier notifications (other tests) were handled by the job long ago.
        jdbc.sql("update notification set whatsapp_at = now() where whatsapp_at is null").update();
        // A decision goes out as the "request update" template; a payslip notice does not (not chosen by the company).
        notifications.send(myId, "leave.approved", () -> Texts.t("Leave approved"), () -> "Your leave from 3 to 7 Nov is approved.", "/leave");
        notifications.send(myId, "payslip.ready", () -> "Payslip", () -> "The payslip for October 2026 can be read in HR Pay.", "/my/payslips");
        notifications.send(userId(HR), "leave.approved", () -> "x", () -> "Not opted in.", "/leave");
        assertThat(consent.sendPending()).isEqualTo(1);
        List<String> update = sent("hrpay_request_update");
        assertThat(update).hasSize(1);
        assertThat(update.getFirst()).contains(myPhone.replace("+", "")).contains("Your leave from 3 to 7 Nov is approved.")
                .contains("\"text\":\"leave\"");
        assertThat(sent("hrpay_payslip_ready")).isEmpty();
        assertThat(consent.sendPending()).isZero();
        String wamid = jdbc.sql("select provider_id from outbox_message where provider_id is not null and user_id = ? order by id desc limit 1")
                .param(myId).query(String.class).single();

        // Meta's webhook: registered with our verify token; reports are signed with the app secret.
        mvc.perform(get("/api/public/whatsapp/webhook").param("hub.mode", "subscribe").param("hub.verify_token", verifyToken)
                .param("hub.challenge", "42")).andExpect(status().isOk()).andExpect(content().string("42"));
        mvc.perform(get("/api/public/whatsapp/webhook").param("hub.mode", "subscribe").param("hub.verify_token", "nope")
                .param("hub.challenge", "42")).andExpect(status().isForbidden());
        String delivered = """
                {"entry":[{"changes":[{"value":{"metadata":{"phone_number_id":"1234567"},
                 "statuses":[{"id":"%s","status":"read"}]}}]}]}""".formatted(wamid);
        mvc.perform(post("/api/public/whatsapp/webhook").contentType(MediaType.APPLICATION_JSON).content(delivered)
                .header("X-Hub-Signature-256", "sha256=00")).andExpect(status().isForbidden());
        mvc.perform(post("/api/public/whatsapp/webhook").contentType(MediaType.APPLICATION_JSON).content(delivered)
                .header("X-Hub-Signature-256", sign(delivered))).andExpect(status().isOk());
        assertThat(jdbc.sql("select read_at is not null and delivered_at is not null from outbox_message where provider_id = ?")
                .param(wamid).query(Boolean.class).single()).isTrue();

        // Replying STOP ends the agreement and gets a confirmation.
        String stop = """
                {"entry":[{"changes":[{"value":{"metadata":{"phone_number_id":"1234567"},
                 "messages":[{"from":"%s","type":"text","text":{"body":"Stop"}}]}}]}]}""".formatted(myPhone.replace("+", ""));
        mvc.perform(post("/api/public/whatsapp/webhook").contentType(MediaType.APPLICATION_JSON).content(stop)
                .header("X-Hub-Signature-256", sign(stop))).andExpect(status().isOk());
        mvc.perform(get("/api/my/whatsapp").session(me)).andExpect(jsonPath("$.optedIn").value(false))
                .andExpect(jsonPath("$.history[0].action").value("OUT"))
                .andExpect(jsonPath("$.history[0].source").value("REPLY"));
        assertThat(requests).anyMatch(r -> r.contains("\"type\":\"text\"") && r.contains("WhatsApp"));
        notifications.send(myId, "leave.rejected", () -> "x", () -> "Refused.", "/leave");
        assertThat(consent.sendPending()).isZero();
    }
}
