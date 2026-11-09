package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.tenancy.Tenants;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

class SectorPacksIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Tenants tenants;

    @Test
    void theCompanySeesThePacksOfItsCountryAndPreviewsTheInterprofessionalOne() throws Exception {
        MockHttpSession hr = login(HR);
        mvc.perform(get("/api/sector-packs").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.country").value("CD"))
                .andExpect(jsonPath("$.packs[?(@.countryCode != 'CD')]").isEmpty())
                .andExpect(jsonPath("$.packs[?(@.code == 'CD_MINING')].status").value(org.hamcrest.Matchers.contains("FRAME")));
        mvc.perform(get("/api/sector-packs/CD_INTERPRO/preview?from=2031-01-01").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[?(@.key == 'FAMILY_DEATH_CLOSE')].current").value(org.hamcrest.Matchers.contains("4")))
                .andExpect(jsonPath("$.lines[?(@.key == 'FAMILY_DEATH_CLOSE')].next").value(org.hamcrest.Matchers.contains("6")))
                .andExpect(jsonPath("$.lines[?(@.key == 'WEEKDAY')].next").value(org.hamcrest.Matchers.contains("0–2 h ×1.3 · 2+ h ×1.6")))
                .andExpect(jsonPath("$.lines[?(@.kind == 'NOTE')].applies").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(false))));
        // A frame has no rules yet; another country's pack is not for this company.
        mvc.perform(get("/api/sector-packs/CD_MINING/preview").session(hr)).andExpect(status().is(422));
        mvc.perform(get("/api/sector-packs/CG_OIL/preview").session(hr)).andExpect(status().is(422));
        mvc.perform(get("/api/sector-packs").session(login(EMPLOYEE))).andExpect(status().isForbidden());
        mvc.perform(get("/api/public/sector-packs?country=cg")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.countryCode != 'CG')]").isEmpty());
    }

    @Test
    void applyingThePackSetsLeaveOvertimeAndSettingsAndSectorChangesReachItsCompanies() throws Exception {
        String email = "patron." + System.nanoTime() % 100000 + "@kinshasa.cd";
        String response = mvc.perform(json(post("/api/platform/companies").session(login(ADMIN)), """
                        {"name":"Kin Services","countryCode":"CD","planCode":"UP_TO_50","modules":["ATTENDANCE","PAYROLL"],
                         "admin":{"firstName":"Jean","lastName":"Kabeya","email":"%s","phone":"+2430815%05d"}}"""
                        .formatted(email, (int) (System.nanoTime() % 100000))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(response, "$.company.id")).longValue();
        MockHttpSession boss = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(boss).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, JsonPath.read(response, "$.temporaryPassword"))))
                .andExpect(status().isOk());

        tenants.runAs(id, () -> assertThat(jdbc.sql("select count(*) from pay_element where code in ('SENIORITY_BONUS', 'END_OF_CAREER')")
                .query(Integer.class).single()).isEqualTo(2));

        mvc.perform(json(post("/api/sector-packs/CD_INTERPRO/apply").session(boss), "{\"from\":\"2031-01-01\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changes").value(0));
        mvc.perform(get("/api/sector-packs").session(boss)).andExpect(jsonPath("$.current").value("CD_INTERPRO"));
        tenants.runAs(id, () -> {
            assertThat(jdbc.sql("select max_days from leave_type where code = 'FAMILY_DEATH_CLOSE'").query(Integer.class).single()).isEqualTo(6);
            assertThat(jdbc.sql("select max_days from leave_type where code = 'FAMILY_MARRIAGE'").query(Integer.class).single()).isEqualTo(3);
            assertThat(jdbc.sql("select value from payroll_setting where key = 'LEAVE_SENIORITY_DAYS'").query(String.class).single()).isEqualTo("2");
            assertThat(jdbc.sql("select value from payroll_setting where key = 'LEAVE_CIRCUMSTANCE_YEAR_MAX'").query(String.class).single()).isEqualTo("18");
            List<String> tiers = jdbc.sql("""
                    select t.kind || ':' || t.from_hour || '-' || coalesce(t.to_hour::text, '') || ':' || t.multiplier
                    from overtime_rate_tier t join overtime_policy p on p.id = t.policy_id
                    where p.effective_from = '2031-01-01' order by t.kind, t.from_hour""").query(String.class).list();
            assertThat(tiers).containsExactly("HOLIDAY:0-:2.00", "REST_DAY:0-:2.00", "WEEKDAY:0-2:1.30", "WEEKDAY:2-:1.60");
            // The rates before that day stay as they were.
            assertThat(jdbc.sql("select count(*) from overtime_policy").query(Integer.class).single()).isEqualTo(2);
        });

        // A change for this sector only: its rate reaches the companies on the pack, not the others.
        MockHttpSession provider = login(ADMIN);
        String created = mvc.perform(json(post("/api/platform/changes").session(provider), """
                        {"countryCode":"CD","effectiveFrom":"2034-01-01","category":"SOCIAL","titleEn":"Sector test","titleFr":"Test de branche",
                         "summaryEn":"Test.","summaryFr":"Test.","reference":"Avenant de test","inApp":"APPLIED","sectorPackCode":"CD_INTERPRO",
                         "rates":[{"code":"INPP_LARGE","effectiveFrom":"2034-01-01","value":0.8}]}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sectorNameEn").value("Interprofessional agreement (all sectors)"))
                .andReturn().getResponse().getContentAsString();
        int change = JsonPath.read(created, "$.id");
        mvc.perform(post("/api/platform/changes/" + change + "/publish").session(provider).with(csrf()))
                .andExpect(jsonPath("$.appliedTo").value(1));
        tenants.runAs(id, () -> {
            assertThat(jdbc.sql("select value from statutory_rate where code = 'INPP_LARGE' and effective_from = '2034-01-01'")
                    .query(BigDecimal.class).single()).isEqualByComparingTo("0.8");
            assertThat(jdbc.sql("select count(*) from notification where kind = 'law.sector'").query(Integer.class).single()).isPositive();
        });
        tenants.runAs(1L, () -> assertThat(jdbc.sql("select count(*) from statutory_rate where code = 'INPP_LARGE' and effective_from = '2034-01-01'")
                .query(Integer.class).single()).isZero());
        mvc.perform(get("/api/changes/recent").session(login(HR)))
                .andExpect(jsonPath("$[?(@.id == %d)]".formatted(change)).isEmpty());
        mvc.perform(get("/api/changes/recent").session(boss))
                .andExpect(jsonPath("$[?(@.id == %d)]".formatted(change)).isNotEmpty());
    }

    @Test
    void theProviderWritesPacks() throws Exception {
        MockHttpSession provider = login(ADMIN);
        String body = """
                {"countryCode":"CD","nameEn":"Test sector","nameFr":"Branche de test","status":"%s","reference":%s,
                 "rules":[{"kind":"SETTING","key":"SENIORITY_BONUS_PERCENT_PER_YEAR","value":"%s","labelEn":"Seniority","labelFr":"Ancienneté"},
                          {"kind":"OVERTIME","key":"WEEKDAY","fromHour":0,"toHour":4,"value":"1.25","labelEn":"OT","labelFr":"HS"},
                          {"kind":"OVERTIME","key":"WEEKDAY","fromHour":4,"value":"1.5","labelEn":"OT","labelFr":"HS"}]}""";
        mvc.perform(json(put("/api/platform/sector-packs/TEST_SECTOR").session(login(HR)), body.formatted("FRAME", "null", "1")))
                .andExpect(status().isForbidden());
        mvc.perform(json(put("/api/platform/sector-packs/TEST_SECTOR").session(provider), body.formatted("READY", "null", "1")))
                .andExpect(status().is(422));
        mvc.perform(json(put("/api/platform/sector-packs/TEST_SECTOR").session(provider), body.formatted("READY", "\"Convention\"", "x")))
                .andExpect(status().is(422));
        mvc.perform(json(put("/api/platform/sector-packs/TEST_SECTOR").session(provider), body.formatted("READY", "\"Convention\"", "1.5")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rules.length()").value(3))
                .andExpect(jsonPath("$.rules[0].position").value(10));
        mvc.perform(get("/api/sector-packs/TEST_SECTOR/preview?from=2031-02-01").session(login(HR))).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].next").value("1.5"));
        mvc.perform(delete("/api/platform/sector-packs/TEST_SECTOR").session(provider).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/platform/sector-packs").session(provider))
                .andExpect(jsonPath("$[?(@.code == 'TEST_SECTOR')]").isEmpty())
                .andExpect(jsonPath("$[?(@.code == 'CD_INTERPRO')].rules.length()").value(org.hamcrest.Matchers.contains(17)));
    }
}
