package cd.hrpay.representation;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.lifecycle.Documents;
import cd.hrpay.notify.Notifications;
import cd.hrpay.payment.Company;
import cd.hrpay.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The staff representation (délégation syndicale): the delegates and their alternates, elections, meetings with the
 * employer, the company rules sent to them, the labour inspector's authorisations, and the hours of the mandate.
 *
 * <p>DRC arrêté 048/2015: delegates in every establishment of at least 10 workers (art. 3), elected for 3 years
 * (art. 30), election announced 3 weeks ahead (art. 6), 15 to 35 paid hours a month (art. 35), a meeting with the
 * employer every three months, minutes to the inspector within eight days (art. 37). Arrêté 036/95: dismissing or
 * transferring a delegate or an alternate needs the labour inspector's decision, or it is void. Code art. 62 and 157.
 * Republic of the Congo: delegates from 7 workers (art. 173, to verify); the number of seats is left to HR.
 */
@Service
public class StaffRepresentation {

    public static final Set<String> SEATS = Set.of("TITULAR", "ALTERNATE");
    public static final Set<String> EVENT_KINDS = Set.of("ELECTION", "MEETING", "RULES_SENT");
    public static final Set<String> ACTIONS = Set.of("DISMISSAL", "TRANSFER");
    static final int ELECTION_NOTICE_DAYS = 21;
    static final int MINUTES_TO_INSPECTOR_DAYS = 8;

    public record Delegate(long id, long userId, String name, String registrationNo, String unit, String seat,
                           String unionName, LocalDate electedOn, LocalDate termEnd, LocalDate endedOn, String endReason,
                           boolean active) {}

    public record Event(long id, String kind, LocalDate heldOn, LocalDate announcedOn, String title, String note,
                        LocalDate inspectorCopyOn, String fileName, boolean hasFile, String createdBy,
                        OffsetDateTime createdAt) {}

    public record Authorisation(long id, long userId, String name, String action, LocalDate decidedOn, String reference,
                                boolean granted, String note, String fileName, boolean hasFile, String createdBy) {}

    public record HoursEntry(long id, long userId, String name, LocalDate onDay, BigDecimal hours, String note,
                             String createdBy) {}

    public record HoursTotal(long userId, String name, String seat, BigDecimal hours) {}

    public record Hours(YearMonth month, BigDecimal min, BigDecimal max, List<HoursTotal> totals, List<HoursEntry> entries) {}

    public record Overview(int headcount, int fromWorkers, Integer requiredSeats, int titulars, int alternates,
                           LocalDate nextTermEnd, LocalDate lastMeeting, LocalDate nextMeetingDue, LocalDate rulesSentOn,
                           List<String> alerts, List<Delegate> delegates, List<Event> events,
                           List<Authorisation> authorisations) {}

    public record File(String name, String contentType, byte[] data) {}

    /** What was saved, and what HR should know about it. */
    public record Saved<T>(T item, List<String> warnings) {}

    private final JdbcClient jdbc;
    private final Notifications notifications;
    private final Company company;
    private final AuditService audit;
    private final Clock clock;

    public StaffRepresentation(JdbcClient jdbc, Notifications notifications, Company company, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.company = company;
        this.audit = audit;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private int intSetting(String key, int fallback) {
        try {
            return jdbc.sql("select value from payroll_setting where key = ?").param(key).query(String.class).optional()
                    .map(String::trim).filter(v -> !v.isEmpty()).map(Integer::parseInt).orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---- headcount and seats -------------------------------------------------------------------------------------

    /** Workers under contract today (employees, not consultants). */
    public int headcount() {
        return jdbc.sql("""
                select count(distinct c.user_id) from employee_contract c join app_user u on u.id = c.user_id
                where not u.not_employee and c.contract_type <> 'CONSULTANT' and c.start_date <= ?
                  and (c.end_date is null or c.end_date >= ?)""").params(today(), today()).query(Integer.class).single();
    }

    /**
     * Titular delegates the law asks for (each with an alternate): DRC arrêté 048/2015 art. 3. Null when the country's
     * rule is not known; zero below the threshold.
     */
    public static Integer requiredSeats(String country, int headcount, int fromWorkers) {
        if (headcount < fromWorkers) {
            return 0;
        }
        if (!"CD".equals(country)) {
            return null;
        }
        if (headcount < 20) {
            return 1;
        }
        if (headcount < 100) {
            return 3;
        }
        if (headcount < 500) {
            return 5;
        }
        if (headcount < 1000) {
            return 9;
        }
        return 9 + (headcount - 1000) / 1000;
    }

    // ---- delegates -----------------------------------------------------------------------------------------------

    private static final String DELEGATES = """
            select d.id, d.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, u.registration_no,
                   (select ou.name from org_assignment a join org_unit ou on ou.id = a.unit_id
                    where a.user_id = d.user_id and a.valid_to is null order by a.valid_from desc limit 1) as unit,
                   d.seat, d.union_name, d.elected_on, d.term_end, d.ended_on, d.end_reason,
                   (d.elected_on <= cast(:today as date) and cast(:today as date) <= coalesce(d.ended_on, d.term_end)) as active
            from staff_delegate d join app_user u on u.id = d.user_id
            """;

    public List<Delegate> delegates() {
        return jdbc.sql(DELEGATES + " order by active desc, d.seat desc, d.term_end desc, name")
                .param("today", today()).query(Delegate.class).list();
    }

    private Delegate delegate(long id) {
        return jdbc.sql(DELEGATES + " where d.id = :id").param("today", today()).param("id", id).query(Delegate.class)
                .optional().orElseThrow(() -> new NotFoundException("Delegate not found."));
    }

    /** The mandate a person holds on a day, if any. */
    public java.util.Optional<Delegate> mandateOn(long userId, LocalDate day) {
        return jdbc.sql(DELEGATES + """
                 where d.user_id = :user and d.elected_on <= :day and :day <= coalesce(d.ended_on, d.term_end)
                 order by d.elected_on desc limit 1""")
                .param("today", today()).param("user", userId).param("day", day).query(Delegate.class).optional();
    }

    @Transactional
    public Saved<Delegate> addDelegate(long userId, String seat, String unionName, LocalDate electedOn, LocalDate termEnd) {
        if (!SEATS.contains(seat)) {
            throw new BusinessRuleException("Choose titular or alternate.");
        }
        if (electedOn == null) {
            throw new BusinessRuleException("Give the date of the election.");
        }
        LocalDate end = termEnd != null ? termEnd : electedOn.plusYears(intSetting("DELEGATE_TERM_YEARS", 3)).minusDays(1);
        if (!end.isAfter(electedOn)) {
            throw new BusinessRuleException("The mandate ends after the election.");
        }
        boolean overlaps = jdbc.sql("""
                select exists (select 1 from staff_delegate where user_id = ? and elected_on <= ?
                               and coalesce(ended_on, term_end) >= ?)""").params(userId, end, electedOn)
                .query(Boolean.class).single();
        if (overlaps) {
            throw new ConflictException("This person already holds a mandate in that time.");
        }
        List<String> warnings = new ArrayList<>();
        boolean underContract = jdbc.sql("""
                select exists (select 1 from employee_contract where user_id = ? and start_date <= ?
                               and (end_date is null or end_date >= ?))""").params(userId, electedOn, electedOn)
                .query(Boolean.class).single();
        if (!underContract) {
            warnings.add(Texts.t("This person has no contract on the day of the election."));
        }
        long id = jdbc.sql("""
                insert into staff_delegate (user_id, seat, union_name, elected_on, term_end, created_by)
                values (?, ?, ?, ?, ?, ?) returning id""")
                .params(userId, seat, clean(unionName, 150), electedOn, end, CurrentUser.email()).query(Long.class).single();
        audit.record("delegate.added", "user", userId, Map.of("seat", seat, "electedOn", electedOn.toString(), "termEnd", end.toString()));
        return new Saved<>(delegate(id), warnings);
    }

    /** Ends a mandate before its term (the person left, resigned the mandate, or was replaced). */
    @Transactional
    public Delegate endDelegate(long id, LocalDate endedOn, String reason) {
        Delegate d = delegate(id);
        if (endedOn == null || endedOn.isBefore(d.electedOn())) {
            throw new BusinessRuleException("Give the day the mandate ended, after the election.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Say why the mandate ended.");
        }
        jdbc.sql("update staff_delegate set ended_on = ?, end_reason = ? where id = ?")
                .params(endedOn, clean(reason, 300), id).update();
        audit.record("delegate.ended", "user", d.userId(), Map.of("endedOn", endedOn.toString(), "reason", reason.strip()));
        return delegate(id);
    }

    /** Removes a delegate entered by mistake. */
    @Transactional
    public void deleteDelegate(long id) {
        Delegate d = delegate(id);
        jdbc.sql("delete from staff_delegate where id = ?").param(id).update();
        audit.record("delegate.deleted", "user", d.userId(), Map.of("seat", d.seat(), "electedOn", d.electedOn().toString()));
    }

    // ---- elections, meetings, company rules ----------------------------------------------------------------------

    private static final String EVENTS = """
            select id, kind, held_on, announced_on, title, note, inspector_copy_on, file_name, (file_data is not null) as has_file,
                   created_by, created_at
            from staff_rep_event
            """;

    public List<Event> events() {
        return jdbc.sql(EVENTS + " order by held_on desc, id desc").query(Event.class).list();
    }

    private Event event(long id) {
        return jdbc.sql(EVENTS + " where id = ?").param(id).query(Event.class).optional()
                .orElseThrow(() -> new NotFoundException("Record not found."));
    }

    @Transactional
    public Saved<Event> addEvent(String kind, LocalDate heldOn, LocalDate announcedOn, String title, String note,
                                 LocalDate inspectorCopyOn, String fileName, byte[] data) {
        if (!EVENT_KINDS.contains(kind)) {
            throw new BusinessRuleException("Choose an election, a meeting or the company rules sent.");
        }
        if (heldOn == null) {
            throw new BusinessRuleException("Give the date.");
        }
        if (announcedOn != null && announcedOn.isAfter(heldOn)) {
            throw new BusinessRuleException("The election is announced before it is held.");
        }
        if (inspectorCopyOn != null && inspectorCopyOn.isBefore(heldOn)) {
            throw new BusinessRuleException("The inspector's copy is sent on or after the date.");
        }
        String type = null;
        String name = null;
        if (data != null && data.length > 0) {
            if (data.length > Documents.MAX_BYTES) {
                throw new BusinessRuleException("A document is at most 10 MB.");
            }
            type = Documents.sniff(data);
            if (type == null) {
                throw new BusinessRuleException("Send a PDF, JPEG or PNG file.");
            }
            name = Documents.cleanName(fileName, type);
        }
        String t = clean(title, 200);
        if (t == null) {
            t = switch (kind) {
                case "ELECTION" -> Texts.in(company.language(), () -> Texts.t("Election of the staff delegates"));
                case "MEETING" -> Texts.in(company.language(), () -> Texts.t("Meeting with the staff delegates"));
                default -> Texts.in(company.language(), () -> Texts.t("Company rules sent to the delegates and the inspector"));
            };
        }
        List<String> warnings = new ArrayList<>();
        if ("ELECTION".equals(kind)) {
            if (announcedOn == null) {
                warnings.add(Texts.t("Give the day the election was announced: at least 3 weeks before the vote (arrêté 048/2015 art. 6)."));
            } else if (ChronoUnit.DAYS.between(announcedOn, heldOn) < ELECTION_NOTICE_DAYS) {
                warnings.add(Texts.t("The election was announced less than 3 weeks before the vote (arrêté 048/2015 art. 6)."));
            }
        }
        if ("MEETING".equals(kind) && inspectorCopyOn == null) {
            warnings.add(Texts.t("Send the minutes, signed by all, to the labour inspector by %s (arrêté 048/2015 art. 37).",
                    Texts.day(heldOn.plusDays(MINUTES_TO_INSPECTOR_DAYS))));
        }
        long id = jdbc.sql("""
                insert into staff_rep_event (kind, held_on, announced_on, title, note, inspector_copy_on, file_name, file_type,
                                             file_data, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(kind, heldOn, "ELECTION".equals(kind) ? announcedOn : null, t, clean(note, 4000), inspectorCopyOn,
                        name, type, data != null && data.length > 0 ? data : null, CurrentUser.email())
                .query(Long.class).single();
        audit.record("staff_rep.event", "staff_rep_event", id, Map.of("kind", kind, "on", heldOn.toString()));
        return new Saved<>(event(id), warnings);
    }

    @Transactional
    public Event setInspectorCopy(long id, LocalDate on) {
        Event e = event(id);
        if (on != null && on.isBefore(e.heldOn())) {
            throw new BusinessRuleException("The inspector's copy is sent on or after the date.");
        }
        jdbc.sql("update staff_rep_event set inspector_copy_on = ? where id = ?").params(on, id).update();
        return event(id);
    }

    @Transactional
    public void deleteEvent(long id) {
        Event e = event(id);
        jdbc.sql("delete from staff_rep_event where id = ?").param(id).update();
        audit.record("staff_rep.event_deleted", "staff_rep_event", id, Map.of("kind", e.kind(), "on", e.heldOn().toString()));
    }

    public File eventFile(long id) {
        return jdbc.sql("select file_name, file_type, file_data from staff_rep_event where id = ? and file_data is not null")
                .param(id).query((rs, n) -> new File(rs.getString(1), rs.getString(2), rs.getBytes(3))).optional()
                .orElseThrow(() -> new NotFoundException("No file."));
    }

    // ---- the inspector's authorisations (arrêté 036/95) ----------------------------------------------------------

    private static final String AUTHORISATIONS = """
            select a.id, a.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, a.action, a.decided_on,
                   a.reference, a.granted, a.note, a.file_name, (a.file_data is not null) as has_file, a.created_by
            from delegate_authorisation a join app_user u on u.id = a.user_id
            """;

    public List<Authorisation> authorisations() {
        return jdbc.sql(AUTHORISATIONS + " order by a.decided_on desc, a.id desc").query(Authorisation.class).list();
    }

    @Transactional
    public Authorisation addAuthorisation(long userId, String action, LocalDate decidedOn, String reference, boolean granted,
                                          String note, String fileName, byte[] data) {
        if (!ACTIONS.contains(action)) {
            throw new BusinessRuleException("Choose dismissal or transfer.");
        }
        if (decidedOn == null || reference == null || reference.isBlank()) {
            throw new BusinessRuleException("Give the date and the reference of the inspector's decision.");
        }
        if (decidedOn.isAfter(today())) {
            throw new BusinessRuleException("The decision is dated today or before.");
        }
        String type = null;
        String name = null;
        if (data != null && data.length > 0) {
            if (data.length > Documents.MAX_BYTES) {
                throw new BusinessRuleException("A document is at most 10 MB.");
            }
            type = Documents.sniff(data);
            if (type == null) {
                throw new BusinessRuleException("Send a PDF, JPEG or PNG file.");
            }
            name = Documents.cleanName(fileName, type);
        }
        long id = jdbc.sql("""
                insert into delegate_authorisation (user_id, action, decided_on, reference, granted, note, file_name, file_type,
                                                    file_data, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(userId, action, decidedOn, clean(reference, 100), granted, clean(note, 500), name, type,
                        data != null && data.length > 0 ? data : null, CurrentUser.email())
                .query(Long.class).single();
        audit.record("delegate.authorisation", "user", userId, Map.of("action", action, "granted", granted,
                "reference", reference.strip()));
        return jdbc.sql(AUTHORISATIONS + " where a.id = ?").param(id).query(Authorisation.class).single();
    }

    public File authorisationFile(long id) {
        return jdbc.sql("select file_name, file_type, file_data from delegate_authorisation where id = ? and file_data is not null")
                .param(id).query((rs, n) -> new File(rs.getString(1), rs.getString(2), rs.getBytes(3))).optional()
                .orElseThrow(() -> new NotFoundException("No file."));
    }

    /**
     * Refuses to dismiss or transfer someone who holds a mandate on the day, unless the labour inspector authorised it
     * during the mandate (arrêté 036/95: otherwise the decision is void).
     */
    public void requireAuthorisation(long userId, String action, LocalDate day) {
        Delegate d = mandateOn(userId, day).orElse(null);
        if (d == null) {
            return;
        }
        boolean authorised = jdbc.sql("""
                select exists (select 1 from delegate_authorisation where user_id = ? and action = ? and granted
                               and decided_on >= ?)""").params(userId, action, d.electedOn()).query(Boolean.class).single();
        if (!authorised) {
            String seat = "TITULAR".equals(d.seat()) ? Texts.t("a staff delegate") : Texts.t("an alternate staff delegate");
            throw new BusinessRuleException("DISMISSAL".equals(action)
                    ? Texts.t("%s is %s until %s: a dismissal needs the labour inspector's authorisation, or it is void (arrêté 036/95, Labour Code art. 62). Record the authorisation on the Staff representation page first.",
                            d.name(), seat, Texts.day(d.termEnd()))
                    : Texts.t("%s is %s until %s: a transfer needs the labour inspector's authorisation, or it is void (arrêté 036/95). Record the authorisation on the Staff representation page first.",
                            d.name(), seat, Texts.day(d.termEnd())));
        }
    }

    // ---- hours of the mandate ------------------------------------------------------------------------------------

    public Hours hours(YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        List<HoursEntry> entries = jdbc.sql("""
                select h.id, h.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, h.on_day, h.hours,
                       h.note, h.created_by
                from delegation_hours h join app_user u on u.id = h.user_id
                where h.on_day between ? and ? order by h.on_day desc, name""").params(from, to).query(HoursEntry.class).list();
        List<HoursTotal> totals = jdbc.sql("""
                select d.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, min(d.seat) as seat,
                       coalesce((select sum(h.hours) from delegation_hours h where h.user_id = d.user_id
                                 and h.on_day between ? and ?), 0) as hours
                from staff_delegate d join app_user u on u.id = d.user_id
                where d.elected_on <= ? and coalesce(d.ended_on, d.term_end) >= ?
                group by d.user_id, u.first_name, u.middle_name, u.last_name order by name""")
                .params(from, to, to, from).query(HoursTotal.class).list();
        return new Hours(month, BigDecimal.valueOf(intSetting("DELEGATE_HOURS_MIN", 15)),
                BigDecimal.valueOf(intSetting("DELEGATE_HOURS_MAX", 35)), totals, entries);
    }

    @Transactional
    public Saved<HoursEntry> addHours(long userId, LocalDate day, BigDecimal hours, String note) {
        if (day == null || hours == null || hours.signum() <= 0 || hours.compareTo(BigDecimal.valueOf(12)) > 0) {
            throw new BusinessRuleException("Give the day and the hours, 12 at most.");
        }
        if (mandateOn(userId, day).isEmpty()) {
            throw new BusinessRuleException("This person holds no mandate on that day.");
        }
        long id = jdbc.sql("""
                insert into delegation_hours (user_id, on_day, hours, note, created_by) values (?, ?, ?, ?, ?) returning id""")
                .params(userId, day, hours, clean(note, 300), CurrentUser.email()).query(Long.class).single();
        audit.record("delegate.hours", "user", userId, Map.of("day", day.toString(), "hours", hours.toPlainString()));
        YearMonth month = YearMonth.from(day);
        BigDecimal total = jdbc.sql("select coalesce(sum(hours), 0) from delegation_hours where user_id = ? and on_day between ? and ?")
                .params(userId, month.atDay(1), month.atEndOfMonth()).query(BigDecimal.class).single();
        List<String> warnings = new ArrayList<>();
        int max = intSetting("DELEGATE_HOURS_MAX", 35);
        if (max > 0 && total.compareTo(BigDecimal.valueOf(max)) > 0) {
            warnings.add(Texts.t("%s hours this month: above the %d hours of the mandate (arrêté 048/2015 art. 35). The hours above are not owed as paid time unless the company agrees.",
                    total.stripTrailingZeros().toPlainString(), max));
        }
        HoursEntry saved = jdbc.sql("""
                select h.id, h.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, h.on_day, h.hours,
                       h.note, h.created_by
                from delegation_hours h join app_user u on u.id = h.user_id where h.id = ?""").param(id)
                .query(HoursEntry.class).single();
        return new Saved<>(saved, warnings);
    }

    @Transactional
    public void deleteHours(long id) {
        int n = jdbc.sql("delete from delegation_hours where id = ?").param(id).update();
        if (n == 0) {
            throw new NotFoundException("Entry not found.");
        }
        audit.record("delegate.hours_deleted", "delegation_hours", id, Map.of());
    }

    // ---- the overview --------------------------------------------------------------------------------------------

    public Overview overview() {
        LocalDate today = today();
        int headcount = headcount();
        int from = intSetting("DELEGATES_FROM_WORKERS", 10);
        Integer required = requiredSeats(company.country(), headcount, from);
        List<Delegate> delegates = delegates();
        int titulars = (int) delegates.stream().filter(d -> d.active() && "TITULAR".equals(d.seat())).count();
        int alternates = (int) delegates.stream().filter(d -> d.active() && "ALTERNATE".equals(d.seat())).count();
        LocalDate nextTermEnd = delegates.stream().filter(Delegate::active).map(Delegate::termEnd).min(LocalDate::compareTo).orElse(null);
        List<Event> events = events();
        LocalDate lastMeeting = events.stream().filter(e -> "MEETING".equals(e.kind())).map(Event::heldOn)
                .max(LocalDate::compareTo).orElse(null);
        LocalDate rulesSent = events.stream().filter(e -> "RULES_SENT".equals(e.kind())).map(Event::heldOn)
                .max(LocalDate::compareTo).orElse(null);
        int months = intSetting("DELEGATE_MEETING_MONTHS", 3);
        LocalDate firstActive = delegates.stream().filter(Delegate::active).map(Delegate::electedOn).min(LocalDate::compareTo).orElse(null);
        LocalDate nextMeeting = titulars == 0 ? null
                : (lastMeeting != null ? lastMeeting : firstActive).plusMonths(months);
        List<String> alerts = new ArrayList<>();
        if (required != null && required > 0 && titulars < required) {
            alerts.add(Texts.t("%d workers: the law asks for %d delegate(s), each with an alternate (arrêté 048/2015 art. 3); %d recorded.",
                    headcount, required, titulars));
        }
        if (required == null && headcount >= from && titulars == 0) {
            alerts.add(Texts.t("%d workers: delegates are elected from %d workers. Record the delegates once elected.", headcount, from));
        }
        if (nextTermEnd != null && !nextTermEnd.isAfter(today.plusDays(60))) {
            alerts.add(Texts.t("Mandates end on %s: announce the election at least 3 weeks before the vote.", Texts.day(nextTermEnd)));
        }
        if (nextMeeting != null && nextMeeting.isBefore(today)) {
            alerts.add(Texts.t("The quarterly meeting with the delegates is overdue since %s (arrêté 048/2015 art. 37).", Texts.day(nextMeeting)));
        }
        for (Event e : events) {
            if ("MEETING".equals(e.kind()) && e.inspectorCopyOn() == null
                    && !today.isBefore(e.heldOn().plusDays(MINUTES_TO_INSPECTOR_DAYS)) && e.heldOn().isAfter(today.minusDays(90))) {
                alerts.add(Texts.t("The minutes of the meeting of %s are not marked as sent to the labour inspector.", Texts.day(e.heldOn())));
            }
        }
        if (titulars > 0 && rulesSent == null) {
            alerts.add(Texts.t("Record when the company rules were sent to the delegates and the labour inspector (Labour Code art. 157)."));
        }
        return new Overview(headcount, from, required, titulars, alternates, nextTermEnd, lastMeeting, nextMeeting, rulesSent,
                alerts, delegates, events, authorisations());
    }

    // ---- the daily job -------------------------------------------------------------------------------------------

    /** Reminders: mandates ending, the quarterly meeting due, minutes not sent to the inspector. */
    @Transactional
    public int run() {
        LocalDate today = today();
        int sent = 0;
        List<Delegate> active = delegates().stream().filter(Delegate::active).toList();
        if (active.isEmpty()) {
            return 0;
        }
        LocalDate termEnd = active.stream().map(Delegate::termEnd).min(LocalDate::compareTo).orElseThrow();
        if (!termEnd.isAfter(today.plusDays(60))) {
            sent += remind("rep.term", termEnd, () -> Texts.t("Staff delegates: mandates end on %s", Texts.day(termEnd)),
                    () -> Texts.t("Organise the election: announce it at least 3 weeks before the vote (arrêté 048/2015 art. 6)."));
        }
        int months = intSetting("DELEGATE_MEETING_MONTHS", 3);
        LocalDate last = jdbc.sql("select max(held_on) from staff_rep_event where kind = 'MEETING'").query(LocalDate.class)
                .optional().orElse(null);
        LocalDate firstActive = active.stream().map(Delegate::electedOn).min(LocalDate::compareTo).orElseThrow();
        LocalDate due = (last != null ? last : firstActive).plusMonths(months);
        if (!due.isAfter(today.plusDays(7))) {
            sent += remind("rep.meeting", due, () -> Texts.t("Meeting with the staff delegates due on %s", Texts.day(due)),
                    () -> Texts.t("The employer meets the délégation every three months; the minutes, signed by all, go to the labour inspector within eight days (arrêté 048/2015 art. 37)."));
        }
        List<Event> unsent = jdbc.sql(EVENTS + """
                 where kind = 'MEETING' and inspector_copy_on is null and held_on <= ? and held_on > ?""")
                .params(today.minusDays(MINUTES_TO_INSPECTOR_DAYS - 2), today.minusDays(60)).query(Event.class).list();
        for (Event e : unsent) {
            sent += remind("rep.minutes", e.heldOn(), () -> Texts.t("Minutes of the meeting of %s", Texts.day(e.heldOn())),
                    () -> Texts.t("Send the minutes to the labour inspector by %s (arrêté 048/2015 art. 37), then mark them as sent.",
                            Texts.day(e.heldOn().plusDays(MINUTES_TO_INSPECTOR_DAYS))));
        }
        return sent;
    }

    /** Tells each holder of the permission once for the day given. */
    private int remind(String kind, LocalDate day, java.util.function.Supplier<String> title, java.util.function.Supplier<String> body) {
        int n = 0;
        for (long holder : notifications.holders("staff.representation")) {
            boolean first = jdbc.sql("insert into reminder_sent (user_id, kind, on_day) values (?, ?, ?) on conflict do nothing")
                    .params(holder, kind, day).update() > 0;
            if (first) {
                notifications.send(holder, kind, title, body, "/representation");
                n++;
            }
        }
        return n;
    }

    private static String clean(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
