package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** Phase 15: imports from spreadsheets, the public API and its keys, webhooks, time clocks. */
class IntegrationsIT extends IntegrationTest {

    @Autowired
    UserRepository users;

    @Autowired
    JdbcClient jdbc;

    private static String file(String name, String csv) {
        return "{\"fileName\":\"%s\",\"content\":\"%s\"}".formatted(name,
                Base64.getEncoder().encodeToString(csv.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void anImportIsCheckedRowByRowAndSavedOnlyWhenEveryRowIsRight() throws Exception {
        MockHttpSession hr = login(HR);
        String n = String.valueOf(System.nanoTime() % 1_000_000);
        // French headers, a semicolon and a French date: what Excel in French saves.
        String bad = """
                Matricule;Prénom;Nom;Email;Téléphone;Date d'embauche;Sexe;Mode de paiement;Banque;Compte bancaire
                IMP-%1$s-1;Aline;Mbuyi;aline.%1$s@import.cd;+243 81 000 %1$s;01/03/2026;F;Banque;Rawbank;00011122233
                IMP-%1$s-2;Jean;Kabeya;jean.%1$s@import.cd;0810;2026-03-15;M;;;
                """.formatted(n);
        mvc.perform(json(post("/api/imports/employees/check").session(hr), file("staff.csv", bad)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(false))
                .andExpect(jsonPath("$.rows").value(2))
                .andExpect(jsonPath("$.withErrors").value(1))
                .andExpect(jsonPath("$.results[0].action").value("CREATE"))
                .andExpect(jsonPath("$.results[1].errors[0]").value(Matchers.containsString("international format")));
        // An import with a bad row saves nothing, not even the good row.
        mvc.perform(json(post("/api/imports/employees").session(hr), file("staff.csv", bad)))
                .andExpect(jsonPath("$.saved").value(false));
        assertThat(users.findByRegistrationNoIgnoreCase("IMP-" + n + "-1")).isEmpty();

        String good = bad.replace(";0810;", ";+243 82 000 " + n + ";");
        mvc.perform(json(post("/api/imports/employees").session(hr), file("staff.csv", good)))
                .andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.withErrors").value(0));
        var aline = users.findByRegistrationNoIgnoreCase("IMP-" + n + "-1").orElseThrow();
        assertThat(aline.getHireDate()).isEqualTo(java.time.LocalDate.of(2026, 3, 1));
        assertThat(jdbc.sql("select payment_method from employee_profile where user_id = ?").param(aline.getId())
                .query(String.class).single()).isEqualTo("BANK");
        // Everyone imported is placed in the structure.
        assertThat(jdbc.sql("select count(*) from org_assignment where user_id = ? and valid_to is null").param(aline.getId())
                .query(Integer.class).single()).isEqualTo(1);
        // The same file again updates the people.
        mvc.perform(json(post("/api/imports/employees/check").session(hr), file("staff.csv", good)))
                .andExpect(jsonPath("$.results[0].action").value("UPDATE"));

        // Contracts, salaries, leave balances and year-to-date totals of the people just imported.
        mvc.perform(json(post("/api/imports/contracts").session(hr), file("c.csv", """
                        registration_no,contract_type,start_date,grade,expatriate
                        IMP-%1$s-1,CDI,2026-03-01,E4A,non
                        IMP-%1$s-2,CDD,2026-03-15,E3A,no
                        """.formatted(n))))
                .andExpect(jsonPath("$.saved").value(false))
                .andExpect(jsonPath("$.results[1].errors[0]").value(Matchers.containsString("end date")));
        mvc.perform(json(post("/api/imports/contracts").session(hr), file("c.csv", """
                        registration_no,contract_type,start_date,end_date,grade,expatriate
                        IMP-%1$s-1,CDI,2026-03-01,,E4A,non
                        IMP-%1$s-2,CDD,2026-03-15,2027-03-14,E3A,no
                        """.formatted(n))))
                .andExpect(jsonPath("$.saved").value(true));
        mvc.perform(json(post("/api/imports/salaries").session(hr), file("s.csv", """
                        registration_no,effective_from,gross_monthly,currency
                        IMP-%1$s-1,2026-06-01,"1 250,50",USD
                        """.formatted(n))))
                .andExpect(jsonPath("$.saved").value(true));
        assertThat(jdbc.sql("select gross_monthly from employee_salary where user_id = ? and effective_from = '2026-06-01'")
                .param(aline.getId()).query(java.math.BigDecimal.class).single()).isEqualByComparingTo("1250.50");
        mvc.perform(json(post("/api/imports/leave-balances").session(hr), file("l.csv", """
                        registration_no,days
                        IMP-%1$s-1,7.5
                        NOBODY-%1$s,3
                        """.formatted(n))))
                .andExpect(jsonPath("$.saved").value(false))
                .andExpect(jsonPath("$.results[1].errors[0]").value(Matchers.containsString("NOBODY")));
        mvc.perform(json(post("/api/imports/year-to-date").session(hr), file("y.csv", """
                        registration_no,year,through_month,currency,gross,cnss,tax,net
                        IMP-%1$s-1,2025,6,USD,6000,210,480,5310
                        """.formatted(n))))
                .andExpect(jsonPath("$.saved").value(true));
        mvc.perform(get("/api/year-summaries/" + aline.getId()).param("year", "2025").session(login(FINANCE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months[0].month").value("2025-01…2025-06"))
                .andExpect(jsonPath("$.totals[0].gross").value(6000));

        // An employee cannot import.
        mvc.perform(json(post("/api/imports/employees/check").session(login(EMPLOYEE)), file("x.csv", good)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/imports/employees/template.xlsx").session(hr)).andExpect(status().isOk());
        // The people imported here leave again, so that other tests see the staff they expect.
        jdbc.sql("update app_user set status = 'DISABLED' where registration_no like ?").param("IMP-" + n + "-%").update();
    }

    @Test
    void anApiKeyReadsAndWritesWithItsOwnPermissionsOnly() throws Exception {
        // The administrator holds what the key is given (an administrator does not send check-ins by default).
        jdbc.sql("insert into role_permission (company_id, role_id, permission_code) select company_id, id, 'attendance.correct' from role where code = 'ADMIN' and company_id = 1 on conflict do nothing").update();
        try {
            keys();
        } finally {
            jdbc.sql("delete from role_permission where permission_code = 'attendance.correct' and role_id = (select id from role where code = 'ADMIN' and company_id = 1)").update();
        }
    }

    private void keys() throws Exception {
        MockHttpSession admin = login(ADMIN);
        String created = mvc.perform(json(post("/api/integrations/keys").session(admin), """
                        {"name":"Access control","permissions":["user.read","attendance.correct"]}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(created, "$.secret");
        long keyId = ((Number) JsonPath.read(created, "$.key.id")).longValue();
        assertThat(token).startsWith("hrp_");

        mvc.perform(get("/api/v1/employees")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/employees").header("Authorization", "Bearer hrp_wrongwrong_" + "x".repeat(32)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/employees").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email=='employee1@demo.hrpay.cd')]").isNotEmpty());
        // Not given to the key: payslips.
        mvc.perform(get("/api/v1/payroll/runs").header("X-API-Key", token)).andExpect(status().isForbidden());
        // The description needs no key.
        mvc.perform(get("/api/v1/openapi.json")).andExpect(status().isOk()).andExpect(jsonPath("$.openapi").value("3.1.0"));

        // A check-in from another system, and the same punch twice.
        String reg = users.findByEmailIgnoreCase(EMPLOYEE).orElseThrow().getRegistrationNo();
        mvc.perform(post("/api/v1/attendance/events").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNo\":\"%s\",\"at\":\"2026-08-03T08:00:00+01:00\"}".formatted(reg)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("IN"));
        mvc.perform(post("/api/v1/attendance/events").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNo\":\"%s\",\"at\":\"2026-08-03T08:01:00+01:00\"}".formatted(reg)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recorded").value(false));
        assertThat(jdbc.sql("select actor from audit_log where action = 'attendance.check_in' order by id desc limit 1")
                .query(String.class).single()).isEqualTo("api:Access control");

        // A key cannot be given more than its maker has; a revoked key stops working.
        mvc.perform(json(post("/api/integrations/keys").session(admin), """
                        {"name":"Too much","permissions":["platform.manage"]}"""))
                .andExpect(status().is(422));
        mvc.perform(post("/api/integrations/keys/" + keyId + "/revoke").session(admin).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/employees").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/integrations/keys").session(login(HR))).andExpect(status().isForbidden());
    }

    @Test
    void webhooksQueueAMessageWhenSomethingHappens() throws Exception {
        MockHttpSession admin = login(ADMIN);
        mvc.perform(json(post("/api/integrations/webhooks").session(admin), """
                        {"url":"https://127.0.0.1/hook","events":["employee.created"]}"""))
                .andExpect(status().is(422));
        String created = mvc.perform(json(post("/api/integrations/webhooks").session(admin), """
                        {"url":"https://8.8.8.8/hr-pay","events":["employee.created","payroll.closed"]}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long hook = ((Number) JsonPath.read(created, "$.webhook.id")).longValue();
        assertThat(JsonPath.<String>read(created, "$.secret")).startsWith("whsec_");

        mvc.perform(json(post("/api/users").session(login(HR)), newUser(null))).andExpect(status().isCreated());
        String payload = jdbc.sql("select payload::text from webhook_delivery where webhook_id = ? and event = 'employee.created'")
                .param(hook).query(String.class).single();
        assertThat(payload).contains("\"registrationNo\": \"T-").doesNotContain("phone").doesNotContain("@test.cd");

        mvc.perform(get("/api/integrations/webhooks/" + hook + "/deliveries").session(admin))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void aTimeClockPushesPunchesThatBecomeCheckInsAndOuts() throws Exception {
        MockHttpSession admin = login(ADMIN);
        String sn = "ZK" + (System.nanoTime() % 1_000_000);
        mvc.perform(json(post("/api/integrations/clocks").session(admin), """
                        {"serialNo":"%s","name":"Entrance","siteId":1}""".formatted(sn)))
                .andExpect(status().isCreated());

        mvc.perform(get("/iclock/cdata").param("SN", "UNKNOWN1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/iclock/cdata").param("SN", sn).param("options", "all"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("GET OPTION FROM: " + sn)));

        var person = users.findByEmailIgnoreCase("employee2@demo.hrpay.cd").orElseThrow();
        String body = person.getRegistrationNo() + "\t2026-09-07 07:55:10\t0\t1\t0\t0\n"
                + person.getRegistrationNo() + "\t2026-09-07 17:02:00\t0\t1\t0\t0\n"
                + "99999\t2026-09-07 08:00:00\t0\t1\t0\t0\n";
        mvc.perform(post("/iclock/cdata?SN=" + sn + "&table=ATTLOG&Stamp=42").contentType(MediaType.TEXT_PLAIN).content(body))
                .andExpect(status().isOk())
                .andExpect(content().string("OK: 3"));
        assertThat(jdbc.sql("select type from clock_event where user_id = ? and work_date = '2026-09-07' and source = 'BIOMETRIC' order by occurred_at")
                .param(person.getId()).query(String.class).list()).containsExactly("IN", "OUT");
        // Sent again (the clock retries): nothing more.
        mvc.perform(post("/iclock/cdata?SN=" + sn + "&table=ATTLOG&Stamp=42").contentType(MediaType.TEXT_PLAIN).content(body))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("select count(*) from clock_event where user_id = ? and work_date = '2026-09-07'")
                .param(person.getId()).query(Integer.class).single()).isEqualTo(2);
        mvc.perform(get("/api/integrations/clocks/punches").param("outcome", "UNKNOWN_PIN").session(admin))
                .andExpect(jsonPath("$[?(@.pin=='99999')]").isNotEmpty());
        // HR enrols someone under the unknown number: the punch waiting under it is recorded.
        var other = users.findByEmailIgnoreCase(EMPLOYEE).orElseThrow();
        try {
            mvc.perform(json(post("/api/integrations/clocks/link").session(admin),
                            "{\"pin\":\"99999\",\"registrationNo\":\"%s\"}".formatted(other.getRegistrationNo())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.recorded").value(1));
            assertThat(jdbc.sql("select type from clock_event where user_id = ? and work_date = '2026-09-07' and source = 'BIOMETRIC'")
                    .param(other.getId()).query(String.class).list()).containsExactly("IN");
            mvc.perform(json(post("/api/integrations/clocks/link").session(admin),
                            "{\"pin\":\"99999\",\"registrationNo\":\"%s\"}".formatted(person.getRegistrationNo())))
                    .andExpect(status().isConflict());
        } finally {
            jdbc.sql("update app_user set clock_pin = null where id = ?").param(other.getId()).update();
        }
        // The clock now asks only for punches after the last one it sent.
        mvc.perform(get("/iclock/cdata").param("SN", sn)).andExpect(content().string(Matchers.containsString("ATTLOGStamp=42")));
    }
}
