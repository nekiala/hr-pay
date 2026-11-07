package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

class CheckInIT extends IntegrationTest {

    static final String INSIDE = "{\"lat\":-4.30375,\"lng\":15.31085,\"accuracyM\":20}";
    static final String OUTSIDE = "{\"lat\":-4.32,\"lng\":15.31085,\"accuracyM\":20}";

    @Autowired
    JdbcClient jdbc;

    private String latestCode(String email) {
        String body = jdbc.sql("select body from outbox_message where recipient = :r order by id desc limit 1")
                .param("r", email).query(String.class).single();
        Matcher m = Pattern.compile("(?:code is|est) (\\d{6})").matcher(body);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private long rejections(String reason) {
        return jdbc.sql("select count(*) from audit_log where action = 'attendance.rejected' and details->>'reason' = :r")
                .param("r", reason).query(Long.class).single();
    }

    @Test
    void fullDayWithCodeOnlyOnTheFirstCheckIn() throws Exception {
        String email = "employee2@demo.hrpay.cd";
        MockHttpSession s = login(email);

        String started = mvc.perform(json(post("/api/attendance/check-in").session(s), INSIDE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CODE_SENT"))
                .andExpect(jsonPath("$.channels[0]").value("EMAIL"))
                .andReturn().getResponse().getContentAsString();
        String challenge = JsonPath.read(started, "$.challengeId");

        mvc.perform(json(post("/api/attendance/check-in/confirm").session(s),
                        "{\"challengeId\":\"%s\",\"code\":\"000000x\"}".formatted(challenge)))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(post("/api/attendance/check-in/confirm").session(s),
                        "{\"challengeId\":\"%s\",\"code\":\"%s\"}".formatted(challenge, latestCode(email))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("IN"));
        // A code works once.
        mvc.perform(json(post("/api/attendance/check-in/confirm").session(s),
                        "{\"challengeId\":\"%s\",\"code\":\"%s\"}".formatted(challenge, latestCode(email))))
                .andExpect(status().isUnprocessableContent());

        mvc.perform(get("/api/attendance/status").session(s)).andExpect(jsonPath("$.checkedIn").value(true));
        mvc.perform(json(post("/api/attendance/check-in").session(s), INSIDE)).andExpect(status().isUnprocessableContent());

        mvc.perform(json(post("/api/attendance/check-out").session(s), INSIDE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("OUT"));
        mvc.perform(json(post("/api/attendance/check-out").session(s), INSIDE)).andExpect(status().isUnprocessableContent());

        // Back from a break the same day: no new code.
        mvc.perform(json(post("/api/attendance/check-in").session(s), INSIDE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CHECKED_IN"));
        mvc.perform(get("/api/attendance/status").session(s)).andExpect(jsonPath("$.today.length()").value(3));
    }

    @Test
    void refusedOutsideTheAreaOrOffTheOfficeNetwork() throws Exception {
        MockHttpSession s = login(FINANCE);
        long gpsBefore = rejections("gps_outside");
        long ipBefore = rejections("ip");

        mvc.perform(json(post("/api/attendance/check-in").session(s), OUTSIDE))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("outside")));
        mvc.perform(json(post("/api/attendance/check-in").session(s), "{\"lat\":-4.30375,\"lng\":15.31085,\"accuracyM\":900}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(post("/api/attendance/check-in").session(s), "{}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(post("/api/attendance/check-in").session(s).with(r -> {
                    r.setRemoteAddr("8.8.8.8");
                    return r;
                }), INSIDE))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("office network")));

        assertThat(rejections("gps_outside")).isEqualTo(gpsBefore + 1);
        assertThat(rejections("ip")).isEqualTo(ipBefore + 1);
    }

    @Test
    void qrCodeFromTheKioskIsRequiredWhenTheSiteAsksForIt() throws Exception {
        MockHttpSession admin = login(ADMIN);
        String site = mvc.perform(json(post("/api/sites").session(admin), """
                        {"code":"QR-SITE","name":"QR site","countryCode":"CD","timeZone":"Africa/Kinshasa",
                         "centerLat":null,"centerLng":null,"radiusM":null,"gpsRequired":false,"maxAccuracyM":100,
                         "active":true,"qrRequired":true,"ipRanges":[{"cidr":"127.0.0.1"}]}"""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long siteId = ((Number) JsonPath.read(site, "$.id")).longValue();

        String body = newUser(null).replace("}}", ",\"siteId\":" + siteId + "},\"initialPassword\":\"" + PASSWORD + "\"}");
        mvc.perform(json(post("/api/users").session(admin), body)).andExpect(status().isCreated());
        String email = JsonPath.read(body, "$.user.email");
        MockHttpSession s = login(email);
        // The temporary password must be changed before anything else matters; the API still works.
        mvc.perform(json(post("/api/attendance/check-in").session(s), "{}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("QR")));

        String link = mvc.perform(get("/api/sites/" + siteId + "/kiosk-link").session(admin))
                .andReturn().getResponse().getContentAsString();
        String kioskUrl = JsonPath.read(link, "$.url");
        String key = kioskUrl.substring(kioskUrl.indexOf("key=") + 4);
        String kiosk = mvc.perform(get("/api/kiosk").param("site", "QR-SITE").param("key", key))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String qrUrl = JsonPath.read(kiosk, "$.url");
        String token = java.net.URLDecoder.decode(qrUrl.substring(qrUrl.indexOf("qr=") + 3), java.nio.charset.StandardCharsets.UTF_8);

        mvc.perform(json(post("/api/attendance/check-in").session(s), "{\"qrToken\":\"%s\"}".formatted(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CODE_SENT"));
        mvc.perform(get("/api/kiosk").param("site", "QR-SITE").param("key", "wrong")).andExpect(status().isNotFound());
    }

    @Test
    void trustedOfficeComputerSkipsGps() throws Exception {
        MockHttpSession admin = login(ADMIN);
        long siteId = jdbc.sql("select id from site where code = 'KIN-HQ'").query(Long.class).single();
        var registered = mvc.perform(json(post("/api/sites/" + siteId + "/devices").session(admin),
                        "{\"name\":\"Reception laptop\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        jakarta.servlet.http.Cookie cookie = registered.getCookie("hrpay_device");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();

        MockHttpSession s = login("manager@demo.hrpay.cd");
        mvc.perform(get("/api/attendance/status").session(s).cookie(cookie))
                .andExpect(jsonPath("$.trustedDevice").value(true));
        mvc.perform(json(post("/api/attendance/check-in").session(s).cookie(cookie), "{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CODE_SENT"));
    }
}
