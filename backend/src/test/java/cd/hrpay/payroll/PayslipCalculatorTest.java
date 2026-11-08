package cd.hrpay.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PayslipCalculatorTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    static PayElements.Element el(long id, String code, String kind, String method, String amount, String percent,
                                  boolean cnss, boolean ipr, boolean prorated, boolean all, int order) {
        return new PayElements.Element(id, code, code, kind, method, amount == null ? null : d(amount),
                amount == null ? null : "USD", percent == null ? null : d(percent), cnss, ipr, null, prorated, all,
                method.equals("COMPUTED"), order, true);
    }

    static final List<PayElements.Element> ELEMENTS = List.of(
            el(1, "BASIC", "EARNING", "COMPUTED", null, null, true, true, true, false, 10),
            el(2, "OVERTIME", "EARNING", "COMPUTED", null, null, true, true, false, false, 20),
            el(3, "HOUSING", "EARNING", "PERCENT_OF_BASIC", null, "30", false, false, true, true, 30),
            el(4, "TRANSPORT", "EARNING", "PER_DAY_WORKED", "2", null, false, false, false, true, 40),
            el(5, "FAMILY_ALLOWANCE", "EARNING", "PER_CHILD", "10", null, false, false, false, true, 50),
            el(18, "THIRTEENTH_MONTH", "EARNING", "COMPUTED", null, null, true, true, false, false, 75),
            el(6, "PERF_BONUS", "EARNING", "MANUAL", null, null, true, true, false, false, 60),
            el(9, "CNSS_EMPLOYEE", "DEDUCTION", "COMPUTED", null, null, false, false, false, false, 110),
            el(10, "IPR", "DEDUCTION", "COMPUTED", null, null, false, false, false, false, 120),
            el(11, "LOAN", "DEDUCTION", "COMPUTED", null, null, false, false, false, false, 130),
            el(12, "ADVANCE", "DEDUCTION", "COMPUTED", null, null, false, false, false, false, 140),
            el(19, "QUINZAINE", "DEDUCTION", "COMPUTED", null, null, false, false, false, false, 143),
            el(14, "CNSS_EMPLOYER", "EMPLOYER_CHARGE", "COMPUTED", null, null, false, false, false, false, 210),
            el(15, "INPP", "EMPLOYER_CHARGE", "COMPUTED", null, null, false, false, false, false, 220),
            el(16, "ONEM", "EMPLOYER_CHARGE", "COMPUTED", null, null, false, false, false, false, 230),
            el(17, "IERE", "EMPLOYER_CHARGE", "COMPUTED", null, null, false, false, false, false, 240));

    static final PayslipCalculator.Rates RATES = new PayslipCalculator.Rates(d("2000"), d("5"), d("13"), d("1"),
            d("0.2"), d("25"), d("30"), IprCalculatorTest.BANDS, d("2"), 9, d("30"), d("14500"));

    static PayslipCalculator.Input input(int paidMinutes, List<PayElements.Assignment> standing,
                                         List<PayElements.Entry> entries, List<PayslipCalculator.LoanDue> loans,
                                         boolean expatriate) {
        return new PayslipCalculator.Input("USD", d("1000"), 10560, paidMinutes, 22, 22, BigDecimal.ZERO, 0, null, null, 2, 3,
                expatriate, ELEMENTS, standing, entries, loans, RATES);
    }

    static PayElements.Entry bonus(String amount) {
        return new PayElements.Entry(1, 1, "A B", 6, "PERF_BONUS", "PERF_BONUS", "EARNING", null, d(amount), "USD",
                null, "hr", null);
    }

    static BigDecimal line(PayslipCalculator.Result r, String code) {
        return r.lines().stream().filter(l -> l.code().equals(code)).map(PayslipCalculator.Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void fullMonthWithAllowancesBonusTaxAndALoanLimitedByTheNetShare() {
        var loan = new PayslipCalculator.LoanDue(7, "LOAN", d("500"), d("2000"), "USD");
        var r = PayslipCalculator.calculate(input(10560, List.of(), List.of(bonus("200")), List.of(loan), false));

        assertThat(line(r, "BASIC")).isEqualByComparingTo("1000");
        assertThat(line(r, "HOUSING")).isEqualByComparingTo("300");
        assertThat(line(r, "TRANSPORT")).isEqualByComparingTo("44");
        assertThat(line(r, "FAMILY_ALLOWANCE")).isEqualByComparingTo("20");
        assertThat(r.gross()).isEqualByComparingTo("1564");
        assertThat(r.cnssBase()).isEqualByComparingTo("1200");              // basic + bonus
        assertThat(line(r, "CNSS_EMPLOYEE")).isEqualByComparingTo("60");
        assertThat(r.taxableBase()).isEqualByComparingTo("1140");
        assertThat(r.taxableBaseCdf()).isEqualByComparingTo("2280000");
        // 27,360,000 a year: 120,000 + 4,992,000 = 5,112,000 / 12 = 426,000; less 6% for 3 dependants = 400,440 CDF.
        assertThat(line(r, "IPR")).isEqualByComparingTo("200.22");
        // Net before loans 1,303.78; 30% would be 391.13, but Code du travail art. 114 allows less: 1/5 up to five
        // monthly SMIG of the category and 1/3 above (308.92).
        assertThat(line(r, "LOAN")).isEqualByComparingTo("308.92");
        assertThat(r.net()).isEqualByComparingTo("994.86");
        assertThat(r.warnings()).anyMatch(w -> w.contains("art. 114"));
        assertThat(r.warnings()).anyMatch(w -> w.startsWith("Loan #7"));
        assertThat(line(r, "CNSS_EMPLOYER")).isEqualByComparingTo("156");
        assertThat(line(r, "INPP")).isEqualByComparingTo("15.64");
        assertThat(line(r, "ONEM")).isEqualByComparingTo("3.13");
        assertThat(line(r, "IERE")).isEqualByComparingTo("0");
        assertThat(r.employerCharges()).isEqualByComparingTo("174.77");
    }

    @Test
    void annualLeavePaidByAnAllocationKeepsHousingOnTheMonthlyPayslip() {
        // 5 of 22 days on annual leave paid by an allocation de congé: the basic (remuneration, art. 7) is not paid
        // again for them, but housing is owed during the leave (art. 138, 142).
        int leave = 5 * 480;
        var in = input(10560 - leave, List.of(), List.of(), List.of(), false);
        var r = PayslipCalculator.calculate(new PayslipCalculator.Input(in.currency(), in.basicMonthly(), in.scheduledMinutes(),
                in.paidMinutes(), in.scheduledDays(), 17, in.overtimeAmount(), in.overtimeMinutes(), null, null,
                in.childrenInCharge(), in.dependantsInCharge(), false, in.elements(), in.standing(), in.entries(), in.loans(),
                in.rates(), leave));
        assertThat(line(r, "BASIC")).isEqualByComparingTo("772.73");
        assertThat(line(r, "HOUSING")).isEqualByComparingTo("300");
        assertThat(line(r, "TRANSPORT")).isEqualByComparingTo("34");        // 17 days worked
        // Without an allocation, unpaid days reduce housing too.
        var unpaid = PayslipCalculator.calculate(in);
        assertThat(line(unpaid, "HOUSING")).isEqualByComparingTo("231.82");
    }

    static PayElements.Entry quinzaine(String amount) {
        return new PayElements.Entry(2, 1, "A B", 19, "QUINZAINE", "QUINZAINE", "DEDUCTION", null, d(amount), "USD",
                null, "hr", null);
    }

    @Test
    void theQuinzaineIsTakenBackBeforeLoans() {
        var loan = new PayslipCalculator.LoanDue(7, "LOAN", d("500"), d("2000"), "USD");
        var r = PayslipCalculator.calculate(input(10560, List.of(), List.of(quinzaine("400")), List.of(loan), false));
        assertThat(line(r, "QUINZAINE")).isEqualByComparingTo("400");
        // The loan's room is worked out on what is left after the quinzaine: here art. 114 (149.90) is below 30%.
        BigDecimal beforeLoans = r.gross().subtract(line(r, "CNSS_EMPLOYEE")).subtract(line(r, "IPR")).subtract(d("400"));
        assertThat(line(r, "LOAN")).isEqualByComparingTo("149.90")
                .isLessThan(beforeLoans.multiply(d("0.30")).setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(r.net()).isPositive();
    }

    @Test
    void aQuinzaineTheNetCannotCoverIsOnlyPartlyTakenAndTheRestCarried() {
        // Two days paid in the month: the net cannot take back the whole quinzaine.
        var r = PayslipCalculator.calculate(input(960, List.of(), List.of(quinzaine("400")), List.of(), false));
        assertThat(line(r, "QUINZAINE")).isLessThan(d("400")).isPositive();
        assertThat(r.net()).isEqualByComparingTo("0");
        assertThat(r.warnings()).anyMatch(w -> w.startsWith("Quinzaine: only"));
    }

    @Test
    void halfTheHoursPaysHalfTheBasicAndProratedAllowances() {
        var r = PayslipCalculator.calculate(input(5280, List.of(), List.of(), List.of(), false));
        assertThat(line(r, "BASIC")).isEqualByComparingTo("500");
        assertThat(line(r, "HOUSING")).isEqualByComparingTo("150");
        assertThat(line(r, "FAMILY_ALLOWANCE")).isEqualByComparingTo("20");   // not prorated
    }

    @Test
    void standingItemsOverrideOrExcludeTheDefaults() {
        var transport = new PayElements.Assignment(1, 1, 4, "TRANSPORT", "TRANSPORT", d("3"), "USD", null, false,
                null, null, null, "hr", null);
        var noHousing = new PayElements.Assignment(2, 1, 3, "HOUSING", "HOUSING", null, null, null, true,
                null, null, null, "hr", null);
        var r = PayslipCalculator.calculate(input(10560, List.of(transport, noHousing), List.of(), List.of(), false));
        assertThat(line(r, "TRANSPORT")).isEqualByComparingTo("66");
        assertThat(r.lines()).noneMatch(l -> l.code().equals("HOUSING"));
    }

    @Test
    void expatriatesCarryIere() {
        var r = PayslipCalculator.calculate(input(10560, List.of(), List.of(), List.of(), true));
        assertThat(line(r, "IERE")).isEqualByComparingTo(r.gross().multiply(d("0.25")));
    }

    @Test
    void cdfAmountsAreConverted() {
        var entry = new PayElements.Entry(1, 1, "A B", 6, "PERF_BONUS", "PERF_BONUS", "EARNING", null, d("100000"),
                "CDF", "Q3", "hr", null);
        var r = PayslipCalculator.calculate(input(10560, List.of(), List.of(entry), List.of(), false));
        assertThat(line(r, "PERF_BONUS")).isEqualByComparingTo("50");
        assertThat(r.lines()).anyMatch(l -> l.label().equals("PERF_BONUS (Q3)"));
    }

    @Test
    void theThirteenthMonthIsAnEarningSubjectToItsElementsTreatment() {
        var in = input(10560, List.of(), List.of(), List.of(), false);
        var withBonus = new PayslipCalculator.Input(in.currency(), in.basicMonthly(), in.scheduledMinutes(), in.paidMinutes(),
                in.scheduledDays(), in.daysWorked(), in.overtimeAmount(), in.overtimeMinutes(), d("812.50"), "81.25% x 1000",
                in.childrenInCharge(), in.dependantsInCharge(), in.expatriate(), in.elements(), in.standing(), in.entries(),
                in.loans(), in.rates());
        var r = PayslipCalculator.calculate(withBonus);
        assertThat(line(r, "THIRTEENTH_MONTH")).isEqualByComparingTo("812.50");
        assertThat(r.cnssBase()).isEqualByComparingTo("1812.50");
        assertThat(r.lines()).anyMatch(l -> l.label().equals("THIRTEENTH_MONTH (81.25% x 1000)"));
    }
    static final PayslipCalculator.Rates RATES_2026 = new PayslipCalculator.Rates(d("2000"), d("5"), d("13"), d("1"),
            d("0.2"), d("25"), d("30"), IprCalculatorTest.BANDS, d("2"), 9, d("30"), d("21500"), "CD", "CDF",
            java.util.Map.of("IPR_MIN_MONTHLY", d("2500")));

    static PayslipCalculator.Input graded(String basic, String coefficient, PayslipCalculator.Rates rates) {
        return new PayslipCalculator.Input("USD", d(basic), 10560, 10560, 22, 22, BigDecimal.ZERO, 0, null, null, 2, 3,
                false, ELEMENTS, List.of(), List.of(), List.of(), rates, 0, coefficient == null ? null : d(coefficient), "MARRIED");
    }

    @Test
    void theBasicIsCheckedAgainstTheGradeMinimumOfDecret2522() {
        // Cat. 3 step 1, coefficient 154: 21,500 x 1.54 x 26 = 860,860 CDF, 430.43 USD at 2,000.
        var below = PayslipCalculator.calculate(graded("430", "154", RATES_2026));
        assertThat(below.warnings()).anyMatch(w -> w.startsWith("The basic salary (860000 CDF) is below the legal minimum")
                && w.contains("= 860860 CDF"));
        assertThat(PayslipCalculator.calculate(graded("431", "154", RATES_2026)).warnings())
                .noneMatch(w -> w.contains("legal minimum of the grade"));
        // No coefficient: the SMIG itself (21,500 x 26 = 559,000 CDF).
        assertThat(PayslipCalculator.calculate(graded("279", null, RATES_2026)).warnings())
                .anyMatch(w -> w.contains("= 559000 CDF"));
    }

    @Test
    void familyAllowancesBelowTheLegalMinimumAreFlagged() {
        // 1/27 of 21,500 a day x 26 days = 20,703.70 CDF a child; 10 USD at 2,000 = 20,000 CDF.
        var r = PayslipCalculator.calculate(graded("1000", "651", RATES_2026));
        assertThat(r.warnings()).anyMatch(w -> w.startsWith("Family allowances (20000 CDF a child) are below the legal minimum"));
        assertThat(PayslipCalculator.calculate(input(10560, List.of(), List.of(), List.of(), false)).warnings())
                .noneMatch(w -> w.startsWith("Family allowances"));
    }

    @Test
    void theIprIsAtLeast2500FcAMonthWhenTaxIsDue() {
        assertThat(PayslipCalculator.minimumMonthly(d("21500"), d("100"))).isEqualByComparingTo("559000");
        var r = PayslipCalculator.calculate(graded("300", null, RATES_2026));
        assertThat(line(r, "IPR").multiply(d("2000"))).isGreaterThanOrEqualTo(d("2500"));
    }
}
