package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.tenancy.Tenants;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

class LawChangesIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Tenants tenants;

    private static final String DRAFT = """
            {"countryCode":"CD","effectiveFrom":"2033-01-01","category":"SOCIAL","titleEn":"INPP test rate","titleFr":"Taux INPP de test",
             "summaryEn":"Test.","summaryFr":"Test.","reference":"Arrêté de test","sourceUrl":"https://example.org/test","inApp":"APPLIED",
             "rates":[{"code":"INPP_LARGE","effectiveFrom":"2033-01-01","value":%s}]}""";

    @Test
    void thePublicSeesPublishedChangesOnly() throws Exception {
        String list = mvc.perform(get("/api/public/changes")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 3)].reference").value(org.hamcrest.Matchers.contains(
                        org.hamcrest.Matchers.startsWith("Arrêté n° 028"))))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<java.util.List<Object>>read(list, "$[?(@.status != 'PUBLISHED')]")).isEmpty();
        mvc.perform(get("/api/public/changes?country=cg")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.countryCode != 'CG')]").isEmpty())
                .andExpect(jsonPath("$[?(@.id == 10)]").isEmpty());
        mvc.perform(get("/api/public/changes.rss?lang=en")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<title>CD · ONEM: 0.5 % of gross pay</title>")));
        // The seeded rates know their change.
        Long change = tenants.callAs(1L, () -> jdbc.sql("select law_change_id from statutory_rate where code = 'ONEM' and effective_from = '2025-10-01'")
                .query(Long.class).single());
        assertThat(change).isEqualTo(3L);
    }

    @Test
    void publishingAddsTheRatesToTheCompaniesOfTheCountry() throws Exception {
        MockHttpSession provider = login(ADMIN);
        mvc.perform(json(post("/api/platform/changes").session(login(HR)), DRAFT.formatted("0.9"))).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/platform/changes").session(provider), DRAFT.formatted("0.9").replace("https://example.org/test", "http://x")))
                .andExpect(status().is(422));
        String created = mvc.perform(json(post("/api/platform/changes").session(provider), DRAFT.formatted("0.9")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(created, "$.id");
        mvc.perform(get("/api/public/changes")).andExpect(jsonPath("$[?(@.id == %d)]".formatted(id)).isEmpty());

        mvc.perform(post("/api/platform/changes/" + id + "/publish").session(provider).with(csrf()))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.appliedTo").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        tenants.runAs(1L, () -> {
            var row = jdbc.sql("select value, law_change_id from statutory_rate where code = 'INPP_LARGE' and effective_from = '2033-01-01'")
                    .query((rs, i) -> new Object[] {rs.getBigDecimal(1), rs.getLong(2)}).single();
            assertThat((java.math.BigDecimal) row[0]).isEqualByComparingTo("0.9");
            assertThat(row[1]).isEqualTo((long) id);
            assertThat(jdbc.sql("select count(*) from notification where kind = 'law.change'").query(Integer.class).single()).isPositive();
        });
        mvc.perform(get("/api/public/changes")).andExpect(jsonPath("$[?(@.id == %d)]".formatted(id)).isNotEmpty());
        mvc.perform(get("/api/changes/recent").session(login(HR))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)]".formatted(id)).isNotEmpty());

        // Published: words may change, rates may not; it is withdrawn, not deleted.
        mvc.perform(json(put("/api/platform/changes/" + id).session(provider), DRAFT.formatted("1.1"))).andExpect(status().is(422));
        mvc.perform(json(put("/api/platform/changes/" + id).session(provider), DRAFT.formatted("0.9").replace("INPP test rate", "INPP rate")))
                .andExpect(jsonPath("$.titleEn").value("INPP rate"));
        mvc.perform(delete("/api/platform/changes/" + id).session(provider).with(csrf())).andExpect(status().is(422));
        mvc.perform(post("/api/platform/changes/" + id + "/withdraw").session(provider).with(csrf()))
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
        mvc.perform(get("/api/public/changes")).andExpect(jsonPath("$[?(@.id == %d)]".formatted(id)).isEmpty());
        mvc.perform(get("/api/platform/changes").session(provider)).andExpect(jsonPath("$[?(@.id == 10)].status").value(
                org.hamcrest.Matchers.contains("DRAFT")));
    }
}
