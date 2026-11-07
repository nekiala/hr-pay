package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * Sessions are kept in PostgreSQL on a real server: after signing in, the session cookie alone is enough, whichever
 * server (or restarted server) answers, because the session is read back from the database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("test")
class SessionStoreIT {

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Test
    void theSessionLivesInTheDatabase() throws Exception {
        CookieManager cookies = new CookieManager();
        HttpClient browser = HttpClient.newBuilder().cookieHandler(cookies).build();
        String base = "http://localhost:" + port;

        browser.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf")).build(), HttpResponse.BodyHandlers.discarding());
        String xsrf = cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN"))
                .findFirst().map(HttpCookie::getValue).orElseThrow();
        HttpResponse<String> login = browser.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", xsrf)
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"admin@demo.hrpay.cd\",\"password\":\"Demo@2026!\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);

        String session = cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("SESSION"))
                .findFirst().map(HttpCookie::getValue).orElseThrow();
        assertThat(jdbc.sql("select count(*) from spring_session where principal_name = 'admin@demo.hrpay.cd'")
                .query(Integer.class).single()).isGreaterThanOrEqualTo(1);

        // Another client holding only the session cookie (another server would see the same) is signed in.
        HttpClient other = HttpClient.newHttpClient();
        HttpResponse<String> me = other.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/me"))
                .header("Cookie", "SESSION=" + session).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("admin@demo.hrpay.cd");

        HttpResponse<String> stranger = other.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/me")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(stranger.statusCode()).isEqualTo(401);
    }
}
