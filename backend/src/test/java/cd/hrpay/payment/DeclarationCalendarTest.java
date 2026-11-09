package cd.hrpay.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DeclarationCalendarTest {

    private static DeclarationCalendar.Rule rule(String decl, String later, Integer after, String month, String day, Integer from) {
        return new DeclarationCalendar.Rule("IPR", "DGI", 15, 15, null, decl == null ? null : new BigDecimal(decl),
                later == null ? null : new BigDecimal(later), after, null, null, month == null ? null : new BigDecimal(month),
                day == null ? null : new BigDecimal(day), from, null, true);
    }

    private static final LocalDate DUE = LocalDate.of(2026, 9, 15);

    @Test
    void aStartedMonthCountsAsAWholeOne() {
        assertThat(DeclarationCalendar.startedMonths(DUE, DUE)).isZero();
        assertThat(DeclarationCalendar.startedMonths(DUE, DUE.plusDays(1))).isEqualTo(1);
        assertThat(DeclarationCalendar.startedMonths(DUE, LocalDate.of(2026, 10, 15))).isEqualTo(1);
        assertThat(DeclarationCalendar.startedMonths(DUE, LocalDate.of(2026, 10, 16))).isEqualTo(2);
    }

    @Test
    void drcTaxLateDeclarationAndPayment() {
        // Loi 004/2003: +25 % for the late declaration, 2 % for each month started.
        var p = DeclarationCalendar.penalty(rule("25", null, null, "2", null, null), new BigDecimal("1000.00"), DUE, DUE,
                LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20), LocalDate.of(2026, 11, 1));
        assertThat(p.lateDeclaration()).isEqualByComparingTo("250");
        assertThat(p.latePayment()).isEqualByComparingTo("40");
        assertThat(p.total()).isEqualByComparingTo("290");
    }

    @Test
    void onTimeCostsNothing() {
        var p = DeclarationCalendar.penalty(rule("25", null, null, "2", "0.5", 21), new BigDecimal("1000"), DUE, DUE,
                DUE, DUE, LocalDate.of(2026, 12, 1));
        assertThat(p.total()).isEqualByComparingTo("0");
    }

    @Test
    void dailyRateCountsFromItsDay() {
        // CNSS: 0.5 % a day from the 21st; paid on the 25th = 5 days.
        var p = DeclarationCalendar.penalty(rule(null, null, null, null, "0.5", 21), new BigDecimal("1000"), DUE, DUE,
                DUE, LocalDate.of(2026, 9, 25), LocalDate.of(2026, 12, 1));
        assertThat(p.latePayment()).isEqualByComparingTo("25");
        var early = DeclarationCalendar.penalty(rule(null, null, null, null, "0.5", 21), new BigDecimal("1000"), DUE, DUE,
                DUE, LocalDate.of(2026, 9, 20), LocalDate.of(2026, 12, 1));
        assertThat(early.total()).isEqualByComparingTo("0");
    }

    @Test
    void congoTaxRisesAfterTwoMonths() {
        var p = DeclarationCalendar.penalty(rule("50", "100", 2, null, null, null), new BigDecimal("1000"), DUE, DUE,
                null, null, LocalDate.of(2026, 10, 1));
        assertThat(p.lateDeclaration()).isEqualByComparingTo("500");
        var later = DeclarationCalendar.penalty(rule("50", "100", 2, null, null, null), new BigDecimal("1000"), DUE, DUE,
                null, null, LocalDate.of(2026, 12, 1));
        assertThat(later.lateDeclaration()).isEqualByComparingTo("1000");
    }

    @Test
    void congoWithheldTaxPaidLateCostsHalfThenAll() {
        // CGI art. 512: 50 % of the sums paid late, 100 % when more than two months late.
        var r = new DeclarationCalendar.Rule("IPR", "Impôts", 15, 15, 5, null, null, 2, new BigDecimal("50"), new BigDecimal("100"),
                null, null, null, null, true);
        var p = DeclarationCalendar.penalty(r, new BigDecimal("1000"), DUE, DUE, DUE, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31));
        assertThat(p.latePayment()).isEqualByComparingTo("500");
        assertThat(p.lateDeclaration()).isEqualByComparingTo("0");
        var later = DeclarationCalendar.penalty(r, new BigDecimal("1000"), DUE, DUE, DUE, null, LocalDate.of(2026, 12, 1));
        assertThat(later.latePayment()).isEqualByComparingTo("1000");
    }

    @Test
    void remindersFiveDaysOneDayThenWeekly() {
        assertThat(DeclarationReminders.remindToday(5)).isTrue();
        assertThat(DeclarationReminders.remindToday(4)).isFalse();
        assertThat(DeclarationReminders.remindToday(1)).isTrue();
        assertThat(DeclarationReminders.remindToday(0)).isFalse();
        assertThat(DeclarationReminders.remindToday(-1)).isTrue();
        assertThat(DeclarationReminders.remindToday(-2)).isFalse();
        assertThat(DeclarationReminders.remindToday(-8)).isTrue();
        assertThat(DeclarationReminders.remindToday(-64)).isFalse();
    }
}
