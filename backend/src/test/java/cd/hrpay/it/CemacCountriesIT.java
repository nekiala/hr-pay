package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.calculator.PublicCalculator;
import cd.hrpay.payment.Declarations;
import cd.hrpay.payroll.CountryRules;
import cd.hrpay.payroll.ExchangeRates;
import cd.hrpay.payroll.PayElements;
import cd.hrpay.payroll.PayslipCalculator;
import cd.hrpay.payroll.StatutoryRates;
import cd.hrpay.platform.CountryPacks;
import cd.hrpay.tenancy.Tenants;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** Cameroon and Gabon: a company starts with its country's contributions, taxes and declarations. */
class CemacCountriesIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Tenants tenants;

    @Autowired
    Declarations declarations;

    @Autowired
    StatutoryRates statutory;

    @Autowired
    PayElements pay;

    @Autowired
    CountryPacks packs;

    @Autowired
    ExchangeRates exchangeRates;

    @Autowired
    Clock clock;

    private long company(String name, String country, String phone) throws Exception {
        String email = "patron." + System.nanoTime() % 100000 + "@" + country.toLowerCase() + ".test";
        String response = mvc.perform(json(post("/api/platform/companies").session(login(ADMIN)), """
                        {"name":"%s","countryCode":"%s","planCode":"UP_TO_50","modules":["ATTENDANCE","PAYROLL"],
                         "admin":{"firstName":"Awa","lastName":"Nguema","email":"%s","phone":"%s%05d"}}"""
                        .formatted(name, country, email, phone, (int) (System.nanoTime() % 100000))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        MockHttpSession boss = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(boss).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, JsonPath.read(response, "$.temporaryPassword"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.company.country").value(country))
                .andExpect(jsonPath("$.company.localCurrency").value("XAF"));
        return ((Number) JsonPath.read(response, "$.company.id")).longValue();
    }

    private PayslipCalculator.Result payslip(String basic, int children, int dependants, String marital, String country) {
        LocalDate on = LocalDate.now(clock);
        CountryRules rules = statutory.countryRules(on);
        var rates = PayslipCalculator.Rates.of(BigDecimal.valueOf(600), country, "XAF", statutory.rate("DEBT_MAX_SHARE", on),
                statutory.brackets(on).brackets(), rules);
        var input = new PayslipCalculator.Input("XAF", new BigDecimal(basic), 10560, 10560, 22, 22, BigDecimal.ZERO, 0,
                null, null, children, dependants, false, basicOnly(), List.of(), List.of(), List.of(), rates, 0, null, marital);
        return PayslipCalculator.calculate(input);
    }

    /** The pack's elements without the allowances given to everyone, so the basic salary is the whole pay. */
    private List<PayElements.Element> basicOnly() {
        return pay.elements().stream().filter(e -> !e.defaultForAll()).toList();
    }

    private static BigDecimal line(PayslipCalculator.Result r, String code) {
        return r.lines().stream().filter(l -> l.code().equals(code)).map(PayslipCalculator.Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void aCameroonCompanyStartsWithCnpsIrppAndItsLocalTaxes() throws Exception {
        long id = company("Douala Services", "CM", "+2376");
        tenants.runAs(id, () -> {
            assertThat(jdbc.sql("select code from pay_element").query(String.class).list())
                    .contains("CAC", "CFC_EMPLOYEE", "CFC_EMPLOYER", "FNE", "RAV", "TDL").doesNotContain("INPP", "ONEM", "TUS");
            assertThat(jdbc.sql("select name from pay_element where code = 'IPR'").query(String.class).single()).startsWith("IRPP");
            assertThat(jdbc.sql("select count(*) from payroll_contribution").query(Integer.class).single()).isEqualTo(10);
            assertThat(declarations.kinds()).containsExactly("CNSS", "IPR", "CAC", "CFC", "FNE", "RAV", "TDL");
            assertThat(jdbc.sql("select count(*) from declaration_rule").query(Integer.class).single()).isEqualTo(7);
            assertThat(jdbc.sql("select distinct trim(currency) from grade").query(String.class).list()).containsExactly("XAF");

            // The rules read back from the company give the payslip of the pack's sample rates.
            var r = payslip("300000", 0, 0, null, "CM");
            assertThat(line(r, "CNSS_EMPLOYEE")).isEqualByComparingTo("12600");
            assertThat(line(r, "IPR")).isEqualByComparingTo("15951.33");
            assertThat(line(r, "CAC")).isEqualByComparingTo("1595.13");
            assertThat(line(r, "RAV")).isEqualByComparingTo("3250");
            assertThat(line(r, "TDL")).isEqualByComparingTo("2000");
            assertThat(r.net()).isEqualByComparingTo("261603.54");
            assertThat(r.employerCharges()).isEqualByComparingTo("46350");
        });
    }

    @Test
    void aGabonCompanyStartsWithCnssCnamgsIrppByPartsAndTcs() throws Exception {
        long id = company("Libreville Conseil", "GA", "+2410");
        tenants.runAs(id, () -> {
            assertThat(jdbc.sql("select code from pay_element").query(String.class).list())
                    .contains("CNAMGS_EMPLOYEE", "CNAMGS_EMPLOYER", "TCS", "FNH", "CFP");
            assertThat(declarations.kinds()).containsExactly("CNSS", "CNAMGS", "IPR", "TCS", "FNH", "CFP");
            assertThat(jdbc.sql("select name from holiday_definition where usual_month = 8 order by usual_day").query(String.class).list())
                    .hasSize(3);
            var r = payslip("600000", 2, 3, "MARRIED", "GA");
            assertThat(line(r, "CNAMGS_EMPLOYEE")).isEqualByComparingTo("12000");
            assertThat(line(r, "IPR")).isEqualByComparingTo("4170");
            assertThat(line(r, "TCS")).isEqualByComparingTo("21150");
            assertThat(r.net()).isEqualByComparingTo("547680");
        });
    }

    @Test
    void thePublicCalculatorWorksForCameroonOnceOpened() {
        var calculator = new PublicCalculator(packs, exchangeRates, tenants, clock, List.of("CM", "GA"));
        var cm = calculator.calculate(new PublicCalculator.Ask("CM", PublicCalculator.Mode.GROSS, new BigDecimal("300000"),
                "XAF", null, null, null, null, 0, null, false, 10, false));
        assertThat(cm.net()).isEqualByComparingTo("261603.54");
        assertThat(cm.sources().stream().map(PublicCalculator.Source::code).toList()).contains("CNSS_EMPLOYEE", "CAC", "SMIG_MONTHLY");
        assertThat(cm.minimum().below()).isFalse();

        // From a wanted net back to a basic salary that gives at least it (the RAV and TDL bands make steps).
        var back = calculator.calculate(new PublicCalculator.Ask("CM", PublicCalculator.Mode.NET, new BigDecimal("250000"), "XAF",
                null, null, null, null, 0, null, false, 10, false));
        assertThat(back.net()).isGreaterThanOrEqualTo(new BigDecimal("250000")).isLessThan(new BigDecimal("251500"));

        var ga = calculator.calculate(new PublicCalculator.Ask("GA", PublicCalculator.Mode.GROSS, new BigDecimal("600000"),
                "XAF", null, null, null, null, 2, "MARRIED", true, 10, false));
        assertThat(ga.net()).isEqualByComparingTo("547680");
    }
}
