package cd.hrpay.overtime;

import static org.assertj.core.api.Assertions.assertThat;

import cd.hrpay.overtime.OvertimeRateTier.Kind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class OvertimeValuationTest {

    static final List<OvertimeRateTier> DRC = List.of(
            new OvertimeRateTier(Kind.WEEKDAY, 0, 6, new BigDecimal("1.30")),
            new OvertimeRateTier(Kind.WEEKDAY, 6, null, new BigDecimal("1.60")),
            new OvertimeRateTier(Kind.REST_DAY, 0, null, new BigDecimal("2.00")),
            new OvertimeRateTier(Kind.HOLIDAY, 0, null, new BigDecimal("2.00")));

    @Test
    void weekdayHoursBeyondSixInAWeekUseTheSecondTier() {
        var priced = OvertimeValuation.price(List.of(
                new OvertimeValuation.Approved(LocalDate.of(2026, 8, 3), Kind.WEEKDAY, 300),
                new OvertimeValuation.Approved(LocalDate.of(2026, 8, 5), Kind.WEEKDAY, 180)), DRC);
        assertThat(priced.get(0).weightedMinutes()).isEqualByComparingTo("390");       // 5 h x 1.30
        assertThat(priced.get(1).weightedMinutes()).isEqualByComparingTo("270");       // 1 h x 1.30 + 2 h x 1.60
    }

    @Test
    void anewWeekStartsAgainAtTheFirstTier() {
        var priced = OvertimeValuation.price(List.of(
                new OvertimeValuation.Approved(LocalDate.of(2026, 8, 7), Kind.WEEKDAY, 420),
                new OvertimeValuation.Approved(LocalDate.of(2026, 8, 10), Kind.WEEKDAY, 60)), DRC);
        assertThat(priced.get(0).weightedMinutes()).isEqualByComparingTo("564");       // 6 h x 1.30 + 1 h x 1.60
        assertThat(priced.get(1).weightedMinutes()).isEqualByComparingTo("78");
    }

    @Test
    void restDaysAndHolidaysAreDoubled() {
        var priced = OvertimeValuation.price(List.of(
                new OvertimeValuation.Approved(LocalDate.of(2026, 8, 8), Kind.REST_DAY, 240),
                new OvertimeValuation.Approved(LocalDate.of(2026, 6, 30), Kind.HOLIDAY, 60)), DRC);
        assertThat(priced).extracting(OvertimeValuation.Priced::weightedMinutes)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("120"), new BigDecimal("480"));
    }
}
