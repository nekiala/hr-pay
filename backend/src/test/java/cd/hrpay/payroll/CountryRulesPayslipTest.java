package cd.hrpay.payroll;

import static cd.hrpay.payroll.PayslipCalculatorTest.d;
import static cd.hrpay.payroll.PayslipCalculatorTest.el;
import static cd.hrpay.payroll.PayslipCalculatorTest.line;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Payslips of countries whose rules are data: Cameroon and Gabon, with the packs' sample rates. */
class CountryRulesPayslipTest {

    static final List<PayElements.Element> BASE = List.of(
            el(1, "BASIC", "EARNING", "COMPUTED", null, null, true, true, true, false, 10),
            el(2, "OVERTIME", "EARNING", "COMPUTED", null, null, true, true, false, false, 20),
            el(10, "IPR", "DEDUCTION", "COMPUTED", null, null, false, false, false, false, 120));

    static PayslipCalculator.Input input(String basic, int children, int dependants, String marital, PayslipCalculator.Rates rates) {
        return new PayslipCalculator.Input("XAF", d(basic), 10560, 10560, 22, 22, BigDecimal.ZERO, 0, null, null,
                children, dependants, false, BASE, List.of(), List.of(), List.of(), rates, 0, null, marital);
    }

    static CountryRules.Row row(String code, String side, String base, String rate, String ceiling, String exempt,
                                String scale, boolean beforeTax) {
        return new CountryRules.Row(code, side, base, rate, ceiling, exempt, scale, beforeTax, 0);
    }

    // ---- Cameroon --------------------------------------------------------------------------------------------

    static final Map<String, BigDecimal> CM_RATES = Map.ofEntries(Map.entry("CNSS_EMPLOYEE", d("4.2")),
            Map.entry("CNSS_EMPLOYER", d("4.2")), Map.entry("CNSS_CEILING", d("750000")), Map.entry("CNSS_FAMILY", d("7")),
            Map.entry("CNSS_ACCIDENT", d("1.75")), Map.entry("CFC_EMPLOYEE", d("1")), Map.entry("CFC_EMPLOYER", d("1.5")),
            Map.entry("FNE", d("1")), Map.entry("CAC", d("10")), Map.entry("TAX_PROFESSIONAL_DEDUCTION", d("30")),
            Map.entry("TAX_YEARLY_ALLOWANCE", d("500000")), Map.entry("TAX_EXEMPT_BELOW", d("62000")),
            Map.entry("SMIG_MONTHLY", d("41875")));

    static final List<CountryRules.Band> RAV = List.of(new CountryRules.Band(d("0"), d("50000"), d("0")),
            new CountryRules.Band(d("50000"), d("100000"), d("750")), new CountryRules.Band(d("100000"), d("200000"), d("1950")),
            new CountryRules.Band(d("200000"), d("300000"), d("3250")), new CountryRules.Band(d("300000"), null, d("4550")));
    static final List<CountryRules.Band> TDL = List.of(new CountryRules.Band(d("0"), d("62000"), d("0")),
            new CountryRules.Band(d("250000"), d("300000"), d("2000")), new CountryRules.Band(d("500000"), null, d("2500")));

    static final CountryRules CAMEROON = CountryRules.of(List.of(
                    row("CNSS_EMPLOYEE", "EMPLOYEE", "CNSS", "CNSS_EMPLOYEE", "CNSS_CEILING", null, null, true),
                    row("CFC_EMPLOYEE", "EMPLOYEE", "TAXABLE", "CFC_EMPLOYEE", null, null, null, false),
                    row("RAV", "EMPLOYEE", "TAXABLE", null, null, null, "RAV", false),
                    row("TDL", "EMPLOYEE", "BASIC", null, null, null, "TDL", false),
                    row("CAC", "EMPLOYEE", "TAX", "CAC", null, null, null, false),
                    row("CNSS_EMPLOYER", "EMPLOYER", "CNSS", "CNSS_EMPLOYER", "CNSS_CEILING", null, null, false),
                    row("CNSS_EMPLOYER_FAMILY", "EMPLOYER", "CNSS", "CNSS_FAMILY", "CNSS_CEILING", null, null, false),
                    row("CNSS_EMPLOYER_ACCIDENT", "EMPLOYER", "CNSS", "CNSS_ACCIDENT", "CNSS_CEILING", null, null, false),
                    row("CFC_EMPLOYER", "EMPLOYER", "TAXABLE", "CFC_EMPLOYER", null, null, null, false),
                    row("FNE", "EMPLOYER", "TAXABLE", "FNE", null, null, null, false)),
            CM_RATES::get, code -> code.equals("RAV") ? RAV : TDL);

    static final List<IprCalculator.Bracket> CM_BANDS = List.of(
            new IprCalculator.Bracket(d("0"), d("2000000"), d("10")),
            new IprCalculator.Bracket(d("2000000"), d("3000000"), d("15")),
            new IprCalculator.Bracket(d("3000000"), d("5000000"), d("25")),
            new IprCalculator.Bracket(d("5000000"), null, d("35")));

    static final PayslipCalculator.Rates CM = PayslipCalculator.Rates.of(d("600"), "CM", "XAF", d("30"), CM_BANDS, CAMEROON);

    @Test
    void cameroonWithholdsCnpsIrppCacCfcRavAndTdl() {
        var r = PayslipCalculator.calculate(input("300000", 0, 0, null, CM));
        assertThat(line(r, "CNSS_EMPLOYEE")).isEqualByComparingTo("12600");    // 4.2%
        assertThat(line(r, "CFC_EMPLOYEE")).isEqualByComparingTo("3000");      // 1% of the taxable pay
        assertThat(line(r, "RAV")).isEqualByComparingTo("3250");               // band 200,000 to 300,000
        assertThat(line(r, "TDL")).isEqualByComparingTo("2000");               // band 250,000 to 300,000 of the basic
        // (300,000 − 12,600) × 12 = 3,448,800; less 30% = 2,414,160; less 500,000 = 1,914,160; at 10% = 191,416 a year.
        assertThat(r.taxableBase()).isEqualByComparingTo("287400");
        assertThat(line(r, "IPR")).isEqualByComparingTo("15951.33");
        assertThat(line(r, "CAC")).isEqualByComparingTo("1595.13");            // 10% of the IRPP
        assertThat(r.employeeDeductions()).isEqualByComparingTo("38396.46");
        assertThat(r.net()).isEqualByComparingTo("261603.54");
        // Employer: pension 12,600, family 21,000, accidents 5,250, crédit foncier 4,500, FNE 3,000.
        assertThat(r.employerCharges()).isEqualByComparingTo("46350");
        assertThat(r.lines()).noneMatch(l -> l.code().equals("INPP") || l.code().equals("TUS"));
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void cameroonStopsCnpsAtTheCeilingAndExemptsLowPay() {
        var high = PayslipCalculator.calculate(input("1000000", 0, 0, null, CM));
        assertThat(line(high, "CNSS_EMPLOYEE")).isEqualByComparingTo("31500");        // 4.2% of 750,000
        assertThat(line(high, "CNSS_EMPLOYER_FAMILY")).isEqualByComparingTo("52500"); // 7% of 750,000
        assertThat(line(high, "FNE")).isEqualByComparingTo("10000");                  // no ceiling

        var low = PayslipCalculator.calculate(input("40000", 0, 0, null, CM));
        assertThat(line(low, "IPR")).isEqualByComparingTo("0");            // below 62,000 a month
        assertThat(low.lines()).noneMatch(l -> l.code().equals("CAC") && l.amount().signum() > 0);
        assertThat(low.lines()).noneMatch(l -> l.code().equals("RAV") || l.code().equals("TDL"));
        assertThat(low.warnings()).anyMatch(w -> w.contains("minimum wage"));   // below 41,875
    }

    // ---- Gabon -----------------------------------------------------------------------------------------------

    static final Map<String, BigDecimal> GA_RATES = Map.ofEntries(Map.entry("CNSS_EMPLOYEE", d("2.5")),
            Map.entry("CNSS_EMPLOYER", d("5")), Map.entry("CNSS_CEILING", d("1500000")), Map.entry("CNSS_FAMILY", d("8")),
            Map.entry("CNSS_ACCIDENT", d("3")), Map.entry("CNAMGS_EMPLOYEE", d("2")), Map.entry("CNAMGS_EMPLOYER", d("4.1")),
            Map.entry("CNAMGS_CEILING", d("2500000")), Map.entry("TCS", d("5")), Map.entry("TCS_THRESHOLD", d("150000")),
            Map.entry("FNH", d("2")), Map.entry("CFP", d("0.5")), Map.entry("TAX_PROFESSIONAL_DEDUCTION", d("20")),
            Map.entry("TAX_MAX_PARTS", d("6")), Map.entry("SMIG_MONTHLY", d("150000")));

    static final CountryRules GABON = CountryRules.of(List.of(
                    row("CNSS_EMPLOYEE", "EMPLOYEE", "CNSS", "CNSS_EMPLOYEE", "CNSS_CEILING", null, null, true),
                    row("CNAMGS_EMPLOYEE", "EMPLOYEE", "CNSS", "CNAMGS_EMPLOYEE", "CNAMGS_CEILING", null, null, true),
                    row("TCS", "EMPLOYEE", "NET_TAXABLE", "TCS", null, "TCS_THRESHOLD", null, false),
                    row("CNSS_EMPLOYER", "EMPLOYER", "CNSS", "CNSS_EMPLOYER", "CNSS_CEILING", null, null, false),
                    row("CNSS_EMPLOYER_FAMILY", "EMPLOYER", "CNSS", "CNSS_FAMILY", "CNSS_CEILING", null, null, false),
                    row("CNSS_EMPLOYER_ACCIDENT", "EMPLOYER", "CNSS", "CNSS_ACCIDENT", "CNSS_CEILING", null, null, false),
                    row("CNAMGS_EMPLOYER", "EMPLOYER", "CNSS", "CNAMGS_EMPLOYER", "CNAMGS_CEILING", null, null, false),
                    row("FNH", "EMPLOYER", "GROSS", "FNH", null, null, null, false),
                    row("CFP", "EMPLOYER", "GROSS", "CFP", null, null, null, false)),
            GA_RATES::get, code -> List.of());

    static final List<IprCalculator.Bracket> GA_BANDS = List.of(
            new IprCalculator.Bracket(d("0"), d("1500000"), d("0")),
            new IprCalculator.Bracket(d("1500000"), d("1920000"), d("5")),
            new IprCalculator.Bracket(d("1920000"), d("2700000"), d("10")),
            new IprCalculator.Bracket(d("2700000"), null, d("15")));

    static final PayslipCalculator.Rates GA = PayslipCalculator.Rates.of(d("600"), "GA", "XAF", d("30"), GA_BANDS, GABON);

    @Test
    void gabonDividesTheIrppByFamilyPartsAndChargesTcsAboveTheThreshold() {
        var r = PayslipCalculator.calculate(input("600000", 2, 3, "MARRIED", GA));
        assertThat(line(r, "CNSS_EMPLOYEE")).isEqualByComparingTo("15000");    // 2.5%
        assertThat(line(r, "CNAMGS_EMPLOYEE")).isEqualByComparingTo("12000");  // 2%
        assertThat(r.taxableBase()).isEqualByComparingTo("573000");
        // 573,000 × 12 = 6,876,000; less 20% = 5,500,800; 3 parts: 1,833,600 a part; 5% of 333,600 = 16,680; × 3 ÷ 12.
        assertThat(line(r, "IPR")).isEqualByComparingTo("4170");
        assertThat(line(r, "TCS")).isEqualByComparingTo("21150");              // 5% of (573,000 − 150,000)
        assertThat(r.net()).isEqualByComparingTo("547680");
        // Employer: CNSS 30,000 + 48,000 + 18,000, CNAMGS 24,600, FNH 12,000, training 3,000.
        assertThat(r.employerCharges()).isEqualByComparingTo("135600");

        // Single, the same pay: one part, more tax.
        var single = PayslipCalculator.calculate(input("600000", 0, 0, "SINGLE", GA));
        assertThat(line(single, "IPR")).isGreaterThan(line(r, "IPR"));
    }

    @Test
    void theDrcAndTheCongoHaveNoRulesAsData() {
        assertThat(PayslipCalculatorTest.RATES.rules()).isNull();
        assertThat(CongoPayslipTest.RATES.rules()).isNull();
    }
}
