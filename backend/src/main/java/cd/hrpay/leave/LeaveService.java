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
                            boolean requiresDocument, Integer maxDays, boolean active, BigDecimal payPercent) {}

    public record GradeCategory(long id, String code, String name, BigDecimal annualLeaveDays, boolean active,
                                BigDecimal anniversaryRaisePercent, String raiseTiming) {}

    public record LeaveRequest(long id, long userId, String employeeName, String registrationNo, long leaveTypeId,
                               String leaveType, LocalDate startDate, LocalDate endDate, BigDecimal workingDays,
                               String reason, String status, OffsetDateTime requestedAt, String decidedBy,
                               OffsetDateTime decidedAt, String decisionNote, String halfDay, boolean requiresDocument,
                               int documents, String documentCheckedBy) {}

    public record Document(long id, long leaveRequestId, String fileName, String contentType, int sizeBytes,
                           String uploadedBy, OffsetDateTime uploadedAt) {}

    public record DocumentFile(String fileName, String contentType, byte[] data) {}

    static final java.util.Set<String> DOCUMENT_TYPES = java.util.Set.of("application/pdf", "image/jpeg", "image/png");
    static final int DOCUMENT_MAX_BYTES = 5 * 1024 * 1024;

    public record Balance(long leaveTypeId, String leaveType, BigDecimal granted, BigDecimal taken,
                          BigDecimal pending, BigDecimal available) {}

    public record LedgerEntry(long id, String leaveType, LocalDate entryDate, BigDecimal days, String kind,
                              Integer year, String note, String createdBy) {}

    /** One day of approved leave: its kind, the share of pay it keeps, and AM or PM for a half day. */
    public record LeaveDay(String type, BigDecimal payPercent, String halfDay) {}

    private static final String REQUEST_SELECT = """
            select r.id, r.user_id, u.first_name || ' ' || u.last_name as employee_name, u.registration_no,
                   r.leave_type_id, t.name as leave_type, r.start_date, r.end_date, r.working_days, r.reason, r.status,
                   r.requested_at, r.decided_by, r.decided_at, r.decision_note, r.half_day, t.requires_document,
                   (select count(*) from leave_document d where d.leave_request_id = r.id) as documents, r.document_checked_by
            from leave_request r join app_user u on u.id = r.user_id join leave_type t on t.id = r.leave_type_id
            """;

    private final JdbcClient jdbc;
    private final UserService users;
    private final UserRepository userRepository;
    private final WorkCalendar calendar;
    private final AuditService audit;
    private final Clock clock;
    private cd.hrpay.org.OrgStructure org;

    @org.springframework.beans.factory.annotation.Autowired
    void setOrg(cd.hrpay.org.OrgStructure org) {
        this.org = org;
    }

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
        return jdbc.sql("select id, code, name, paid, uses_balance, requires_document, max_days, active, pay_percent from leave_type order by id")
                .query(LeaveType.class).list();
    }

    public List<GradeCategory> categories() {
        return jdbc.sql("select id, code, name, annual_leave_days, active, anniversary_raise_percent, raise_timing from grade_category order by code")
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
    public LeaveType updateType(long id, String name, BigDecimal payPercent, boolean usesBalance, boolean requiresDocument,
                                Integer maxDays, boolean active) {
        boolean paid = payPercent.signum() > 0;
        if (jdbc.sql("""
                update leave_type set name = ?, paid = ?, pay_percent = ?, uses_balance = ?, requires_document = ?, max_days = ?,
                active = ? where id = ?""").params(name, paid, payPercent, usesBalance, requiresDocument, maxDays, active, id)
                .update() == 0) {
            throw new NotFoundException("Leave type not found.");
        }
        audit.record("leave_type.updated", "leave_type", id, Map.of("name", name, "payPercent", payPercent,
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
            int every = Integer.parseInt(setting("LEAVE_SENIORITY_YEARS", "0"));
            if (hired != null && every > 0) {
                // Full years of service on 1 January of the year.
                long years = ChronoUnit.YEARS.between(hired, LocalDate.of(year, 1, 1));
                if (years >= every) {
                    days = days.add(new BigDecimal(setting("LEAVE_SENIORITY_DAYS", "0")).multiply(BigDecimal.valueOf(years / every)));
                }
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
        if (java.util.Objects.equals(userId, CurrentUser.id())) {
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

    private String setting(String key, String fallback) {
        return jdbc.sql("select value from payroll_setting where key = ?").param(key).query(String.class).optional().orElse(fallback);
    }

    public Map<String, String> settings() {
        return Map.of("LEAVE_SENIORITY_YEARS", setting("LEAVE_SENIORITY_YEARS", "0"),
                "LEAVE_SENIORITY_DAYS", setting("LEAVE_SENIORITY_DAYS", "0"));
    }

    @Transactional
    public Map<String, String> saveSettings(int seniorityYears, BigDecimal seniorityDays) {
        jdbc.sql("""
                insert into payroll_setting (key, value) values ('LEAVE_SENIORITY_YEARS', ?), ('LEAVE_SENIORITY_DAYS', ?)
                on conflict (key) do update set value = excluded.value""")
                .params(String.valueOf(seniorityYears), seniorityDays.stripTrailingZeros().toPlainString()).update();
        audit.record("leave.settings_changed", "payroll_setting", null, Map.of("seniorityYears", seniorityYears,
                "seniorityDays", seniorityDays));
        return settings();
    }

    // ---- Requests -------------------------------------------------------------------------------------------

    /** Days the employee is expected at work in the period: rest days and public holidays are not counted. */
    @Transactional
    public BigDecimal workingDays(AppUser user, LocalDate start, LocalDate end) {
        return BigDecimal.valueOf(calendar.plan(user, start, end).stream().filter(WorkCalendar.DayPlan::expectedAtWork).count());
    }

    @Transactional
    public LeaveRequest request(AppUser user, long typeId, LocalDate start, LocalDate end, String reason) {
        return request(user, typeId, start, end, null, reason);
    }

    @Transactional
    public LeaveRequest request(AppUser user, long typeId, LocalDate start, LocalDate end, String halfDay, String reason) {
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
        if (halfDay != null && !start.equals(end)) {
            throw new BusinessRuleException("A half day covers a single day.");
        }
        BigDecimal days = workingDays(user, start, end);
        if (days.signum() == 0) {
            throw new BusinessRuleException("There are no working days in this period.");
        }
        if (halfDay != null) {
            days = new BigDecimal("0.5");
        }
        int overlapping = jdbc.sql("""
                select count(*) from leave_request where user_id = ? and status in ('PENDING', 'AWAITING_HR', 'APPROVED')
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
                insert into leave_request (user_id, leave_type_id, start_date, end_date, working_days, reason, requested_by, half_day)
                values (?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(user.getId(), typeId, start, end, days, reason == null || reason.isBlank() ? null : reason.trim(),
                        user.getEmail(), halfDay).query(Long.class).single();
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
            return jdbc.sql(REQUEST_SELECT + " where r.status in ('PENDING', 'AWAITING_HR') and r.user_id <> ? order by r.start_date")
                    .param(me).query(LeaveRequest.class).list();
        }
        if (CurrentUser.has("leave.approve.team")) {
            java.util.Set<Long> team = org.teamOf(me);
            return jdbc.sql(REQUEST_SELECT + " where r.status = 'PENDING' and r.user_id <> ? order by r.start_date")
                    .param(me).query(LeaveRequest.class).list().stream().filter(r -> team.contains(r.userId())).toList();
        }
        return List.of();
    }

    private boolean canDecide(LeaveRequest r) {
        if (java.util.Objects.equals(r.userId(), CurrentUser.id())) {
            return false;
        }
        return CurrentUser.has("leave.manage") || (CurrentUser.has("leave.approve.team")
                && org.inTeam(CurrentUser.id(), r.userId()));
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
        String status = approve ? "APPROVED" : "REJECTED";
        if (approve && type.requiresDocument()) {
            if (CurrentUser.has("leave.manage")) {
                // HR approving directly checks the document at the same time.
                requireDocument(r);
                jdbc.sql("update leave_request set document_checked_by = ?, document_checked_at = now() where id = ?")
                        .params(CurrentUser.email(), id).update();
            } else {
                status = "AWAITING_HR";
            }
        }
        setStatus(id, status, note);
        audit.record(approve ? "leave.approved" : "leave.rejected", "leave_request", id,
                note == null ? Map.of("status", status) : Map.of("note", note, "status", status));
        return find(id);
    }

    private void requireDocument(LeaveRequest r) {
        if (jdbc.sql("select count(*) from leave_document where leave_request_id = ?").param(r.id()).query(Integer.class).single() == 0) {
            throw new BusinessRuleException(r.leaveType() + " needs a supporting document (for example a medical certificate). Add it first.");
        }
    }

    /** HR checks the document of leave the manager approved: it then counts as approved, or is rejected. */
    @Transactional
    public LeaveRequest checkDocument(long id, boolean accept, String note) {
        LeaveRequest r = find(id);
        if (!CurrentUser.has("leave.manage")) {
            throw new AccessDeniedException("Only HR checks leave documents.");
        }
        if (java.util.Objects.equals(r.userId(), CurrentUser.id())) {
            throw new AccessDeniedException("You cannot check your own leave.");
        }
        if (!r.status().equals("AWAITING_HR")) {
            throw new BusinessRuleException("This leave is not waiting for HR.");
        }
        if (accept) {
            requireDocument(r);
            jdbc.sql("update leave_request set status = 'APPROVED', document_checked_by = ?, document_checked_at = now() where id = ?")
                    .params(CurrentUser.email(), id).update();
        } else {
            if (note == null || note.isBlank()) {
                throw new BusinessRuleException("Say why the document is not accepted.");
            }
            returnDays(r, "Document not accepted: " + note.trim());
            setStatus(id, "REJECTED", note);
        }
        audit.record(accept ? "leave.document_accepted" : "leave.document_refused", "leave_request", id,
                note == null ? Map.of() : Map.of("note", note));
        return find(id);
    }

    private void returnDays(LeaveRequest r, String note) {
        if (type(r.leaveTypeId()).usesBalance()) {
            jdbc.sql("""
                    insert into leave_ledger (user_id, leave_type_id, entry_date, days, kind, leave_request_id, note, created_by)
                    values (?, ?, ?, ?, 'RETURNED', ?, ?, ?)""")
                    .params(r.userId(), r.leaveTypeId(), today(), r.workingDays(), r.id(), note, CurrentUser.email()).update();
        }
    }

    // ---- Documents ------------------------------------------------------------------------------------------

    private boolean canSee(LeaveRequest r) {
        return java.util.Objects.equals(r.userId(), CurrentUser.id()) || CurrentUser.has("leave.manage")
                || (CurrentUser.has("leave.approve.team") && org.inTeam(CurrentUser.id(), r.userId()));
    }

    public List<Document> documents(long requestId) {
        LeaveRequest r = find(requestId);
        if (!canSee(r)) {
            throw new AccessDeniedException("You cannot see this leave.");
        }
        return jdbc.sql("""
                select id, leave_request_id, file_name, content_type, size_bytes, uploaded_by, uploaded_at
                from leave_document where leave_request_id = ? order by id""").param(requestId).query(Document.class).list();
    }

    @Transactional
    public Document addDocument(long requestId, String fileName, String contentType, byte[] data) {
        LeaveRequest r = find(requestId);
        if (!java.util.Objects.equals(r.userId(), CurrentUser.id()) && !CurrentUser.has("leave.manage")) {
            throw new AccessDeniedException("Only the employee or HR can add a document.");
        }
        if (!List.of("PENDING", "AWAITING_HR", "APPROVED").contains(r.status())) {
            throw new BusinessRuleException("This leave is closed.");
        }
        if (!DOCUMENT_TYPES.contains(contentType)) {
            throw new BusinessRuleException("Send a PDF, JPEG or PNG file.");
        }
        if (data.length == 0 || data.length > DOCUMENT_MAX_BYTES) {
            throw new BusinessRuleException("The file must be at most 5 MB.");
        }
        String name = fileName == null || fileName.isBlank() ? "document" : fileName.trim();
        name = name.length() > 200 ? name.substring(name.length() - 200) : name;
        long id = jdbc.sql("""
                insert into leave_document (leave_request_id, file_name, content_type, size_bytes, data, uploaded_by)
                values (?, ?, ?, ?, ?, ?) returning id""")
                .params(requestId, name, contentType, data.length, data, CurrentUser.email()).query(Long.class).single();
        audit.record("leave.document_added", "leave_request", requestId, Map.of("document", id, "file", name));
        return documents(requestId).stream().filter(d -> d.id() == id).findFirst().orElseThrow();
    }

    public DocumentFile document(long requestId, long documentId) {
        LeaveRequest r = find(requestId);
        if (!canSee(r)) {
            throw new AccessDeniedException("You cannot see this leave.");
        }
        return jdbc.sql("select file_name, content_type, data from leave_document where id = ? and leave_request_id = ?")
                .params(documentId, requestId).query(DocumentFile.class).optional()
                .orElseThrow(() -> new NotFoundException("Document not found."));
    }

    /** The employee cancels a pending request; HR can also cancel an approved one, which returns the days. */
    @Transactional
    public LeaveRequest cancel(long id, String note) {
        LeaveRequest r = find(id);
        boolean own = java.util.Objects.equals(r.userId(), CurrentUser.id());
        if (r.status().equals("PENDING") && own) {
            setStatus(id, "CANCELLED", note);
        } else if (r.status().equals("AWAITING_HR") && (own || CurrentUser.has("leave.manage"))) {
            returnDays(r, "Cancelled before HR checked the document");
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
                select r.start_date, r.end_date, t.name, t.pay_percent, r.half_day from leave_request r join leave_type t on t.id = r.leave_type_id
                where r.user_id = ? and r.status = 'APPROVED' and r.start_date <= ? and r.end_date >= ?""")
                .params(userId, to, from)
                .query((rs, n) -> {
                    LocalDate s = rs.getObject(1, LocalDate.class);
                    LocalDate e = rs.getObject(2, LocalDate.class);
                    for (LocalDate d = s; !d.isAfter(e); d = d.plusDays(1)) {
                        days.put(d, new LeaveDay(rs.getString(3), rs.getBigDecimal(4), rs.getString(5)));
                    }
                    return null;
                }).list();
        return days;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneId.of("Africa/Kinshasa")));
    }
}
