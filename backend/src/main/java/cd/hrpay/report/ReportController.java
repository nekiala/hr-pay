package cd.hrpay.report;

import cd.hrpay.attendance.AttendanceDays;
import cd.hrpay.attendance.DayCalculator;
import cd.hrpay.audit.AuditService;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** CSV exports that open directly in Excel (UTF-8 with BOM). Every export is audited. */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final AttendanceDays attendance;
    private final UserRepository users;
    private final AuditService audit;

    public ReportController(AttendanceDays attendance, UserRepository users, AuditService audit) {
        this.attendance = attendance;
        this.users = users;
        this.audit = audit;
    }

    private cd.hrpay.org.OrgStructure org;

    @org.springframework.beans.factory.annotation.Autowired
    void setOrg(cd.hrpay.org.OrgStructure org) {
        this.org = org;
    }

    /** Unit and Direction of each person at the end of the month (or today, for the current month). */
    private Map<Long, cd.hrpay.org.OrgStructure.Placement> placements(YearMonth month) {
        java.time.LocalDate last = month.atEndOfMonth();
        java.time.LocalDate today = org.today();
        return org.placementsOn(last.isAfter(today) ? today : last);
    }

    /** Keeps the people of a unit and the units below it, when a unit is chosen. */
    private List<AppUser> inUnit(List<AppUser> people, Long unitId, Map<Long, cd.hrpay.org.OrgStructure.Placement> where) {
        if (unitId == null) {
            return people;
        }
        java.util.Set<Long> branch = org.branch(unitId);
        return people.stream().filter(u -> where.containsKey(u.getId()) && branch.contains(where.get(u.getId()).unitId())).toList();
    }

    private static String dir(Map<Long, cd.hrpay.org.OrgStructure.Placement> where, AppUser u) {
        var p = where.get(u.getId());
        return p == null ? "" : p.directionName();
    }

    private static String unit(Map<Long, cd.hrpay.org.OrgStructure.Placement> where, AppUser u) {
        var p = where.get(u.getId());
        return p == null ? "" : p.unitName();
    }

    /** Everyone for HR and Finance; a manager without attendance.read.all gets their team. */
    private List<AppUser> scope() {
        List<AppUser> list = CurrentUser.has("attendance.read.all") ? users.findAll()
                : users.findAllById(org.teamOf(CurrentUser.id()));
        return list.stream().filter(AppUser::isActive)
                .sorted(Comparator.comparing(AppUser::getLastName).thenComparing(AppUser::getFirstName)).toList();
    }

    @GetMapping("/attendance.csv")
    @PreAuthorize("hasAuthority('report.attendance.export')")
    @Transactional
    public void attendanceReport(@RequestParam YearMonth month, @RequestParam(defaultValue = "false") boolean detail,
                                 @RequestParam(required = false) Long unitId, HttpServletResponse response) throws IOException {
        PrintWriter out = start(response, "attendance-" + month + (detail ? "-daily" : "") + ".csv");
        var where = placements(month);
        List<AppUser> people = inUnit(scope(), unitId, where);
        if (detail) {
            row(out, "Registration no", "Name", "Direction", "Unit", "Date", "Status", "Arrived", "Left", "Scheduled h", "Worked h",
                    "Paid h", "Late min", "Late deducted min", "Left early min", "Early deducted min", "Overtime h",
                    "Overtime status", "No end of day", "Closed automatically", "Corrected by HR", "Mission", "Leave");
        } else {
            row(out, "Registration no", "Name", "Direction", "Unit", "Site", "Scheduled h (month)", "Scheduled h (to date)", "Paid h",
                    "Worked h", "Late days", "Absent days", "Leave days", "Days without end of day",
                    "Overtime approved h", "Overtime waiting h");
        }
        for (AppUser u : people) {
            AttendanceDays.MonthSummary m = attendance.month(u, month, false);
            String name = u.getFirstName() + " " + u.getLastName();
            if (detail) {
                Map<java.time.LocalDate, AttendanceDays.OvertimeDay> ot = m.overtime().stream()
                        .collect(Collectors.toMap(AttendanceDays.OvertimeDay::date, o -> o));
                var zone = java.time.ZoneId.of(m.timeZone());
                for (DayCalculator.DayResult d : m.days()) {
                    if (d.date().isAfter(m.asOf())) {
                        break;
                    }
                    var o = ot.get(d.date());
                    row(out, u.getRegistrationNo(), name, dir(where, u), unit(where, u), d.date().toString(), d.status().name(),
                            d.firstIn() == null ? "" : d.firstIn().atZoneSameInstant(zone).toLocalTime().toString(),
                            d.lastOut() == null ? "" : d.lastOut().atZoneSameInstant(zone).toLocalTime().toString(),
                            h(d.scheduledMinutes()), h(d.workedMinutes()), h(d.payableMinutes()),
                            String.valueOf(d.lateMinutes()), String.valueOf(d.lateDeductedMinutes()),
                            String.valueOf(d.earlyMinutes()), String.valueOf(d.earlyDeductedMinutes()),
                            h(d.overtimeMinutes()), o == null ? "" : o.status().name(), yes(d.missingCheckOut()),
                            yes(d.autoClosed()), yes(d.corrected()), yes(d.mission()),
                            d.leaveType() == null ? "" : d.leaveType());
                }
            } else {
                long leaveDays = m.days().stream().filter(d -> !d.date().isAfter(m.asOf()))
                        .filter(d -> d.status() == DayCalculator.Status.LEAVE).count();
                row(out, u.getRegistrationNo(), name, dir(where, u), unit(where, u), u.getSite() == null ? "" : u.getSite().getName(),
                        h(m.scheduledMinutesMonth()), h(m.scheduledMinutesToDate()), h(m.payableMinutesToDate()),
                        h(m.workedMinutesToDate()), String.valueOf(m.lateDays()), String.valueOf(m.absentDays()),
                        String.valueOf(leaveDays), String.valueOf(m.missingCheckOutDays()),
                        h(m.overtimeApprovedMinutes()), h(m.overtimePendingMinutes()));
            }
        }
        out.flush();
        audit.record("report.attendance_exported", "report", null, Map.of("month", month.toString(),
                "detail", detail, "employees", people.size(), "unit", unitId == null ? "" : unitId));
    }

    @GetMapping("/finance.csv")
    @PreAuthorize("hasAuthority('report.finance.export') and hasAuthority('salary.read')")
    @Transactional
    public void financeReport(@RequestParam YearMonth month, @RequestParam(required = false) Long unitId,
                              HttpServletResponse response) throws IOException {
        PrintWriter out = start(response, "finance-" + month + ".csv");
        var where = placements(month);
        List<AppUser> people = inUnit(users.findAll().stream().filter(AppUser::isActive)
                .sorted(Comparator.comparing(AppUser::getLastName).thenComparing(AppUser::getFirstName)).toList(), unitId, where);
        row(out, "Registration no", "Name", "Direction", "Unit", "Cost centre", "Currency", "Gross monthly", "Scheduled h (month)", "Paid h to date",
                "Gross earned to date", "Overtime approved h", "Overtime pay", "Total gross to date", "As of");
        for (AppUser u : people) {
            AttendanceDays.MonthSummary m = attendance.month(u, month, true);
            var e = m.earnings();
            var p = where.get(u.getId());
            row(out, u.getRegistrationNo(), u.getFirstName() + " " + u.getLastName(), dir(where, u), unit(where, u),
                    p == null || p.costCentre() == null ? "" : p.costCentre(), e == null ? "" : e.currency(), e == null ? "" : e.grossMonthly().toPlainString(),
                    h(m.scheduledMinutesMonth()), h(m.payableMinutesToDate()),
                    e == null ? "" : e.earnedToDate().toPlainString(), h(m.overtimeApprovedMinutes()),
                    e == null ? "" : e.overtimeEarned().toPlainString(),
                    e == null ? "" : e.earnedToDate().add(e.overtimeEarned()).toPlainString(), m.asOf().toString());
        }
        out.flush();
        audit.record("report.finance_exported", "report", null, Map.of("month", month.toString(),
                "employees", people.size(), "unit", unitId == null ? "" : unitId));
    }

    private static PrintWriter start(HttpServletResponse response, String filename) throws IOException {
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        PrintWriter out = response.getWriter();
        out.write('﻿');
        return out;
    }

    private static String h(int minutes) {
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String yes(boolean b) {
        return b ? "yes" : "";
    }

    private static void row(PrintWriter out, String... cells) {
        out.write(java.util.Arrays.stream(cells).map(ReportController::cell).collect(Collectors.joining(",")));
        out.write("\r\n");
    }

    /** Quotes when needed, and neutralises leading =, +, - and @ so a cell is never run as a formula. */
    public static String cell(String v) {
        if (v == null) {
            return "";
        }
        String s = !v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 && !v.matches("-?[0-9.]+") ? "'" + v : v;
        return s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("'")
                ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
