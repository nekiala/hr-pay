package cd.hrpay.it;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Base class for tests against a real PostgreSQL database (hrpay_test). The schema is wiped and
 * migrated once per test run, then the sample data is loaded.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationTest.CleanDatabase.class)
public abstract class IntegrationTest {

    protected static final String PASSWORD = "Demo@2026!";
    protected static final String ADMIN = "admin@demo.hrpay.cd";
    protected static final String HR = "hr@demo.hrpay.cd";
    protected static final String FINANCE = "finance@demo.hrpay.cd";
    protected static final String EMPLOYEE = "employee1@demo.hrpay.cd";

    @TestConfiguration
    static class CleanDatabase {
        @Bean
        FlywayMigrationStrategy cleanThenMigrate() {
            return (Flyway flyway) -> {
                flyway.clean();
                flyway.migrate();
            };
        }
    }

    @Autowired
    protected MockMvc mvc;

    private static int sequence;

    protected MockHttpSession login(String email) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk());
        return session;
    }

    protected static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** A unique, valid employee payload. */
    protected static String newUser(String roles) {
        int n = ++sequence;
        String user = """
                {"email":"user%1$d.%2$d@test.cd","registrationNo":"T-%1$d-%2$d","phone":"+24382%2$07d",
                 "firstName":"Test","lastName":"User %1$d"}""".formatted(n, (int) (System.nanoTime() % 10_000_000));
        return roles == null ? "{\"orgUnitId\":1,\"user\":" + user + "}" : "{\"orgUnitId\":1,\"roles\":" + roles + ",\"user\":" + user + "}";
    }
}
