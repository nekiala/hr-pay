package cd.hrpay.payroll;

import static cd.hrpay.payroll.PayslipCalculatorTest.d;
import static cd.hrpay.payroll.PayslipCalculatorTest.el;
import static cd.hrpay.payroll.PayslipCalculatorTest.line;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Republic of the Congo's payslip: CNSS with its ceilings, ITS with the family quotient, the TUS. Sample rates. */
class CongoPayslipTest {

    static final List<IprCalculator.Bracket> ITS_BANDS = List.of(
            new IprCalculator.Bracket(d("0"), d("464000"), d("1")),
            new IprCalculator.Bracket(d("464000"), d("1000000"), d("10")),
            new IprCalculator.Bracket(d("1000000"), d("3000000"), d("25")),
            new IprCalculator.Bracket(d("3000000"), null, d("40")));

    static final List<PayElements.Element> ELEMENTS = List.of(
            el(1, "BASIC", "EARNING", "COMPUTED", null, null, true, true, true, false, 10),
            el(2, "OVERTIME", "EARNING", "COMPUTED", null, null, true, true, false, false, 20),
            el(9, "CNSS_EMPLOYEE", "DEDUCTION", "COMPUTED", null, null, false, false, false, false, 110),
            el(10, "IPR", "DEDUCTION", "COMPUTED", null, null, false, false, false, false, 120),
            el(14, "CNSS_EMPLOYER", "EMPLOYER_CHARGE", "COMPUTED", null, null, false, false, false, false, 210),
            el(21, "CNSS_EMPLOYER_FAMILY", "EMPLOYER_CHARGE", "COMPUTED", null, null, false, false, false, false, 220),
            el(22, "CNSS_EMPLOYER_ACCIDENT", "EMPLOYER_CHARGE", "COMPUTED", null, null, false, false, false, false, 225),
            el(23, "TUS", "EMPLOYER_CHARGE", "COMPUTED", null, null, false, false, false, false, 230));

    static final PayslipCalculator.Rates RATES = new PayslipCalculator.Rates(d("600"), d("4"), d("8"), BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, d("30"), ITS_BANDS, BigDecimal.ZERO, 0, d("100"), null, "CG", "XAF",
            Map.of("CNSS_CEILING", d("1200000"), "CNSS_FAMILY", d("10.03"), "CNSS_ACCIDENT", d("2.25"),
                    "CNSS_FAMILY_CEILING", d("600000"), "TUS", d("7.5"), "ITS_PROFESSIONAL_DEDUCTION", d("20"),
                    "ITS_MAX_PARTS", d("6.5"), "SMIG_MONTHLY", d("90000")));

    static PayslipCalculator.Input input(String basic, int children, int dependants) {
        return new PayslipCalculator.Input("XAF", d(basic), 10560, 10560, 22, 22, BigDecimal.ZERO, 0, null, null,
                children, dependants, false, ELEMENTS, List.of(), List.of(), List.of(), RATES);
    }

    @Test
    void aMarriedEmployeeWithTwoChildrenAboveTheCeilings() {
        var r = PayslipCalculator.calculate(input("1500000", 2, 3));
        // Pensions stop at 1,200,000: 4% = 48,000.
        assertThat(line(r, "CNSS_EMPLOYEE")).isEqualByComparingTo("48000");
        assertThat(r.taxableBaseCdf()).isEqualByComparingTo("1452000");
        // 17,424,000 a year, less 20% = 13,939,200, rounded down to 13,939,000, over 3 parts = 4,646,333.33 a part:
        // 4,640 + 53,600 + 500,000 + 658,533.33 = 1,216,773.33 a part, x 3 = 3,650,320 a year, 304,193.33 a month.
        assertThat(line(r, "IPR")).isEqualByComparingTo("304193.33");
        assertThat(line(r, "CNSS_EMPLOYER")).isEqualByComparingTo("96000");          // 8% of 1,200,000
        assertThat(line(r, "CNSS_EMPLOYER_FAMILY")).isEqualByComparingTo("60180");   // 10.03% of 600,000
        assertThat(line(r, "CNSS_EMPLOYER_ACCIDENT")).isEqualByComparingTo("13500"); // 2.25% of 600,000
        assertThat(line(r, "TUS")).isEqualByComparingTo("112500");                  // 7.5% of the gross
        assertThat(r.employerCharges()).isEqualByComparingTo("282180");
        assertThat(r.net()).isEqualByComparingTo("1147806.67");
        assertThat(r.lines()).noneMatch(l -> l.code().equals("INPP") || l.code().equals("ONEM"));
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void aSinglePersonHasOnePartAndALowPayIsBelowTheSmig() {
        var r = PayslipCalculator.calculate(input("80000", 0, 0));
        assertThat(line(r, "CNSS_EMPLOYEE")).isEqualByComparingTo("3200");
        // 76,800 x 12 = 921,600, less 20% = 737,280, rounded down to 737,000: 4,640 + 27,300 = 31,940 a year.
        assertThat(line(r, "IPR")).isEqualByComparingTo("2661.67");
        assertThat(r.warnings()).anyMatch(w -> w.contains("minimum wage"));
    }

    // ---- Loi n° 42-2025 du 31 décembre 2025 (finance law 2026), CGI art. 116 A to 116 G ------------------------

    static final List<IprCalculator.Bracket> ITS_2026 = List.of(
            new IprCalculator.Bracket(d("0"), d("615000"), d("0")),
            new IprCalculator.Bracket(d("615000"), d("1500000"), d("10")),
            new IprCalculator.Bracket(d("1500000"), d("3500000"), d("15")),
            new IprCalculator.Bracket(d("3500000"), d("5000000"), d("20")),
            new IprCalculator.Bracket(d("5000000"), null, d("30")));

    static final PayslipCalculator.Rates RATES_2026 = new PayslipCalculator.Rates(d("600"), d("4"), d("8"), BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, d("30"), ITS_2026, BigDecimal.ZERO, 0, d("100"), null, "CG", "XAF",
            Map.ofEntries(Map.entry("CNSS_CEILING", d("1200000")), Map.entry("CNSS_FAMILY", d("10.03")),
                    Map.entry("CNSS_ACCIDENT", d("2.25")), Map.entry("CNSS_FAMILY_CEILING", d("600000")),
                    Map.entry("TUS", d("7.5")), Map.entry("ITS_PROFESSIONAL_DEDUCTION", d("20")),
                    Map.entry("ITS_MAX_PARTS", d("6.5")), Map.entry("SMIG_MONTHLY", d("70400")),
                    Map.entry("ITS_FIRST_BAND_FLAT", d("1200")), Map.entry("ITS_MINIMUM_YEARLY", d("1200"))));

    static PayslipCalculator.Input input2026(String basic, int paidMinutes, int children, int dependants, String marital) {
        return new PayslipCalculator.Input("XAF", d(basic), 10560, paidMinutes, 22, 22, BigDecimal.ZERO, 0, null, null,
                children, dependants, false, ELEMENTS, List.of(), List.of(), List.of(), RATES_2026, 0, null, marital);
    }

    @Test
    void the2026BandsStartWithAFlatAmountPerPart() {
        var r = PayslipCalculator.calculate(input2026("1500000", 10560, 2, 3, "MARRIED"));
        // 13,939,000 over 3 parts = 4,646,333.33: 1,200 + 88,500 + 300,000 + 229,266.67 = 618,966.67 a part,
        // x 3 = 1,856,900 a year, 154,741.67 a month.
        assertThat(line(r, "IPR")).isEqualByComparingTo("154741.67");
    }

    @Test
    void aSingleParentHasTwoPartsWithOneChild() {
        var r = PayslipCalculator.calculate(input2026("300000", 10560, 1, 1, "SINGLE"));
        // 288,000 x 12 = 3,456,000, less 20% = 2,764,800, rounded down to 2,764,000, over 2 parts = 1,382,000:
        // 1,200 + 76,700 = 77,900 a part, x 2 = 155,800 a year, 12,983.33 a month.
        assertThat(line(r, "IPR")).isEqualByComparingTo("12983.33");
        assertThat(r.warnings()).noneMatch(w -> w.contains("minimum wage"));
    }

    @Test
    void aGrossBelowTheSmigPaysTheMinimumTax() {
        // Ten minutes paid: the taxable income rounds down to nothing, but a gross below the SMIG owes 1,200 a year.
        var r = PayslipCalculator.calculate(input2026("60000", 10, 0, 0, "SINGLE"));
        assertThat(line(r, "IPR")).isEqualByComparingTo("100");
        assertThat(r.warnings()).anyMatch(w -> w.contains("minimum wage"));
    }

    @Test
    void partsFollowTheMaritalStatusAndChildrenUpToTheMaximum() {
        BigDecimal max = d("6.5");
        assertThat(ItsCalculator.parts("SINGLE", false, 0, max)).isEqualByComparingTo("1");
        assertThat(ItsCalculator.parts("MARRIED", false, 0, max)).isEqualByComparingTo("2");
        assertThat(ItsCalculator.parts("SINGLE", false, 1, max)).isEqualByComparingTo("2");
        assertThat(ItsCalculator.parts("DIVORCED", false, 2, max)).isEqualByComparingTo("2.5");
        assertThat(ItsCalculator.parts("MARRIED", true, 1, max)).isEqualByComparingTo("2.5");
        assertThat(ItsCalculator.parts("WIDOWED", false, 2, max)).isEqualByComparingTo("3");
        assertThat(ItsCalculator.parts("WIDOWED", false, 0, max)).isEqualByComparingTo("1");
        assertThat(ItsCalculator.parts("SINGLE", false, 3, max)).isEqualByComparingTo("3");
        assertThat(ItsCalculator.parts(null, true, 0, max)).isEqualByComparingTo("2");
        assertThat(ItsCalculator.parts("MARRIED", true, 12, max)).isEqualByComparingTo("6.5");
    }
}
