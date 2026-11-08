package cd.hrpay.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import cd.hrpay.attendance.DayCalculator.DayResult;
import cd.hrpay.attendance.DayCalculator.Status;
import cd.hrpay.overtime.OvertimeRateTier;
import cd.hrpay.schedule.WorkCalendar.DayPlan;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DayCalculatorTest {

    static final ZoneId KIN = ZoneId.of("Africa/Kinshasa");
    static final LocalDate MON = LocalDate.of(2026, 11, 9);
    static final Instant LATER = LocalDate.of(2026, 11, 20).atStartOfDay(KIN).toInstant();

    /** 08:00-17:00, unpaid lunch 12:00-13:00, 480 paid minutes, 10 minutes grace, late and early deducted. */
    static DayPlan workday(LocalDate date, boolean holiday) {
        return new DayPlan(date, true, holiday, LocalTime.of(8, 0), LocalTime.of(17, 0), true, LocalTime.of(12, 0), 60,
                false, 480, 10, true, true, 1L);
    }

    static DayPlan restDay(LocalDate date) {
        return new DayPlan(date, false, false, null, null, false, null, 0, false, 0, 10, true, true, 1L);
    }

    static List<ClockEvent> events(LocalDate date, String... times) {
        List<ClockEvent> list = new ArrayList<>();
        for (int i = 0; i < times.length; i++) {
            var at = date.atTime(LocalTime.parse(times[i])).atZone(KIN).toOffsetDateTime();
            list.add(new ClockEvent(1L, i % 2 == 0 ? ClockEvent.Type.IN : ClockEvent.Type.OUT, at, date,
                    ClockEvent.Source.SELF, "t"));
        }
        return list;
    }

    static DayResult day(DayPlan plan, Instant now, String... times) {
        return DayCalculator.compute(plan, events(plan.date(), times), KIN, now, 15);
    }

    @Test
    void fullDayExcludesLunchAndIgnoresSmallEarlyArrival() {
        DayResult r = day(workday(MON, false), LATER, "07:55", "17:00");
        assertThat(r.status()).isEqualTo(Status.PRESENT);
        assertThat(r.workedMinutes()).isEqualTo(485);
        assertThat(r.payableMinutes()).isEqualTo(480);
        assertThat(r.overtimeMinutes()).isZero();
        assertThat(r.lateMinutes()).isZero();
    }

    @Test
    void lateWithinGraceIsNotDeducted() {
        DayResult r = day(workday(MON, false), LATER, "08:08", "17:00");
        assertThat(r.lateMinutes()).isEqualTo(8);
        assertThat(r.lateDeductedMinutes()).isZero();
        assertThat(r.payableMinutes()).isEqualTo(480);
    }

    @Test
    void lateBeyondGraceAndEarlyDepartureAreDeducted() {
        DayResult r = day(workday(MON, false), LATER, "08:30", "16:00");
        assertThat(r.lateDeductedMinutes()).isEqualTo(30);
        assertThat(r.earlyDeductedMinutes()).isEqualTo(60);
        assertThat(r.payableMinutes()).isEqualTo(390);
    }

    @Test
    void aNursingMotherLeavingAnHourEarlyIsPaidTheFullDay() {
        // Code du travail art. 132: two half-hour nursing breaks a day count as paid working time.
        DayPlan plan = new DayPlan(MON, true, false, LocalTime.of(8, 0), LocalTime.of(17, 0), true,
                LocalTime.of(12, 0), 60, false, 480, 10, true, true, 1L, 60);
        assertThat(day(plan, LATER, "08:00", "16:00").payableMinutes()).isEqualTo(480);
        assertThat(day(plan, LATER, "08:00", "15:00").payableMinutes()).isEqualTo(420);
    }

    @Test
    void lateNotDeductedWhenScheduleSaysSo() {
        DayPlan plan = new DayPlan(MON, true, false, LocalTime.of(8, 0), LocalTime.of(17, 0), true,
                LocalTime.of(12, 0), 60, false, 480, 10, false, true, 1L);
        DayResult r = day(plan, LATER, "08:30", "17:00");
        assertThat(r.lateDeductedMinutes()).isZero();
        assertThat(r.payableMinutes()).isEqualTo(480);
    }

    @Test
    void arrivingDuringLunchOnlyLosesWorkingTime() {
        DayResult r = day(workday(MON, false), LATER, "12:30", "17:00");
        assertThat(r.lateMinutes()).isEqualTo(270);
        assertThat(r.payableMinutes()).isEqualTo(240);
    }

    @Test
    void overtimeAfterScheduleIsRoundedDownAndNotPayable() {
        DayResult r = day(workday(MON, false), LATER, "08:00", "19:10");
        assertThat(r.overtimeMinutes()).isEqualTo(120);
        assertThat(r.overtimeKind()).isEqualTo(OvertimeRateTier.Kind.WEEKDAY);
        assertThat(r.payableMinutes()).isEqualTo(480);
    }

    @Test
    void breakInTheDayIsNotPaid() {
        DayResult r = day(workday(MON, false), LATER, "08:00", "10:00", "11:00", "17:00");
        assertThat(r.payableMinutes()).isEqualTo(420);
    }

    @Test
    void restDayWorkIsAllOvertime() {
        DayResult r = day(restDay(MON.plusDays(5)), LATER, "09:00", "13:00");
        assertThat(r.status()).isEqualTo(Status.REST_DAY);
        assertThat(r.overtimeMinutes()).isEqualTo(240);
        assertThat(r.overtimeKind()).isEqualTo(OvertimeRateTier.Kind.REST_DAY);
        assertThat(r.payableMinutes()).isZero();
    }

    @Test
    void holidayOnWorkdayIsPaidAndWorkIsHolidayOvertime() {
        assertThat(day(workday(MON, true), LATER).payableMinutes()).isEqualTo(480);
        DayResult worked = day(workday(MON, true), LATER, "08:00", "12:00");
        assertThat(worked.status()).isEqualTo(Status.HOLIDAY);
        assertThat(worked.payableMinutes()).isEqualTo(480);
        assertThat(worked.overtimeMinutes()).isEqualTo(240);
        assertThat(worked.overtimeKind()).isEqualTo(OvertimeRateTier.Kind.HOLIDAY);
    }

    @Test
    void absentFutureAndNotStarted() {
        assertThat(day(workday(MON, false), LATER).status()).isEqualTo(Status.ABSENT);
        Instant mondayMorning = MON.atTime(7, 0).atZone(KIN).toInstant();
        assertThat(day(workday(MON, false), mondayMorning).status()).isEqualTo(Status.NOT_STARTED);
        assertThat(day(workday(MON.plusDays(1), false), mondayMorning).status()).isEqualTo(Status.FUTURE);
    }

    @Test
    void openDayTodayCountsUntilNow() {
        Instant tenAm = MON.atTime(10, 0).atZone(KIN).toInstant();
        DayResult r = day(workday(MON, false), tenAm, "08:00");
        assertThat(r.status()).isEqualTo(Status.IN_PROGRESS);
        assertThat(r.payableMinutes()).isEqualTo(120);
        assertThat(r.earlyMinutes()).isZero();
        assertThat(r.missingCheckOut()).isFalse();
    }

    @Test
    void missingCheckOutCountsUntilScheduleEndAndIsFlagged() {
        DayResult r = day(workday(MON, false), LATER, "08:00");
        assertThat(r.missingCheckOut()).isTrue();
        assertThat(r.payableMinutes()).isEqualTo(480);
        assertThat(r.lastOut()).isNull();
    }
}
