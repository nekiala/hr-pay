package cd.hrpay.attendance;

import cd.hrpay.attendance.DayCalculator.DayResult;
import cd.hrpay.overtime.OvertimePolicy;
import cd.hrpay.overtime.OvertimePolicyRepository;
import cd.hrpay.salary.SalaryService;
import cd.hrpay.schedule.WorkCalendar;
import cd.hrpay.schedule.WorkCalendar.DayPlan;
import cd.hrpay.user.AppUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Daily attendance results and the month-to-date summary, computed from schedules and clock events. */
@Service
public class AttendanceDays {

    public record Earnings(BigDecimal grossMonthly, String currency, BigDecimal earnedToDate,
                           BigDecimal minuteRate, BigDecimal overtimeEarned) {}

    public enum OvertimeStatus { PENDING, APPROVED, REJECTED, AUTOMATIC }

    public record OvertimeDay(LocalDate date, int minutes, cd.hrpay.overtime.OvertimeRateTier.Kind kind,
                              OvertimeStatus status, int approvedMinutes, String decidedBy, String note) {}

    public record MonthSummary(YearMonth month, String timeZone, LocalDate asOf, int scheduledMinutesMonth, int scheduledMinutesToDate,
                               int payableMinutesToDate, int workedMinutesToDate, int overtimeMinutesToDate,
                               int lateDays, int absentDays, int missingCheckOutDays, Earnings earnings,
                               int overtimeApprovedMinutes, int overtimePendingMinutes, List<OvertimeDay> overtime,
                               List<DayResult> days) {}

    private final WorkCalendar calendar;
    private final ClockEventRepository events;
    private final OvertimePolicyRepository policies;
    private final SalaryService salaries;
    private final Clock clock;
    private final cd.hrpay.overtime.OvertimeDecisions decisions;
    private final cd.hrpay.leave.LeaveService leave;
    private cd.hrpay.mission.MissionDays missions;

    @org.springframework.beans.factory.annotation.Autowired
    void setMissions(cd.hrpay.mission.MissionDays missions) {
        this.missions = missions;
    }

    public AttendanceDays(WorkCalendar calendar, ClockEventRepository events, OvertimePolicyRepository policies,
                          SalaryService salaries, Clock clock, cd.hrpay.overtime.OvertimeDecisions decisions,
                          cd.hrpay.leave.LeaveService leave) {
        this.leave = leave;
        this.decisions = decisions;
        this.calendar = calendar;
        this.events = events;
        this.policies = policies;
        this.salaries = salaries;
        this.clock = clock;
    }

    /** A day's result with the grace period of its schedule (lateness or early departure within it is tolerated). */
    public record Graced(DayResult day, int graceMinutes) {}

    @Transactional // not read-only: the calendar creates a year's holiday dates on first use
    public List<DayResult> days(AppUser user, LocalDate from, LocalDate to) {
        return withGrace(user, from, to).stream().map(Graced::day).toList();
    }

    /** As {@link #days}, with each day's grace period. */
    @Transactional // not read-only: the calendar creates a year's holiday dates on first use
    public List<Graced> withGrace(AppUser user, LocalDate from, LocalDate to) {
        ZoneId zone = CheckInService.zone(user.getSite());
        String country = user.getSite() == null ? "CD" : user.getSite().getCountryCode();
        int rounding = policies.findFirstByCountryCodeAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(country, to)
                .map(OvertimePolicy::getRoundingMinutes).orElse(15);
        Map<LocalDate, List<ClockEvent>> byDay = events.findForPeriod(user.getId(), from, to).stream()
                .collect(Collectors.groupingBy(ClockEvent::getWorkDate));
        List<DayPlan> plans = calendar.plan(user, from, to);
        Map<LocalDate, cd.hrpay.leave.LeaveService.LeaveDay> onLeave = leave.approvedDays(user.getId(), from, to);
        Map<LocalDate, String> travel = missions == null ? Map.of() : missions.of(user.getId(), from, to);
        return plans.stream()
                .map(p -> {
                    DayResult r = DayCalculator.compute(p, byDay.getOrDefault(p.date(), List.of()), zone,
                            clock.instant(), rounding);
                    var l = onLeave.get(p.date());
                    // A travel mission: worked and paid without a check-in (leave and missions never overlap).
                    String away = travel.get(p.date());
                    if (away != null && l == null) {
                        return new Graced(r.onTravel(away), p.graceMinutes());
                    }
                    if (l != null && l.halfDay() != null && p.expectedAtWork()) {
                        return new Graced(r.onHalfLeave(l.type(), l.payPercent()), p.graceMinutes());
                    }
                    boolean replace = l != null && p.expectedAtWork() && (r.status() == DayCalculator.Status.ABSENT
                            || r.status() == DayCalculator.Status.FUTURE || r.status() == DayCalculator.Status.NOT_STARTED);
                    return new Graced(replace ? r.onLeave(l.type(), l.payPercent()) : r, p.graceMinutes());
                })
                .toList();
    }

    /**
     * Weekday overtime not recorded (a simulation's "what if"), priced with the person's overtime policy in force on a
     * date: the minutes weighted by the policy's tiers, as if worked in one week.
     */
    public BigDecimal weightedWeekdayOvertime(AppUser user, LocalDate on, int minutes) {
        if (minutes <= 0) {
            return BigDecimal.ZERO;
        }
        String country = user.getSite() == null ? "CD" : user.getSite().getCountryCode();
        OvertimePolicy policy = policies.findFirstByCountryCodeAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                country, on).orElse(null);
        LocalDate monday = on.with(java.time.DayOfWeek.MONDAY);
        return cd.hrpay.overtime.OvertimeValuation.price(List.of(new cd.hrpay.overtime.OvertimeValuation.Approved(monday,
                        cd.hrpay.overtime.OvertimeRateTier.Kind.WEEKDAY, minutes)), policy == null ? List.of() : policy.getTiers())
                .stream().map(cd.hrpay.overtime.OvertimeValuation.Priced::weightedMinutes).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Month to date. Earnings are only filled in when {@code withEarnings} is true (the caller may see salaries). */
    @Transactional // not read-only: the calendar creates a year's holiday dates on first use
    public MonthSummary month(AppUser user, YearMonth month, boolean withEarnings) {
        ZoneId zone = CheckInService.zone(user.getSite());
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        LocalDate asOf = today.isBefore(from) ? from.minusDays(1) : today.isAfter(to) ? to : today;
        LocalDate weekStart = from.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        List<DayResult> withLeadIn = days(user, weekStart, to);
        List<DayResult> days = withLeadIn.stream().filter(d -> !d.date().isBefore(from)).toList();
        List<DayResult> toDate = days.stream().filter(d -> !d.date().isAfter(asOf)).toList();

        int scheduledMonth = days.stream().mapToInt(DayResult::scheduledMinutes).sum();
        int scheduledToDate = toDate.stream().mapToInt(DayResult::scheduledMinutes).sum();
        int payable = toDate.stream().mapToInt(DayResult::payableMinutes).sum();
        int worked = toDate.stream().mapToInt(DayResult::workedMinutes).sum();
        int overtime = toDate.stream().mapToInt(DayResult::overtimeMinutes).sum();
        int lateDays = (int) toDate.stream().filter(d -> d.lateMinutes() > 0).count();
        int absent = (int) toDate.stream().filter(d -> d.status() == DayCalculator.Status.ABSENT).count();
        int missing = (int) toDate.stream().filter(DayResult::missingCheckOut).count();

        // Overtime: each day's status, then priced with weekly tiers (counting the week's days before the month).
        String country = user.getSite() == null ? "CD" : user.getSite().getCountryCode();
        OvertimePolicy policy = policies.findFirstByCountryCodeAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                country, to).orElse(null);
        boolean needsApproval = policy == null || policy.isRequiresApproval();
        Map<LocalDate, cd.hrpay.overtime.OvertimeDecisions.Decision> decided = decisions
                .forPeriod(user.getId(), weekStart, to).stream()
                .collect(Collectors.toMap(cd.hrpay.overtime.OvertimeDecisions.Decision::workDate, d -> d));
        List<OvertimeDay> overtimeDays = withLeadIn.stream()
                .filter(d -> d.overtimeMinutes() > 0 && !d.date().isAfter(asOf))
                .map(d -> {
                    var decision = decided.get(d.date());
                    if (decision != null) {
                        int approved = Math.min(decision.approvedMinutes(), d.overtimeMinutes());
                        return new OvertimeDay(d.date(), d.overtimeMinutes(), d.overtimeKind(),
                                OvertimeStatus.valueOf(decision.status()), approved, decision.decidedBy(), decision.note());
                    }
                    return needsApproval
                            ? new OvertimeDay(d.date(), d.overtimeMinutes(), d.overtimeKind(), OvertimeStatus.PENDING, 0, null, null)
                            : new OvertimeDay(d.date(), d.overtimeMinutes(), d.overtimeKind(), OvertimeStatus.AUTOMATIC,
                                    d.overtimeMinutes(), null, null);
                }).toList();
        List<cd.hrpay.overtime.OvertimeValuation.Priced> priced = cd.hrpay.overtime.OvertimeValuation.price(
                overtimeDays.stream().filter(o -> o.approvedMinutes() > 0)
                        .map(o -> new cd.hrpay.overtime.OvertimeValuation.Approved(o.date(), o.kind(), o.approvedMinutes()))
                        .toList(),
                policy == null ? List.of() : policy.getTiers());
        BigDecimal weightedInMonth = priced.stream().filter(p -> !p.date().isBefore(from))
                .map(cd.hrpay.overtime.OvertimeValuation.Priced::weightedMinutes).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<OvertimeDay> monthOvertime = overtimeDays.stream().filter(o -> !o.date().isBefore(from)).toList();
        int approvedMinutes = monthOvertime.stream().mapToInt(OvertimeDay::approvedMinutes).sum();
        int pendingMinutes = monthOvertime.stream().filter(o -> o.status() == OvertimeStatus.PENDING)
                .mapToInt(OvertimeDay::minutes).sum();

        Earnings earnings = null;
        if (withEarnings && scheduledMonth > 0) {
            earnings = salaries.agreed(user.getId(), asOf.isBefore(from) ? from : asOf).map(s -> {
                BigDecimal rate = s.grossMonthly().divide(BigDecimal.valueOf(scheduledMonth), 10, RoundingMode.HALF_UP);
                BigDecimal earned = rate.multiply(BigDecimal.valueOf(payable)).setScale(2, RoundingMode.HALF_UP);
                BigDecimal overtimeEarned = rate.multiply(weightedInMonth).setScale(2, RoundingMode.HALF_UP);
                return new Earnings(s.grossMonthly(), s.currency(), earned, rate.setScale(6, RoundingMode.HALF_UP),
                        overtimeEarned);
            }).orElse(null);
        }
        return new MonthSummary(month, zone.getId(), asOf, scheduledMonth, scheduledToDate, payable, worked, overtime, lateDays,
                absent, missing, earnings, approvedMinutes, pendingMinutes, monthOvertime, days);
    }
}
