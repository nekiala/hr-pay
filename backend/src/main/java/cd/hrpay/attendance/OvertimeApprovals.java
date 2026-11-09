package cd.hrpay.attendance;

import cd.hrpay.attendance.DayCalculator.DayResult;
import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.overtime.OvertimeDecisions;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import cd.hrpay.user.UserService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Overtime is paid only once approved, by HR for everyone or by a manager for their team (see OrgStructure.teamOf). */
@Service
public class OvertimeApprovals {

    public record PendingDay(long userId, String employeeName, String registrationNo, LocalDate date, int minutes,
                             String kind, OffsetDateTime firstIn, OffsetDateTime lastOut) {}

    private final AttendanceDays attendance;
    private final OvertimeDecisions decisions;
    private final UserRepository userRepository;
    private final UserService users;
    private final AuditService audit;
    private cd.hrpay.org.OrgStructure org;

    @org.springframework.beans.factory.annotation.Autowired
    void setOrg(cd.hrpay.org.OrgStructure org) {
        this.org = org;
    }

    public OvertimeApprovals(AttendanceDays attendance, OvertimeDecisions decisions, UserRepository userRepository,
                             UserService users, AuditService audit) {
        this.attendance = attendance;
        this.decisions = decisions;
        this.userRepository = userRepository;
        this.users = users;
        this.audit = audit;
    }

    private List<AppUser> scope() {
        List<AppUser> list = CurrentUser.has("overtime.approve.all") ? userRepository.findAll()
                : CurrentUser.has("overtime.approve.team") ? userRepository.findAllById(org.teamOf(CurrentUser.id()))
                : List.of();
        return list.stream().filter(AppUser::isActive).filter(u -> !u.getId().equals(CurrentUser.id())).toList();
    }

    private boolean inScope(AppUser user) {
        if (user.getId().equals(CurrentUser.id())) {
            return false;
        }
        return CurrentUser.has("overtime.approve.all")
                || (CurrentUser.has("overtime.approve.team") && org.inTeam(CurrentUser.id(), user.getId()));
    }

    @Transactional
    public List<PendingDay> pending(LocalDate from, LocalDate to) {
        List<PendingDay> result = new ArrayList<>();
        for (AppUser u : scope()) {
            Set<LocalDate> done = decisions.forPeriod(u.getId(), from, to).stream()
                    .map(OvertimeDecisions.Decision::workDate).collect(Collectors.toSet());
            for (DayResult d : attendance.days(u, from, to)) {
                if (d.overtimeMinutes() > 0 && !done.contains(d.date()) && d.status() != DayCalculator.Status.IN_PROGRESS) {
                    result.add(new PendingDay(u.getId(), u.fullName(), u.getRegistrationNo(),
                            d.date(), d.overtimeMinutes(), d.overtimeKind().name(), d.firstIn(), d.lastOut()));
                }
            }
        }
        result.sort(java.util.Comparator.comparing(PendingDay::date).thenComparing(PendingDay::employeeName));
        return result;
    }

    @Transactional
    public OvertimeDecisions.Decision decide(long userId, LocalDate date, boolean approve, Integer minutes, String note) {
        AppUser user = users.find(userId);
        if (!inScope(user)) {
            throw new AccessDeniedException("You cannot decide on this employee's overtime.");
        }
        if (decisions.find(userId, date).isPresent()) {
            throw new ConflictException("Overtime for " + date + " was already decided.");
        }
        DayResult day = attendance.days(user, date, date).getFirst();
        if (day.overtimeMinutes() <= 0) {
            throw new BusinessRuleException("There is no overtime on " + date + ".");
        }
        if (day.status() == DayCalculator.Status.IN_PROGRESS) {
            throw new BusinessRuleException("Wait until the day is over.");
        }
        int approved = approve ? (minutes == null ? day.overtimeMinutes() : minutes) : 0;
        if (approved < 0 || approved > day.overtimeMinutes()) {
            throw new BusinessRuleException("Approve between 0 and " + day.overtimeMinutes() + " minutes.");
        }
        if (!approve && (note == null || note.isBlank())) {
            throw new BusinessRuleException("Give a reason for the rejection.");
        }
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        decisions.insert(userId, date, day.overtimeMinutes(), approved, day.overtimeKind().name(),
                approve ? "APPROVED" : "REJECTED", cleanNote, CurrentUser.email());
        audit.record(approve ? "overtime.approved" : "overtime.rejected", "user", userId,
                Map.of("date", date.toString(), "computedMinutes", day.overtimeMinutes(), "approvedMinutes", approved));
        return decisions.find(userId, date).orElseThrow();
    }
}
