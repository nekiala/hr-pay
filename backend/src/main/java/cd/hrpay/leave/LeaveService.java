package cd.hrpay.leave;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.schedule.WorkCalendar;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import cd.hrpay.user.UserService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leave requests, approvals and balances. A balance is the sum of its ledger rows: yearly grants from the
 * employee's grade category, approved leave taken, HR adjustments and payouts. Unused days carry over.
 */
@Service
public class LeaveService {

    public record LeaveType(long id, String code, String name, boolean paid, boolean usesBalance,
                            boolean requiresDocument, Integer maxDays, boolean active) {}

    public record GradeCategory(long id, String code, String name, BigDecimal annualLeaveDays, boolean active) {}

    public record LeaveRequest(long id, long userId, String employeeName, String registrationNo, long leaveTypeId,
                               String leaveType, LocalDate startDate, LocalDate endDate, BigDecimal workingDays,
                               String reason, String status, OffsetDateTime requestedAt, String decidedBy,
                               OffsetDateTime decidedAt, String decisionNote) {}

    public record Balance(long leaveTypeId, String leaveType, BigDecimal granted, BigDecimal taken,
                          BigDecimal pending, BigDecimal available) {}

    public record LedgerEntry(long id, String leaveType, LocalDate entryDate, BigDecimal days, String kind,
                              Integer year, String note, String createdBy) {}

    public record LeaveDay(String type, boolean paid) {}

    private static final String REQUEST_SELECT = """
            select r.id, r.user_id, u.first_name || ' ' || u.last_name as employee_name, u.registration_no,
                   r.leave_type_id, t.name as leave_type, r.start_date, r.end_date, r.working_days, r.reason, r.status,
                   r.requested_at, r.decided_by, r.decided_at, r.decision_note
            from leave_request r join app_user u on u.id = r.user_id join leave_type t on t.id = r.leave_type_id
            """;

    private final JdbcClient jdbc;
    private final UserService users;
    private final UserRepository userRepository;
    private final WorkCalendar calendar;
    private final AuditService audit;
    private final Clock clock;

    public LeaveService(JdbcClient jdbc, UserService users, UserRepository userRepository, WorkCalendar calendar,
                        AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.userRepository = userRepository;
        this.calendar = calendar;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- Settings -------------------------------------------------------------------------------------------

    public List<LeaveType> types() {
        return jdbc.sql("select id, code, name, paid, uses_balance, requires_document, max_days, active from leave_type order by id")
                .query(LeaveType.class).list();
    }

    public List<GradeCategory> categories() {
        return jdbc.sql("select id, code, name, annual_leave_days, active from grade_category order by code")
                .query(GradeCategory.class).list();
    }

    @Transactional
    public GradeCategory saveCategory(Long id, String code, String name, BigDecimal days, boolean active) {
        if (id == null) {
            if (jdbc.sql("select count(*) from grade_category where upper(code) = upper(?)").param(code)
                    .query(Integer.class).single() > 0) {
                throw new ConflictException("The code " + code + " is already used.");
            }
            id = jdbc.sql("insert into grade_category (code, name, annual_leave_days, active) values (?, ?, ?, ?) returning id")
                    .params(code.toUpperCase(), name, days, active).query(Long.class).single();
        } else if (jdbc.sql("update grade_category set name = ?, annual_leave_days = ?, active = ? where id = ?")
                .params(name, days, active, id).update() == 0) {
            throw new NotFoundException("Grade category not found.");
        }
        audit.record("grade_category.saved", "grade_category", id, Map.of("name", name, "annualLeaveDays", days));
        long savedId = id;
        return categories().stream().filter(c -> c.id() == savedId).findFirst().orElseThrow();
    }

    @Transactional
    public LeaveType updateType(long id, String name, boolean paid, boolean usesBalance, boolean requiresDocument,
                                Integer maxDays, boolean active) {
        if (jdbc.sql("""
                update leave_type set name = ?, paid = ?, uses_balance = ?, requires_document = ?, max_days = ?, active = ?
                where id = ?""").params(name, paid, usesBalance, requiresDocument, maxDays, active, id).update() == 0) {
            throw new NotFoundException("Leave type not found.");
        }
        audit.record("leave_type.updated", "leave_type", id, Map.of("name", name, "paid", paid,
                "usesBalance", usesBalance));
        return type(id);
    }

    private LeaveType type(long id) {
        return types().stream().filter(t -> t.id() == id).findFirst()
                .orElseThrow(() -> new NotFoundException("Leave type not found."));
    }

    // ---- Balances -------------------------------------------------------------------------------------------

    public List<Balance> balances(long userId) {
        return jdbc.sql("""
                select t.id as leave_type_id, t.name as leave_type,
                       coalesce(sum(l.days) filter (where l.kind in ('GRANT', 'ADJUSTMENT')), 0) as granted,
                       -coalesce(sum(l.days) filter (where l.kind in ('TAKEN', 'RETURNED', 'PAYOUT')), 0) as taken,
                       coalesce((select sum(r.working_days) from leave_request r
                                 where r.user_id = :user and r.leave_type_id = t.id and r.status = 'PENDING'), 0) as pending,
                       coalesce(sum(l.days), 0) as available
                from leave_type t left join leave_ledger l on l.leave_type_id = t.id and l.user_id = :user
                where t.uses_balance group by t.id, t.name order by t.id""")
                .param("user", userId).query(Balance.class).list();
    }

    public List<LedgerEntry> ledger(long userId) {
        return jdbc.sql("""
                select l.id, t.name as leave_type, l.entry_date, l.days, l.kind, l.year, l.note, l.created_by
                from leave_ledger l join leave_type t on t.id = l.leave_type_id
                where l.user_id = ? order by l.entry_date desc, l.id desc""").param(userId).query(LedgerEntry.class).list();
    }

    private BigDecimal available(long userId, long typeId) {
        return jdbc.sql("select coalesce(sum(days), 0) from leave_ledger where user_id = ? and leave_type_id = ?")
                .params(userId, typeId).query(BigDecimal.class).single();
    }

    private BigDecimal pending(long userId, long typeId) {
        return jdbc.sql("select coalesce(sum(working_days), 0) from leave_request where user_id = ? and leave_type_id = ? and status = 'PENDING'")
                .params(userId, typeId).query(BigDecimal.class).single();
    }

    /**
     * Grants each active employee's annual leave for a year from their grade category, prorated by the months
     * left in the year when hired during it. Already granted employees are skipped, so it can be run again.
     */
    @Transactional
    public Map<String, Integer> grantAnnual(int year) {
        long annualId = jdbc.sql("select id from leave_type where code = 'ANNUAL'").query(Long.class).single();
        int granted = 0;
        int skipped = 0;
        int noCategory = 0;
        for (AppUser u : userRepository.findAll()) {
            if (!u.isActive()) {
                continue;
            }
            if (u.getGradeCategoryId() == null) {
                noCategory++;
                continue;
            }
            BigDecimal days = jdbc.sql("select annual_leave_days from grade_category where id = ?")
                    .param(u.getGradeCategoryId()).query(BigDecimal.class).single();
            LocalDate hired = u.getHireDate();
            if (hired != null && hired.getYear() > year) {
                continue;
            }
            if (hired != null && hired.getYear() == year) {
                int months = 12 - hired.getMonthValue() + 1;
                days = days.multiply(BigDecimal.valueOf(months)).divide(BigDecimal.valueOf(12), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.TWO).setScale(0, RoundingMode.HALF_UP).divide(BigDecimal.TWO, 2, RoundingMode.UNNECESSARY);
            }
            int rows = jdbc.sql("""
                    insert into leave_ledger (user_id, leave_type_id, entry_date, days, kind, year, note, created_by)
                    values (?, ?, ?, ?, 'GRANT', ?, ?, ?) on conflict do nothing""")
                    .params(u.getId(), annualId, LocalDate.of(year, 1, 1), days, year, "Annual leave " + year,
                            CurrentUser.email()).update();
            if (rows == 1) {
                granted++;
            } else {
                skipped++;
            }
        }
        audit.record("leave.granted_annual", "leave_type", annualId, Map.of("year", year, "granted", granted,
                "alreadyGranted", skipped, "withoutCategory", noCategory));
        return Map.of("granted", granted, "alreadyGranted", skipped, "withoutCategory", noCategory);
    }

    @Transactional
    public void adjust(long userId, long typeId, BigDecimal days, String note) {
        users.find(userId);
        if (userId == CurrentUser.id()) {
            throw new AccessDeniedException("You cannot adjust your own leave balance.");
        }
        if (days.signum() == 0) {
            throw new BusinessRuleException("Enter a number of days other than zero.");
        }
        jdbc.sql("""
                insert into leave_ledger (user_id, leave_type_id, entry_date, days, kind, note, created_by)
                values (?, ?, ?, ?, 'ADJUSTMENT', ?, ?)""")
                .params(userId, typeId, today(), days, note.trim(), CurrentUser.email()).update();
        audit.record("leave.adjusted", "user", userId, Map.of("leaveTypeId", typeId, "days", days, "note", note));
    }

    // ---- Requests -------------------------------------------------------------------------------------------

    /** Days the employee is expected at work in the period: rest days and public holidays are not counted. */
    @Transactional
    public BigDecimal workingDays(AppUser user, LocalDate start, LocalDate end) {
        return BigDecimal.valueOf(calendar.plan(user, start, end).stream().filter(WorkCalendar.DayPlan::expectedAtWork).count());
    }

    @Transactional
    public LeaveRequest request(AppUser user, long typeId, LocalDate start, LocalDate end, String reason) {
        LeaveType type = type(typeId);
        if (!type.active()) {
            throw new BusinessRuleException("This kind of leave is not available.");
        }
        if (end.isBefore(start)) {
            throw new BusinessRuleException("The leave must end on or after its first day.");
        }
        if (start.isBefore(today().minusDays(60)) || end.isAfter(today().plusYears(1))) {
            throw new BusinessRuleException("Choose dates from 60 days ago to one year ahead.");
        }
        long calendarDays = ChronoUnit.DAYS.between(start, end) + 1;
        if (type.maxDays() != null && calendarDays > type.maxDays()) {
            throw new BusinessRuleException(type.name() + " lasts at most " + type.maxDays() + " days.");
        }
        BigDecimal days = workingDays(user, start, end);
        if (days.signum() == 0) {
            throw new BusinessRuleException("There are no working days in this period.");
        }
        int overlapping = jdbc.sql("""
                select count(*) from leave_request where user_id = ? and status in ('PENDING', 'APPROVED')
                and start_date <= ? and end_date >= ?""").params(user.getId(), end, start).query(Integer.class).single();
        if (overlapping > 0) {
            throw new ConflictException("You already have leave requested or approved in this period.");
        }
        if (type.usesBalance()) {
            BigDecimal free = available(user.getId(), typeId).subtract(pending(user.getId(), typeId));
            if (free.compareTo(days) < 0) {
                throw new BusinessRuleException("This needs " + days.stripTrailingZeros().toPlainString()
                        + " days but only " + free.stripTrailingZeros().toPlainString() + " are available.");
            }
        }
        long id = jdbc.sql("""
                insert into leave_request (user_id, leave_type_id, start_date, end_date, working_days, reason, requested_by)
                values (?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(user.getId(), typeId, start, end, days, reason == null || reason.isBlank() ? null : reason.trim(),
                        user.getEmail()).query(Long.class).single();
        audit.record("leave.requested", "leave_request", id, Map.of("userId", user.getId(), "type", type.code(),
                "start", start.toString(), "end", end.toString(), "days", days));
        return find(id);
    }

    public LeaveRequest find(long id) {
        return jdbc.sql(REQUEST_SELECT + " where r.id = ?").param(id).query(LeaveRequest.class).optional()
                .orElseThrow(() -> new NotFoundException("Leave request not found."));
    }

    public List<LeaveRequest> forUser(long userId) {
        return jdbc.sql(REQUEST_SELECT + " where r.user_id = ? order by r.start_date desc").param(userId)
                .query(LeaveRequest.class).list();
    }

    public List<LeaveRequest> toDecide() {
        Long me = CurrentUser.id();
        if (CurrentUser.has("leave.manage")) {
            return jdbc.sql(REQUEST_SELECT + " where r.status = 'PENDING' and r.user_id <> ? order by r.start_date")
                    .param(me).query(LeaveRequest.class).list();
        }
        if (CurrentUser.has("leave.approve.team")) {
            return jdbc.sql(REQUEST_SELECT + " where r.status = 'PENDING' and u.manager_id = ? order by r.start_date")
                    .param(me).query(LeaveRequest.class).list();
        }
        return List.of();
    }

    private boolean canDecide(LeaveRequest r) {
        if (r.userId() == CurrentUser.id()) {
            return false;
        }
        return CurrentUser.has("leave.manage") || (CurrentUser.has("leave.approve.team")
                && CurrentUser.id().equals(users.find(r.userId()).getManagerId()));
    }

    @Transactional
    public LeaveRequest decide(long id, boolean approve, String note) {
        LeaveRequest r = find(id);
        if (!canDecide(r)) {
            throw new AccessDeniedException("You cannot decide on this leave request.");
        }
        if (!r.status().equals("PENDING")) {
            throw new BusinessRuleException("This request was already decided.");
        }
        if (!approve && (note == null || note.isBlank())) {
            throw new BusinessRuleException("Give a reason for the rejection.");
        }
        LeaveType type = type(r.leaveTypeId());
        if (approve && type.usesBalance()) {
            BigDecimal free = available(r.userId(), type.id());
            if (free.compareTo(r.workingDays()) < 0) {
                throw new BusinessRuleException("Only " + free.stripTrailingZeros().toPlainString()
                        + " days are left in the balance.");
            }
            jdbc.sql("""
                    insert into leave_ledger (user_id, leave_type_id, entry_date, days, kind, leave_request_id, note, created_by)
                    values (?, ?, ?, ?, 'TAKEN', ?, ?, ?)""")
                    .params(r.userId(), type.id(), r.startDate(), r.workingDays().negate(), id,
                            r.startDate() + " to " + r.endDate(), CurrentUser.email()).update();
        }
        setStatus(id, approve ? "APPROVED" : "REJECTED", note);
        audit.record(approve ? "leave.approved" : "leave.rejected", "leave_request", id,
                note == null ? Map.of() : Map.of("note", note));
        return find(id);
    }

    /** The employee cancels a pending request; HR can also cancel an approved one, which returns the days. */
    @Transactional
    public LeaveRequest cancel(long id, String note) {
        LeaveRequest r = find(id);
        boolean own = r.userId() == CurrentUser.id();
        if (r.status().equals("PENDING") && own) {
            setStatus(id, "CANCELLED", note);
        } else if (r.status().equals("APPROVED") && CurrentUser.has("leave.manage") && !own) {
            if (note == null || note.isBlank()) {
                throw new BusinessRuleException("Give a reason for cancelling approved leave.");
            }
            if (type(r.leaveTypeId()).usesBalance()) {
                jdbc.sql("""
                        insert into leave_ledger (user_id, leave_type_id, entry_date, days, kind, leave_request_id, note, created_by)
                        values (?, ?, ?, ?, 'RETURNED', ?, ?, ?)""")
                        .params(r.userId(), r.leaveTypeId(), today(), r.workingDays(), id, "Cancelled: " + note.trim(),
                                CurrentUser.email()).update();
            }
            setStatus(id, "CANCELLED", note);
        } else {
            throw new BusinessRuleException("This request cannot be cancelled.");
        }
        audit.record("leave.cancelled", "leave_request", id, note == null ? Map.of() : Map.of("note", note));
        return find(id);
    }

    private void setStatus(long id, String status, String note) {
        jdbc.sql("update leave_request set status = ?, decided_by = ?, decided_at = now(), decision_note = ? where id = ?")
                .params(status, CurrentUser.email(), note == null || note.isBlank() ? null : note.trim(), id).update();
    }

    /** Approved leave by date, for attendance: those days are not absences. */
    public Map<LocalDate, LeaveDay> approvedDays(long userId, LocalDate from, LocalDate to) {
        Map<LocalDate, LeaveDay> days = new HashMap<>();
        jdbc.sql("""
                select r.start_date, r.end_date, t.name, t.paid from leave_request r join leave_type t on t.id = r.leave_type_id
                where r.user_id = ? and r.status = 'APPROVED' and r.start_date <= ? and r.end_date >= ?""")
                .params(userId, to, from)
                .query((rs, n) -> {
                    LocalDate s = rs.getObject(1, LocalDate.class);
                    LocalDate e = rs.getObject(2, LocalDate.class);
                    for (LocalDate d = s; !d.isAfter(e); d = d.plusDays(1)) {
                        days.put(d, new LeaveDay(rs.getString(3), rs.getBoolean(4)));
                    }
                    return null;
                }).list();
        return days;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneId.of("Africa/Kinshasa")));
    }
}
