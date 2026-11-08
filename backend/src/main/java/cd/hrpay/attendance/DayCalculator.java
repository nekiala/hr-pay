package cd.hrpay.attendance;

import cd.hrpay.overtime.OvertimeRateTier;
import cd.hrpay.schedule.WorkCalendar.DayPlan;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns one day's plan and clock events into worked, payable and overtime minutes.
 *
 * <p>Rules:
 * <ul>
 *   <li>Unpaid lunch is never counted as worked, whenever the employee is present during it.</li>
 *   <li>Payable minutes are the minutes worked inside the schedule, plus late arrival or early departure that is
 *       within the grace period or that the schedule does not deduct, capped at the scheduled minutes.</li>
 *   <li>Minutes worked outside the schedule, or on a rest day or holiday, are overtime, rounded down to the
 *       policy's rounding step. Overtime is only paid after approval, so it is not in payable minutes.</li>
 *   <li>A day still open after its end is counted until the schedule end and flagged as a missing check-out.</li>
 *   <li>A holiday on a workday is paid in full.</li>
 * </ul>
 */
public final class DayCalculator {

    public enum Status { FUTURE, NOT_STARTED, IN_PROGRESS, PRESENT, ABSENT, HOLIDAY, REST_DAY, NO_SCHEDULE, LEAVE }

    public record Session(OffsetDateTime in, OffsetDateTime out, boolean open) {}

    /** A clock event as shown to people correcting the day. */
    public record Entry(Long id, ClockEvent.Type type, OffsetDateTime at, ClockEvent.Source source, String note,
                        String createdBy) {}

    public record DayResult(LocalDate date, Status status, int scheduledMinutes, int workedMinutes,
                            int payableMinutes, int lateMinutes, int lateDeductedMinutes, int earlyMinutes,
                            int earlyDeductedMinutes, int overtimeMinutes, OvertimeRateTier.Kind overtimeKind,
                            boolean missingCheckOut, OffsetDateTime firstIn, OffsetDateTime lastOut,
                            List<Session> sessions, boolean autoClosed, boolean corrected, boolean mission,
                            List<Entry> entries, String leaveType) {

        /** This day as approved leave: it pays the leave type's share of the scheduled minutes. */
        public DayResult onLeave(String type, java.math.BigDecimal payPercent) {
            return new DayResult(date, Status.LEAVE, scheduledMinutes, workedMinutes, share(scheduledMinutes, payPercent), 0, 0,
                    0, 0, overtimeMinutes, overtimeKind, false, firstIn, lastOut, sessions, autoClosed, corrected,
                    mission, entries, type);
        }

        /**
         * Half a day of approved leave: the leave pays its share of half the scheduled minutes, the time worked in the
         * other half is paid as usual, and lateness or early departure that day is not deducted.
         */
        public DayResult onHalfLeave(String type, java.math.BigDecimal payPercent) {
            int fromWork = payableMinutes + lateDeductedMinutes + earlyDeductedMinutes;
            int paid = Math.min(scheduledMinutes, fromWork + share(scheduledMinutes / 2, payPercent));
            Status s = workedMinutes == 0 && (status == Status.ABSENT || status == Status.FUTURE || status == Status.NOT_STARTED)
                    ? Status.LEAVE : status;
            return new DayResult(date, s, scheduledMinutes, workedMinutes, paid, 0, 0, 0, 0, overtimeMinutes,
                    overtimeKind, missingCheckOut, firstIn, lastOut, sessions, autoClosed, corrected, mission, entries,
                    type + " (half day)");
        }

        private static int share(int minutes, java.math.BigDecimal percent) {
            return percent.multiply(java.math.BigDecimal.valueOf(minutes))
                    .divide(java.math.BigDecimal.valueOf(100), 0, java.math.RoundingMode.HALF_UP).intValue();
        }
    }

    private record Span(long from, long to) {
        long length() { return Math.max(0, to - from); }
        Span intersect(Span o) { return new Span(Math.max(from, o.from), Math.min(to, o.to)); }
    }

    private DayCalculator() {}

    public static DayResult compute(DayPlan plan, List<ClockEvent> events, ZoneId zone, Instant now,
                                    int roundingMinutes) {
        LocalDate date = plan.date();
        LocalDate today = LocalDate.ofInstant(now, zone);
        Instant dayStart = date.atStartOfDay(zone).toInstant();
        boolean scheduled = plan.scheduleId() != null && plan.workday();
        Span window = scheduled ? new Span(minute(dayStart, at(date, plan.start(), zone)),
                minute(dayStart, at(date, plan.end(), zone))) : null;
        Span lunch = scheduled && plan.lunchEnabled() && !plan.lunchPaid() && plan.lunchStart() != null
                ? new Span(minute(dayStart, at(date, plan.lunchStart(), zone)),
                        minute(dayStart, at(date, plan.lunchStart(), zone)) + plan.lunchMinutes())
                : null;

        // Pair IN and OUT events into sessions. A second IN while open, or an OUT with nothing open, is ignored.
        List<Session> sessions = new ArrayList<>();
        OffsetDateTime openIn = null;
        for (ClockEvent e : events) {
            if (e.getType() == ClockEvent.Type.IN && openIn == null) {
                openIn = e.getOccurredAt();
            } else if (e.getType() == ClockEvent.Type.OUT && openIn != null) {
                sessions.add(new Session(openIn, e.getOccurredAt(), false));
                openIn = null;
            }
        }
        boolean missingCheckOut = false;
        boolean inProgress = false;
        if (openIn != null) {
            Instant scheduleEnd = window == null ? null : dayStart.plus(Duration.ofMinutes(window.to()));
            if (date.equals(today) && (scheduleEnd == null || now.isBefore(scheduleEnd))) {
                inProgress = true;
                sessions.add(new Session(openIn, OffsetDateTime.ofInstant(now, zone), true));
            } else {
                missingCheckOut = true;
                Instant end = scheduleEnd != null && scheduleEnd.isAfter(openIn.toInstant()) ? scheduleEnd
                        : openIn.toInstant();
                sessions.add(new Session(openIn, OffsetDateTime.ofInstant(end, zone), true));
            }
        }

        long worked = 0;
        long inWindow = 0;
        for (Session s : sessions) {
            Span span = new Span(minute(dayStart, s.in().toInstant()), minute(dayStart, s.out().toInstant()));
            worked += span.length() - (lunch == null ? 0 : span.intersect(lunch).length());
            if (window != null) {
                Span inside = span.intersect(window);
                inWindow += inside.length() - (lunch == null ? 0 : inside.intersect(lunch).length());
            }
        }
        OffsetDateTime firstIn = sessions.isEmpty() ? null : sessions.getFirst().in();
        Session last = sessions.isEmpty() ? null : sessions.getLast();
        OffsetDateTime lastOut = last == null || last.open() ? null : last.out();

        OvertimeRateTier.Kind kind = plan.holiday() ? OvertimeRateTier.Kind.HOLIDAY
                : scheduled ? OvertimeRateTier.Kind.WEEKDAY : OvertimeRateTier.Kind.REST_DAY;
        int scheduledMinutes = plan.scheduledMinutes();

        if (plan.scheduleId() == null) {
            return result(date, Status.NO_SCHEDULE, 0, worked, 0, 0, 0, 0, 0, 0, null, missingCheckOut, firstIn,
                    lastOut, sessions, events);
        }
        if (!scheduled || plan.holiday()) {
            int overtime = round(worked, roundingMinutes);
            Status status = plan.holiday() ? Status.HOLIDAY : Status.REST_DAY;
            int payable = plan.holiday() && scheduled ? scheduledMinutes : 0;
            return result(date, status, plan.holiday() && scheduled ? scheduledMinutes : 0, worked, payable, 0, 0, 0,
                    0, overtime, overtime > 0 ? kind : null, missingCheckOut, firstIn, lastOut, sessions, events);
        }

        if (sessions.isEmpty()) {
            Instant end = dayStart.plus(Duration.ofMinutes(window.to()));
            Status status = date.isAfter(today) ? Status.FUTURE
                    : date.equals(today) && now.isBefore(end) ? Status.NOT_STARTED : Status.ABSENT;
            return result(date, status, scheduledMinutes, 0, 0, 0, 0, 0, 0, 0, null, false, null, null, sessions, events);
        }

        long late = Math.max(0, minute(dayStart, firstIn.toInstant()) - window.from());
        late = Math.min(late, window.length());
        boolean lateDeducted = plan.deductLate() && late > plan.graceMinutes();
        long early = 0;
        if (!inProgress) {
            long lastEnd = minute(dayStart, last.out().toInstant());
            early = Math.min(Math.max(0, window.to() - lastEnd), window.length());
        }
        boolean earlyDeducted = plan.deductEarly() && early > plan.graceMinutes();
        // Lateness and early departure overlapping lunch are not real loss of paid time.
        long lateLoss = lunch == null ? late : late - new Span(window.from(), window.from() + late).intersect(lunch).length();
        long earlyLoss = lunch == null ? early : early - new Span(window.to() - early, window.to()).intersect(lunch).length();

        long payable = inWindow + (lateDeducted ? 0 : lateLoss) + (earlyDeducted ? 0 : earlyLoss);
        // Paid breaks the employee may take off the schedule (nursing breaks) are not lost time.
        payable = Math.min(payable + plan.paidBreakMinutes(), scheduledMinutes);
        int overtime = round(worked - inWindow, roundingMinutes);
        return result(date, inProgress ? Status.IN_PROGRESS : Status.PRESENT, scheduledMinutes, worked, payable,
                late, lateDeducted ? late : 0, early, earlyDeducted ? early : 0, overtime, overtime > 0 ? kind : null,
                missingCheckOut, firstIn, lastOut, sessions, events);
    }

    private static DayResult result(LocalDate date, Status status, int scheduled, long worked, long payable, long late,
                                    long lateDeducted, long early, long earlyDeducted, int overtime,
                                    OvertimeRateTier.Kind kind, boolean missing, OffsetDateTime firstIn,
                                    OffsetDateTime lastOut, List<Session> sessions, List<ClockEvent> events) {
        boolean autoClosed = events.stream().anyMatch(e -> e.getSource() == ClockEvent.Source.AUTO_CLOSE);
        boolean corrected = events.stream().anyMatch(e -> e.getSource() == ClockEvent.Source.HR_MANUAL);
        boolean mission = events.stream().anyMatch(e -> e.getSource() == ClockEvent.Source.MISSION);
        List<Entry> entries = events.stream().map(e -> new Entry(e.getId(), e.getType(), e.getOccurredAt(),
                e.getSource(), e.getNote(), e.getCreatedBy())).toList();
        return new DayResult(date, status, scheduled, (int) worked, (int) payable, (int) late, (int) lateDeducted,
                (int) early, (int) earlyDeducted, overtime, kind, missing, firstIn, lastOut, List.copyOf(sessions),
                autoClosed, corrected, mission, entries, null);
    }

    private static int round(long minutes, int step) {
        if (minutes <= 0) {
            return 0;
        }
        return (int) (step <= 1 ? minutes : minutes / step * step);
    }

    private static Instant at(LocalDate date, LocalTime time, ZoneId zone) {
        return date.atTime(time).atZone(zone).toInstant();
    }

    private static long minute(Instant dayStart, Instant t) {
        return Math.floorDiv(Duration.between(dayStart, t).toSeconds(), 60);
    }
}
