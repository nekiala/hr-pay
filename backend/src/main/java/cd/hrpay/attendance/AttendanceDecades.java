package cd.hrpay.attendance;

import cd.hrpay.attendance.DayCalculator.DayResult;
import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.org.OrgStructure;
import cd.hrpay.overtime.OvertimeDecisions;
import cd.hrpay.overtime.OvertimePolicy;
import cd.hrpay.overtime.OvertimePolicyRepository;
import cd.hrpay.payroll.PayPeriods;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The attendance decades (1 to 10, 11 to 20, 21 to the end of the month). HR closes each decade once its days are
 * checked: from then on its clock entries, corrections, overtime decisions and leave approvals are locked, until HR
 * reopens it with a reason. The decade sheet (fiche de pointage décadaire) lists each person's days for the unit head
 * to sign.
 */
@Service
public class AttendanceDecades {

    private static final ZoneId KINSHASA = ZoneId.of("Africa/Kinshasa");

    /** A decade and what still waits in it. Pending counts are filled in only for the decade asked in detail. */
    public record Decade(int number, LocalDate from, LocalDate to, String status, boolean ended, String closedBy,
                         OffsetDateTime closedAt, String reopenedBy, OffsetDateTime reopenedAt, String reopenReason,
                         int correctionsPending, int leavePending) {}

    /** One person's days in a decade. */
    public record Person(long userId, String name, String registrationNo, Long unitId, String unit, int scheduledDays,
                         int workedDays, int absentDays, int leaveDays, int missionDays, int lateDays, int lateMinutes,
                         int missingCheckOutDays, int overtimeMinutes, int overtimePendingMinutes, int correctionsPending,
                         int leavePending) {

        int pending() {
            return (overtimePendingMinutes > 0 ? 1 : 0) + correctionsPending + leavePending;
        }
    }

    public record UnitSummary(Long unitId, String unit, int people, int absentDays, int overtimePendingMinutes,
                              int correctionsPending, int leavePending) {}

    public record Detail(Decade decade, List<UnitSummary> units, List<Person> people) {}

    private final JdbcClient jdbc;
    private final AttendanceDays attendance;
    private final UserRepository users;
    private final OrgStructure org;
    private final OvertimeDecisions decisions;
    private final OvertimePolicyRepository policies;
    private final AuditService audit;
    private final Clock clock;

    public AttendanceDecades(JdbcClient jdbc, AttendanceDays attendance, UserRepository users, OrgStructure org,
                             OvertimeDecisions decisions, OvertimePolicyRepository policies, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.attendance = attendance;
        this.users = users;
        this.org = org;
        this.decisions = decisions;
        this.policies = policies;
        this.audit = audit;
        this.clock = clock;
    }

    /** Refuses a change to attendance on days of a closed decade. */
    public static void requireOpen(JdbcClient jdbc, LocalDate from, LocalDate to) {
        jdbc.sql("""
                        select start_date, end_date from attendance_decade
                        where status = 'CLOSED' and start_date <= ? and end_date >= ? order by start_date limit 1""")
                .params(to, from).query((rs, i) -> new LocalDate[] {rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class)})
                .optional().ifPresent(d -> {
                    throw new BusinessRuleException(Texts.t("The attendance decade of %s to %s is closed. Ask HR to reopen it first.",
                            Texts.day(d[0]), Texts.day(d[1])));
                });
    }

    /** The decade that holds a day. */
    public static PayPeriods.Decade of(LocalDate day) {
        int n = day.getDayOfMonth() <= 10 ? 1 : day.getDayOfMonth() <= 20 ? 2 : 3;
        LocalDate start = day.withDayOfMonth(n == 1 ? 1 : n == 2 ? 11 : 21);
        return new PayPeriods.Decade(n, start, n == 3 ? day.withDayOfMonth(day.lengthOfMonth()) : start.plusDays(9));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(KINSHASA));
    }

    private record Row(LocalDate startDate, String status, String closedBy, OffsetDateTime closedAt, String reopenedBy,
                       OffsetDateTime reopenedAt, String reopenReason) {}

    private Map<LocalDate, Row> rows(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        select start_date, status, closed_by, closed_at, reopened_by, reopened_at, reopen_reason
                        from attendance_decade where start_date between ? and ?""").params(from, to)
                .query(Row.class).list().stream().collect(Collectors.toMap(Row::startDate, r -> r));
    }

    /** The three decades of a month. */
    public List<Decade> month(YearMonth month) {
        Map<LocalDate, Row> rows = rows(month.atDay(1), month.atEndOfMonth());
        LocalDate today = today();
        return PayPeriods.decades(month.atDay(1), month.atEndOfMonth()).stream()
                .map(d -> decade(d, rows.get(d.from()), today, count("""
                        select count(*) from correction_request where status in ('PENDING', 'PROPOSED') and work_date between ? and ?""",
                        d.from(), d.to()), count("""
                        select count(*) from leave_request where status in ('PENDING', 'AWAITING_HR') and start_date <= ? and end_date >= ?""",
                        d.to(), d.from())))
                .toList();
    }

    private int count(String sql, LocalDate a, LocalDate b) {
        return jdbc.sql(sql).params(a, b).query(Integer.class).single();
    }

    private static Decade decade(PayPeriods.Decade d, Row r, LocalDate today, int corrections, int leave) {
        return new Decade(d.number(), d.from(), d.to(), r == null ? "OPEN" : r.status(), d.to().isBefore(today),
                r == null ? null : r.closedBy(), r == null ? null : r.closedAt(), r == null ? null : r.reopenedBy(),
                r == null ? null : r.reopenedAt(), r == null ? null : r.reopenReason(), corrections, leave);
    }

    private PayPeriods.Decade require(LocalDate start) {
        if (start.getDayOfMonth() != 1 && start.getDayOfMonth() != 11 && start.getDayOfMonth() != 21) {
            throw new BusinessRuleException("A decade starts on the 1st, the 11th or the 21st.");
        }
        return of(start);
    }

    /** Each person under contract in the decade, with their days, and the totals by unit. */
    @Transactional
    public Detail detail(LocalDate start, Long unitId) {
        PayPeriods.Decade d = require(start);
        Map<Long, OrgStructure.Placement> placed = org.placementsOn(d.to().isAfter(today()) ? today() : d.to());
        Map<Long, Integer> corrections = perUser("""
                select user_id, count(*) from correction_request where status in ('PENDING', 'PROPOSED')
                and work_date between ? and ? group by user_id""", d.from(), d.to());
        Map<Long, Integer> leave = perUser("""
                select user_id, count(*) from leave_request where status in ('PENDING', 'AWAITING_HR')
                and start_date <= ? and end_date >= ? group by user_id""", d.to(), d.from());
        List<Long> staff = jdbc.sql("""
                select distinct c.user_id from employee_contract c join app_user u on u.id = c.user_id
                where c.start_date <= ? and (c.end_date is null or c.end_date >= ?) order by c.user_id""")
                .params(d.to(), d.from()).query(Long.class).list();
        List<Person> people = new ArrayList<>();
        for (AppUser u : users.findAllById(staff)) {
            OrgStructure.Placement p = placed.get(u.getId());
            if (unitId != null && (p == null || p.unitId() != unitId)) {
                continue;
            }
            people.add(person(u, d, p, corrections.getOrDefault(u.getId(), 0), leave.getOrDefault(u.getId(), 0)));
        }
        people.sort(Comparator.comparing((Person x) -> Objects.requireNonNullElse(x.unit(), "")).thenComparing(Person::name));
        Map<Long, List<Person>> byUnit = people.stream()
                .collect(Collectors.groupingBy(x -> Objects.requireNonNullElse(x.unitId(), 0L), java.util.LinkedHashMap::new, Collectors.toList()));
        List<UnitSummary> units = byUnit.values().stream().map(list -> new UnitSummary(list.getFirst().unitId(),
                list.getFirst().unit(), list.size(), list.stream().mapToInt(Person::absentDays).sum(),
                list.stream().mapToInt(Person::overtimePendingMinutes).sum(),
                list.stream().mapToInt(Person::correctionsPending).sum(), list.stream().mapToInt(Person::leavePending).sum()))
                .toList();
        Row row = rows(d.from(), d.from()).get(d.from());
        return new Detail(decade(d, row, today(), corrections.values().stream().mapToInt(Integer::intValue).sum(),
                leave.values().stream().mapToInt(Integer::intValue).sum()), units, people);
    }

    private Map<Long, Integer> perUser(String sql, LocalDate a, LocalDate b) {
        Map<Long, Integer> out = new HashMap<>();
        jdbc.sql(sql).params(a, b).query((rs, i) -> out.put(rs.getLong(1), rs.getInt(2))).list();
        return out;
    }

    private Person person(AppUser u, PayPeriods.Decade d, OrgStructure.Placement p, int corrections, int leave) {
        LocalDate to = d.to().isAfter(today()) ? today() : d.to();
        List<DayResult> days = to.isBefore(d.from()) ? List.of() : attendance.days(u, d.from(), to);
        String country = u.getSite() == null ? "CD" : u.getSite().getCountryCode();
        boolean needsApproval = policies.findFirstByCountryCodeAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(country, d.to())
                .map(OvertimePolicy::isRequiresApproval).orElse(true);
        Map<LocalDate, OvertimeDecisions.Decision> decided = decisions.forPeriod(u.getId(), d.from(), d.to()).stream()
                .collect(Collectors.toMap(OvertimeDecisions.Decision::workDate, x -> x));
        int overtime = 0;
        int pending = 0;
        for (DayResult day : days) {
            if (day.overtimeMinutes() <= 0) {
                continue;
            }
            var decision = decided.get(day.date());
            if (decision != null) {
                overtime += Math.min(decision.approvedMinutes(), day.overtimeMinutes());
            } else if (needsApproval) {
                pending += day.status() == DayCalculator.Status.IN_PROGRESS ? 0 : day.overtimeMinutes();
            } else {
                overtime += day.overtimeMinutes();
            }
        }
        return new Person(u.getId(), u.fullName(), u.getRegistrationNo(), p == null ? null : p.unitId(),
                p == null ? null : p.unitName(),
                (int) days.stream().filter(x -> x.scheduledMinutes() > 0 && x.status() != DayCalculator.Status.HOLIDAY).count(),
                (int) days.stream().filter(x -> x.workedMinutes() > 0 || x.mission()).count(),
                (int) days.stream().filter(x -> x.status() == DayCalculator.Status.ABSENT).count(),
                (int) days.stream().filter(x -> x.status() == DayCalculator.Status.LEAVE).count(),
                (int) days.stream().filter(x -> x.status() == DayCalculator.Status.MISSION).count(),
                (int) days.stream().filter(x -> x.lateMinutes() > 0).count(),
                days.stream().mapToInt(DayResult::lateMinutes).sum(),
                (int) days.stream().filter(DayResult::missingCheckOut).count(),
                overtime, pending, corrections, leave);
    }

    /**
     * Closes a decade once it has ended and nothing waits in it: corrections, leave requests and overtime must be
     * decided first, since they cannot be once it is closed.
     */
    @Transactional
    public Decade close(LocalDate start) {
        PayPeriods.Decade d = require(start);
        if (!d.to().isBefore(today())) {
            throw new BusinessRuleException(Texts.t("The decade ends on %s: close it after that day.", Texts.day(d.to())));
        }
        Row row = rows(d.from(), d.from()).get(d.from());
        if (row != null && row.status().equals("CLOSED")) {
            throw new ConflictException("This decade is already closed.");
        }
        Detail detail = detail(start, null);
        int corrections = detail.people().stream().mapToInt(Person::correctionsPending).sum();
        int leave = detail.people().stream().mapToInt(Person::leavePending).sum();
        int overtime = (int) detail.people().stream().filter(x -> x.overtimePendingMinutes() > 0).count();
        if (corrections + leave + overtime > 0) {
            throw new BusinessRuleException(Texts.t(
                    "Decide first what still waits in this decade: %s correction(s), %s leave request(s), overtime of %s person(s).",
                    corrections, leave, overtime));
        }
        if (row == null) {
            jdbc.sql("""
                    insert into attendance_decade (start_date, end_date, status, closed_by, closed_at)
                    values (?, ?, 'CLOSED', ?, now())""").params(d.from(), d.to(), CurrentUser.email()).update();
        } else {
            jdbc.sql("update attendance_decade set status = 'CLOSED', closed_by = ?, closed_at = now() where start_date = ?")
                    .params(CurrentUser.email(), d.from()).update();
        }
        audit.record("attendance.decade_closed", "attendance_decade", null, Map.of("from", d.from().toString(), "to", d.to().toString()));
        return month(YearMonth.from(d.from())).get(d.number() - 1);
    }

    /** Reopens a closed decade, with a reason; a payroll already approved for its days stays as it is. */
    @Transactional
    public Decade reopen(LocalDate start, String reason) {
        PayPeriods.Decade d = require(start);
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Give a short reason.");
        }
        if (jdbc.sql("""
                update attendance_decade set status = 'OPEN', reopened_by = ?, reopened_at = now(), reopen_reason = ?
                where start_date = ? and status = 'CLOSED'""").params(CurrentUser.email(), reason.trim(), d.from()).update() == 0) {
            throw new ConflictException("This decade is not closed.");
        }
        audit.record("attendance.decade_reopened", "attendance_decade", null, Map.of("from", d.from().toString(),
                "to", d.to().toString(), "reason", reason.trim()));
        return month(YearMonth.from(d.from())).get(d.number() - 1);
    }

    /** The decade sheet as a report: one row per person, by unit, with a place for the unit head's signature. */
    public cd.hrpay.payroll.report.Report sheet(LocalDate start, Long unitId) {
        Detail detail = detail(start, unitId);
        PayPeriods.Decade d = of(start);
        var cols = List.of(cd.hrpay.payroll.report.Report.Column.text(Texts.t("Matricule")),
                cd.hrpay.payroll.report.Report.Column.text(Texts.t("Name")),
                cd.hrpay.payroll.report.Report.Column.number(Texts.t("Scheduled days")),
                cd.hrpay.payroll.report.Report.Column.number(Texts.t("Days worked")),
                cd.hrpay.payroll.report.Report.Column.number(Texts.t("Absences")),
                cd.hrpay.payroll.report.Report.Column.number(Texts.t("Leave")),
                cd.hrpay.payroll.report.Report.Column.number(Texts.t("Mission")),
                cd.hrpay.payroll.report.Report.Column.number(Texts.t("Late arrivals")),
                cd.hrpay.payroll.report.Report.Column.text(Texts.t("Overtime")),
                cd.hrpay.payroll.report.Report.Column.text(Texts.t("Signature")));
        List<cd.hrpay.payroll.report.Report.Table> tables = new ArrayList<>();
        Map<String, List<Person>> byUnit = detail.people().stream().collect(Collectors.groupingBy(
                x -> Objects.requireNonNullElse(x.unit(), Texts.t("No unit")), java.util.LinkedHashMap::new, Collectors.toList()));
        byUnit.forEach((unit, list) -> tables.add(new cd.hrpay.payroll.report.Report.Table(unit, cols, list.stream()
                .map(x -> cd.hrpay.payroll.report.Report.Row.of(List.of(Objects.requireNonNullElse(x.registrationNo(), ""), x.name(),
                        x.scheduledDays(), x.workedDays(), x.absentDays(), x.leaveDays(), x.missionDays(), x.lateDays(),
                        hours(x.overtimeMinutes()) + (x.overtimePendingMinutes() > 0
                                ? Texts.t(" (%s waiting)", hours(x.overtimePendingMinutes())) : ""), "")))
                .toList())));
        String status = detail.decade().status().equals("CLOSED")
                ? Texts.t("Closed on %s by %s", Texts.day(detail.decade().closedAt().atZoneSameInstant(KINSHASA).toLocalDate()),
                        detail.decade().closedBy())
                : Texts.t("Not closed yet");
        return new cd.hrpay.payroll.report.Report("DECADE_SHEET",
                Texts.t("Decade attendance sheet, %s to %s", Texts.day(d.from()), Texts.day(d.to())), status,
                !detail.decade().status().equals("CLOSED"), tables,
                List.of(Texts.t("Seen and checked by the head of the unit (name, date, signature):"),
                        Texts.t("HR (name, date, signature):")));
    }

    private static String hours(int minutes) {
        return minutes / 60 + " h " + String.format("%02d", minutes % 60);
    }

    /**
     * Two days before a decade ends: tells each manager whose team still has overtime or corrections waiting in it,
     * so they decide before HR closes it. Returns how many managers were told.
     */
    @Transactional
    public int remind(cd.hrpay.notify.Notifications notifications) {
        LocalDate today = today();
        PayPeriods.Decade d = of(today);
        if (!today.plusDays(2).equals(d.to())) {
            return 0;
        }
        Detail detail = detail(d.from(), null);
        Map<Long, Integer> waiting = new HashMap<>();
        for (Person p : detail.people()) {
            if (p.overtimePendingMinutes() > 0 || p.correctionsPending() > 0) {
                org.managerOf(p.userId()).ifPresent(b -> waiting.merge(b.userId(), 1, Integer::sum));
            }
        }
        Set<Long> told = waiting.keySet();
        for (Long manager : told) {
            int n = waiting.get(manager);
            notifications.send(manager, "DECADE_REMINDER",
                    () -> Texts.t("Attendance decade ends on %s", Texts.day(d.to())),
                    () -> Texts.t("%s person(s) in your team still have overtime or corrections waiting. Decide before HR closes the decade.", n),
                    "/overtime");
        }
        return told.size();
    }
}
