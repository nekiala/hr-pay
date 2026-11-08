package cd.hrpay.schedule;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ScheduleController {

    public record DayInput(@Min(1) @Max(7) int weekday, boolean workday, LocalTime startTime, LocalTime endTime,
                           boolean lunchEnabled, LocalTime lunchStart, Integer lunchMinutes, boolean lunchPaid) {}

    public record ScheduleInput(
            @NotBlank @Pattern(regexp = "[A-Z0-9_-]{2,32}", message = "Use 2 to 32 capital letters, digits, - or _")
            String code,
            @NotBlank @Size(max = 150) String name,
            @Min(0) @Max(120) int graceMinutes,
            boolean deductLateArrival, boolean deductEarlyDeparture, boolean active,
            @NotNull @Size(min = 7, max = 7) List<@Valid DayInput> days) {}

    public record DayView(int weekday, boolean workday, LocalTime startTime, LocalTime endTime, boolean lunchEnabled,
                          LocalTime lunchStart, Integer lunchMinutes, boolean lunchPaid, int scheduledMinutes) {}

    public record ScheduleView(Long id, String code, String name, int graceMinutes, boolean deductLateArrival,
                               boolean deductEarlyDeparture, boolean active, int weeklyMinutes, List<DayView> days,
                               List<String> warnings) {
        static ScheduleView of(WorkSchedule s) {
            List<DayView> days = s.getDays().stream()
                    .map(d -> new DayView(d.getWeekday(), d.isWorkday(), d.getStartTime(), d.getEndTime(),
                            d.isLunchEnabled(), d.getLunchStart(), d.getLunchMinutes(), d.isLunchPaid(),
                            d.scheduledMinutes())).toList();
            return new ScheduleView(s.getId(), s.getCode(), s.getName(), s.getGraceMinutes(), s.isDeductLateArrival(),
                    s.isDeductEarlyDeparture(), s.isActive(), s.weeklyMinutes(), days, legalWarnings(days));
        }
    }

    /**
     * The legal working day is 8 hours (DRC Code du travail art. 119 as amended by Loi 16/010; Republic of the Congo, 40 hours a
     * week over 5 days). A longer planned day is allowed but the time above is overtime.
     */
    static final int LEGAL_DAY_MINUTES = 480;

    static List<String> legalWarnings(List<DayView> days) {
        List<String> out = new java.util.ArrayList<>();
        for (DayView d : days) {
            if (d.workday() && d.scheduledMinutes() > LEGAL_DAY_MINUTES) {
                out.add(Texts.t("%s: %s of work planned, more than the legal 8 hours a day; the time above is overtime.",
                        java.time.DayOfWeek.of(d.weekday()).getDisplayName(java.time.format.TextStyle.FULL, Texts.locale()),
                        hours(d.scheduledMinutes())));
            }
        }
        return out;
    }

    private static String hours(int minutes) {
        return minutes / 60 + "h" + (minutes % 60 == 0 ? "" : String.format("%02d", minutes % 60));
    }

    public record AssignInput(@NotNull Long scheduleId, @NotNull LocalDate effectiveFrom) {}

    public record AssignmentView(Long id, Long scheduleId, String scheduleName, LocalDate effectiveFrom) {
        static AssignmentView of(UserSchedule u) {
            return new AssignmentView(u.getId(), u.getSchedule().getId(), u.getSchedule().getName(), u.getEffectiveFrom());
        }
    }

    private final WorkScheduleRepository schedules;
    private final UserScheduleRepository assignments;
    private final UserRepository users;
    private final AuditService audit;

    public ScheduleController(WorkScheduleRepository schedules, UserScheduleRepository assignments,
                              UserRepository users, AuditService audit) {
        this.schedules = schedules;
        this.assignments = assignments;
        this.users = users;
        this.audit = audit;
    }

    @GetMapping("/schedules")
    @PreAuthorize("hasAuthority('schedule.manage') or hasAuthority('user.read')")
    public List<ScheduleView> list() {
        return schedules.findAllByOrderByNameAsc().stream().map(ScheduleView::of).toList();
    }

    @GetMapping("/schedules/{id}")
    @PreAuthorize("hasAuthority('schedule.manage') or hasAuthority('user.read')")
    public ScheduleView get(@PathVariable Long id) {
        return ScheduleView.of(find(id));
    }

    @PostMapping("/schedules")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('schedule.manage')")
    @Transactional
    public ScheduleView create(@Valid @RequestBody ScheduleInput body) {
        if (schedules.existsByCodeIgnoreCase(body.code())) {
            throw new ConflictException("A schedule with code " + body.code() + " already exists.");
        }
        WorkSchedule s = new WorkSchedule(body.code());
        apply(s, body);
        schedules.save(s);
        audit.record("schedule.created", "work_schedule", s.getId(), Map.of("code", s.getCode(),
                "weeklyMinutes", s.weeklyMinutes()));
        return ScheduleView.of(s);
    }

    @PutMapping("/schedules/{id}")
    @PreAuthorize("hasAuthority('schedule.manage')")
    @Transactional
    public ScheduleView update(@PathVariable Long id, @Valid @RequestBody ScheduleInput body) {
        WorkSchedule s = find(id);
        if (!s.getCode().equalsIgnoreCase(body.code())) {
            throw new BusinessRuleException("A schedule's code cannot be changed.");
        }
        int before = s.weeklyMinutes();
        apply(s, body);
        audit.record("schedule.updated", "work_schedule", s.getId(),
                Map.of("weeklyMinutesBefore", before, "weeklyMinutesAfter", s.weeklyMinutes()));
        return ScheduleView.of(s);
    }

    @GetMapping("/users/{userId}/schedules")
    @PreAuthorize("hasAuthority('user.read')")
    public List<AssignmentView> assignments(@PathVariable Long userId) {
        return assignments.findByUserIdOrderByEffectiveFromDesc(userId).stream().map(AssignmentView::of).toList();
    }

    @PostMapping("/users/{userId}/schedules")
    @PreAuthorize("hasAuthority('schedule.manage')")
    @Transactional
    public AssignmentView assign(@PathVariable Long userId, @Valid @RequestBody AssignInput body) {
        users.findById(userId).orElseThrow(() -> new NotFoundException("User not found."));
        WorkSchedule schedule = find(body.scheduleId());
        UserSchedule assignment = assignments.findByUserIdAndEffectiveFrom(userId, body.effectiveFrom())
                .orElseGet(() -> new UserSchedule(userId, schedule, body.effectiveFrom()));
        assignment.setSchedule(schedule);
        assignments.save(assignment);
        audit.record("schedule.assigned", "user", userId, Map.of("schedule", schedule.getCode(),
                "effectiveFrom", body.effectiveFrom().toString()));
        return AssignmentView.of(assignment);
    }

    private WorkSchedule find(Long id) {
        return schedules.findById(id).orElseThrow(() -> new NotFoundException("Schedule not found."));
    }

    private static void apply(WorkSchedule s, ScheduleInput body) {
        Set<Integer> seen = new HashSet<>();
        List<ScheduleDay> days = body.days().stream().map(d -> {
            if (!seen.add(d.weekday())) {
                throw new BusinessRuleException("Each weekday must appear once.");
            }
            if (d.workday()) {
                if (d.startTime() == null || d.endTime() == null || !d.startTime().isBefore(d.endTime())) {
                    throw new BusinessRuleException("Day " + d.weekday() + ": the start must be before the end.");
                }
                if (d.lunchEnabled()) {
                    if (d.lunchStart() == null || d.lunchMinutes() == null || d.lunchMinutes() <= 0) {
                        throw new BusinessRuleException("Day " + d.weekday() + ": set the lunch start and duration.");
                    }
                    LocalTime lunchEnd = d.lunchStart().plus(Duration.ofMinutes(d.lunchMinutes()));
                    if (d.lunchStart().isBefore(d.startTime()) || lunchEnd.isAfter(d.endTime())
                            || lunchEnd.isBefore(d.lunchStart())) {
                        throw new BusinessRuleException("Day " + d.weekday() + ": lunch must fall within working hours.");
                    }
                }
            }
            return new ScheduleDay(d.weekday(), d.workday(), d.startTime(), d.endTime(), d.lunchEnabled(),
                    d.lunchStart(), d.lunchMinutes(), d.lunchPaid());
        }).sorted(java.util.Comparator.comparingInt(ScheduleDay::getWeekday)).toList();
        s.setName(body.name().trim());
        s.setGraceMinutes(body.graceMinutes());
        s.setDeductLateArrival(body.deductLateArrival());
        s.setDeductEarlyDeparture(body.deductEarlyDeparture());
        s.setActive(body.active());
        s.setDays(days);
    }
}
