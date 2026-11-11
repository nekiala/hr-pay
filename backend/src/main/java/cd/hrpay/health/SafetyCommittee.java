package cd.hrpay.health;

import static cd.hrpay.health.HealthSupport.clean;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.payment.Company;
import cd.hrpay.security.CurrentUser;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The health and safety committee (comité de sécurité, d'hygiène et d'embellissement des lieux de travail, CSHE). DRC
 * arrêté 043/2008: required from 20 workers; the employer presides, the safety head is secretary, with the doctor, the
 * training head and worker members named by the delegates (2 for 20 to 50 workers, 4 to 100, 6 to 500, 8 above, 10 from
 * 1,000), for two years; a meeting at least every two months; an inspection walk every month; a yearly report to the
 * Minister through the labour inspection by 31 March. No rule is known for the other countries: off by default.
 */
@Service
public class SafetyCommittee {

    public static final Set<String> ROLES = Set.of("PRESIDENT", "SECRETARY", "DOCTOR", "TRAINING", "WORKER");
    public static final Set<String> EVENT_KINDS = Set.of("MEETING", "INSPECTION", "REPORT", "MEDICAL_REPORT");

    public record Member(long id, Long userId, String name, String role, LocalDate fromOn, LocalDate termEnd,
                         LocalDate endedOn, boolean active) {}

    public record Event(long id, String kind, LocalDate heldOn, Integer year, String title, String note, String fileName,
                        boolean hasFile, String createdBy, OffsetDateTime createdAt) {}

    public record Action(long id, Long eventId, Long accidentId, String risk, String action, Long ownerId, String ownerName,
                         LocalDate dueOn, LocalDate doneOn, boolean late) {}

    public record Overview(int headcount, int from, boolean required, Integer workerSeats, int workers, LocalDate lastMeeting,
                           LocalDate nextMeetingDue, LocalDate lastInspection, LocalDate nextInspectionDue,
                           List<String> alerts, List<Member> members, List<Event> events, List<Action> actions) {}

    private final JdbcClient jdbc;
    private final HealthSupport support;
    private final Company company;
    private final AuditService audit;
    private final Clock clock;

    public SafetyCommittee(JdbcClient jdbc, HealthSupport support, Company company, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.support = support;
        this.company = company;
        this.audit = audit;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Worker members the DRC arrêté 043/2008 asks for; null when the country's rule is not known. */
    public static Integer workerSeats(String country, int headcount, int from) {
        if (from <= 0 || headcount < from) {
            return 0;
        }
        if (!"CD".equals(country)) {
            return null;
        }
        if (headcount <= 50) {
            return 2;
        }
        if (headcount <= 100) {
            return 4;
        }
        if (headcount <= 500) {
            return 6;
        }
        return headcount < 1000 ? 8 : 10;
    }

    /** Whether the committee is required now. */
    public boolean required() {
        int from = support.intSetting("SAFETY_COMMITTEE_FROM", 0);
        return from > 0 && support.headcount(today()) >= from;
    }

    // ---- members -----------------------------------------------------------------------------------------------

    private static final String MEMBERS = """
            select m.id, m.user_id, coalesce(person_name(u.first_name, u.middle_name, u.last_name), m.name) as name, m.role,
                   m.from_on, m.term_end, m.ended_on,
                   (m.from_on <= cast(:today as date) and cast(:today as date) <= coalesce(m.ended_on, m.term_end)) as active
            from safety_member m left join app_user u on u.id = m.user_id
            """;

    public List<Member> members() {
        return jdbc.sql(MEMBERS + " order by active desc, case m.role when 'PRESIDENT' then 0 when 'SECRETARY' then 1 "
                + "when 'DOCTOR' then 2 when 'TRAINING' then 3 else 4 end, name").param("today", today()).query(Member.class).list();
    }

    private Member member(long id) {
        return jdbc.sql(MEMBERS + " where m.id = :id").param("today", today()).param("id", id).query(Member.class).optional()
                .orElseThrow(() -> new NotFoundException("Member not found."));
    }

    @Transactional
    public Member addMember(Long userId, String name, String role, LocalDate fromOn, LocalDate termEnd) {
        if (!ROLES.contains(role)) {
            throw new BusinessRuleException("Choose the member's role.");
        }
        if (userId == null && (name == null || name.isBlank())) {
            throw new BusinessRuleException("Choose an employee or give the name.");
        }
        if (fromOn == null) {
            throw new BusinessRuleException("Give the first day of the mandate.");
        }
        LocalDate end = termEnd != null ? termEnd : fromOn.plusYears(support.intSetting("SAFETY_TERM_YEARS", 2)).minusDays(1);
        if (!end.isAfter(fromOn)) {
            throw new BusinessRuleException("The mandate ends after it starts.");
        }
        long id = jdbc.sql("""
                insert into safety_member (user_id, name, role, from_on, term_end, created_by) values (?, ?, ?, ?, ?, ?)
                returning id""").params(userId, userId == null ? clean(name, 150) : null, role, fromOn, end, CurrentUser.email())
                .query(Long.class).single();
        audit.record("safety.member_added", "safety_member", id, Map.of("role", role));
        return member(id);
    }

    @Transactional
    public Member endMember(long id, LocalDate endedOn) {
        Member m = member(id);
        if (endedOn == null || endedOn.isBefore(m.fromOn())) {
            throw new BusinessRuleException("Give the day the mandate ended, after it started.");
        }
        jdbc.sql("update safety_member set ended_on = ? where id = ?").params(endedOn, id).update();
        audit.record("safety.member_ended", "safety_member", id, Map.of("endedOn", endedOn.toString()));
        return member(id);
    }

    @Transactional
    public void deleteMember(long id) {
        member(id);
        jdbc.sql("delete from safety_member where id = ?").param(id).update();
        audit.record("safety.member_deleted", "safety_member", id, Map.of());
    }

    // ---- meetings, inspection walks, reports -------------------------------------------------------------------

    private static final String EVENTS = """
            select id, kind, held_on, year, title, note, file_name, (file_data is not null) as has_file, created_by, created_at
            from safety_event
            """;

    public List<Event> events() {
        return jdbc.sql(EVENTS + " order by held_on desc, id desc").query(Event.class).list();
    }

    private Event event(long id) {
        return jdbc.sql(EVENTS + " where id = ?").param(id).query(Event.class).optional()
                .orElseThrow(() -> new NotFoundException("Record not found."));
    }

    @Transactional
    public Event addEvent(String kind, LocalDate heldOn, Integer year, String title, String note, String fileName, byte[] data) {
        if (!EVENT_KINDS.contains(kind)) {
            throw new BusinessRuleException("Choose a meeting, an inspection walk or a report.");
        }
        if (heldOn == null || heldOn.isAfter(today())) {
            throw new BusinessRuleException("Give the date, today or before.");
        }
        boolean report = kind.endsWith("REPORT");
        Integer y = report ? (year != null ? year : heldOn.getYear() - 1) : null;
        HealthSupport.Upload file = HealthSupport.upload(fileName, data);
        String t = clean(title, 200);
        if (t == null) {
            t = Texts.in(company.language(), () -> eventName(kind, y));
        }
        long id = jdbc.sql("""
                insert into safety_event (kind, held_on, year, title, note, file_name, file_type, file_data, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(kind, heldOn, y, t, clean(note, 4000), file.name(), file.type(), file.data(), CurrentUser.email())
                .query(Long.class).single();
        audit.record("safety.event", "safety_event", id, Map.of("kind", kind, "on", heldOn.toString()));
        return event(id);
    }

    static String eventName(String kind, Integer year) {
        return switch (kind) {
            case "MEETING" -> Texts.t("Meeting of the health and safety committee");
            case "INSPECTION" -> Texts.t("Inspection walk of the workplace");
            case "REPORT" -> Texts.t("Yearly report of the health and safety committee %d", year);
            default -> Texts.t("Yearly activity report of the medical service %d", year);
        };
    }

    @Transactional
    public void deleteEvent(long id) {
        Event e = event(id);
        jdbc.sql("delete from safety_event where id = ?").param(id).update();
        audit.record("safety.event_deleted", "safety_event", id, Map.of("kind", e.kind()));
    }

    public HealthSupport.File eventFile(long id) {
        return jdbc.sql("select file_name, file_type, file_data from safety_event where id = ? and file_data is not null")
                .param(id).query((rs, n) -> new HealthSupport.File(rs.getString(1), rs.getString(2), rs.getBytes(3)))
                .optional().orElseThrow(() -> new NotFoundException("No file."));
    }

    /** Whether a yearly report of the kind was recorded as sent for the year. */
    boolean reportSent(String kind, int year) {
        return jdbc.sql("select exists (select 1 from safety_event where kind = ? and year = ?)").params(kind, year)
                .query(Boolean.class).single();
    }

    // ---- actions -----------------------------------------------------------------------------------------------

    private static final String ACTIONS = """
            select a.id, a.event_id, a.accident_id, a.risk, a.action, a.owner_id,
                   person_name(u.first_name, u.middle_name, u.last_name) as owner_name, a.due_on, a.done_on,
                   (a.done_on is null and a.due_on < cast(:today as date)) as late
            from safety_action a left join app_user u on u.id = a.owner_id
            """;

    public List<Action> actions() {
        return jdbc.sql(ACTIONS + " order by (a.done_on is not null), a.due_on nulls last, a.id")
                .param("today", today()).query(Action.class).list();
    }

    private Action action(long id) {
        return jdbc.sql(ACTIONS + " where a.id = :id").param("today", today()).param("id", id).query(Action.class).optional()
                .orElseThrow(() -> new NotFoundException("Action not found."));
    }

    @Transactional
    public Action addAction(String risk, String action, Long ownerId, LocalDate dueOn, Long eventId, Long accidentId) {
        if (risk == null || risk.isBlank() || action == null || action.isBlank()) {
            throw new BusinessRuleException("Say the risk found and what is done about it.");
        }
        long id = jdbc.sql("""
                insert into safety_action (risk, action, owner_id, due_on, event_id, accident_id, created_by)
                values (?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(clean(risk, 500), clean(action, 500), ownerId, dueOn, eventId, accidentId, CurrentUser.email())
                .query(Long.class).single();
        audit.record("safety.action_added", "safety_action", id, Map.of());
        return action(id);
    }

    @Transactional
    public Action doneAction(long id, LocalDate doneOn) {
        action(id);
        jdbc.sql("update safety_action set done_on = ? where id = ?").params(doneOn, id).update();
        audit.record("safety.action_done", "safety_action", id, Map.of("done", doneOn == null ? "" : doneOn.toString()));
        return action(id);
    }

    @Transactional
    public void deleteAction(long id) {
        action(id);
        jdbc.sql("delete from safety_action where id = ?").param(id).update();
        audit.record("safety.action_deleted", "safety_action", id, Map.of());
    }

    // ---- the overview ------------------------------------------------------------------------------------------

    private LocalDate last(List<Event> events, String kind) {
        return events.stream().filter(e -> kind.equals(e.kind())).map(Event::heldOn).max(LocalDate::compareTo).orElse(null);
    }

    public Overview overview() {
        LocalDate today = today();
        int headcount = support.headcount(today);
        int from = support.intSetting("SAFETY_COMMITTEE_FROM", 0);
        boolean required = from > 0 && headcount >= from;
        Integer seats = workerSeats(company.country(), headcount, from);
        List<Member> members = members();
        List<Event> events = events();
        List<Action> actions = actions();
        int workers = (int) members.stream().filter(m -> m.active() && "WORKER".equals(m.role())).count();
        boolean any = members.stream().anyMatch(Member::active);
        LocalDate firstActive = members.stream().filter(Member::active).map(Member::fromOn).min(LocalDate::compareTo).orElse(null);
        LocalDate lastMeeting = last(events, "MEETING");
        LocalDate lastInspection = last(events, "INSPECTION");
        int every = support.intSetting("SAFETY_MEETING_MONTHS", 2);
        LocalDate nextMeeting = !any ? null : (lastMeeting != null ? lastMeeting : firstActive).plusMonths(every);
        LocalDate nextInspection = !any ? null : (lastInspection != null ? lastInspection : firstActive).plusMonths(1);
        List<String> alerts = new ArrayList<>();
        if (required && !any) {
            alerts.add(Texts.t("%d workers: a health and safety committee is required from %d workers (arrêté 043/2008). Record its members.",
                    headcount, from));
        }
        if (required && seats != null && any && workers < seats) {
            alerts.add(Texts.t("The committee needs %d worker members, named by the staff delegates; %d recorded.", seats, workers));
        }
        if (any && members.stream().filter(Member::active).noneMatch(m -> "PRESIDENT".equals(m.role()))) {
            alerts.add(Texts.t("No president: the employer or a representative presides the committee."));
        }
        if (nextMeeting != null && nextMeeting.isBefore(today)) {
            alerts.add(Texts.t("The committee meeting is overdue since %s: it meets at least every %d months.", Texts.day(nextMeeting), every));
        }
        if (nextInspection != null && nextInspection.isBefore(today)) {
            alerts.add(Texts.t("The monthly inspection walk is overdue since %s.", Texts.day(nextInspection)));
        }
        long late = actions.stream().filter(Action::late).count();
        if (late > 0) {
            alerts.add(Texts.t("%d action(s) past their due date.", late));
        }
        int lastYear = today.getYear() - 1;
        if (required && "CD".equals(company.country()) && !reportSent("REPORT", lastYear)) {
            LocalDate due = LocalDate.of(today.getYear(), 3, 31);
            alerts.add(today.isAfter(due)
                    ? Texts.t("The committee's yearly report for %d was due on %s: record it once sent.", lastYear, Texts.day(due))
                    : Texts.t("The committee's yearly report for %d goes to the labour inspection by %s.", lastYear, Texts.day(due)));
        }
        return new Overview(headcount, from, required, seats, workers, lastMeeting, nextMeeting, lastInspection, nextInspection,
                alerts, members, events, actions);
    }

    // ---- the daily job -----------------------------------------------------------------------------------------

    /** Reminds HR of the meeting and the inspection walk due, and of the yearly reports in the first quarter. */
    @Transactional
    public int run() {
        LocalDate today = today();
        int sent = 0;
        Overview o = overview();
        if (o.nextMeetingDue() != null && !o.nextMeetingDue().isAfter(today.plusDays(7))) {
            sent += support.remind("safety.meeting", o.nextMeetingDue(),
                    () -> Texts.t("Health and safety committee: meeting due on %s", Texts.day(o.nextMeetingDue())),
                    () -> Texts.t("The committee meets at least every two months; keep the minutes on the Health and safety page (arrêté 043/2008)."),
                    "/health");
        }
        if (o.nextInspectionDue() != null && !o.nextInspectionDue().isAfter(today.plusDays(3))) {
            sent += support.remind("safety.inspection", o.nextInspectionDue(),
                    () -> Texts.t("Inspection walk of the workplace due on %s", Texts.day(o.nextInspectionDue())),
                    () -> Texts.t("Record what was found and the actions decided."), "/health");
        }
        boolean cd = "CD".equals(company.country());
        int lastYear = today.getYear() - 1;
        LocalDate due = LocalDate.of(today.getYear(), 3, 31);
        boolean quarter = today.getMonthValue() <= 3 || today.isBefore(due.plusDays(15));
        if (cd && quarter && (today.getDayOfMonth() == 1 || today.equals(due.minusDays(7)) || today.equals(due.plusDays(1)))) {
            if (o.required() && !reportSent("REPORT", lastYear)) {
                sent += support.remind("safety.report", today,
                        () -> Texts.t("Yearly report of the health and safety committee %d: due by %s", lastYear, Texts.day(due)),
                        () -> Texts.t("Print the report from the Health and safety page, have it signed and send it through the labour inspection."),
                        "/health");
            }
            if (!reportSent("MEDICAL_REPORT", lastYear)) {
                sent += support.remind("medical.report", today,
                        () -> Texts.t("Yearly activity report of the medical service %d: due by %s", lastYear, Texts.day(due)),
                        () -> Texts.t("The doctor or medical service writes it in the first quarter (arrêté 01/76); record it once sent."),
                        "/health");
            }
        }
        return sent;
    }
}
