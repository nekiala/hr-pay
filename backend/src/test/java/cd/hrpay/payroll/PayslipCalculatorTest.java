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
        // Net before loans 1,303.78; at most 30% (391.13) goes to the loan.
        assertThat(line(r, "LOAN")).isEqualByComparingTo("391.13");
        assertThat(r.net()).isEqualByComparingTo("912.65");
        assertThat(r.warnings()).anyMatch(w -> w.startsWith("Loan #7"));
        assertThat(line(r, "CNSS_EMPLOYER")).isEqualByComparingTo("156");
        assertThat(line(r, "INPP")).isEqualByComparingTo("15.64");
        assertThat(line(r, "ONEM")).isEqualByComparingTo("3.13");
        assertThat(line(r, "IERE")).isEqualByComparingTo("0");
        assertThat(r.employerCharges()).isEqualByComparingTo("174.77");
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
}
