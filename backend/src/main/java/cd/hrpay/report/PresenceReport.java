package cd.hrpay.report;

import cd.hrpay.attendance.AttendanceDays;
import cd.hrpay.attendance.DayCalculator;
import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.Sheet;
import cd.hrpay.org.OrgStructure;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who is in today (or on a given day), and the exceptions of a period: late arrivals, early departures, absences,
 * days without a check-out, days closed automatically and days corrected. HR sees everyone; a head sees their branch.
 */
@RestController
@RequestMapping("/api/reports")
public class PresenceReport {

    private static final ZoneId ZONE = ZoneId.of("Africa/Kinshasa");
    private static final int MAX_DAYS = 92;

    public record Person(long userId, String name, String registrationNo, String directionName, String unitName,
                         String siteName, String status, LocalTime firstIn, LocalTime lastOut, int lateMinutes,
                         boolean mission, String leaveType, boolean missingCheckOut) {}

    public record Presence(LocalDate date, Map<String, Integer> counts, List<Person> people) {}

    public record Issue(long userId, String name, String registrationNo, String directionName, String unitName,
                            LocalDate date, String kind, int minutes, String detail) {}

    public record Exceptions(LocalDate from, LocalDate to, Map<String, Integer> counts, List<Issue> rows) {}

    private final AttendanceDays attendance;
    private final UserRepository users;
    private final OrgStructure org;
    private final AuditService audit;

    public PresenceReport(AttendanceDays attendance, UserRepository users, OrgStructure org, AuditService audit) {
        this.attendance = attendance;
        this.users = users;
        this.org = org;
        this.audit = audit;
    }

    private List<AppUser> scope(Long unitId, Map<Long, OrgStructure.Placement> where) {
        List<AppUser> list = CurrentUser.has("attendance.read.all") ? users.findAll() : users.findAllById(org.teamOf(CurrentUser.id()));
        Set<Long> branch = unitId == null ? null : org.branch(unitId);
        return list.stream().filter(AppUser::isActive)
                .filter(u -> branch == null || (where.containsKey(u.getId()) && branch.contains(where.get(u.getId()).unitId())))
                .sorted(Comparator.comparing(AppUser::getLastName).thenComparing(AppUser::getFirstName)).toList();
    }

    private static LocalTime local(java.time.OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(ZONE).toLocalTime().truncatedTo(ChronoUnit.MINUTES);
    }

    @GetMapping("/presence")
    @PreAuthorize("hasAnyAuthority('attendance.read.all', 'attendance.read.team')")
    @Transactional(readOnly = true)
    public Presence presence(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                             @RequestParam(required = false) Long unitId) {
        LocalDate today = org.today();
        LocalDate day = date == null ? today : date;
        if (day.isAfter(today)) {
            throw new BusinessRuleException("Choose today or a day before.");
        }
        Map<Long, OrgStructure.Placement> where = org.placementsOn(day);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String k : List.of("expected", "in", "late", "notYetIn", "absent", "leave", "mission", "off")) {
            counts.put(k, 0);
        }
        List<Person> people = new ArrayList<>();
        for (AppUser u : scope(unitId, where)) {
            DayCalculator.DayResult d = attendance.days(u, day, day).getFirst();
            var p = where.get(u.getId());
            String status = d.mission() && d.workedMinutes() == 0 ? "MISSION" : d.status().name();
            people.add(new Person(u.getId(), u.getFirstName() + " " + u.getLastName(), u.getRegistrationNo(),
                    p == null ? null : p.directionName(), p == null ? null : p.unitName(),
                    u.getSite() == null ? null : u.getSite().getName(), status, local(d.firstIn()), local(d.lastOut()),
                    d.lateMinutes(), d.mission(), d.leaveType(), d.missingCheckOut()));
            switch (d.status()) {
                case PRESENT, IN_PROGRESS -> counts.merge("in", 1, Integer::sum);
                case NOT_STARTED -> counts.merge(d.mission() ? "mission" : "notYetIn", 1, Integer::sum);
                case ABSENT -> counts.merge(d.mission() ? "mission" : "absent", 1, Integer::sum);
                case LEAVE -> counts.merge("leave", 1, Integer::sum);
                default -> counts.merge("off", 1, Integer::sum);
            }
            if (d.scheduledMinutes() > 0) {
                counts.merge("expected", 1, Integer::sum);
            }
            if (d.lateMinutes() > 0) {
                counts.merge("late", 1, Integer::sum);
            }
        }
        return new Presence(day, counts, people);
    }

    private List<Issue> exceptionRows(LocalDate from, LocalDate to, Long unitId) {
        if (to.isBefore(from)) {
            throw new BusinessRuleException("The end comes before the start.");
        }
        LocalDate last = to.isAfter(org.today()) ? org.today() : to;
        if (ChronoUnit.DAYS.between(from, last) >= MAX_DAYS) {
            throw new BusinessRuleException("Choose at most " + MAX_DAYS + " days.");
        }
        Map<Long, OrgStructure.Placement> where = org.placementsOn(last);
        List<Issue> rows = new ArrayList<>();
        for (AppUser u : scope(unitId, where)) {
            var p = where.get(u.getId());
            String name = u.getFirstName() + " " + u.getLastName();
            String dir = p == null ? null : p.directionName();
            String unit = p == null ? null : p.unitName();
            for (DayCalculator.DayResult d : attendance.days(u, from, last)) {
                if (d.status() == DayCalculator.Status.FUTURE || d.status() == DayCalculator.Status.NOT_STARTED) {
                    continue;
                }
                java.util.function.BiConsumer<String, Object[]> add = (kind, x) -> rows.add(new Issue(u.getId(), name,
                        u.getRegistrationNo(), dir, unit, d.date(), kind, (int) x[0], (String) x[1]));
                if (d.status() == DayCalculator.Status.ABSENT && !d.mission()) {
                    add.accept("ABSENT", new Object[] {d.scheduledMinutes(), "No check-in on a working day"});
                }
                if (d.lateMinutes() > 0) {
                    add.accept("LATE", new Object[] {d.lateMinutes(), "Arrived at " + local(d.firstIn())
                            + (d.lateDeductedMinutes() > 0 ? ", " + d.lateDeductedMinutes() + " min deducted" : "")});
                }
                if (d.earlyMinutes() > 0) {
                    add.accept("LEFT_EARLY", new Object[] {d.earlyMinutes(), "Left at " + local(d.lastOut())
                            + (d.earlyDeductedMinutes() > 0 ? ", " + d.earlyDeductedMinutes() + " min deducted" : "")});
                }
                if (d.missingCheckOut()) {
                    add.accept("NO_CHECK_OUT", new Object[] {0, "Checked in at " + local(d.firstIn()) + ", no check-out"});
                }
                if (d.autoClosed()) {
                    add.accept("AUTO_CLOSED", new Object[] {0, "Closed automatically at the end of the day"});
                }
                if (d.corrected()) {
                    add.accept("CORRECTED", new Object[] {0, "Entries added or removed by HR"});
                }
            }
        }
        return rows;
    }

    @GetMapping("/exceptions")
    @PreAuthorize("hasAnyAuthority('attendance.read.all', 'attendance.read.team')")
    @Transactional(readOnly = true)
    public Exceptions exceptions(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(required = false) Long unitId) {
        List<Issue> rows = exceptionRows(from, to, unitId);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String k : List.of("ABSENT", "LATE", "LEFT_EARLY", "NO_CHECK_OUT", "AUTO_CLOSED", "CORRECTED")) {
            counts.put(k, (int) rows.stream().filter(r -> r.kind().equals(k)).count());
        }
        return new Exceptions(from, to, counts, rows);
    }

    @GetMapping({"/exceptions.csv", "/exceptions.xlsx"})
    @PreAuthorize("hasAuthority('report.attendance.export')")
    @Transactional
    public void exceptionsFile(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                               @RequestParam(required = false) Long unitId,
                               HttpServletRequest request, HttpServletResponse response) throws IOException {
        List<Issue> rows = exceptionRows(from, to, unitId);
        Sheet out = Sheet.open(request, response, "exceptions-" + from + "-to-" + to);
        out.row("Registration no", "Name", "Direction", "Unit", "Date", "Exception", "Minutes", "Detail");
        for (Issue e : rows) {
            out.row(e.registrationNo(), e.name(), e.directionName(), e.unitName(), e.date().toString(), e.kind(),
                    e.minutes() == 0 ? null : e.minutes(), e.detail());
        }
        out.finish();
        audit.record("report.exceptions_exported", "report", null, Map.of("from", from.toString(), "to", to.toString(),
                "rows", rows.size(), "unit", unitId == null ? "" : unitId));
    }
}
