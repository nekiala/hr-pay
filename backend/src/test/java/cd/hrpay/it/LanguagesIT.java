package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.i18n.Texts;
import cd.hrpay.payment.Declarations;
import cd.hrpay.payroll.ExchangeRates;
import cd.hrpay.tenancy.Tenants;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** French and English, per person and per company, and the Republic of the Congo's set-up. */
class LanguagesIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Tenants tenants;

    @Autowired
    Declarations declarations;

    @Autowired
    ExchangeRates rates;

    @Test
    void errorsComeInTheLanguageTheAppAsksFor() throws Exception {
        MockHttpSession hr = login(HR);
        mvc.perform(get("/api/users/999999").session(hr).header("Accept-Language", "fr"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Utilisateur introuvable."));
        mvc.perform(get("/api/users/999999").session(hr).header("Accept-Language", "en"))
                .andExpect(jsonPath("$.detail").value("User not found."));
        // A message built from a template is translated with its values.
        assertThat(Texts.in("fr", () -> Texts.translate("A role with code SITE_BOSS already exists.")))
                .isEqualTo("Un rôle avec le code SITE_BOSS existe déjà.");
        assertThat(Texts.in("fr", () -> Texts.t("The weights must add up to 100%% (now %s%%).", 90)))
                .isEqualTo("Les poids doivent totaliser 100 % (actuellement 90 %).");
        assertThat(Texts.in("en", () -> Texts.t("Choose a month."))).isEqualTo("Choose a month.");
    }

    @Test
    void eachPersonPicksALanguageOrFollowsTheCompany() throws Exception {
        MockHttpSession me = login(EMPLOYEE);
        mvc.perform(get("/api/auth/me").session(me))
                .andExpect(jsonPath("$.language").value("fr")).andExpect(jsonPath("$.ownLanguage").isEmpty())
                .andExpect(jsonPath("$.company.language").value("fr")).andExpect(jsonPath("$.company.country").value("CD"))
                .andExpect(jsonPath("$.company.localCurrency").value("CDF"));
        mvc.perform(json(put("/api/account/language").session(me), "{\"language\":\"de\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/account/language").session(me), "{\"language\":\"en\"}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(me))
                .andExpect(jsonPath("$.language").value("en")).andExpect(jsonPath("$.ownLanguage").value("en"));
        mvc.perform(json(put("/api/account/language").session(me), "{\"language\":null}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(me)).andExpect(jsonPath("$.language").value("fr"));

        // The company's language is HR's to set.
        mvc.perform(json(put("/api/company/language").session(me), "{\"language\":\"en\"}")).andExpect(status().isForbidden());
        MockHttpSession hr = login(HR);
        mvc.perform(json(put("/api/company/language").session(hr), "{\"language\":\"en\"}")).andExpect(status().is2xxSuccessful());
        try {
            mvc.perform(get("/api/auth/me").session(me)).andExpect(jsonPath("$.language").value("en"));
        } finally {
            mvc.perform(json(put("/api/company/language").session(hr), "{\"language\":\"fr\"}")).andExpect(status().is2xxSuccessful());
        }
    }

    @Test
    void aRepublicOfTheCongoCompanyStartsWithItsOwnRulesInFrench() throws Exception {
        String email = "patron." + System.nanoTime() % 100000 + "@brazza.cg";
        String response = mvc.perform(json(post("/api/platform/companies").session(login(ADMIN)), """
                        {"name":"Brazza Logistique","countryCode":"CG","planCode":"UP_TO_50","modules":["ATTENDANCE","PAYROLL"],
                         "admin":{"firstName":"Ines","lastName":"Mabiala","email":"%s","phone":"+2420655%05d"}}"""
                        .formatted(email, (int) (System.nanoTime() % 100000))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(response, "$.company.id")).longValue();
        MockHttpSession boss = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(boss).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, JsonPath.read(response, "$.temporaryPassword"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.company.country").value("CG"))
                .andExpect(jsonPath("$.company.localCurrency").value("XAF"))
                .andExpect(jsonPath("$.language").value("fr"));

        tenants.runAs(id, () -> {
            List<String> codes = jdbc.sql("select code from pay_element order by display_order").query(String.class).list();
            assertThat(codes).contains("CNSS_EMPLOYER_FAMILY", "CNSS_EMPLOYER_ACCIDENT", "TUS").doesNotContain("INPP", "ONEM", "IERE");
            assertThat(jdbc.sql("select name from pay_element where code = 'IPR'").query(String.class).single())
                    .startsWith("ITS");
            assertThat(jdbc.sql("select name from holiday_definition order by usual_month, usual_day").query(String.class).list())
                    .contains("Fête nationale", "Fête de la République").doesNotContain("Patrice Lumumba Day");
            assertThat(jdbc.sql("select distinct trim(currency) from grade").query(String.class).list()).containsExactly("XAF");
            assertThat(jdbc.sql("select count(*) from statutory_rate where note like '%to verify%'").query(Integer.class).single())
                    .isEqualTo(11);
            assertThat(declarations.kinds()).containsExactly("CNSS", "IPR", "TUS");
        });

        // One dollar in XAF, from the BCC's rates of both in CDF.
        tenants.callAsPlatform(() -> jdbc.sql("""
                insert into exchange_rate (currency, rate_date, cdf_per_unit, source, entered_by) values
                    ('XAF', date '2001-01-02', 4.5, 'test', 'test'), ('USD', date '2001-01-02', 2700, 'test', 'test')
                on conflict do nothing""").update());
        ExchangeRates.Rate xaf = rates.local("XAF", LocalDate.of(2001, 1, 2));
        assertThat(xaf.cdfPerUnit()).isEqualByComparingTo(new BigDecimal("600"));
        assertThat(rates.local("CDF", LocalDate.of(2001, 1, 2)).cdfPerUnit()).isEqualByComparingTo("2700");
    }
}
