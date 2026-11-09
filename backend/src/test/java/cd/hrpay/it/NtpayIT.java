package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** NTPay answers on a local port, as its payment-batch API does (https://ntpay.cloud/docs, "Paiements de masse"). */
class NtpayIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    UserRepository users;

    HttpServer ntpay;
    final List<String> requests = new CopyOnWriteArrayList<>();
    final Map<String, JsonNode> batches = new ConcurrentHashMap<>();
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void startNtpay() throws Exception {
        ntpay = HttpServer.create(new InetSocketAddress("127.0.0.1", 18098), 0);
        ntpay.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = ex.getRequestURI().getPath();
            requests.add(ex.getRequestMethod() + " " + path + " auth=" + ex.getRequestHeaders().getFirst("Authorization")
                    + " key=" + ex.getRequestHeaders().getFirst("Idempotency-Key") + " " + body);
            int code;
            String answer;
            if (ex.getRequestMethod().equals("POST") && path.equals("/api/payment-batches")) {
                JsonNode sent = json.readTree(body);
                String batchCode = "PMIT" + (batches.size() + 1);
                batches.put(batchCode, sent);
                code = 201;
                answer = """
                        {"result":"success","message":"Payment batch created, waiting for approval","batch":{"batch_code":"%s",
                         "reference":"%s","status":"pending_approval","finished":false,"currency":"USD","lines_count":%d}}"""
                        .formatted(batchCode, sent.path("reference").asText(), sent.path("lines").size());
            } else if (ex.getRequestMethod().equals("GET") && batches.containsKey(path.substring(path.lastIndexOf('/') + 1))) {
                JsonNode sent = batches.get(path.substring(path.lastIndexOf('/') + 1));
                StringBuilder lines = new StringBuilder();
                for (JsonNode l : sent.path("lines")) {
                    boolean fails = l.path("phone").asText().endsWith("0002");
                    lines.append(lines.isEmpty() ? "" : ",").append("""
                            {"phone":"%s","amount":"%s","reference":"%s","status":"%s","payout_reference":"%s","attempts":1%s}"""
                            .formatted(l.path("phone").asText(), l.path("amount").asText(), l.path("reference").asText(),
                                    fails ? "failed" : "paid", fails ? "" : "PO-" + l.path("reference").asText(),
                                    fails ? ",\"error\":\"Solde insuffisant\"" : ""));
                }
                code = 200;
                answer = """
                        {"batch":{"batch_code":"%s","status":"approved","finished":true,"lines":[%s]}}"""
                        .formatted(path.substring(path.lastIndexOf('/') + 1), lines);
            } else {
                code = 404;
                answer = "{\"message\":\"Not found\"}";
            }
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        ntpay.start();
    }

    @AfterEach
    void stopNtpay() {
        ntpay.stop(0);
    }

    long id(String email) {
        return users.findByEmailIgnoreCase(email).orElseThrow().getId();
    }

    static String sign(String key, String timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "v1=" + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aMobileMoneyFileIsPaidThroughAnNtpayBatch() throws Exception {
        MockHttpSession hr = login(HR);
        MockHttpSession finance = login(FINANCE);
        List<Long> people = List.of(id(EMPLOYEE), id("employee2@demo.hrpay.cd"), id("employee3@demo.hrpay.cd"));
        List<String> phones = List.of("0810000001", "0850000002", "0810000003");
        record Profile(long userId, String paymentMethod, String mobileMoneyOperator, String mobileMoneyNumber) {}
        List<Profile> before = people.stream().map(u -> jdbc.sql("""
                select user_id, payment_method, mobile_money_operator, mobile_money_number from employee_profile where user_id = ?""")
                .param(u).query(Profile.class).single()).toList();
        long run = jdbc.sql("insert into advance_run (month) values ('2031-03-01') returning id").query(Long.class).single();
        try {
            for (int i = 0; i < people.size(); i++) {
                jdbc.sql("update employee_profile set payment_method = 'MOBILE_MONEY', mobile_money_operator = 'M-Pesa', mobile_money_number = ? where user_id = ?")
                        .params(phones.get(i), people.get(i)).update();
                jdbc.sql("""
                        insert into salary_advance (run_id, user_id, month, amount, currency, available, earned_net, percent, status,
                            signed_name, signed_at, sha256)
                        values (?, ?, '2031-03-01', 50, 'USD', 100, 200, 50, 'APPROVED', 'Test', now(), 'x')""")
                        .params(run, people.get(i)).update();
            }

            // The keys: kept by those who manage the company, never shown again.
            mvc.perform(get("/api/ntpay/settings").session(finance)).andExpect(jsonPath("$.configured").value(false));
            mvc.perform(json(put("/api/ntpay/settings").session(finance), "{\"enabled\":true,\"apiKey\":\"ntp_test_123456\"}"))
                    .andExpect(status().isForbidden());
            String settings = mvc.perform(json(put("/api/ntpay/settings").session(hr), """
                            {"enabled":true,"testMode":true,"apiKey":"ntp_test_123456","signatureKey":"sig_test_123456"}"""))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(true))
                    .andExpect(jsonPath("$.hasSignatureKey").value(true))
                    .andReturn().getResponse().getContentAsString();
            assertThat(settings).doesNotContain("ntp_test_123456").doesNotContain("sig_test_123456");
            String url = JsonPath.read(settings, "$.notificationUrl");
            String token = url.substring(url.lastIndexOf('/') + 1);
            mvc.perform(post("/api/ntpay/settings/test").session(finance).with(csrf()))
                    .andExpect(jsonPath("$.checkResult").value(org.hamcrest.Matchers.startsWith("Connection works")));

            String file = mvc.perform(json(post("/api/payments/2031-03/batches?kind=ADVANCE").session(finance), """
                            {"method":"MOBILE_MONEY","provider":"M-PESA","currency":"USD"}"""))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            long fileId = JsonPath.<Number>read(file, "$.batch.id").longValue();

            // Sent: one batch, the key and an idempotency key, every line by its number in international form.
            mvc.perform(json(post("/api/payment-batches/" + fileId + "/ntpay").session(hr), "{}")).andExpect(status().isForbidden());
            mvc.perform(json(post("/api/payment-batches/" + fileId + "/ntpay").session(finance), "{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.batch.ntpayStatus").value("pending_approval"))
                    .andExpect(jsonPath("$.batch.ntpayBatchCode").value("PMIT1"))
                    .andExpect(jsonPath("$.lines[*].ntpayStatus").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("waiting"))));
            String sent = requests.stream().filter(r -> r.startsWith("POST /api/payment-batches ")).findFirst().orElseThrow();
            assertThat(sent).contains("auth=Bearer ntp_test_123456").contains("\"phone\":\"243850000002\"")
                    .contains("\"reference\":\"HRPAY-L").contains("\"currency\":\"USD\"").contains(token)
                    .doesNotContain("key=null");
            // While NTPay holds it, the file is neither marked paid by hand nor cancelled, nor sent twice.
            mvc.perform(json(post("/api/payment-batches/" + fileId + "/paid").session(finance), """
                            {"paidOn":"2026-11-01","reference":"X"}""")).andExpect(status().isConflict());
            mvc.perform(json(post("/api/payment-batches/" + fileId + "/cancel").session(finance), "{\"reason\":\"No\"}"))
                    .andExpect(status().isConflict());
            mvc.perform(json(post("/api/payment-batches/" + fileId + "/ntpay").session(finance), "{}")).andExpect(status().isConflict());

            // NTPay's notification: refused without the right signature, applied once with it.
            String body = """
                    {"type":"payment_batch","event":"finished","batch_code":"PMIT1","status":"approved","finished":true}""";
            String now = String.valueOf(System.currentTimeMillis() / 1000);
            mvc.perform(post("/api/public/ntpay/" + token).contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("X-NTPay-Timestamp", now).header("X-NTPay-Signature", sign("wrong_key_123", now, body))
                            .header("X-NTPay-Notification-Id", "n-1"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/api/public/ntpay/" + token).contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("X-NTPay-Timestamp", now).header("X-NTPay-Signature", sign("sig_test_123456", now, body))
                            .header("X-NTPay-Notification-Id", "n-1"))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/public/ntpay/" + token).contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("X-NTPay-Timestamp", now).header("X-NTPay-Signature", sign("sig_test_123456", now, body))
                            .header("X-NTPay-Notification-Id", "n-1"))
                    .andExpect(status().isOk());

            // Two paid: the file is paid with them; the failed one leaves it and can be paid again.
            mvc.perform(get("/api/payment-batches/" + fileId).session(finance))
                    .andExpect(jsonPath("$.batch.status").value("PAID"))
                    .andExpect(jsonPath("$.batch.reference").value("PMIT1"))
                    .andExpect(jsonPath("$.batch.ntpayFinished").value(true))
                    .andExpect(jsonPath("$.batch.lineCount").value(2))
                    .andExpect(jsonPath("$.lines[*].ntpayPayoutReference").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.startsWith("PO-HRPAY-L"))));
            assertThat(jdbc.sql("select status from salary_advance where run_id = ? order by user_id").param(run).query(String.class).list())
                    .containsExactlyInAnyOrder("PAID", "PAID", "APPROVED");
            mvc.perform(get("/api/payments/2031-03?kind=ADVANCE").session(finance))
                    .andExpect(jsonPath("$.toPay[0].count").value(1));
            assertThat(jdbc.sql("select count(*) from notification where user_id = ? and kind = 'payment.failed'").param(people.get(1))
                    .query(Integer.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("select count(*) from notification where user_id = ? and kind = 'payment.sent'").param(people.get(0))
                    .query(Integer.class).single()).isPositive();
            assertThat(requests.stream().filter(r -> r.startsWith("GET /api/payment-batches/PMIT1")).count()).isEqualTo(1);
        } finally {
            jdbc.sql("delete from payment_line where advance_id in (select id from salary_advance where run_id = ?)").param(run).update();
            jdbc.sql("delete from payment_batch where advance_run_id = ?").param(run).update();
            jdbc.sql("delete from salary_advance where run_id = ?").param(run).update();
            jdbc.sql("delete from advance_run where id = ?").param(run).update();
            jdbc.sql("delete from company_ntpay").update();
            for (Profile p : before) {
                jdbc.sql("update employee_profile set payment_method = ?, mobile_money_operator = ?, mobile_money_number = ? where user_id = ?")
                        .params(p.paymentMethod(), p.mobileMoneyOperator(), p.mobileMoneyNumber(), p.userId()).update();
            }
        }
    }
}
