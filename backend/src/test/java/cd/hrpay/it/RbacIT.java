package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class RbacIT extends IntegrationTest {

    private long createUser(MockHttpSession session, String body) throws Exception {
        String response = mvc.perform(json(post("/api/users").session(session), body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private long roleId(MockHttpSession session, String code) throws Exception {
        String roles = mvc.perform(get("/api/roles").session(session)).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.<java.util.List<Object>>read(roles, "$[?(@.code=='" + code + "')].id").getFirst())
                .longValue();
    }

    @Test
    void employeesCannotListUsers() throws Exception {
        mvc.perform(get("/api/users").session(login(EMPLOYEE))).andExpect(status().isForbidden());
    }

    @Test
    void hrCreatesEmployeesButNotAdministrators() throws Exception {
        MockHttpSession hr = login(HR);
        mvc.perform(json(post("/api/users").session(hr), newUser(null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles[0]").value("EMPLOYEE"));
        mvc.perform(json(post("/api/users").session(hr), newUser("[\"ADMIN\"]")))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void hrCannotDisableAnAdministrator() throws Exception {
        MockHttpSession admin = login(ADMIN);
        long id = createUser(admin, newUser("[\"ADMIN\"]"));
        mvc.perform(post("/api/users/" + id + "/disable").session(login(HR)).with(csrf()))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void emailRegistrationNumberAndPhoneMustBeUnique() throws Exception {
        MockHttpSession hr = login(HR);
        String base = """
                {"orgUnitId":1,"user":{"email":"%s","registrationNo":"%s","phone":"%s","firstName":"A","lastName":"B"}}""";
        mvc.perform(json(post("/api/users").session(hr), base.formatted("uniq@test.cd", "UNQ-1", "+243899000001")))
                .andExpect(status().isCreated());
        mvc.perform(json(post("/api/users").session(hr), base.formatted("UNIQ@test.cd", "UNQ-2", "+243899000002")))
                .andExpect(status().isConflict());
        mvc.perform(json(post("/api/users").session(hr), base.formatted("uniq2@test.cd", "unq-1", "+243899000003")))
                .andExpect(status().isConflict());
        mvc.perform(json(post("/api/users").session(hr), base.formatted("uniq3@test.cd", "UNQ-3", "+243 899 000 001")))
                .andExpect(status().isConflict());
    }

    @Test
    void mandatoryFieldsAreValidated() throws Exception {
        mvc.perform(json(post("/api/users").session(login(HR)),
                        "{\"user\":{\"email\":\"bad\",\"registrationNo\":\"\",\"phone\":\"12\",\"firstName\":\"A\",\"lastName\":\"B\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['user.email']").exists())
                .andExpect(jsonPath("$.errors['user.phone']").exists());
    }

    @Test
    void disabledUsersAreSignedOutOnTheirNextRequest() throws Exception {
        MockHttpSession admin = login(ADMIN);
        String body = newUser(null).replace("}}", "},\"initialPassword\":\"" + PASSWORD + "\"}");
        long id = createUser(admin, body);
        String email = JsonPath.read(body, "$.user.email");

        MockHttpSession user = login(email);
        mvc.perform(get("/api/auth/me").session(user)).andExpect(status().isOk());
        mvc.perform(post("/api/users/" + id + "/disable").session(admin).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(user)).andExpect(status().isUnauthorized());
    }

    @Test
    void permissionChangesApplyWithoutLoggingInAgain() throws Exception {
        MockHttpSession admin = login(ADMIN);
        MockHttpSession finance = login(FINANCE);
        mvc.perform(get("/api/audit").session(finance)).andExpect(status().isForbidden());

        long financeRole = roleId(admin, "FINANCE");
        String role = mvc.perform(get("/api/roles").session(admin)).andReturn().getResponse().getContentAsString();
        java.util.List<String> perms = JsonPath.read(role, "$[?(@.code=='FINANCE')].permissions[*]");
        String withAudit = new java.util.ArrayList<>(perms) {{ add("audit.read"); }}.stream()
                .map(p -> "\"" + p + "\"").collect(java.util.stream.Collectors.joining(","));
        mvc.perform(json(put("/api/roles/" + financeRole).session(admin),
                        "{\"name\":\"Finance\",\"permissions\":[" + withAudit + "]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/audit").session(finance)).andExpect(status().isOk());

        String without = perms.stream().map(p -> "\"" + p + "\"").collect(java.util.stream.Collectors.joining(","));
        mvc.perform(json(put("/api/roles/" + financeRole).session(admin),
                        "{\"name\":\"Finance\",\"permissions\":[" + without + "]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/audit").session(finance)).andExpect(status().isForbidden());
    }

    @Test
    void customRolesCanBeCreatedButSystemRolesStay() throws Exception {
        MockHttpSession admin = login(ADMIN);
        mvc.perform(json(post("/api/roles").session(admin),
                        "{\"code\":\"SITE_MANAGER\",\"name\":\"Site manager\",\"permissions\":[\"site.read\",\"user.read\"]}"))
                .andExpect(status().isCreated());
        mvc.perform(json(post("/api/roles").session(admin),
                        "{\"code\":\"BAD_ROLE\",\"name\":\"Bad\",\"permissions\":[\"does.not.exist\"]}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(delete("/api/roles/" + roleId(admin, "HR")).session(admin).with(csrf()))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(put("/api/roles/" + roleId(admin, "ADMIN")).session(admin),
                        "{\"name\":\"Administrator\",\"permissions\":[\"user.read\"]}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void hrCannotManageRoles() throws Exception {
        mvc.perform(json(post("/api/roles").session(login(HR)),
                        "{\"code\":\"X_ROLE\",\"name\":\"X\",\"permissions\":[]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void changesAreAudited() throws Exception {
        MockHttpSession admin = login(ADMIN);
        long id = createUser(admin, newUser(null));
        String audit = mvc.perform(get("/api/audit").param("entityType", "user").param("entityId", String.valueOf(id))
                        .session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<java.util.List<String>>read(audit, "$.items[*].action")).contains("user.created");
        assertThat(JsonPath.<java.util.List<String>>read(audit, "$.items[*].actor")).contains(ADMIN);
    }
}
