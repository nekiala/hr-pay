package cd.hrpay.attendance;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.org.OrgStructure;
import cd.hrpay.security.CurrentUser;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attendance correction requests. An employee asks for a missing entry to be added or a wrong one removed; a head of
 * their branch proposes it (or rejects it); HR applies it, which goes through {@link CorrectionService} like any HR
 * correction. A head can also propose a correction for someone in their branch directly, and HR can apply a request
 * that no head has looked at yet.
 */
@Service
public class CorrectionRequests {

    public record Request(long id, long userId, String employeeName, String registrationNo, LocalDate workDate,
                          String action, String eventType, LocalTime localTime, Long eventId, OffsetDateTime eventAt,
                          String reason, String status, String requestedBy, OffsetDateTime requestedAt,
                          String proposedBy, OffsetDateTime proposedAt, String decidedBy, OffsetDateTime decidedAt,
                          String decisionNote) {}

    private static final String SELECT = """
            select r.id, r.user_id, person_name(u.first_name, u.middle_name, u.last_name) as employee_name, u.registration_no, r.work_date,
                   r.action, r.event_type, r.local_time, r.event_id, e.occurred_at as event_at, r.reason, r.status,
                   r.requested_by, r.requested_at, r.proposed_by, r.proposed_at, r.decided_by, r.decided_at, r.decision_note
            from correction_request r join app_user u on u.id = r.user_id left join clock_event e on e.id = r.event_id""";

    private final JdbcClient jdbc;
    private final CorrectionService corrections;
    private final ClockEventRepository events;
    private final OrgStructure org;
    private final AuditService audit;
    private final Clock clock;

    public CorrectionRequests(JdbcClient jdbc, CorrectionService corrections, ClockEventRepository events,
                              OrgStructure org, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.corrections = corrections;
        this.events = events;
        this.org = org;
        this.audit = audit;
        this.clock = clock;
    }

    private static boolean isHr() {
        return CurrentUser.has("attendance.correct");
    }

    private boolean canPropose(long userId) {
        return userId != CurrentUser.id() && CurrentUser.has("attendance.correct.propose") && org.inTeam(CurrentUser.id(), userId);
    }

    public Request get(long id) {
        return jdbc.sql(SELECT + " where r.id = ?").param(id).query(Request.class).optional()
                .orElseThrow(() -> new NotFoundException("Correction request not found."));
    }

    public record Entry(long id, String type, OffsetDateTime occurredAt, String source) {}

    /** The entries of one person's day, for choosing the one to remove: their own, a head's branch, or anyone for HR. */
    public List<Entry> entries(Long forUser, LocalDate workDate) {
        long userId = forUser == null ? CurrentUser.id() : forUser;
        if (userId != CurrentUser.id() && !isHr() && !canPropose(userId)) {
            throw new AccessDeniedException("You cannot see this person's entries.");
        }
        return jdbc.sql("""
                select id, type, occurred_at, source from clock_event
                where user_id = ? and work_date = ? and voided_at is null order by occurred_at""")
                .params(userId, workDate).query(Entry.class).list();
    }

    public List<Request> mine() {
        return jdbc.sql(SELECT + " where r.user_id = ? order by r.work_date desc, r.id desc").param(CurrentUser.id())
                .query(Request.class).list();
    }

    /** What the current user can act on: HR sees every open request; a head sees the pending ones of their branch. */
    public List<Request> toDecide() {
        long me = CurrentUser.id();
        if (isHr()) {
            return jdbc.sql(SELECT + " where r.status in ('PENDING', 'PROPOSED') and r.user_id <> ? order by r.work_date, r.id")
                    .param(me).query(Request.class).list();
        }
        if (!CurrentUser.has("attendance.correct.propose")) {
            return List.of();
        }
        Set<Long> team = org.teamOf(me);
        return jdbc.sql(SELECT + " where r.status = 'PENDING' order by r.work_date, r.id").query(Request.class).list()
                .stream().filter(r -> team.contains(r.userId())).toList();
    }

    @Transactional
    public Request create(Long forUser, LocalDate workDate, String action, ClockEvent.Type type, LocalTime time,
                          Long eventId, String reason) {
        long me = CurrentUser.id();
        long userId = forUser == null ? me : forUser;
        boolean own = userId == me;
        if (own ? !CurrentUser.has("attendance.correct.request") : !canPropose(userId)) {
            throw new AccessDeniedException(own ? "You cannot ask for corrections."
                    : "Only a head of this person's unit (or a unit above) can propose a correction for them.");
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Africa/Kinshasa")));
        if (workDate.isAfter(today)) {
            throw new BusinessRuleException("The day cannot be in the future.");
        }
        if (workDate.isBefore(today.minusDays(62))) {
            throw new BusinessRuleException("Corrections can be asked for up to two months back; older days go to HR directly.");
        }
        if (action.equals("ADD")) {
            if (type == null || time == null) {
                throw new BusinessRuleException("Say whether the check-in or the check-out is missing, and at what time.");
            }
            eventId = null;
        } else {
            if (eventId == null) {
                throw new BusinessRuleException("Choose the entry to remove.");
            }
            ClockEvent e = events.findById(eventId).orElseThrow(() -> new NotFoundException("Entry not found."));
            if (e.getUserId() != userId || !e.getWorkDate().equals(workDate)) {
                throw new BusinessRuleException("That entry is not one of this person's on " + workDate + ".");
            }
            if (e.getVoidedAt() != null) {
                throw new ConflictException("That entry is already removed.");
            }
            type = null;
            time = null;
        }
        // A head's own proposal skips the head step.
        String status = own ? "PENDING" : "PROPOSED";
        long id = jdbc.sql("""
                insert into correction_request (user_id, work_date, action, event_type, local_time, event_id, reason, status,
                    requested_by, proposed_by, proposed_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(userId, workDate, action, type == null ? null : type.name(), time, eventId, reason.trim(), status,
                        CurrentUser.email(), own ? null : CurrentUser.email(), own ? null : OffsetDateTime.now(clock))
                .query(Long.class).single();
        audit.record("correction_request.created", "user", userId, Map.of("request", id, "action", action,
                "workDate", workDate.toString()));
        return get(id);
    }

    @Transactional
    public Request propose(long id, String note) {
        Request r = open(id, Set.of("PENDING"));
        if (!canPropose(r.userId())) {
            throw new AccessDeniedException("Only a head of this person's unit (or a unit above) can propose it.");
        }
        jdbc.sql("update correction_request set status = 'PROPOSED', proposed_by = ?, proposed_at = now(), decision_note = ? where id = ?")
                .params(CurrentUser.email(), clean(note), id).update();
        audit.record("correction_request.proposed", "user", r.userId(), Map.of("request", id));
        return get(id);
    }

    @Transactional
    public Request apply(long id, String note) {
        Request r = open(id, Set.of("PENDING", "PROPOSED"));
        if (!isHr()) {
            throw new AccessDeniedException("Only HR applies corrections.");
        }
        String why = "Request #" + id + ": " + r.reason() + (clean(note) == null ? "" : " (" + note.trim() + ")");
        if (r.action().equals("ADD")) {
            corrections.add(r.userId(), ClockEvent.Type.valueOf(r.eventType()), r.workDate().atTime(r.localTime()),
                    r.workDate(), why.length() > 500 ? why.substring(0, 500) : why);
        } else {
            corrections.voidEvent(r.eventId(), why.length() > 500 ? why.substring(0, 500) : why);
        }
        decide(id, "APPLIED", note);
        audit.record("correction_request.applied", "user", r.userId(), Map.of("request", id));
        return get(id);
    }

    @Transactional
    public Request reject(long id, String note) {
        Request r = open(id, Set.of("PENDING", "PROPOSED"));
        boolean head = r.status().equals("PENDING") && canPropose(r.userId());
        if (!isHr() && !head) {
            throw new AccessDeniedException("Only HR, or a head of this person's branch before it is proposed, can reject it.");
        }
        if (r.userId() == CurrentUser.id()) {
            throw new AccessDeniedException("You cannot decide on your own request.");
        }
        if (clean(note) == null) {
            throw new BusinessRuleException("Say why the request is rejected.");
        }
        decide(id, "REJECTED", note);
        audit.record("correction_request.rejected", "user", r.userId(), Map.of("request", id));
        return get(id);
    }

    @Transactional
    public Request cancel(long id) {
        Request r = open(id, Set.of("PENDING"));
        if (r.userId() != CurrentUser.id()) {
            throw new AccessDeniedException("Only the person who asked can cancel it.");
        }
        jdbc.sql("update correction_request set status = 'CANCELLED', decided_by = ?, decided_at = now() where id = ?")
                .params(CurrentUser.email(), id).update();
        return get(id);
    }

    private Request open(long id, Set<String> allowed) {
        Request r = get(id);
        if (!allowed.contains(r.status())) {
            throw new ConflictException("This request is already " + r.status().toLowerCase() + ".");
        }
        return r;
    }

    private void decide(long id, String status, String note) {
        jdbc.sql("update correction_request set status = ?, decided_by = ?, decided_at = now(), decision_note = coalesce(?, decision_note) where id = ?")
                .params(status, CurrentUser.email(), clean(note), id).update();
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
