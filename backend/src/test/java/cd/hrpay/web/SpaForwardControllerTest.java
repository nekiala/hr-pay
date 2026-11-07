package cd.hrpay.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Every page of the app reloads; files, the API and the clocks are never sent the page. */
class SpaForwardControllerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SpaForwardController()).build();

    @Test
    void pagesAreForwardedToTheApp() throws Exception {
        for (String page : new String[] {"/", "/billing", "/departures", "/imports", "/platform/companies/3",
                "/users/12/file", "/my/documents", "/payslips/4/correct"}) {
            mvc.perform(get(page)).andExpect(forwardedUrl("/index.html"));
        }
    }

    @Test
    void filesAndTheApiAreNot() throws Exception {
        for (String path : new String[] {"/sw.js", "/manifest.webmanifest", "/assets/index-abc.js", "/api/users", "/iclock/cdata",
                "/actuator/health", "/oauth2/authorization/google"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }
}
