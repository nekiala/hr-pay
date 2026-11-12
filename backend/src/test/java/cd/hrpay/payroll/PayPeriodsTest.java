package cd.hrpay.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class PayPeriodsTest {

    private static final PayPeriods.Setting FROM_21 = new PayPeriods.Setting(PayPeriods.DECADES, 21);

    @Test
    void decadesFromThe21stRunFromThe21stOfTheMonthBeforeToThe20th() {
        PayPeriods.Period p = PayPeriods.of(YearMonth.of(2026, 11), FROM_21);
        assertThat(p.from()).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(p.to()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(p.decades()).containsExactly(
                new PayPeriods.Decade(1, LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 31)),
                new PayPeriods.Decade(2, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 10)),
                new PayPeriods.Decade(3, LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 20)));
        assertThat(p.calendar()).isFalse();
    }

    @Test
    void shortMonthsOnlyShortenTheirLastDecade() {
        PayPeriods.Period march = PayPeriods.of(YearMonth.of(2027, 3), FROM_21);
        assertThat(march.decades().getFirst()).isEqualTo(new PayPeriods.Decade(1, LocalDate.of(2027, 2, 21), LocalDate.of(2027, 2, 28)));
        PayPeriods.Period leap = PayPeriods.of(YearMonth.of(2028, 3), FROM_21);
        assertThat(leap.decades().getFirst().to()).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(PayPeriods.of(YearMonth.of(2027, 5), FROM_21).decades().getFirst().to()).isEqualTo(LocalDate.of(2027, 4, 30));
    }

    @Test
    void consecutivePeriodsNeitherOverlapNorLeaveAGap() {
        for (PayPeriods.Setting s : List.of(FROM_21, new PayPeriods.Setting(PayPeriods.DECADES, 11),
                new PayPeriods.Setting(PayPeriods.DECADES, 1), new PayPeriods.Setting(PayPeriods.MONTH, 1))) {
            YearMonth m = YearMonth.of(2027, 1);
            for (int i = 0; i < 24; i++, m = m.plusMonths(1)) {
                PayPeriods.Period p = PayPeriods.of(m, s);
                PayPeriods.Period next = PayPeriods.of(m.plusMonths(1), s);
                assertThat(next.from()).isEqualTo(p.to().plusDays(1));
                assertThat(p.decades()).hasSize(3);
                assertThat(p.decades().getFirst().from()).isEqualTo(p.from());
                assertThat(p.decades().getLast().to()).isEqualTo(p.to());
                // Each period is exactly as long as one month.
                long days = p.to().toEpochDay() - p.from().toEpochDay() + 1;
                assertThat(days).isEqualTo(p.from().lengthOfMonth());
            }
        }
    }


    @Test
    void theCalendarMonthIsLeftAsItIs() {
        assertThat(PayPeriods.of(YearMonth.of(2027, 2), new PayPeriods.Setting(PayPeriods.MONTH, 1)).calendar()).isTrue();
        assertThat(PayPeriods.of(YearMonth.of(2027, 2), new PayPeriods.Setting(PayPeriods.DECADES, 1)).calendar()).isTrue();
        assertThat(PayPeriods.of(YearMonth.of(2027, 2), new PayPeriods.Setting(PayPeriods.DECADES, 11)).from())
                .isEqualTo(LocalDate.of(2027, 1, 11));
    }
}
