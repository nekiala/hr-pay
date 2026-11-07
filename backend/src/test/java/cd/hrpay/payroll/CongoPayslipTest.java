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
        // 17,424,000 a year, less 20% = 13,939,200, over 3 parts = 4,646,400 a part:
        // 4,640 + 53,600 + 500,000 + 658,560 = 1,216,800 a part, x 3 = 3,650,400 a year, 304,200 a month.
        assertThat(line(r, "IPR")).isEqualByComparingTo("304200");
        assertThat(line(r, "CNSS_EMPLOYER")).isEqualByComparingTo("96000");          // 8% of 1,200,000
        assertThat(line(r, "CNSS_EMPLOYER_FAMILY")).isEqualByComparingTo("60180");   // 10.03% of 600,000
        assertThat(line(r, "CNSS_EMPLOYER_ACCIDENT")).isEqualByComparingTo("13500"); // 2.25% of 600,000
        assertThat(line(r, "TUS")).isEqualByComparingTo("112500");                  // 7.5% of the gross
        assertThat(r.employerCharges()).isEqualByComparingTo("282180");
        assertThat(r.net()).isEqualByComparingTo("1147800");
        assertThat(r.lines()).noneMatch(l -> l.code().equals("INPP") || l.code().equals("ONEM"));
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void aSinglePersonHasOnePartAndALowPayIsBelowTheSmig() {
        var r = PayslipCalculator.calculate(input("80000", 0, 0));
        assertThat(line(r, "CNSS_EMPLOYEE")).isEqualByComparingTo("3200");
        // 76,800 x 12 = 921,600, less 20% = 737,280: 4,640 + 27,328 = 31,968 a year, 2,664 a month.
        assertThat(line(r, "IPR")).isEqualByComparingTo("2664");
        assertThat(r.warnings()).anyMatch(w -> w.contains("minimum wage"));
    }

    @Test
    void partsCountASpouseAndHalfAPartPerChildUpToTheMaximum() {
        assertThat(ItsCalculator.parts(false, 0, d("6.5"))).isEqualByComparingTo("1");
        assertThat(ItsCalculator.parts(true, 1, d("6.5"))).isEqualByComparingTo("2.5");
        assertThat(ItsCalculator.parts(true, 12, d("6.5"))).isEqualByComparingTo("6.5");
    }
}
