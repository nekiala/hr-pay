package cd.hrpay.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

class AuthIT extends IntegrationTest {

    @Test
    void apiNeedsASignedInUser() throws Exception {
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginReturnsRolesAndPermissions() throws Exception {
        MockHttpSession session = login(HR);
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(HR))
                .andExpect(jsonPath("$.roles[0]").value("HR"))
                .andExpect(jsonPath("$.permissions", Matchers.hasItem("user.create")))
                .andExpect(jsonPath("$.permissions", Matchers.not(Matchers.hasItem("role.assign"))));
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        mvc.perform(json(post("/api/auth/login"), "{\"email\":\"%s\",\"password\":\"nope-12345\"}".formatted(FINANCE)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postsWithoutCsrfTokenAreRefused() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(HR, PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void fiveFailuresBlockTheAccountTemporarily() throws Exception {
        String email = "employee3@demo.hrpay.cd";
        for (int i = 0; i < 5; i++) {
            mvc.perform(json(post("/api/auth/login"), "{\"email\":\"%s\",\"password\":\"wrong-pass-1\"}".formatted(email)))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(json(post("/api/auth/login"), "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        MockHttpSession session = login(FINANCE);
        mvc.perform(post("/api/auth/logout").session(session).with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void googleIsOffUntilConfigured() throws Exception {
        mvc.perform(get("/api/auth/providers"))
                .andExpect(jsonPath("$.password").value(true))
                .andExpect(jsonPath("$.google").value(false));
    }
}
