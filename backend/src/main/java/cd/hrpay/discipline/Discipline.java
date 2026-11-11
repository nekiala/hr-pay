package cd.hrpay.discipline;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.leave.LeaveService;
import cd.hrpay.lifecycle.Letters;
import cd.hrpay.notify.Notifications;
import cd.hrpay.org.OrgStructure;
import cd.hrpay.payment.Company;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.Contracts;
import cd.hrpay.user.UserRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The discipline register. A case holds the facts, the hearing where the employee defends themself (DRC Code du travail
 * art. 62), HR's decision and the letter that notifies it with its reason (art. 61 ter). A unit head may propose a case
 * for their team; HR accepts or rejects it. Sanctions never take money from pay (art. 111 and 112 forbid fines): only a
 * mise à pied (art. 57-5, at most two times fifteen days a year and only when the company rules provide it) makes its
 * days unpaid, as leave HR records. A dismissal for serious misconduct is notified within 15 working days of knowing the
 * facts (art. 72): the case warns when that time has passed.
 */
@Service
public class Discipline {

    public static final String RANKING_MONTHS = "DISCIPLINE_RANKING_MONTHS";
    public static final String FOLLOW_UP_DAYS = "LATENESS_FOLLOWUP_DAYS";
    public static final String SUSPENSION_ALLOWED = "DISCIPLINE_SUSPENSION_ALLOWED";
    public static final String SUSPENSION_MAX_DAYS = "DISCIPLINE_SUSPENSION_MAX_DAYS";
    public static final String SUSPENSION_YEAR_DAYS = "DISCIPLINE_SUSPENSION_YEAR_DAYS";

    /** Working days to notify a dismissal for serious misconduct, from the day the facts were known (art. 72). */
    public static final int SERIOUS_MISCONDUCT_DAYS = 15;

    public record SanctionType(long id, String code, String name, int weight, boolean suspends, boolean dismissal,
                               String letterCode, int position, boolean active) {}

    public record Case(long id, long userId, String employeeName, String registrationNo, String number, String status,
                       String origin, LocalDate factsDate, LocalDate knownDate, String facts, String ruleBroken,
                       LocalDate latenessMonth, String openedBy, String openedByName, OffsetDateTime openedAt,
                       String reviewedBy, OffsetDateTime reviewedAt, String reviewNote, LocalDateTime hearingAt,
                       String hearingPlace, Long hearingLetterId, String hearingLetterStatus, LocalDate heardOn,
                       Boolean attended, String statement, String hearingNote, String heardBy, Long sanctionTypeId,
                       String sanctionCode, String sanctionName, String decisionNote, LocalDate decidedOn,
                       String decidedBy, LocalDate suspensionFrom, LocalDate suspensionTo, Integer suspensionDays,
                       Long letterId, String letterStatus, OffsetDateTime notifiedAt, String contest,
                       OffsetDateTime contestedAt, String cancelledBy, OffsetDateTime cancelledAt, String cancelReason,
                       List<String> warnings) {}

    public record Settings(int rankingMonths, int followUpDays, boolean suspensionAllowed, int suspensionMaxDays,
                           int suspensionYearDays) {}

    private static final String SELECT = """
            select c.id, c.user_id, person_name(u.first_name, u.middle_name, u.last_name) as employee_name, u.registration_no,
                   c.number, c.status, c.origin, c.facts_date, c.known_date, c.facts, c.rule_broken, c.lateness_month,
                   c.opened_by, c.opened_by_name, c.opened_at, c.reviewed_by, c.reviewed_at, c.review_note, c.hearing_at,
                   c.hearing_place, c.hearing_letter_id, hl.status as hearing_letter_status, c.heard_on, c.attended,
                   c.statement, c.hearing_note, c.heard_by, c.sanction_type_id, t.code as sanction_code,
                   t.name as sanction_name, c.decision_note, c.decided_on, c.decided_by, c.suspension_from,
                   c.suspension_to, c.suspension_days, c.letter_id, dl.status as letter_status, c.notified_at, c.contest,
                   c.contested_at, c.cancelled_by, c.cancelled_at, c.cancel_reason, null as warnings
            from discipline_case c join app_user u on u.id = c.user_id
            left join sanction_type t on t.id = c.sanction_type_id
            left join employee_letter hl on hl.id = c.hearing_letter_id
            left join employee_letter dl on dl.id = c.letter_id""";

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final OrgStructure org;
    private final Contracts contracts;
    private final LeaveService leave;
    private final Letters letters;
    private final Notifications notifications;
    private final AuditService audit;
    private final Company company;

    private cd.hrpay.representation.StaffRepresentation representation;

    @org.springframework.beans.factory.annotation.Autowired
    void setRepresentation(@org.springframework.context.annotation.Lazy cd.hrpay.representation.StaffRepresentation representation) {
        this.representation = representation;
    }

    public Discipline(JdbcClient jdbc, UserRepository users, OrgStructure org, Contracts contracts, LeaveService leave,
                      Letters letters, Notifications notifications, AuditService audit, Company company) {
        this.jdbc = jdbc;
        this.users = users;
        this.org = org;
        this.contracts = contracts;
        this.leave = leave;
        this.letters = letters;
        this.notifications = notifications;
        this.audit = audit;
        this.company = company;
    }

    private static boolean hr() {
        return CurrentUser.has("discipline.manage");
    }

    private LocalDate today() {
        return org.today();
    }

    // ---- settings and sanction types ---------------------------------------------------------------------------

    private String setting(String key, String fallback) {
        return jdbc.sql("select value from payroll_setting where key = ?").param(key).query(String.class).optional().orElse(fallback);
    }

    private int intSetting(String key, int fallback) {
        try {
            return Integer.parseInt(setting(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public Settings settings() {
        return new Settings(intSetting(RANKING_MONTHS, 24), intSetting(FOLLOW_UP_DAYS, 4),
                Boolean.parseBoolean(setting(SUSPENSION_ALLOWED, "false")), intSetting(SUSPENSION_MAX_DAYS, 15),
                intSetting(SUSPENSION_YEAR_DAYS, 30));
    }

    @Transactional
    public Settings saveSettings(Settings s) {
        if (s.rankingMonths() < 1 || s.rankingMonths() > 120) {
            throw new BusinessRuleException("Sanctions count in the rankings for 1 to 120 months.");
        }
        if (s.followUpDays() < 1 || s.followUpDays() > 31) {
            throw new BusinessRuleException("The lateness follow-up starts at 1 to 31 late days in a month.");
        }
        if (s.suspensionMaxDays() < 1 || s.suspensionYearDays() < s.suspensionMaxDays()) {
            throw new BusinessRuleException("A mise à pied lasts at least one day, and the yearly limit is at least one mise à pied.");
        }
        if ("CD".equals(company.country()) && (s.suspensionMaxDays() > 15 || s.suspensionYearDays() > 30)) {
            throw new BusinessRuleException("In the DRC a mise à pied lasts at most 15 days, and 30 days a year (Labour Code art. 57).");
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put(RANKING_MONTHS, String.valueOf(s.rankingMonths()));
        values.put(FOLLOW_UP_DAYS, String.valueOf(s.followUpDays()));
        values.put(SUSPENSION_ALLOWED, String.valueOf(s.suspensionAllowed()));
        values.put(SUSPENSION_MAX_DAYS, String.valueOf(s.suspensionMaxDays()));
        values.put(SUSPENSION_YEAR_DAYS, String.valueOf(s.suspensionYearDays()));
        values.forEach((k, v) -> jdbc.sql("""
                insert into payroll_setting (key, value) values (?, ?)
                on conflict (company_id, key) do update set value = excluded.value""").params(k, v).update());
        audit.record("discipline.settings", "payroll_setting", "DISCIPLINE", values);
        return settings();
    }

    public List<SanctionType> types() {
        return jdbc.sql("""
                select id, code, name, weight, suspends, dismissal, letter_code, position, active from sanction_type
                order by position, id""").query(SanctionType.class).list();
    }

    private SanctionType type(long id) {
        return types().stream().filter(t -> t.id() == id).findFirst()
                .orElseThrow(() -> new NotFoundException("Sanction type not found."));
    }

    @Transactional
    public SanctionType saveType(long id, String name, int weight, boolean active) {
        type(id);
        if (name == null || name.isBlank()) {
            throw new BusinessRuleException("Give the sanction a name.");
        }
        if (weight < 0 || weight > 10) {
            throw new BusinessRuleException("The weight goes from 0 to 10.");
        }
        jdbc.sql("update sanction_type set name = ?, weight = ?, active = ? where id = ?")
                .params(name.trim(), weight, active, id).update();
        audit.record("discipline.type.saved", "sanction_type", id, Map.of("name", name.trim(), "weight", weight, "active", active));
        return type(id);
    }

    // ---- reading cases -----------------------------------------------------------------------------------------

    /** HR sees every case; a head sees the cases they proposed and those of their team, without the hearing notes. */
    public List<Case> list(String status, Long userId) {
        StringBuilder sql = new StringBuilder(SELECT).append(" where true");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            if ("ACTIVE".equals(status)) {
                sql.append(" and c.status in ('PROPOSED', 'OPEN', 'HEARING', 'HEARD', 'DECIDED')");
            } else {
                sql.append(" and c.status = ?");
                params.add(status);
            }
        }
        if (userId != null) {
            sql.append(" and c.user_id = ?");
            params.add(userId);
        }
        sql.append(" order by c.opened_at desc");
        List<Case> all = jdbc.sql(sql.toString()).params(params).query(Case.class).list();
        if (hr()) {
            return all.stream().map(this::withWarnings).toList();
        }
        Set<Long> team = org.teamOf(CurrentUser.id());
        String me = CurrentUser.email();
        return all.stream().filter(c -> team.contains(c.userId()) || me.equalsIgnoreCase(c.openedBy()))
                .map(Discipline::forHead).toList();
    }

    public Case find(long id) {
        Case c = jdbc.sql(SELECT + " where c.id = ?").param(id).query(Case.class).optional()
                .orElseThrow(() -> new NotFoundException("Discipline case not found."));
        if (hr()) {
            return withWarnings(c);
        }
        if (CurrentUser.has("discipline.propose")
                && (org.teamOf(CurrentUser.id()).contains(c.userId()) || CurrentUser.email().equalsIgnoreCase(c.openedBy()))) {
            return forHead(c);
        }
        throw new AccessDeniedException("Not your case");
    }

    private Case raw(long id) {
        return jdbc.sql(SELECT + " where c.id = ?").param(id).query(Case.class).optional()
                .orElseThrow(() -> new NotFoundException("Discipline case not found."));
    }

    /** A head follows the case without HR's notes and the employee's own words. */
    private static Case forHead(Case c) {
        return new Case(c.id(), c.userId(), c.employeeName(), c.registrationNo(), c.number(), c.status(), c.origin(),
                c.factsDate(), c.knownDate(), c.facts(), c.ruleBroken(), c.latenessMonth(), c.openedBy(), c.openedByName(),
                c.openedAt(), c.reviewedBy(), c.reviewedAt(), c.reviewNote(), c.hearingAt(), c.hearingPlace(), null, null,
                c.heardOn(), c.attended(), null, null, null, c.sanctionTypeId(), c.sanctionCode(), c.sanctionName(), null,
                c.decidedOn(), null, c.suspensionFrom(), c.suspensionTo(), c.suspensionDays(), null, null, c.notifiedAt(),
                null, c.contestedAt(), null, c.cancelledAt(), null, List.of());
    }

    /** Working days (every day but Sunday) after the facts were known, up to a day. */
    static int workingDaysBetween(LocalDate known, LocalDate on) {
        int n = 0;
        for (LocalDate d = known.plusDays(1); !d.isAfter(on); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                n++;
            }
        }
        return n;
    }

    private Case withWarnings(Case c) {
        List<String> w = new ArrayList<>();
        boolean undecided = Set.of("OPEN", "HEARING", "HEARD").contains(c.status());
        if (undecided && workingDaysBetween(c.knownDate(), today()) > SERIOUS_MISCONDUCT_DAYS) {
            w.add(Texts.t("More than %d working days have passed since the facts were known: a dismissal for serious misconduct can no longer be notified (Labour Code art. 72). A lighter sanction stays possible.",
                    SERIOUS_MISCONDUCT_DAYS));
        }
        if ("HEARING".equals(c.status()) && c.hearingAt() != null && c.hearingAt().toLocalDate().isBefore(today())) {
            w.add(Texts.t("The hearing date has passed: record what was said, or that the employee did not come."));
        }
        if (c.contestedAt() != null && !"CANCELLED".equals(c.status())) {
            w.add(Texts.t("The employee contested this sanction on %s.", Texts.day(c.contestedAt().toLocalDate())));
        }
        return new Case(c.id(), c.userId(), c.employeeName(), c.registrationNo(), c.number(), c.status(), c.origin(),
                c.factsDate(), c.knownDate(), c.facts(), c.ruleBroken(), c.latenessMonth(), c.openedBy(), c.openedByName(),
                c.openedAt(), c.reviewedBy(), c.reviewedAt(), c.reviewNote(), c.hearingAt(), c.hearingPlace(),
                c.hearingLetterId(), c.hearingLetterStatus(), c.heardOn(), c.attended(), c.statement(), c.hearingNote(),
                c.heardBy(), c.sanctionTypeId(), c.sanctionCode(), c.sanctionName(), c.decisionNote(), c.decidedOn(),
                c.decidedBy(), c.suspensionFrom(), c.suspensionTo(), c.suspensionDays(), c.letterId(), c.letterStatus(),
                c.notifiedAt(), c.contest(), c.contestedAt(), c.cancelledBy(), c.cancelledAt(), c.cancelReason(), w);
    }

    // ---- the steps ---------------------------------------------------------------------------------------------

    private void require(Case c, String... statuses) {
        if (!Set.of(statuses).contains(c.status())) {
            throw new BusinessRuleException("This step is not possible for a case that is %s.".formatted(statusName(c.status())));
        }
    }

    private static String statusName(String status) {
        return switch (status) {
            case "PROPOSED" -> Texts.t("proposed");
            case "OPEN" -> Texts.t("open");
            case "HEARING" -> Texts.t("waiting for its hearing");
            case "HEARD" -> Texts.t("heard");
            case "DECIDED" -> Texts.t("decided");
            case "NOTIFIED" -> Texts.t("notified");
            case "CLOSED" -> Texts.t("closed");
            case "REJECTED" -> Texts.t("rejected");
            default -> Texts.t("cancelled");
        };
    }

    /** HR opens a case; a head proposes one for someone of their team, which HR then accepts or rejects. */
    @Transactional
    public Case open(long userId, LocalDate factsDate, LocalDate knownDate, String facts, String ruleBroken,
                     LocalDate latenessMonth) {
        boolean asHr = hr();
        if (!asHr && !(CurrentUser.has("discipline.propose") && org.teamOf(CurrentUser.id()).contains(userId))) {
            throw new AccessDeniedException("Not in your team");
        }
        if (userId == CurrentUser.id()) {
            throw new BusinessRuleException("You cannot open a case about yourself.");
        }
        if (facts == null || facts.isBlank()) {
            throw new BusinessRuleException("Describe the facts: what happened, when and where.");
        }
        LocalDate known = knownDate == null ? today() : knownDate;
        if (factsDate == null || factsDate.isAfter(today()) || known.isAfter(today()) || known.isBefore(factsDate)) {
            throw new BusinessRuleException("The facts happen on or before the day they were known, and neither is in the future.");
        }
        contracts.require(userId, factsDate);
        AppUser me = users.findById(CurrentUser.id()).orElseThrow();
        int year = today().getYear();
        int n = jdbc.sql("select count(*) from discipline_case where number like ?").param("DC-" + year + "-%")
                .query(Integer.class).single() + 1;
        String number = "DC-%d-%04d".formatted(year, n);
        long id = jdbc.sql("""
                insert into discipline_case (user_id, number, status, origin, facts_date, known_date, facts, rule_broken,
                                             lateness_month, opened_by, opened_by_name)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(userId, number, asHr ? "OPEN" : "PROPOSED", asHr ? "HR" : "HEAD", factsDate, known, facts.trim(),
                        blank(ruleBroken), latenessMonth == null ? null : latenessMonth.withDayOfMonth(1), me.getEmail(), me.fullName())
                .query(Long.class).single();
        audit.record("discipline.opened", "user", userId, Map.of("case", id, "number", number, "origin", asHr ? "HR" : "HEAD"));
        if (!asHr) {
            String who = users.findById(userId).map(AppUser::fullName).orElse("");
            notifications.sendToHolders("discipline.manage", "discipline.proposed",
                    () -> Texts.t("A discipline case to review"),
                    () -> Texts.t("%s proposes case %s about %s.", me.fullName(), number, who), "/discipline");
        }
        return find(id);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    @Transactional
    public Case review(long id, boolean accept, String note) {
        Case c = raw(id);
        require(c, "PROPOSED");
        if (!accept && (note == null || note.isBlank())) {
            throw new BusinessRuleException("Say why the case is rejected: the head who proposed it will read it.");
        }
        jdbc.sql("update discipline_case set status = ?, reviewed_by = ?, reviewed_at = now(), review_note = ? where id = ?")
                .params(accept ? "OPEN" : "REJECTED", CurrentUser.email(), blank(note), id).update();
        audit.record(accept ? "discipline.accepted" : "discipline.rejected", "user", c.userId(), Map.of("case", id));
        users.findByEmailIgnoreCase(c.openedBy()).ifPresent(head -> notifications.send(head.getId(), "discipline.reviewed",
                () -> accept ? Texts.t("Discipline case accepted") : Texts.t("Discipline case rejected"),
                () -> accept ? Texts.t("HR accepted case %s about %s.", c.number(), c.employeeName())
                        : Texts.t("HR rejected case %s about %s: %s", c.number(), c.employeeName(), note.trim()), "/discipline"));
        return find(id);
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** The case's own fields for its letters. */
    private Map<String, String> values(Case c) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("discipline.number", c.number());
        v.put("discipline.facts", c.facts() + (c.ruleBroken() == null ? "" : "\n\n" + Texts.t("Rule not followed: %s", c.ruleBroken())));
        v.put("discipline.facts_date", Texts.day(c.factsDate()));
        v.put("discipline.hearing_date", c.hearingAt() == null ? "" : Texts.t("%s at %s", Texts.day(c.hearingAt().toLocalDate()),
                c.hearingAt().toLocalTime().format(TIME)));
        v.put("discipline.hearing_place", c.hearingPlace() == null ? "" : c.hearingPlace());
        v.put("discipline.heard_on", Texts.day(c.heardOn()));
        v.put("discipline.sanction", c.sanctionName() == null ? "" : c.sanctionName());
        v.put("discipline.decided_on", Texts.day(c.decidedOn()));
        v.put("discipline.suspension_days", c.suspensionDays() == null ? "" : String.valueOf(c.suspensionDays()));
        v.put("discipline.suspension_from", Texts.day(c.suspensionFrom()));
        v.put("discipline.suspension_to", Texts.day(c.suspensionTo()));
        v.put("discipline.return_date", c.suspensionTo() == null ? "" : Texts.day(c.suspensionTo().plusDays(1)));
        return v;
    }

    /** A letter drafted from the company's template, with the case's fields written in; HR reads it before sending. */
    private Long draftLetter(Case c, String templateCode) {
        Letters.Template t = letters.templates().stream().filter(x -> x.code().equals(templateCode) && x.active())
                .findFirst().orElse(null);
        if (t == null) {
            return null;
        }
        Map<String, String> v = Texts.in(company.language(), () -> values(c));
        String body = t.body();
        String title = t.name();
        for (Map.Entry<String, String> e : v.entrySet()) {
            String pattern = "\\{\\{\\s*" + java.util.regex.Pattern.quote(e.getKey()) + "\\s*}}";
            body = body.replaceAll(pattern, java.util.regex.Matcher.quoteReplacement(e.getValue()));
            title = title.replaceAll(pattern, java.util.regex.Matcher.quoteReplacement(e.getValue()));
        }
        return letters.create(c.userId(), t.id(), title, body, "LETTER", true).id();
    }

    private void dropDraft(Long letterId) {
        if (letterId == null) {
            return;
        }
        Letters.Letter l = letters.find(letterId);
        if (Set.of("DRAFT", "SENT").contains(l.status())) {
            letters.cancel(letterId);
        }
    }

    /** Sets (or moves) the hearing and drafts the invitation for HR to read and send. */
    @Transactional
    public Case convene(long id, LocalDateTime at, String place) {
        Case c = raw(id);
        require(c, "OPEN", "HEARING");
        if (at == null || at.toLocalDate().isBefore(today())) {
            throw new BusinessRuleException("Choose the day and time of the hearing, from today.");
        }
        if (place == null || place.isBlank()) {
            throw new BusinessRuleException("Say where the hearing takes place.");
        }
        dropDraft(c.hearingLetterId());
        jdbc.sql("update discipline_case set status = 'HEARING', hearing_at = ?, hearing_place = ?, hearing_letter_id = null where id = ?")
                .params(at, place.trim(), id).update();
        Long letter = draftLetter(raw(id), "DISCIPLINE_HEARING");
        jdbc.sql("update discipline_case set hearing_letter_id = ? where id = ?").params(letter, id).update();
        audit.record("discipline.convened", "user", c.userId(), Map.of("case", id, "at", at.toString()));
        return find(id);
    }

    /** What the employee said at the hearing (or that they did not come although invited). */
    @Transactional
    public Case heard(long id, LocalDate on, boolean attended, String statement, String note) {
        Case c = raw(id);
        require(c, "HEARING");
        if (on == null || on.isAfter(today())) {
            throw new BusinessRuleException("The hearing took place today or before.");
        }
        if (attended && (statement == null || statement.isBlank())) {
            throw new BusinessRuleException("Write down what the employee said in their defence.");
        }
        if (!attended && (c.hearingLetterStatus() == null || "DRAFT".equals(c.hearingLetterStatus()))) {
            throw new BusinessRuleException("Send the invitation first: an employee who was not invited has not refused to come.");
        }
        jdbc.sql("""
                update discipline_case set status = 'HEARD', heard_on = ?, attended = ?, statement = ?, hearing_note = ?,
                    heard_by = ? where id = ?""")
                .params(on, attended, blank(statement), blank(note), CurrentUser.email(), id).update();
        audit.record("discipline.heard", "user", c.userId(), Map.of("case", id, "attended", attended));
        return find(id);
    }

    /**
     * HR's decision after the hearing: a sanction, or none (the case closes). A mise à pied is recorded as unpaid leave
     * on its days; a dismissal then goes through the departure page. The letter is drafted for HR to read and send.
     */
    @Transactional
    public Case decide(long id, Long sanctionTypeId, String note, LocalDate suspensionFrom, Integer suspensionDays) {
        Case c = raw(id);
        require(c, "HEARD");
        if (sanctionTypeId == null) {
            if (note == null || note.isBlank()) {
                throw new BusinessRuleException("Say why no sanction is taken.");
            }
            jdbc.sql("""
                    update discipline_case set status = 'CLOSED', decision_note = ?, decided_on = ?, decided_by = ?,
                        decided_at = now() where id = ?""").params(note.trim(), today(), CurrentUser.email(), id).update();
            audit.record("discipline.closed", "user", c.userId(), Map.of("case", id));
            return find(id);
        }
        SanctionType t = type(sanctionTypeId);
        if (!t.active()) {
            throw new BusinessRuleException("This sanction is not used any more.");
        }
        if (t.dismissal() && representation != null) {
            representation.requireAuthorisation(c.userId(), "DISMISSAL", today());
        }
        if (t.dismissal() && workingDaysBetween(c.knownDate(), today()) > SERIOUS_MISCONDUCT_DAYS) {
            throw new BusinessRuleException("A dismissal for serious misconduct is notified within %d working days of knowing the facts (Labour Code art. 72); they were known on %s."
                    .formatted(SERIOUS_MISCONDUCT_DAYS, Texts.day(c.knownDate())));
        }
        LocalDate to = null;
        Long leaveId = null;
        if (t.suspends()) {
            Settings s = settings();
            if (!s.suspensionAllowed()) {
                throw new BusinessRuleException("A mise à pied is possible only when the company rules or the collective agreement provide it (Labour Code art. 57). Turn it on in the discipline settings once they do.");
            }
            if (suspensionFrom == null || suspensionDays == null || suspensionDays < 1) {
                throw new BusinessRuleException("Give the first day and the number of days of the mise à pied.");
            }
            if (suspensionDays > s.suspensionMaxDays()) {
                throw new BusinessRuleException("A mise à pied lasts at most %d days.".formatted(s.suspensionMaxDays()));
            }
            if (suspensionFrom.isBefore(today())) {
                throw new BusinessRuleException("A mise à pied starts today or later, once the employee knows of it.");
            }
            to = suspensionFrom.plusDays(suspensionDays - 1L);
            int taken = jdbc.sql("""
                    select coalesce(sum(suspension_days), 0) from discipline_case where user_id = ? and id <> ?
                    and status in ('DECIDED', 'NOTIFIED') and extract(year from suspension_from) = ?""")
                    .params(c.userId(), id, suspensionFrom.getYear()).query(Integer.class).single();
            if (taken + suspensionDays > s.suspensionYearDays()) {
                throw new BusinessRuleException("A mise à pied is limited to %d days a year; %d are already given in %d."
                        .formatted(s.suspensionYearDays(), taken, suspensionFrom.getYear()));
            }
            leaveId = suspensionLeave(c.userId(), suspensionFrom, to, c.number());
        }
        jdbc.sql("""
                update discipline_case set status = 'DECIDED', sanction_type_id = ?, decision_note = ?, decided_on = ?,
                    decided_by = ?, decided_at = now(), suspension_from = ?, suspension_to = ?, suspension_days = ?,
                    leave_request_id = ? where id = ?""")
                .params(t.id(), blank(note), today(), CurrentUser.email(), t.suspends() ? suspensionFrom : null, to,
                        t.suspends() ? suspensionDays : null, leaveId, id).update();
        Case decided = raw(id);
        Long letter = t.letterCode() == null ? null : draftLetter(decided, t.letterCode());
        jdbc.sql("update discipline_case set letter_id = ? where id = ?").params(letter, id).update();
        audit.record("discipline.decided", "user", c.userId(), Map.of("case", id, "sanction", t.code()));
        return find(id);
    }

    /** The days of a mise à pied as approved unpaid leave: the employee does not work and is not paid. */
    private long suspensionLeave(long userId, LocalDate from, LocalDate to, String number) {
        contracts.require(userId, from);
        contracts.require(userId, to);
        Long typeId = jdbc.sql("select id from leave_type where code = 'SUSPENSION'").query(Long.class).optional()
                .orElseThrow(() -> new BusinessRuleException("The mise à pied leave type is missing."));
        int overlapping = jdbc.sql("""
                select count(*) from leave_request where user_id = ? and status in ('PENDING', 'AWAITING_HR', 'APPROVED')
                and start_date <= ? and end_date >= ?""").params(userId, to, from).query(Integer.class).single();
        if (overlapping > 0) {
            throw new BusinessRuleException("The employee has leave requested or approved in these days: choose other dates.");
        }
        AppUser user = users.findById(userId).orElseThrow();
        java.math.BigDecimal days = leave.workingDays(user, from, to);
        if (days.signum() == 0) {
            throw new BusinessRuleException("There are no working days in this period.");
        }
        long id = jdbc.sql("""
                insert into leave_request (user_id, leave_type_id, start_date, end_date, working_days, reason, status,
                                           requested_by, decided_by, decided_at, decision_note)
                values (?, ?, ?, ?, ?, ?, 'APPROVED', ?, ?, now(), ?) returning id""")
                .params(userId, typeId, from, to, days, Texts.in(company.language(), () -> Texts.t("Discipline case %s", number)),
                        CurrentUser.email(), CurrentUser.email(), number).query(Long.class).single();
        audit.record("leave.recorded", "leave_request", id, Map.of("userId", userId, "type", "SUSPENSION",
                "start", from.toString(), "end", to.toString(), "days", days));
        return id;
    }

    /** HR edits a letter of the case before it goes. */
    @Transactional
    public Case editLetter(long id, String which, String title, String body) {
        Case c = raw(id);
        Long letter = letterOf(c, which);
        letters.update(letter, title, body, true);
        return find(id);
    }

    private static Long letterOf(Case c, String which) {
        Long letter = "hearing".equals(which) ? c.hearingLetterId() : "decision".equals(which) ? c.letterId() : null;
        if (letter == null) {
            throw new NotFoundException("This case has no such letter.");
        }
        return letter;
    }

    public Letters.Letter letter(long id, String which) {
        return letters.find(letterOf(raw(id), which));
    }

    /** A letter of the case as a PDF: the draft as it would go, or the sent (or signed) copy. */
    public byte[] letterFile(long id, String which) {
        Long letter = letterOf(raw(id), which);
        Letters.Letter l = letters.find(letter);
        return "DRAFT".equals(l.status()) || ("CANCELLED".equals(l.status()) && l.sha256() == null)
                ? letters.draftPdf(letter) : letters.file(letter);
    }

    /** Sends a letter of the case to the employee to sign for receipt; the decision letter notifies the sanction. */
    @Transactional
    public Case sendLetter(long id, String which) {
        Case c = raw(id);
        if ("hearing".equals(which)) {
            require(c, "HEARING");
        } else {
            require(c, "DECIDED");
        }
        letters.send(letterOf(c, which));
        if ("decision".equals(which)) {
            jdbc.sql("update discipline_case set status = 'NOTIFIED', notified_at = now() where id = ?").param(id).update();
            audit.record("discipline.notified", "user", c.userId(), Map.of("case", id));
        }
        return find(id);
    }

    /** Withdraws a sanction (or stops a case): it stays in the history but no longer counts; a mise à pied's leave goes. */
    @Transactional
    public Case cancel(long id, String reason) {
        Case c = raw(id);
        require(c, "PROPOSED", "OPEN", "HEARING", "HEARD", "DECIDED", "NOTIFIED");
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Say why the case is cancelled.");
        }
        Long leaveId = jdbc.sql("select leave_request_id from discipline_case where id = ?").param(id).query(Long.class)
                .optional().orElse(null);
        if (leaveId != null) {
            jdbc.sql("update leave_request set status = 'CANCELLED', decision_note = ? where id = ? and status = 'APPROVED'")
                    .params(Texts.in(company.language(), () -> Texts.t("Mise à pied cancelled")), leaveId).update();
        }
        if (!"NOTIFIED".equals(c.status())) {
            dropDraft(c.letterId());
        }
        if ("HEARING".equals(c.status())) {
            dropDraft(c.hearingLetterId());
        }
        jdbc.sql("""
                update discipline_case set status = 'CANCELLED', cancelled_by = ?, cancelled_at = now(), cancel_reason = ?
                where id = ?""").params(CurrentUser.email(), reason.trim(), id).update();
        audit.record("discipline.cancelled", "user", c.userId(), Map.of("case", id, "reason", reason.trim()));
        if (Set.of("DECIDED", "NOTIFIED").contains(c.status())) {
            notifications.send(c.userId(), "discipline.cancelled", () -> Texts.t("A sanction was withdrawn"),
                    () -> Texts.t("Case %s was cancelled; the sanction no longer counts.", c.number()), "/my/documents");
        }
        return find(id);
    }

    // ---- the employee ------------------------------------------------------------------------------------------

    /** The employee's own cases, once they were invited to a hearing; never the proposals or HR's notes. */
    public List<Case> mine(long userId) {
        return jdbc.sql(SELECT + """
                 where c.user_id = ? and c.status in ('HEARING', 'HEARD', 'DECIDED', 'NOTIFIED', 'CLOSED', 'CANCELLED')
                 and (hl.status in ('SENT', 'SIGNED', 'DECLINED') or dl.status in ('SENT', 'SIGNED', 'DECLINED'))
                order by c.opened_at desc""").param(userId).query(Case.class).list().stream()
                .map(c -> new Case(c.id(), c.userId(), c.employeeName(), c.registrationNo(), c.number(), c.status(), c.origin(),
                        c.factsDate(), c.knownDate(), c.facts(), c.ruleBroken(), null, null, null, c.openedAt(), null, null,
                        null, c.hearingAt(), c.hearingPlace(), c.hearingLetterId(), c.hearingLetterStatus(), c.heardOn(),
                        c.attended(), c.statement(), null, null, c.sanctionTypeId(), c.sanctionCode(), c.sanctionName(),
                        "CLOSED".equals(c.status()) ? null : c.decisionNote(), c.decidedOn(), null, c.suspensionFrom(),
                        c.suspensionTo(), c.suspensionDays(), c.letterId(), c.letterStatus(), c.notifiedAt(), c.contest(),
                        c.contestedAt(), null, c.cancelledAt(), c.cancelReason(), List.of()))
                .toList();
    }

    /** The employee contests a notified sanction in writing; HR is told and may cancel it. */
    @Transactional
    public Case contest(long id, long userId, String text) {
        Case c = raw(id);
        if (c.userId() != userId) {
            throw new NotFoundException("Discipline case not found.");
        }
        require(c, "NOTIFIED");
        if (c.contestedAt() != null) {
            throw new BusinessRuleException("You have already contested this sanction.");
        }
        if (text == null || text.isBlank()) {
            throw new BusinessRuleException("Write why you contest the sanction.");
        }
        jdbc.sql("update discipline_case set contest = ?, contested_at = now() where id = ?").params(text.trim(), id).update();
        audit.record("discipline.contested", "user", userId, Map.of("case", id));
        notifications.sendToHolders("discipline.manage", "discipline.contested", () -> Texts.t("A sanction is contested"),
                () -> Texts.t("%s contests the sanction of case %s.", c.employeeName(), c.number()), "/discipline");
        return mine(userId).stream().filter(x -> x.id() == id).findFirst().orElseThrow();
    }
}
