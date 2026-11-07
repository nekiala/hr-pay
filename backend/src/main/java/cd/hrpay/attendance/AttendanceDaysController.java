package cd.hrpay.attendance;

import cd.hrpay.attendance.AttendanceDays.MonthSummary;
import cd.hrpay.attendance.DayCalculator.DayResult;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserService;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@cd.hrpay.platform.RequiresModule("ATTENDANCE")
@RequestMapping("/api/attendance")
public class AttendanceDaysController {

    private final AttendanceDays attendance;
    private final UserService users;

    public AttendanceDaysController(AttendanceDays attendance, UserService users) {
        this.attendance = attendance;
        this.users = users;
    }

    @GetMapping("/month")
    @PreAuthorize("hasAuthority('attendance.read.own')")
    public MonthSummary myMonth(@RequestParam(required = false) YearMonth month) {
        AppUser user = users.find(CurrentUser.id());
        return attendance.month(user, month == null ? YearMonth.now() : month, CurrentUser.has("salary.read.own"));
    }

    @GetMapping("/users/{id}/month")
    @PreAuthorize("hasAuthority('attendance.read.all')")
    public MonthSummary userMonth(@PathVariable Long id, @RequestParam(required = false) YearMonth month) {
        return attendance.month(users.find(id), month == null ? YearMonth.now() : month, CurrentUser.has("salary.read"));
    }

    @GetMapping("/days")
    @PreAuthorize("hasAuthority('attendance.read.own')")
    public List<DayResult> myDays(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        check(from, to);
        return attendance.days(users.find(CurrentUser.id()), from, to);
    }

    @GetMapping("/users/{id}/days")
    @PreAuthorize("hasAuthority('attendance.read.all')")
    public List<DayResult> userDays(@PathVariable Long id,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        check(from, to);
        return attendance.days(users.find(id), from, to);
    }

    private static void check(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 92) {
            throw new BusinessRuleException("Choose a period of at most 93 days.");
        }
    }
}
