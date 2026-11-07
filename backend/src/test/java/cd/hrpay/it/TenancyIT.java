package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.platform.LicenceTool;
import cd.hrpay.platform.Licences;
import cd.hrpay.tenancy.Tenants;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** Companies on one installation never see each other's data, and each gets its own working set-up. */
class TenancyIT extends IntegrationTest {

    @Autowired
    Tenants tenants;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Licences licences;

    private static int companies;

    record NewCompany(long id, String email, String password) {}

    private NewCompany createCompany(String plan, String modules) throws Exception {
        int n = ++companies;
        String email = "boss" + n + "." + System.nanoTime() % 100000 + "@acme.cd";
        String body = """
                {"name":"Acme %d","countryCode":"CD","planCode":"%s","modules":%s,
                 "admin":{"firstName":"Ada","lastName":"Boss","email":"%s","phone":"+2439970%05d"}}"""
                .formatted(n, plan, modules, email, (int) (System.nanoTime() % 100000));
        String response = mvc.perform(json(post("/api/platform/companies").session(login(ADMIN)), body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        NewCompany created = new NewCompany(((Number) JsonPath.read(response, "$.company.id")).longValue(),
                JsonPath.read(response, "$.adminEmail"), JsonPath.read(response, "$.temporaryPassword"));
        // The administrator signs in with the temporary password and must change it first.
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(created.email(), created.password())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.company.id").value(created.id()))
                .andExpect(jsonPath("$.mustChangePassword").value(true));
        mvc.perform(json(post("/api/auth/password").session(session),
                        "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(created.password(), PASSWORD)))
                .andExpect(status().isNoContent());
        return created;
    }

    private MockHttpSession signIn(NewCompany c) throws Exception {
        MockHttpSession session = login(c.email());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(jsonPath("$.company.id").value(c.id()));
        return session;
    }

    @AfterEach
    void resetInstallation() {
        tenants.callAsPlatform(() -> jdbc.sql("update installation set licence = null, started_at = now()").update());
    }

    @Test
    void theAppRunsAsTheRoleTheCompanyPoliciesApplyTo() {
        assertThat(jdbc.sql("select current_user").query(String.class).single()).isEqualTo("hrpay_app");
        // With a company that has no rows, a query with no filter at all finds nothing.
        assertThat(tenants.callAs(-1L, () -> jdbc.sql("select count(*) from app_user").query(Integer.class).single()))
                .isZero();
        assertThat(tenants.callAs(-1L, () -> jdbc.sql("select count(*) from payroll_setting").query(Integer.class).single()))
                .isZero();
    }

    @Test
    void aNewCompanyStartsWithTheCountrySetUpAndOnlyItsAdministrator() throws Exception {
        NewCompany acme = createCompany("UP_TO_300", "[\"ATTENDANCE\",\"PAYROLL\",\"EVALUATIONS\"]");
        MockHttpSession boss = signIn(acme);

        mvc.perform(get("/api/users").session(boss))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].email").value(acme.email()));
        mvc.perform(get("/api/grades").session(boss)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6));
        mvc.perform(get("/api/roles").session(boss)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));
        mvc.perform(get("/api/holidays/definitions").session(boss)).andExpect(status().isOk());

        tenants.runAs(acme.id(), () -> {
            assertThat(jdbc.sql("select count(*) from pay_element").query(Integer.class).single()).isEqualTo(20);
            assertThat(jdbc.sql("select count(*) from ipr_bracket").query(Integer.class).single()).isEqualTo(10);
            assertThat(jdbc.sql("select count(*) from org_unit where parent_id is null").query(Integer.class).single()).isOne();
            assertThat(jdbc.sql("select value from payroll_setting where key = 'RAISES_FROM'").query(String.class).single())
                    .isEqualTo(LocalDate.now().withDayOfMonth(1).toString());
            // The installation's own permissions stay with the provider's company.
            assertThat(jdbc.sql("select count(*) from role_permission where permission_code in ('platform.manage', 'system.monitor')")
                    .query(Integer.class).single()).isZero();
        });
    }

    @Test
    void companiesNeverSeeEachOthersPeople() throws Exception {
        NewCompany acme = createCompany("UP_TO_50", "[\"ATTENDANCE\",\"PAYROLL\"]");
        MockHttpSession boss = signIn(acme);
        long demoHr = tenants.callAs(1L, () -> jdbc.sql("select id from app_user where email = ?").param(HR)
                .query(Long.class).single());
        long acmeBoss = tenants.callAs(acme.id(), () -> jdbc.sql("select id from app_user").query(Long.class).single());

        mvc.perform(get("/api/users/" + demoHr).session(boss)).andExpect(status().isNotFound());
        mvc.perform(get("/api/users/" + acmeBoss).session(login(HR))).andExpect(status().isNotFound());
        mvc.perform(get("/api/users").session(login(HR))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.email=='" + acme.email() + "')]").isEmpty());
        mvc.perform(get("/api/payroll/runs").session(boss)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // A change aimed at another company's row finds nothing to change.
        int changed = tenants.callAs(acme.id(), () -> jdbc.sql("update app_user set first_name = 'X' where id = ?")
                .param(demoHr).update());
        assertThat(changed).isZero();
        // And a row cannot be written into another company.
        assertThatThrownBy(() -> tenants.callAs(acme.id(), () -> jdbc.sql(
                "insert into payroll_setting (company_id, key, value) values (1, 'INTRUDER', 'x')").update()))
                .hasStackTraceContaining("row-level security");

        // The same registration number may exist in two companies, an email only once in the installation.
        String user = """
                {"orgUnitId":%d,"user":{"email":"%s","registrationNo":"HR-0001","phone":"+243899100200",
                 "firstName":"A","lastName":"B"}}""";
        long acmeTop = tenants.callAs(acme.id(), () -> jdbc.sql("select id from org_unit where parent_id is null")
                .query(Long.class).single());
        mvc.perform(json(post("/api/users").session(boss), user.formatted(acmeTop, HR))).andExpect(status().isConflict());
        mvc.perform(json(post("/api/users").session(boss), user.formatted(acmeTop, "new.hr@acme.cd")))
                .andExpect(status().isCreated());
    }

    @Test
    void onlyTheProviderManagesCompaniesAndTheInstallation() throws Exception {
        NewCompany acme = createCompany("UP_TO_50", "[\"ATTENDANCE\"]");
        MockHttpSession boss = signIn(acme);
        mvc.perform(get("/api/auth/me").session(boss))
                .andExpect(jsonPath("$.permissions[?(@ == 'platform.manage')]").isEmpty())
                .andExpect(jsonPath("$.permissions[?(@ == 'system.monitor')]").isEmpty())
                .andExpect(jsonPath("$.company.modules[0]").value("ATTENDANCE"));
        mvc.perform(get("/api/platform/companies").session(boss)).andExpect(status().isForbidden());
        mvc.perform(get("/api/system/status").session(boss)).andExpect(status().isForbidden());
        mvc.perform(get("/api/permissions").session(boss))
                .andExpect(jsonPath("$[?(@.code == 'platform.manage')]").isEmpty());

        // Copying the permission into a role does not open the console either.
        long adminRole = tenants.callAs(acme.id(), () -> jdbc.sql("select id from role where code = 'ADMIN'")
                .query(Long.class).single());
        tenants.runAs(acme.id(), () -> jdbc.sql("insert into role_permission (role_id, permission_code) values (?, 'platform.manage')")
                .param(adminRole).update());
        mvc.perform(get("/api/platform/companies").session(signIn(acme))).andExpect(status().isForbidden());

        mvc.perform(get("/api/platform/companies").session(login(ADMIN))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].provider").value(true))
                .andExpect(jsonPath("$[?(@.id == " + acme.id() + ")].employees").value(1));
    }

    @Test
    void aModuleOutsideThePlanCanBeReadButNotChanged() throws Exception {
        NewCompany acme = createCompany("UP_TO_50", "[\"ATTENDANCE\"]");
        MockHttpSession boss = signIn(acme);
        mvc.perform(json(post("/api/exchange-rates").session(boss), "{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Your plan does not include Payroll. Ask your provider to add it."));
        mvc.perform(get("/api/payroll/runs").session(boss)).andExpect(status().isOk());

        mvc.perform(json(put("/api/platform/companies/" + acme.id()).session(login(ADMIN)),
                        "{\"planCode\":\"UP_TO_50\",\"modules\":[\"ATTENDANCE\",\"PAYROLL\"],\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modules.length()").value(2));
        mvc.perform(json(post("/api/exchange-rates").session(boss), "{}")).andExpect(status().isBadRequest());
    }

    @Test
    void goingOverThePlanSizeWarnsWithoutBlocking() throws Exception {
        tenants.callAsPlatform(() -> jdbc.sql("""
                insert into plan (code, name, max_employees, display_order) values ('TEST_ONE', 'Test, one employee', 1, 99)
                on conflict do nothing""").update());
        NewCompany acme = createCompany("TEST_ONE", "[\"ATTENDANCE\",\"PAYROLL\"]");
        MockHttpSession boss = signIn(acme);
        mvc.perform(get("/api/auth/me").session(boss)).andExpect(jsonPath("$.company.notices").isEmpty());
        long top = tenants.callAs(acme.id(), () -> jdbc.sql("select id from org_unit where parent_id is null")
                .query(Long.class).single());
        mvc.perform(json(post("/api/users").session(boss), """
                        {"orgUnitId":%d,"user":{"email":"second.%d@acme.cd","registrationNo":"E-2","phone":"+243899100%03d",
                         "firstName":"Second","lastName":"Person"}}""".formatted(top, System.nanoTime() % 100000,
                        (int) (System.nanoTime() % 1000))))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/auth/me").session(boss))
                .andExpect(jsonPath("$.company.employees").value(2))
                .andExpect(jsonPath("$.company.notices[0].level").value("warn"))
                .andExpect(jsonPath("$.company.notices[0].text").value(
                        "2 active employees: your plan (Test, one employee) covers up to 1. Move to the next plan; nothing is blocked meanwhile."));
        // Payroll still works: the guard looks at modules only.
        mvc.perform(json(post("/api/exchange-rates").session(boss), "{}")).andExpect(status().isBadRequest());
    }

    @Test
    void scheduledJobsRunOnceForEachCompany() throws Exception {
        NewCompany acme = createCompany("UP_TO_50", "[\"ATTENDANCE\"]");
        List<Long> seen = new ArrayList<>();
        List<Integer> users = new ArrayList<>();
        tenants.forEachCompany("test", id -> {
            seen.add(id);
            users.add(jdbc.sql("select count(*) from app_user").query(Integer.class).single());
        });
        assertThat(seen).contains(1L, acme.id());
        assertThat(users.get(seen.indexOf(acme.id()))).isOne();
    }

    // ---- Licences ----

    private static String signed(LocalDate expires, int grace) throws Exception {
        String key = new String(TenancyIT.class.getResourceAsStream("/licence/test-private.key").readAllBytes(),
                StandardCharsets.US_ASCII).lines().filter(l -> !l.startsWith("#")).findFirst().orElseThrow();
        PrivateKey privateKey = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(key.trim())));
        return LicenceTool.sign(new Licences.Licence("L-TEST", "Acme SARL", LocalDate.now(), expires, 300, 2,
                List.of("ATTENDANCE", "PAYROLL"), grace), privateKey);
    }

    private Licences.State state() {
        return tenants.callAsPlatform(() -> licences.status().state());
    }

    @Test
    void aSignedLicenceIsCheckedOfflineAndHasAGracePeriod() throws Exception {
        assertThat(state()).isEqualTo(Licences.State.EVALUATION);
        tenants.callAsPlatform(() -> jdbc.sql("update installation set started_at = now() - interval '40 days'").update());
        assertThat(state()).isEqualTo(Licences.State.LOCKED);

        String file = signed(LocalDate.now().plusDays(200), 30);
        mvc.perform(json(post("/api/platform/licence").session(login(ADMIN)),
                        "{\"content\":" + tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(file) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("VALID"))
                .andExpect(jsonPath("$.licence.licensee").value("Acme SARL"));

        install(signed(LocalDate.now().plusDays(10), 30));
        assertThat(state()).isEqualTo(Licences.State.EXPIRING);
        install(signed(LocalDate.now().minusDays(10), 30));
        assertThat(state()).isEqualTo(Licences.State.GRACE);
        install(signed(LocalDate.now().minusDays(40), 30));
        assertThat(state()).isEqualTo(Licences.State.LOCKED);

        // A changed licence fails its signature.
        String tampered = file.replace(file.lines().reduce((a, b) -> b).orElseThrow(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                        "{\"id\":\"L-TEST\",\"licensee\":\"Acme SARL\",\"expires\":\"2099-01-01\",\"modules\":[\"PAYROLL\"]}"
                                .getBytes(StandardCharsets.UTF_8))
                        + file.lines().reduce((a, b) -> b).orElseThrow().substring(
                        file.lines().reduce((a, b) -> b).orElseThrow().indexOf('.')));
        assertThatThrownBy(() -> licences.verify(tampered)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("signature");
        assertThatThrownBy(() -> licences.verify("hello")).isInstanceOf(BusinessRuleException.class);
    }

    private void install(String file) {
        tenants.callAsPlatform(() -> licences.install(file, "test"));
    }
}
