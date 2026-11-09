package cd.hrpay.integration;

import cd.hrpay.attendance.AttendanceDays;
import cd.hrpay.attendance.ClockEvent;
import cd.hrpay.attendance.ClockIngest;
import cd.hrpay.attendance.DayCalculator;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.payroll.Accounting;
import cd.hrpay.payroll.PayElements;
import cd.hrpay.payroll.PayrollRuns;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public API, version 1: what other systems read from HR Pay and the few things they send to it. Signed in with
 * an access key (see {@link ApiKeyFilter}); described in {@code /api/v1/openapi.json}. Field names stay stable within
 * the version; new fields may be added.
 */
@RestController
@RequestMapping("/api/v1")
public class PublicApiController {

    public record Employee(String registrationNo, String firstName, String lastName, String email, String phone, String status,
                           String site, String unit, LocalDate hireDate, String gradeCategory, String manager, String clockPin,
                           String postnom) {}

    public record Unit(String code, String name, String level, String parent, LocalDate validFrom, LocalDate validTo) {}

    public record Day(String registrationNo, LocalDate date, String status, int scheduledMinutes, int workedMinutes,
                      int payableMinutes, int lateMinutes, int overtimeMinutes, OffsetDateTime firstIn, OffsetDateTime lastOut,
                      boolean missingCheckOut, String leaveType) {}

    public record Event(long id, String registrationNo, String type, OffsetDateTime at, LocalDate workDate, String source,
                        String site, boolean voided) {}

    public record EventInput(@NotBlank String registrationNo, String type, @NotNull OffsetDateTime at, @Size(max = 200) String note) {}

    public record EventResult(boolean recorded, Long id, String type, String ignored) {}

    public record Leave(long id, String registrationNo, String type, LocalDate startDate, LocalDate endDate, BigDecimal workingDays,
                        String status, OffsetDateTime requestedAt, OffsetDateTime decidedAt) {}

    public record Run(String month, String status, int payslips, OffsetDateTime calculatedAt, OffsetDateTime approvedAt,
                      OffsetDateTime closedAt) {}

    public record Payslip(String registrationNo, String name, String number, String kind, String currency, BigDecimal gross,
                          BigDecimal employeeDeductions, BigDecimal net, BigDecimal employerCharges) {}

    public record EntryInput(@NotBlank String registrationNo, @NotBlank String element, @NotNull BigDecimal amount,
                             @Size(min = 3, max = 3) String currency, @Size(max = 200) String note) {}

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final AttendanceDays attendance;
    private final ClockIngest ingest;
    private final PayElements payElements;
    private final Accounting accounting;

    public PublicApiController(JdbcClient jdbc, UserRepository users, AttendanceDays attendance, ClockIngest ingest,
                               PayElements payElements, Accounting accounting) {
        this.jdbc = jdbc;
        this.users = users;
        this.attendance = attendance;
        this.ingest = ingest;
        this.payElements = payElements;
        this.accounting = accounting;
    }

    @GetMapping(value = "/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> openapi() throws IOException {
        try (InputStream in = new ClassPathResource("api/openapi.json").getInputStream()) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    // ---- people -----------------------------------------------------------------------------------------------

    private static final String EMPLOYEES = """
            select u.registration_no, u.first_name, u.last_name, u.email, u.phone_e164 as phone, u.status, s.code as site,
                   (select o.code from org_assignment a join org_unit o on o.id = a.unit_id
                    where a.user_id = u.id and a.valid_to is null limit 1) as unit,
                   u.hire_date, g.code as grade_category, m.registration_no as manager, coalesce(u.clock_pin, u.registration_no) as clock_pin,
                   u.middle_name as postnom
            from app_user u left join site s on s.id = u.site_id left join grade_category g on g.id = u.grade_category_id
            left join app_user m on m.id = u.manager_id""";

    @GetMapping("/employees")
    @PreAuthorize("hasAuthority('user.read')")
    public List<Employee> employees(@RequestParam(required = false) String status) {
        if (status != null && !status.isBlank()) {
            return jdbc.sql(EMPLOYEES + " where u.status = ? order by u.registration_no").param(status.toUpperCase())
                    .query(Employee.class).list();
        }
        return jdbc.sql(EMPLOYEES + " order by u.registration_no").query(Employee.class).list();
    }

    @GetMapping("/employees/{registrationNo}")
    @PreAuthorize("hasAuthority('user.read')")
    public Employee employee(@PathVariable String registrationNo) {
        return jdbc.sql(EMPLOYEES + " where upper(u.registration_no) = upper(?)").param(registrationNo).query(Employee.class)
                .optional().orElseThrow(() -> new NotFoundException("No one has this registration number."));
    }

    @GetMapping("/units")
    @PreAuthorize("hasAuthority('user.read')")
    public List<Unit> units() {
        return jdbc.sql("""
                select u.code, u.name, l.name as level, p.code as parent, u.valid_from, u.valid_to
                from org_unit u join org_level l on l.id = u.level_id left join org_unit p on p.id = u.parent_id
                order by u.code""").query(Unit.class).list();
    }

    // ---- attendance -------------------------------------------------------------------------------------------

    private static void range(LocalDate from, LocalDate to, int days) {
        if (to.isBefore(from)) {
            throw new BusinessRuleException("The end of the period is before its start.");
        }
        if (ChronoUnit.DAYS.between(from, to) > days) {
            throw new BusinessRuleException("Ask for at most %s days at a time.".formatted(days));
        }
    }

    private AppUser person(String registrationNo) {
        return users.findByRegistrationNoIgnoreCase(registrationNo.trim())
                .orElseThrow(() -> new NotFoundException("No one has this registration number."));
    }

    @GetMapping("/attendance/days")
    @PreAuthorize("hasAuthority('attendance.read.all')")
    public List<Day> days(@RequestParam LocalDate from, @RequestParam LocalDate to, @RequestParam(required = false) String registrationNo) {
        range(from, to, 31);
        List<AppUser> people = registrationNo != null ? List.of(person(registrationNo))
                : users.findAll().stream().filter(AppUser::isActive).toList();
        List<Day> out = new ArrayList<>();
        for (AppUser u : people) {
            for (DayCalculator.DayResult d : attendance.days(u, from, to)) {
                out.add(new Day(u.getRegistrationNo(), d.date(), d.status().name(), d.scheduledMinutes(), d.workedMinutes(),
                        d.payableMinutes(), d.lateMinutes(), d.overtimeMinutes(), d.firstIn(), d.lastOut(), d.missingCheckOut(),
                        d.leaveType()));
            }
        }
        return out;
    }

    @GetMapping("/attendance/events")
    @PreAuthorize("hasAuthority('attendance.read.all')")
    public List<Event> events(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        range(from, to, 31);
        return jdbc.sql("""
                select e.id, u.registration_no, e.type, e.occurred_at as at, e.work_date, e.source, s.code as site,
                       e.voided_at is not null as voided
                from clock_event e join app_user u on u.id = e.user_id left join site s on s.id = e.site_id
                where e.work_date between ? and ? order by e.occurred_at""").params(from, to).query(Event.class).list();
    }

    /** A check-in or check-out from another system (a time clock of another brand, an access control system). */
    @PostMapping("/attendance/events")
    @PreAuthorize("hasAuthority('attendance.correct')")
    public ResponseEntity<EventResult> addEvent(@Valid @RequestBody EventInput in) {
        AppUser user = person(in.registrationNo());
        ClockEvent.Type type = null;
        if (in.type() != null && !in.type().isBlank()) {
            try {
                type = ClockEvent.Type.valueOf(in.type().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BusinessRuleException("The type is IN or OUT.");
            }
        }
        if (in.at().isAfter(OffsetDateTime.now().plusMinutes(5))) {
            throw new BusinessRuleException("The time is in the future.");
        }
        ClockIngest.Outcome o = ingest.record(user, type, in.at(), ClockEvent.Source.API, null, in.note(), CurrentUser.email());
        return o.event() == null
                ? ResponseEntity.ok(new EventResult(false, null, null, o.ignored()))
                : ResponseEntity.status(HttpStatus.CREATED).body(new EventResult(true, o.event().getId(), o.event().getType().name(), null));
    }

    // ---- leave ------------------------------------------------------------------------------------------------

    @GetMapping("/leave/requests")
    @PreAuthorize("hasAuthority('leave.manage')")
    public List<Leave> leave(@RequestParam LocalDate from, @RequestParam LocalDate to, @RequestParam(required = false) String status) {
        range(from, to, 400);
        String sql = """
                select r.id, u.registration_no, t.code as type, r.start_date, r.end_date, r.working_days, r.status, r.requested_at, r.decided_at
                from leave_request r join app_user u on u.id = r.user_id join leave_type t on t.id = r.leave_type_id
                where r.end_date >= ? and r.start_date <= ?""" + (status == null || status.isBlank() ? "" : " and r.status = ?")
                + " order by r.start_date, r.id";
        var spec = jdbc.sql(sql).params(from, to);
        if (status != null && !status.isBlank()) {
            spec = spec.param(status.toUpperCase());
        }
        return spec.query(Leave.class).list();
    }

    // ---- payroll ----------------------------------------------------------------------------------------------

    @GetMapping("/payroll/runs")
    @PreAuthorize("hasAuthority('payslip.read.all')")
    public List<Run> runs() {
        return jdbc.sql("""
                select to_char(r.month, 'YYYY-MM') as month, r.status,
                       (select count(*) from payslip p where p.run_id = r.id and (p.kind = 'REGULAR' or p.number is not null)) as payslips,
                       r.calculated_at, r.approved_at, r.closed_at
                from payroll_run r order by r.month desc""").query(Run.class).list();
    }

    @GetMapping("/payroll/runs/{month}/payslips")
    @PreAuthorize("hasAuthority('payslip.read.all')")
    public List<Payslip> payslips(@PathVariable YearMonth month) {
        return jdbc.sql("""
                select u.registration_no, person_name(u.first_name, u.middle_name, u.last_name) as name, p.number, p.kind, trim(p.currency) as currency,
                       p.gross, p.employee_deductions, p.net, p.employer_charges
                from payslip p join payroll_run r on r.id = p.run_id join app_user u on u.id = p.user_id
                where r.month = ? and (p.kind = 'REGULAR' or p.number is not null)
                order by u.registration_no, p.id""").param(month.atDay(1)).query(Payslip.class).list();
    }

    /** A pay entry of an open month: a bonus, an allowance or a deduction worked out elsewhere. */
    @PostMapping("/payroll/runs/{month}/entries")
    @PreAuthorize("hasAuthority('payroll.run')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addEntry(@PathVariable YearMonth month, @Valid @RequestBody EntryInput in) {
        AppUser user = person(in.registrationNo());
        PayElements.Element e = payElements.element(in.element().trim().toUpperCase());
        String currency = in.currency() != null ? in.currency().toUpperCase() : e.defaultCurrency() != null ? e.defaultCurrency() : "USD";
        PayElements.Entry entry = payElements.addEntry(user.getId(), e.id(), month, in.amount(), currency, in.note());
        return Map.of("id", entry.id(), "registrationNo", user.getRegistrationNo(), "element", e.code(), "month", month.toString(),
                "amount", in.amount(), "currency", currency);
    }

    @GetMapping("/accounting/{month}")
    @PreAuthorize("hasAuthority('accounting.export')")
    public Accounting.Journal journal(@PathVariable YearMonth month, @RequestParam(defaultValue = "false") boolean byCostCentre) {
        return accounting.journal(month, byCostCentre);
    }
}
