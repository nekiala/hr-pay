package cd.hrpay.health;

import static cd.hrpay.health.HealthSupport.clean;

import cd.hrpay.audit.AuditService;
import com.fasterxml.jackson.annotation.JsonProperty;
import cd.hrpay.common.BusinessRuleException;
import org.springframework.security.access.AccessDeniedException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.leave.LeaveService;
import cd.hrpay.lifecycle.Letters;
import cd.hrpay.notify.Notifications;
import cd.hrpay.org.OrgStructure;
import cd.hrpay.payment.Company;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The work accident register. DRC Loi 16/009 art. 65: an accident is declared to the CNSS within 60 days, an
 * occupational disease within 120 days, with a copy to the labour inspection and to the victim. Code art. 106: during
 * the incapacity the worker keeps two thirds of the pay for six months; the accident day is paid in full. Republic of
 * the Congo: 48 hours (to verify). Anyone can report an accident for themself, a unit head for the team; HR completes it.
 */
@Service
public class WorkAccidents {

    public static final Set<String> KINDS = Set.of("WORK", "COMMUTING", "DISEASE");
    public static final String LEAVE_CODE = "WORK_ACCIDENT";
    public static final String LETTER_CODE = "HEALTH_ACCIDENT_DECLARATION";
    static final ZoneId ZONE = ZoneId.of("Africa/Kinshasa");

    public record Accident(long id, long userId, String name, String registrationNo, String unit, String kind,
                           OffsetDateTime occurredAt, String place, String description, String witnesses, String injury,
                           String firstAid, LocalDate offFrom, LocalDate offTo, String status, String reportedBy,
                           OffsetDateTime reportedAt, LocalDate declareBy, long daysLeft, LocalDate declaredOn,
                           String declarationRef, LocalDate inspectionCopyOn, LocalDate victimCopyOn, Long letterId,
                           Long leaveRequestId, String note) {

        /** Calendar days off work, the accident day not counted. */
        @JsonProperty("daysOff")
        public long daysOff() {
            return offFrom == null || offTo == null ? 0 : ChronoUnit.DAYS.between(offFrom, offTo) + 1;
        }
    }

    public record Figures(int year, int accidents, int withTimeOff, long daysLost, int headcount, BigDecimal hoursWorked,
                          BigDecimal frequencyRate, BigDecimal severityRate, List<MonthCount> byMonth, List<UnitCount> byUnit) {}

    public record MonthCount(String month, int accidents, long daysLost) {}

    public record UnitCount(String unit, int accidents, long daysLost) {}

    private final JdbcClient jdbc;
    private final HealthSupport support;
    private final MedicalVisits visits;
    private final LeaveService leave;
    private final Letters letters;
    private final UserRepository users;
    private final OrgStructure org;
    private final Notifications notifications;
    private final Company company;
    private final AuditService audit;
    private final Clock clock;

    public WorkAccidents(JdbcClient jdbc, HealthSupport support, MedicalVisits visits, LeaveService leave, Letters letters,
                         UserRepository users, OrgStructure org, Notifications notifications, Company company,
                         AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.support = support;
        this.visits = visits;
        this.leave = leave;
        this.letters = letters;
        this.users = users;
        this.org = org;
        this.notifications = notifications;
        this.company = company;
        this.audit = audit;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static final String SELECT = """
            select a.id, a.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, u.registration_no,
                   (select ou.name from org_assignment x join org_unit ou on ou.id = x.unit_id
                    where x.user_id = a.user_id and x.valid_to is null order by x.valid_from desc limit 1) as unit,
                   a.kind, a.occurred_at, a.place, a.description, a.witnesses, a.injury, a.first_aid, a.off_from, a.off_to,
                   a.status, a.reported_by, a.reported_at, a.declared_on, a.declaration_ref, a.inspection_copy_on,
                   a.victim_copy_on, a.letter_id, a.leave_request_id, a.note
            from work_accident a join app_user u on u.id = a.user_id
            """;

    private record Row(long id, long userId, String name, String registrationNo, String unit, String kind,
                       OffsetDateTime occurredAt, String place, String description, String witnesses, String injury,
                       String firstAid, LocalDate offFrom, LocalDate offTo, String status, String reportedBy,
                       OffsetDateTime reportedAt, LocalDate declaredOn, String declarationRef, LocalDate inspectionCopyOn,
                       LocalDate victimCopyOn, Long letterId, Long leaveRequestId, String note) {}

    private Accident accident(Row r) {
        LocalDate day = r.occurredAt().atZoneSameInstant(ZONE).toLocalDate();
        LocalDate by = day.plusDays("DISEASE".equals(r.kind()) ? support.intSetting("DISEASE_DECLARE_DAYS", 120)
                : support.intSetting("ACCIDENT_DECLARE_DAYS", 60));
        return new Accident(r.id(), r.userId(), r.name(), r.registrationNo(), r.unit(), r.kind(), r.occurredAt(), r.place(),
                r.description(), r.witnesses(), r.injury(), r.firstAid(), r.offFrom(), r.offTo(), r.status(), r.reportedBy(),
                r.reportedAt(), by, ChronoUnit.DAYS.between(today(), by), r.declaredOn(), r.declarationRef(),
                r.inspectionCopyOn(), r.victimCopyOn(), r.letterId(), r.leaveRequestId(), r.note());
    }

    public Accident find(long id) {
        return jdbc.sql(SELECT + " where a.id = ?").param(id).query(Row.class).optional().map(this::accident)
                .orElseThrow(() -> new NotFoundException("Accident not found."));
    }

    /** The register of a year, the latest first. */
    public List<Accident> list(int year) {
        OffsetDateTime from = LocalDate.of(year, 1, 1).atStartOfDay(ZONE).toOffsetDateTime();
        return jdbc.sql(SELECT + " where a.occurred_at >= ? and a.occurred_at < ? order by a.occurred_at desc")
                .params(from, from.plusYears(1)).query(Row.class).list().stream().map(this::accident).toList();
    }

    /** Accidents not declared yet, whatever the year. */
    public List<Accident> undeclared() {
        return jdbc.sql(SELECT + " where a.declared_on is null order by a.occurred_at").query(Row.class).list().stream()
                .map(this::accident).toList();
    }

    public List<Accident> of(long userId) {
        return jdbc.sql(SELECT + " where a.user_id = ? order by a.occurred_at desc").param(userId).query(Row.class).list()
                .stream().map(this::accident).toList();
    }

    // ---- reporting ---------------------------------------------------------------------------------------------

    /**
     * Reports an accident: for oneself, for someone of one's team (a unit head), or for anyone (HR). HR is told at
     * once, since the declaration runs from the accident day.
     */
    @Transactional
    public Accident report(long userId, String kind, OffsetDateTime occurredAt, String place, String description,
                           String witnesses, String injury, String firstAid) {
        Long me = CurrentUser.id();
        if (!CurrentUser.has(HealthSupport.PERMISSION) && (me == null || (me != userId && !org.teamOf(me).contains(userId)))) {
            throw new AccessDeniedException("You can report an accident for yourself or for your team.");
        }
        if (!KINDS.contains(kind)) {
            throw new BusinessRuleException("Choose an accident at work, on the way, or an occupational disease.");
        }
        if (occurredAt == null || occurredAt.isAfter(OffsetDateTime.now(clock).plusMinutes(5))) {
            throw new BusinessRuleException("Give when it happened, now or before.");
        }
        if (description == null || description.isBlank()) {
            throw new BusinessRuleException("Say what happened.");
        }
        long id = jdbc.sql("""
                insert into work_accident (user_id, kind, occurred_at, place, description, witnesses, injury, first_aid,
                                           reported_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(userId, kind, occurredAt, clean(place, 200), clean(description, 4000), clean(witnesses, 500),
                        clean(injury, 500), clean(firstAid, 500), CurrentUser.email()).query(Long.class).single();
        audit.record("accident.reported", "user", userId, Map.of("accident", id, "kind", kind));
        Accident a = find(id);
        support.remind("accident.rep." + id, a.occurredAt().atZoneSameInstant(ZONE).toLocalDate(),
                () -> Texts.t("Work accident reported: %s", a.name()),
                () -> Texts.t("Complete the register and declare it to the CNSS by %s.", Texts.day(a.declareBy())), "/health");
        return a;
    }

    /**
     * HR completes the register. Days off are recorded as work accident leave (paid at its share, art. 106) from the
     * first day off, for six months at most; a long absence plans the return-to-work visit.
     */
    @Transactional
    public HealthSafety.Saved<Accident> complete(long id, String kind, OffsetDateTime occurredAt, String place,
                                                 String description, String witnesses, String injury, String firstAid,
                                                 LocalDate offFrom, LocalDate offTo, String note) {
        Accident a = find(id);
        if (!KINDS.contains(kind)) {
            throw new BusinessRuleException("Choose an accident at work, on the way, or an occupational disease.");
        }
        if (occurredAt == null || description == null || description.isBlank()) {
            throw new BusinessRuleException("Say when and what happened.");
        }
        LocalDate day = occurredAt.atZoneSameInstant(ZONE).toLocalDate();
        if (offFrom != null && offFrom.isBefore(day)) {
            throw new BusinessRuleException("Days off start on or after the accident day.");
        }
        if (offFrom != null && offTo != null && offTo.isBefore(offFrom)) {
            throw new BusinessRuleException("The last day off is on or after the first.");
        }
        if (offFrom == null && offTo != null) {
            throw new BusinessRuleException("Give the first day off.");
        }
        List<String> warnings = new ArrayList<>();
        Long leaveId = a.leaveRequestId();
        boolean datesChanged = !java.util.Objects.equals(offFrom, a.offFrom()) || !java.util.Objects.equals(offTo, a.offTo());
        if (datesChanged) {
            if (leaveId != null) {
                jdbc.sql("update leave_request set status = 'CANCELLED', decision_note = ? where id = ? and status = 'APPROVED'")
                        .params(Texts.in(company.language(), () -> Texts.t("Accident days changed")), leaveId).update();
                leaveId = null;
            }
            if (offFrom != null && offTo != null) {
                int months = support.intSetting("ACCIDENT_PAY_MONTHS", 6);
                LocalDate paidUntil = day.plusMonths(months);
                LocalDate to = offTo.isAfter(paidUntil) ? paidUntil : offTo;
                if (offTo.isAfter(paidUntil)) {
                    warnings.add(Texts.t("The accident leave keeps its pay for %d months, until %s: record the days after it as sick or unpaid leave.",
                            months, Texts.day(paidUntil)));
                }
                if (!to.isBefore(offFrom)) {
                    leaveId = accidentLeave(a.userId(), offFrom, to, a, warnings);
                }
            }
        }
        jdbc.sql("""
                update work_accident set kind = ?, occurred_at = ?, place = ?, description = ?, witnesses = ?, injury = ?,
                       first_aid = ?, off_from = ?, off_to = ?, note = ?, status = 'RECORDED', leave_request_id = ?
                where id = ?""")
                .params(kind, occurredAt, clean(place, 200), clean(description, 4000), clean(witnesses, 500), clean(injury, 500),
                        clean(firstAid, 500), offFrom, offTo, clean(note, 1000), leaveId, id).update();
        if (offFrom != null && offTo != null
                && ChronoUnit.DAYS.between(offFrom, offTo) + 1 >= support.intSetting("MEDICAL_RETURN_AFTER_DAYS", 30)) {
            visits.planReturn(a.userId(), offTo.plusDays(1), leaveId, id);
            warnings.add(Texts.t("A return-to-work visit is planned for %s (arrêté 28/75).", Texts.day(offTo.plusDays(1))));
        }
        audit.record("accident.recorded", "user", a.userId(), Map.of("accident", id, "kind", kind));
        return new HealthSafety.Saved<>(find(id), warnings);
    }

    private Long accidentLeave(long userId, LocalDate from, LocalDate to, Accident a, List<String> warnings) {
        Long typeId = jdbc.sql("select id from leave_type where code = ?").param(LEAVE_CODE).query(Long.class).optional()
                .orElse(null);
        if (typeId == null) {
            warnings.add(Texts.t("The work accident leave type is missing: record the days off on the Leave page."));
            return null;
        }
        int overlapping = jdbc.sql("""
                select count(*) from leave_request where user_id = ? and status in ('PENDING', 'AWAITING_HR', 'APPROVED')
                and start_date <= ? and end_date >= ?""").params(userId, to, from).query(Integer.class).single();
        if (overlapping > 0) {
            warnings.add(Texts.t("Leave is already requested or approved in these days: check the Leave page, the accident leave was not added."));
            return null;
        }
        AppUser user = users.findById(userId).orElseThrow();
        BigDecimal days = leave.workingDays(user, from, to);
        if (days.signum() == 0) {
            warnings.add(Texts.t("No working days for this person in these days: check the work schedule, the accident leave was not added."));
            return null;
        }
        String reason = Texts.in(company.language(), () -> Texts.t("Work accident of %s", Texts.day(
                a.occurredAt().atZoneSameInstant(ZONE).toLocalDate())));
        long id = jdbc.sql("""
                insert into leave_request (user_id, leave_type_id, start_date, end_date, working_days, reason, status,
                                           requested_by, decided_by, decided_at, decision_note)
                values (?, ?, ?, ?, ?, ?, 'APPROVED', ?, ?, now(), ?) returning id""")
                .params(userId, typeId, from, to, days, reason, CurrentUser.email(), CurrentUser.email(), reason)
                .query(Long.class).single();
        audit.record("leave.recorded", "leave_request", id, Map.of("userId", userId, "type", LEAVE_CODE,
                "start", from.toString(), "end", to.toString(), "days", days));
        warnings.add(Texts.t("%s working days recorded as work accident leave, from %s to %s.",
                days.stripTrailingZeros().toPlainString(), Texts.day(from), Texts.day(to)));
        return id;
    }

    /** Records the declaration to the CNSS and the copies sent. */
    @Transactional
    public Accident declare(long id, LocalDate declaredOn, String reference, LocalDate inspectionCopyOn, LocalDate victimCopyOn) {
        Accident a = find(id);
        LocalDate day = a.occurredAt().atZoneSameInstant(ZONE).toLocalDate();
        for (LocalDate d : java.util.Arrays.asList(declaredOn, inspectionCopyOn, victimCopyOn)) {
            if (d != null && (d.isBefore(day) || d.isAfter(today()))) {
                throw new BusinessRuleException("The declaration is dated between the accident and today.");
            }
        }
        jdbc.sql("""
                update work_accident set declared_on = ?, declaration_ref = ?, inspection_copy_on = ?, victim_copy_on = ?
                where id = ?""").params(declaredOn, clean(reference, 100), inspectionCopyOn, victimCopyOn, id).update();
        audit.record("accident.declared", "user", a.userId(), Map.of("accident", id,
                "declaredOn", declaredOn == null ? "" : declaredOn.toString()));
        return find(id);
    }

    /** The declaration letter, drafted from the company's model with the accident's facts; HR reads it before sending. */
    @Transactional
    public HealthSafety.Saved<Accident> draftLetter(long id) {
        Accident a = find(id);
        if (a.letterId() != null) {
            Letters.Letter l = letters.find(a.letterId());
            if (!"CANCELLED".equals(l.status())) {
                return new HealthSafety.Saved<>(a, List.of(Texts.t("The declaration letter is drafted already: open it from Documents and letters.")));
            }
        }
        Letters.Template t = letters.templates().stream().filter(x -> x.code().equals(LETTER_CODE) && x.active())
                .findFirst().orElse(null);
        if (t == null) {
            throw new BusinessRuleException("No declaration letter model is active: add one on Documents and letters (code HEALTH_ACCIDENT_DECLARATION).");
        }
        Map<String, String> v = Texts.in(company.language(), () -> values(a));
        String body = t.body();
        String title = t.name();
        for (Map.Entry<String, String> e : v.entrySet()) {
            String pattern = "\\{\\{\\s*" + java.util.regex.Pattern.quote(e.getKey()) + "\\s*}}";
            body = body.replaceAll(pattern, java.util.regex.Matcher.quoteReplacement(e.getValue()));
            title = title.replaceAll(pattern, java.util.regex.Matcher.quoteReplacement(e.getValue()));
        }
        long letterId = letters.create(a.userId(), t.id(), title, body, "LETTER", false).id();
        jdbc.sql("update work_accident set letter_id = ? where id = ?").params(letterId, id).update();
        return new HealthSafety.Saved<>(find(id), List.of(Texts.t("The declaration letter is ready as a draft: read it and send it from Documents and letters.")));
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** The accident's own fields for the declaration letter. */
    static Map<String, String> values(Accident a) {
        Map<String, String> v = new LinkedHashMap<>();
        var at = a.occurredAt().atZoneSameInstant(ZONE);
        v.put("accident.kind", kindName(a.kind()));
        v.put("accident.date", Texts.day(at.toLocalDate()));
        v.put("accident.time", at.toLocalTime().format(TIME));
        v.put("accident.place", a.place() == null ? "" : a.place());
        v.put("accident.description", a.description());
        v.put("accident.witnesses", a.witnesses() == null ? Texts.t("None") : a.witnesses());
        v.put("accident.injury", a.injury() == null ? "" : a.injury());
        v.put("accident.first_aid", a.firstAid() == null ? "" : a.firstAid());
        v.put("accident.days_off", a.offFrom() == null ? Texts.t("No time off")
                : a.offTo() == null ? Texts.t("From %s", Texts.day(a.offFrom()))
                : Texts.t("%d days, from %s to %s", a.daysOff(), Texts.day(a.offFrom()), Texts.day(a.offTo())));
        return v;
    }

    public static String kindName(String kind) {
        return switch (kind) {
            case "COMMUTING" -> Texts.t("Accident on the way to or from work");
            case "DISEASE" -> Texts.t("Occupational disease");
            default -> Texts.t("Accident at work");
        };
    }

    /** Removes an accident entered by mistake, with the leave it recorded. */
    @Transactional
    public void delete(long id) {
        Accident a = find(id);
        if (a.leaveRequestId() != null) {
            jdbc.sql("update leave_request set status = 'CANCELLED' where id = ? and status = 'APPROVED'")
                    .param(a.leaveRequestId()).update();
        }
        jdbc.sql("delete from medical_visit where accident_id = ? and visited_on is null").param(id).update();
        jdbc.sql("delete from work_accident where id = ?").param(id).update();
        audit.record("accident.deleted", "user", a.userId(), Map.of("accident", id));
    }

    // ---- figures -----------------------------------------------------------------------------------------------

    /**
     * The year's figures. Frequency rate: accidents with time off per million hours worked; severity rate: days lost
     * per thousand hours. Hours are estimated from the headcount (173.33 hours a month).
     */
    public Figures figures(int year) {
        List<Accident> all = list(year).stream().filter(a -> !"DISEASE".equals(a.kind())).toList();
        Map<String, long[]> months = new LinkedHashMap<>();
        for (int m = 1; m <= 12; m++) {
            months.put(YearMonth.of(year, m).toString(), new long[2]);
        }
        Map<String, long[]> units = new LinkedHashMap<>();
        int withTime = 0;
        long days = 0;
        for (Accident a : list(year)) {
            long[] c = months.get(YearMonth.from(a.occurredAt().atZoneSameInstant(ZONE)).toString());
            c[0]++;
            c[1] += a.daysOff();
            long[] u = units.computeIfAbsent(a.unit() == null ? "—" : a.unit(), k -> new long[2]);
            u[0]++;
            u[1] += a.daysOff();
        }
        for (Accident a : all) {
            if (a.daysOff() > 0) {
                withTime++;
                days += a.daysOff();
            }
        }
        LocalDate last = LocalDate.of(year, 12, 31).isAfter(today()) ? today() : LocalDate.of(year, 12, 31);
        int monthsCounted = last.getYear() == year ? last.getMonthValue() : 12;
        BigDecimal workers = BigDecimal.ZERO;
        for (int m = 1; m <= monthsCounted; m++) {
            workers = workers.add(BigDecimal.valueOf(support.headcount(YearMonth.of(year, m).atEndOfMonth().isAfter(today())
                    ? today() : YearMonth.of(year, m).atEndOfMonth())));
        }
        BigDecimal hours = workers.multiply(new BigDecimal("173.33")).setScale(0, RoundingMode.HALF_UP);
        BigDecimal freq = hours.signum() == 0 ? null
                : BigDecimal.valueOf(withTime).multiply(BigDecimal.valueOf(1_000_000)).divide(hours, 2, RoundingMode.HALF_UP);
        BigDecimal sev = hours.signum() == 0 ? null
                : BigDecimal.valueOf(days).multiply(BigDecimal.valueOf(1000)).divide(hours, 2, RoundingMode.HALF_UP);
        List<MonthCount> byMonth = new ArrayList<>();
        months.forEach((k, c) -> byMonth.add(new MonthCount(k, (int) c[0], c[1])));
        List<UnitCount> byUnit = new ArrayList<>();
        units.forEach((k, c) -> byUnit.add(new UnitCount(k, (int) c[0], c[1])));
        return new Figures(year, list(year).size(), withTime, days, support.headcount(last), hours, freq, sev, byMonth, byUnit);
    }

    // ---- the daily job -----------------------------------------------------------------------------------------

    /** Reminds HR of the declarations due: a week and three days before, the day before, then the day after. */
    @Transactional
    public int run() {
        int sent = 0;
        for (Accident a : undeclared()) {
            long left = a.daysLeft();
            if (left == 7 || left == 3 || left == 1 || left == -1) {
                sent += support.remind("accident.decl." + a.id(), today(),
                        () -> left < 0 ? Texts.t("Work accident of %s: the CNSS declaration is late", a.name())
                                : Texts.t("Work accident of %s: declare it to the CNSS by %s", a.name(), Texts.day(a.declareBy())),
                        () -> Texts.t("Send the declaration to the CNSS with a copy to the labour inspection and to the victim (Loi 16/009 art. 65), then record it."),
                        "/health");
            }
        }
        return sent;
    }
}
