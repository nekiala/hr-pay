package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.ops.AuditChain;
import cd.hrpay.security.DataCrypto;
import cd.hrpay.tenancy.Tenants;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

class SecurityIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Tenants tenants;

    @Autowired
    AuditChain chain;

    private long newPerson(MockHttpSession hr) throws Exception {
        String body = mvc.perform(json(post("/api/users").session(hr), newUser("[\"EMPLOYEE\"]")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private <T> T db(java.util.function.Supplier<T> work) {
        return tenants.callAs(1L, work);
    }

    @Test
    void identifiersAreEncryptedInTheDatabaseAndReadBackPlain() throws Exception {
        MockHttpSession hr = login(HR);
        long id = newPerson(hr);
        mvc.perform(json(put("/api/users/" + id + "/profile").session(hr), """
                        {"maritalStatus":"SINGLE","paymentMethod":"BANK","bankName":"Rawbank","bankAccount":"0501-2233445-66",
                         "cnssNumber":"CNSS-998877","taxNumber":"A1234567Z"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankAccount").value("0501-2233445-66"))
                .andExpect(jsonPath("$.cnssNumber").value("CNSS-998877"));
        List<String> stored = db(() -> jdbc.sql("select bank_account, cnss_number, tax_number from employee_profile where user_id = ?")
                .param(id).query((rs, i) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3))).single());
        assertThat(stored).allMatch(DataCrypto::isEncrypted).noneMatch(s -> s.contains("0501") || s.contains("998877"));

        // A copy of the person's data reads them as entered, and holds no secret.
        String export = mvc.perform(get("/api/users/" + id + "/data-export").session(hr))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(export).contains("0501-2233445-66", "CNSS-998877").doesNotContain("enc:v1:", "password_hash");
        mvc.perform(get("/api/users/" + id + "/data-export").session(login(EMPLOYEE))).andExpect(status().isForbidden());
    }

    @Test
    void anEmployeeDownloadsTheirOwnDataAndAsksForACorrection() throws Exception {
        MockHttpSession me = login(EMPLOYEE);
        String export = mvc.perform(get("/api/account/data-export").session(me))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(export, "$.person")).hasSize(1);
        assertThat(export).contains(EMPLOYEE).doesNotContain("totp_secret", "session_id");

        mvc.perform(json(post("/api/account/corrections").session(me), "{\"field\":\"SHOE_SIZE\",\"requested\":\"44\"}"))
                .andExpect(status().isUnprocessableContent());
        String asked = mvc.perform(json(post("/api/account/corrections").session(me),
                        "{\"field\":\"BIRTH_DATE\",\"current\":\"1990-01-01\",\"requested\":\"1990-10-01\",\"reason\":\"Typo\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn().getResponse().getContentAsString();
        Number request = JsonPath.read(asked, "$.id");

        MockHttpSession hr = login(HR);
        mvc.perform(get("/api/privacy/corrections?status=OPEN").session(hr))
                .andExpect(jsonPath("$[?(@.id == " + request + ")].field").value("BIRTH_DATE"));
        mvc.perform(json(post("/api/privacy/corrections/" + request + "/refuse").session(hr), "{\"answer\":\"\"}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(post("/api/privacy/corrections/" + request + "/done").session(hr), "{\"answer\":\"Corrected\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        mvc.perform(get("/api/account/corrections").session(me))
                .andExpect(jsonPath("$[?(@.id == " + request + ")].answer").value("Corrected"));
        mvc.perform(get("/api/privacy/corrections").session(me)).andExpect(status().isForbidden());
    }

    @Test
    void aFormerEmployeeIsErasedOnlyAfterTheRetentionPeriod() throws Exception {
        MockHttpSession hr = login(HR);
        long id = newPerson(hr);
        String grades = mvc.perform(get("/api/grades").session(hr)).andReturn().getResponse().getContentAsString();
        long grade = JsonPath.<List<Number>>read(grades, "$[?(@.code=='G1B')].id").getFirst().longValue();
        mvc.perform(json(post("/api/users/" + id + "/contracts").session(hr), """
                        {"contractType":"PERMANENT","startDate":"2010-01-01","gradeId":%d,"expatriate":false}""".formatted(grade)))
                .andExpect(status().isCreated());
        mvc.perform(json(put("/api/users/" + id + "/profile").session(hr), """
                        {"maritalStatus":"SINGLE","paymentMethod":"BANK","bankName":"Rawbank","bankAccount":"1111-2222"}"""))
                .andExpect(status().isOk());
        mvc.perform(post("/api/users/" + id + "/disable").session(hr).with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());

        // Left five years ago: still kept for ten.
        db(() -> jdbc.sql("update employee_contract set end_date = current_date - interval '5 years' where user_id = ?").param(id).update());
        mvc.perform(post("/api/privacy/erase/" + id).session(hr).with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isUnprocessableContent());

        db(() -> jdbc.sql("update employee_contract set end_date = '2014-12-31' where user_id = ?").param(id).update());
        mvc.perform(get("/api/privacy/retention").session(hr))
                .andExpect(jsonPath("$.eraseAfterYears").value(10))
                .andExpect(jsonPath("$.due[?(@.userId == " + id + ")].leftOn").value("2014-12-31"));
        mvc.perform(post("/api/privacy/erase/" + id).session(hr).with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());
        String name = db(() -> jdbc.sql("select first_name || ' ' || email from app_user where id = ?").param(id).query(String.class).single());
        assertThat(name).isEqualTo("Erased erased-" + id + "@erased.invalid");
        long profiles = db(() -> jdbc.sql("select count(*) from employee_profile where user_id = ?").param(id).query(Long.class).single());
        assertThat(profiles).isZero();
        long contracts = db(() -> jdbc.sql("select count(*) from employee_contract where user_id = ?").param(id).query(Long.class).single());
        assertThat(contracts).isEqualTo(1);
    }

    @Test
    void theAuditLogIsChained() throws Exception {
        login(FINANCE);
        AuditChain.Check c = chain.check();
        assertThat(c.intact()).isTrue();
        assertThat(c.rows()).isPositive();
        assertThat(c.lastHash()).hasSize(64);
        List<Long> gaps = tenants.callAsPlatform(() -> jdbc.sql("""
                select chain_seq from (select chain_seq, lag(chain_seq) over (order by chain_seq) as before from audit_log) x
                where before is not null and chain_seq <> before + 1""").query(Long.class).list());
        assertThat(gaps).isEmpty();
        mvc.perform(get("/api/system/status").session(login(ADMIN)))
                .andExpect(jsonPath("$.dataKey").value(true))
                .andExpect(jsonPath("$.auditChain.intact").value(true));
    }

    @Test
    void theCompanySetsItsMicrosoftTenant() throws Exception {
        MockHttpSession admin = login(ADMIN);
        mvc.perform(get("/api/company/sign-in").session(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.microsoftAvailable").value(false));
        mvc.perform(json(put("/api/company/sign-in").session(admin), "{\"microsoftTenant\":\"not-a-tenant\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/company/sign-in").session(admin), "{\"microsoftTenant\":\"72F988BF-86F1-41AF-91AB-2D7CD011DB47\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.microsoftTenant").value("72f988bf-86f1-41af-91ab-2d7cd011db47"));
        mvc.perform(json(put("/api/company/sign-in").session(admin), "{\"microsoftTenant\":\"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.microsoftTenant").doesNotExist());
        mvc.perform(get("/api/company/sign-in").session(login(HR))).andExpect(status().isForbidden());
    }
}
