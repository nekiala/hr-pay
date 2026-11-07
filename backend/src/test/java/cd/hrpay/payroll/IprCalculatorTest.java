package cd.hrpay.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class IprCalculatorTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    // Annual bands: 0-1,200,000 at 0%, 1,200,000-2,400,000 at 10%, above at 20%.
    static final List<IprCalculator.Bracket> BANDS = List.of(
            new IprCalculator.Bracket(d("0"), d("1200000"), d("0")),
            new IprCalculator.Bracket(d("1200000"), d("2400000"), d("10")),
            new IprCalculator.Bracket(d("2400000"), null, d("20")));

    @Test
    void appliesTheBandsToTheAnnualisedIncome() {
        // 300,000 a month = 3,600,000 a year: 0 + 120,000 + 240,000 = 360,000 a year = 30,000 a month.
        var r = IprCalculator.monthly(d("300000"), 0, BANDS, d("2"), 9, d("30"));
        assertThat(r.tax()).isEqualByComparingTo("30000");
        assertThat(r.reduction()).isEqualByComparingTo("0");
    }

    @Test
    void incomeInTheFreeBandPaysNothing() {
        assertThat(IprCalculator.monthly(d("100000"), 0, BANDS, d("2"), 9, d("30")).tax()).isEqualByComparingTo("0");
        assertThat(IprCalculator.monthly(d("0"), 3, BANDS, d("2"), 9, d("30")).tax()).isEqualByComparingTo("0");
    }

    @Test
    void reducesPerDependantUpToTheMaximum() {
        // 3 dependants x 2% of 30,000 = 1,800.
        assertThat(IprCalculator.monthly(d("300000"), 3, BANDS, d("2"), 9, d("30")).tax()).isEqualByComparingTo("28200");
        // 12 dependants count as 9: 18% of 30,000 = 5,400.
        var r = IprCalculator.monthly(d("300000"), 12, BANDS, d("2"), 9, d("30"));
        assertThat(r.reduction()).isEqualByComparingTo("5400");
        assertThat(r.tax()).isEqualByComparingTo("24600");
    }

    @Test
    void capsTheTaxAtAShareOfTheIncome() {
        // With a 5% cap, 300,000 a month pays at most 15,000.
        var r = IprCalculator.monthly(d("300000"), 0, BANDS, d("2"), 9, d("5"));
        assertThat(r.tax()).isEqualByComparingTo("15000");
        assertThat(r.capped()).isEqualByComparingTo("15000");
    }
}
