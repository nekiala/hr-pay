package cd.hrpay.health;

import static cd.hrpay.health.HealthSupport.clean;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.Notifications;
import cd.hrpay.org.OrgStructure;
import cd.hrpay.payment.Company;
import cd.hrpay.security.CurrentUser;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Medical visits at work. DRC Code art. 38 and arrêté 28/75: an exam before hiring, a certificate valid one year (six
 * months under 18), a visit after 30 days or more off; arrêté 01/76: one exam a year for every worker. The employer
 * pays. Only the result the doctor gives the employer is kept (fit, fit with restrictions, unfit for now, unfit),
 * never a diagnosis.
 */
@Service
public class MedicalVisits {

    public static final Set<String> KINDS = Set.of("HIRING", "PROVISIONAL", "YEARLY", "RETURN", "SPECIAL");
    public static final Set<String> RESULTS = Set.of("FIT", "FIT_RESTRICTED", "UNFIT_TEMPORARY", "UNFIT");
    /** Leave kinds after which a long absence calls for a return-to-work visit. */
    static final List<String> RETURN_LEAVE = List.of("SICK", "WORK_ACCIDENT", "MATERNITY");

    public record Visit(long id, long userId, String name, String registrationNo, String unit, String kind, LocalDate dueOn,
                        LocalDate visitedOn, String doctor, String result, String restrictions, LocalDate validUntil,
                        String note, String fileName, boolean hasFile, Long accidentId, Long leaveRequestId,
                        String createdBy) {}

    /** The next visit a person needs: planned already (plannedId) or worked out from the last one. */
    public record Due(long userId, String name, String registrationNo, String unit, String site, String kind,
                      LocalDate dueOn, long daysLeft, Long plannedId, LocalDate lastVisitOn, String lastResult,
                      String restrictions) {}

    /** What a unit head sees of a team member: fit or not, and the restrictions. */
    public record Fitness(long userId, String name, String registrationNo, String result, String restrictions,
                          LocalDate validUntil, LocalDate nextDue) {}

    /** How medical care is organised, and what arrêté 01/76 asks for the headcount (to verify). */
    public record Service(String organisation, int headcount, String required, boolean verified) {}

    public record Page(List<Due> due, List<Visit> recent, Service service) {}

    private final JdbcClient jdbc;
    private final HealthSupport support;
    private final OrgStructure org;
    private final Notifications notifications;
    private final Company company;
    private final AuditService audit;
    private final Clock clock;

    public MedicalVisits(JdbcClient jdbc, HealthSupport support, OrgStructure org, Notifications notifications,
                         Company company, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.support = support;
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
            select v.id, v.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, u.registration_no,
                   (select ou.name from org_assignment a join org_unit ou on ou.id = a.unit_id
                    where a.user_id = v.user_id and a.valid_to is null order by a.valid_from desc limit 1) as unit,
                   v.kind, v.due_on, v.visited_on, v.doctor, v.result, v.restrictions, v.valid_until, v.note, v.file_name,
                   (v.file_data is not null) as has_file, v.accident_id, v.leave_request_id, v.created_by
            from medical_visit v join app_user u on u.id = v.user_id
            """;

    public Visit visit(long id) {
        return jdbc.sql(SELECT + " where v.id = ?").param(id).query(Visit.class).optional()
                .orElseThrow(() -> new NotFoundException("Medical visit not found."));
    }

    public List<Visit> of(long userId) {
        return jdbc.sql(SELECT + " where v.user_id = ? order by coalesce(v.visited_on, v.due_on) desc, v.id desc")
                .param(userId).query(Visit.class).list();
    }

    public Page page(int days) {
        LocalDate until = today().plusDays(Math.max(0, days));
        List<Due> due = due().stream().filter(d -> !d.dueOn().isAfter(until)).toList();
        List<Visit> recent = jdbc.sql(SELECT + " where v.visited_on is not null order by v.visited_on desc, v.id desc limit 100")
                .query(Visit.class).list();
        return new Page(due, recent, service());
    }

    /** Visits that took place in a year. */
    public int held(int year) {
        return jdbc.sql("select count(*) from medical_visit where visited_on between ? and ?")
                .params(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)).query(Integer.class).single();
    }

    // ---- what is due -------------------------------------------------------------------------------------------

    private record Person(long userId, String name, String registrationNo, String unit, String site, LocalDate hireDate,
                          LocalDate birthDate) {}

    private record Last(long userId, String kind, LocalDate visitedOn, String result, String restrictions,
                        LocalDate validUntil) {}

    /** Validity of a certificate given on a day: one year, six months under 18 (AD 28/75). */
    LocalDate validity(LocalDate visitedOn, LocalDate birthDate) {
        boolean minor = birthDate != null && birthDate.plusYears(18).isAfter(visitedOn);
        int months = minor ? support.intSetting("MEDICAL_VALID_MONTHS_MINOR", 6) : support.intSetting("MEDICAL_VALID_MONTHS", 12);
        return visitedOn.plusMonths(months).minusDays(1);
    }

    /** For each worker under contract today, the next visit due, the soonest first. */
    public List<Due> due() {
        LocalDate today = today();
        List<Person> people = jdbc.sql("""
                select u.id as user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, u.registration_no,
                       (select ou.name from org_assignment a join org_unit ou on ou.id = a.unit_id
                        where a.user_id = u.id and a.valid_to is null order by a.valid_from desc limit 1) as unit,
                       (select s.name from site s where s.id = u.site_id) as site,
                       (select min(c.start_date) from employee_contract c where c.user_id = u.id) as hire_date,
                       (select p.birth_date from employee_profile p where p.user_id = u.id) as birth_date
                from app_user u
                where not u.not_employee and exists (select 1 from employee_contract c where c.user_id = u.id
                      and c.contract_type <> 'CONSULTANT' and c.start_date <= ? and (c.end_date is null or c.end_date >= ?))
                order by name""").params(today.plusDays(60), today).query(Person.class).list();
        Map<Long, Last> last = new HashMap<>();
        jdbc.sql("""
                select distinct on (user_id) user_id, kind, visited_on, result, restrictions, valid_until
                from medical_visit where visited_on is not null order by user_id, visited_on desc, id desc""")
                .query(Last.class).list().forEach(l -> last.put(l.userId(), l));
        Map<Long, Visit> planned = new HashMap<>();
        jdbc.sql(SELECT + " where v.visited_on is null order by v.due_on").query(Visit.class).list()
                .forEach(v -> planned.putIfAbsent(v.userId(), v));
        List<Due> out = new ArrayList<>();
        for (Person p : people) {
            Last l = last.get(p.userId());
            Visit plan = planned.get(p.userId());
            String kind;
            LocalDate due;
            if (plan != null) {
                kind = plan.kind();
                due = plan.dueOn();
            } else if (l == null) {
                boolean recent = p.hireDate() != null && p.hireDate().isAfter(today.minusDays(365));
                kind = recent ? "HIRING" : "YEARLY";
                due = recent ? p.hireDate() : today;
            } else if ("UNFIT_TEMPORARY".equals(l.result())) {
                kind = "SPECIAL";
                due = l.validUntil() != null ? l.validUntil() : l.visitedOn().plusMonths(1);
            } else if ("UNFIT".equals(l.result())) {
                continue;
            } else {
                kind = "YEARLY";
                due = (l.validUntil() != null ? l.validUntil() : validity(l.visitedOn(), p.birthDate())).plusDays(1);
            }
            out.add(new Due(p.userId(), p.name(), p.registrationNo(), p.unit(), p.site(), kind, due,
                    ChronoUnit.DAYS.between(today, due), plan == null ? null : plan.id(),
                    l == null ? null : l.visitedOn(), l == null ? null : l.result(), l == null ? null : l.restrictions()));
        }
        out.sort(java.util.Comparator.comparing(Due::dueOn).thenComparing(Due::name));
        return out;
    }

    // ---- recording ---------------------------------------------------------------------------------------------

    /**
     * Plans a visit (no result) or records one that took place. A result needs the day of the visit. The certificate's
     * validity defaults to one year (six months under 18); a nurse's provisional certificate lasts until the doctor's
     * exam, planned within three months. A fit result at hiring fills the contract's medical fitness when empty.
     */
    @Transactional
    public HealthSafety.Saved<Visit> add(long userId, String kind, LocalDate dueOn, LocalDate visitedOn, String doctor,
                                         String result, String restrictions, LocalDate validUntil, String note,
                                         String fileName, byte[] data) {
        if (!KINDS.contains(kind)) {
            throw new BusinessRuleException("Choose the kind of visit.");
        }
        HealthSupport.Upload file = HealthSupport.upload(fileName, data);
        if (visitedOn == null) {
            if (dueOn == null) {
                throw new BusinessRuleException("Give the day of the visit, or the day it is due.");
            }
            if (result != null || file.present()) {
                throw new BusinessRuleException("A result needs the day of the visit.");
            }
            long id = insert(userId, kind, dueOn, null, doctor, null, null, null, note, file, null, null);
            Visit v = visit(id);
            notifications.send(userId, "medical.planned",
                    () -> Texts.t("Medical visit due on %s", Texts.day(dueOn)),
                    () -> Texts.t("HR will tell you the place and time. The visit is paid by the company."), "/my/documents");
            return new HealthSafety.Saved<>(v, List.of());
        }
        if (visitedOn.isAfter(today())) {
            throw new BusinessRuleException("A visit that took place is dated today or before.");
        }
        if (!RESULTS.contains(result)) {
            throw new BusinessRuleException("Choose the result the doctor gave.");
        }
        if ("FIT_RESTRICTED".equals(result) && (restrictions == null || restrictions.isBlank())) {
            throw new BusinessRuleException("Write the restrictions the doctor gave.");
        }
        List<String> warnings = new ArrayList<>();
        LocalDate valid = valid(userId, kind, visitedOn, result, validUntil);
        long id = insert(userId, kind, dueOn, visitedOn, doctor, result, restrictions, valid, note, file, null, null);
        followUp(userId, kind, visitedOn, result, doctor, warnings);
        return new HealthSafety.Saved<>(visit(id), warnings);
    }

    /** Records the result of a planned visit. */
    @Transactional
    public HealthSafety.Saved<Visit> record(long id, LocalDate visitedOn, String doctor, String result, String restrictions,
                                            LocalDate validUntil, String note, String fileName, byte[] data) {
        Visit v = visit(id);
        if (v.visitedOn() != null) {
            throw new BusinessRuleException("This visit is recorded already.");
        }
        if (visitedOn == null || visitedOn.isAfter(today())) {
            throw new BusinessRuleException("A visit that took place is dated today or before.");
        }
        if (!RESULTS.contains(result)) {
            throw new BusinessRuleException("Choose the result the doctor gave.");
        }
        if ("FIT_RESTRICTED".equals(result) && (restrictions == null || restrictions.isBlank())) {
            throw new BusinessRuleException("Write the restrictions the doctor gave.");
        }
        HealthSupport.Upload file = HealthSupport.upload(fileName, data);
        LocalDate valid = valid(v.userId(), v.kind(), visitedOn, result, validUntil);
        jdbc.sql("""
                update medical_visit set visited_on = ?, doctor = ?, result = ?, restrictions = ?, valid_until = ?,
                       note = coalesce(?, note), file_name = coalesce(?, file_name), file_type = coalesce(?, file_type),
                       file_data = coalesce(?, file_data)
                where id = ?""")
                .params(visitedOn, clean(doctor, 150), result, "FIT".equals(result) ? null : clean(restrictions, 500), valid,
                        clean(note, 500), file.name(), file.type(), file.data(), id).update();
        audit.record("medical.recorded", "user", v.userId(), Map.of("visit", id, "kind", v.kind(), "on", visitedOn.toString()));
        List<String> warnings = new ArrayList<>();
        followUp(v.userId(), v.kind(), visitedOn, result, doctor, warnings);
        return new HealthSafety.Saved<>(visit(id), warnings);
    }

    private LocalDate valid(long userId, String kind, LocalDate visitedOn, String result, LocalDate validUntil) {
        if (validUntil != null) {
            if (validUntil.isBefore(visitedOn)) {
                throw new BusinessRuleException("The certificate is valid until a day after the visit.");
            }
            return validUntil;
        }
        if ("UNFIT".equals(result)) {
            return null;
        }
        if ("UNFIT_TEMPORARY".equals(result)) {
            return visitedOn.plusMonths(1);
        }
        if ("PROVISIONAL".equals(kind)) {
            return visitedOn.plusMonths(support.intSetting("MEDICAL_PROVISIONAL_MONTHS", 3)).minusDays(1);
        }
        LocalDate birth = jdbc.sql("select birth_date from employee_profile where user_id = ?").param(userId)
                .query(LocalDate.class).optional().orElse(null);
        return validity(visitedOn, birth);
    }

    private void followUp(long userId, String kind, LocalDate visitedOn, String result, String doctor, List<String> warnings) {
        boolean fit = "FIT".equals(result) || "FIT_RESTRICTED".equals(result);
        if ("PROVISIONAL".equals(kind) && fit) {
            LocalDate due = visitedOn.plusMonths(support.intSetting("MEDICAL_PROVISIONAL_MONTHS", 3));
            insert(userId, "HIRING", due, null, null, null, null, null, null, HealthSupport.Upload.NONE, null, null);
            warnings.add(Texts.t("A nurse's certificate is provisional: the doctor's exam is planned for %s.", Texts.day(due)));
        }
        if (("HIRING".equals(kind) || "PROVISIONAL".equals(kind)) && fit) {
            Long contract = jdbc.sql("select id from employee_contract where user_id = ? order by start_date desc, id desc limit 1")
                    .param(userId).query(Long.class).optional().orElse(null);
            if (contract != null) {
                int n = jdbc.sql("""
                        update contract_terms set medical_fit_on = ?, medical_doctor = coalesce(medical_doctor, ?)
                        where contract_id = ? and medical_fit_on is null""").params(visitedOn, clean(doctor, 150), contract).update();
                if (n == 0) {
                    jdbc.sql("""
                            insert into contract_terms (contract_id, medical_fit_on, medical_doctor) values (?, ?, ?)
                            on conflict (contract_id) do nothing""").params(contract, visitedOn, clean(doctor, 150)).update();
                }
            }
        }
        if ("UNFIT".equals(result)) {
            warnings.add(Texts.t("Unfit for the job: look for another post the person can hold, with the doctor, before any other step."));
        }
        if ("UNFIT_TEMPORARY".equals(result)) {
            warnings.add(Texts.t("Unfit for now: the person does not take up the job until a new visit says fit."));
        }
    }

    private long insert(long userId, String kind, LocalDate dueOn, LocalDate visitedOn, String doctor, String result,
                        String restrictions, LocalDate validUntil, String note, HealthSupport.Upload file, Long leaveId,
                        Long accidentId) {
        long id = jdbc.sql("""
                insert into medical_visit (user_id, kind, due_on, visited_on, doctor, result, restrictions, valid_until, note,
                                           file_name, file_type, file_data, leave_request_id, accident_id, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(userId, kind, dueOn, visitedOn, clean(doctor, 150), result,
                        "FIT".equals(result) ? null : clean(restrictions, 500), validUntil, clean(note, 500), file.name(),
                        file.type(), file.data(), leaveId, accidentId, CurrentUser.email() == null ? "system" : CurrentUser.email())
                .query(Long.class).single();
        audit.record(visitedOn == null ? "medical.planned" : "medical.recorded", "user", userId,
                Map.of("visit", id, "kind", kind));
        return id;
    }

    /** Plans the return-to-work visit after a long absence, once per absence (or per accident). */
    long planReturn(long userId, LocalDate due, Long leaveId, Long accidentId) {
        Long existing = jdbc.sql("""
                select id from medical_visit where kind = 'RETURN' and visited_on is null and user_id = ?
                  and (leave_request_id = ? or accident_id = ?)""")
                .params(userId, leaveId, accidentId).query(Long.class).optional().orElse(null);
        if (existing != null) {
            jdbc.sql("update medical_visit set due_on = ?, leave_request_id = coalesce(?, leave_request_id) where id = ?")
                    .params(due, leaveId, existing).update();
            return existing;
        }
        return insert(userId, "RETURN", due, null, null, null, null, null, null, HealthSupport.Upload.NONE, leaveId, accidentId);
    }

    @Transactional
    public void delete(long id) {
        Visit v = visit(id);
        jdbc.sql("delete from medical_visit where id = ?").param(id).update();
        audit.record("medical.deleted", "user", v.userId(), Map.of("visit", id, "kind", v.kind()));
    }

    public HealthSupport.File file(long id) {
        return jdbc.sql("select file_name, file_type, file_data from medical_visit where id = ? and file_data is not null")
                .param(id).query((rs, n) -> new HealthSupport.File(rs.getString(1), rs.getString(2), rs.getBytes(3)))
                .optional().orElseThrow(() -> new NotFoundException("No file."));
    }

    // ---- who sees what -----------------------------------------------------------------------------------------

    /** The people a unit head answers for. */
    public Set<Long> teamIds(long managerId) {
        return org.teamOf(managerId);
    }

    /** A unit head's team: fit or not and the restrictions, nothing else. */
    public List<Fitness> team(long managerId) {
        Set<Long> team = org.teamOf(managerId);
        if (team.isEmpty()) {
            return List.of();
        }
        Map<Long, Due> due = new HashMap<>();
        due().forEach(d -> due.put(d.userId(), d));
        return jdbc.sql("""
                select u.id as user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, u.registration_no,
                       l.result, l.restrictions, l.valid_until, cast(null as date) as next_due
                from app_user u
                left join lateral (select v.result, v.restrictions, v.valid_until from medical_visit v
                                   where v.user_id = u.id and v.visited_on is not null
                                   order by v.visited_on desc, v.id desc limit 1) l on true
                where u.id in (:team) order by name""").param("team", team).query(Fitness.class).list().stream()
                .map(f -> new Fitness(f.userId(), f.name(), f.registrationNo(), f.result(), f.restrictions(), f.validUntil(),
                        due.containsKey(f.userId()) ? due.get(f.userId()).dueOn() : null))
                .toList();
    }

    // ---- the company's medical service -------------------------------------------------------------------------

    public static final Set<String> ORGANISATIONS = Set.of("OWN", "SHARED", "OUTSIDE");

    public Service service() {
        int headcount = support.headcount(today());
        String org = support.setting("MEDICAL_SERVICE", "OUTSIDE");
        boolean cd = "CD".equals(company.country());
        String required;
        if (!cd) {
            required = Texts.t("Have a medical or health service for the workers. The rule by headcount is not known yet.");
        } else if (headcount < 100) {
            required = Texts.t("Under 100 workers: a doctor or clinic under contract, or a service shared with other companies (arrêté 01/76).");
        } else if (headcount < 1000) {
            required = Texts.t("From 100 workers: a nurse at the workplace and a doctor who visits regularly (arrêté 01/76).");
        } else {
            required = Texts.t("From 1,000 workers: the company's own medical service with a doctor (arrêté 01/76).");
        }
        return new Service(org, headcount, required, false);
    }

    @Transactional
    public Service saveService(String organisation) {
        if (!ORGANISATIONS.contains(organisation)) {
            throw new BusinessRuleException("Choose how medical care is organised.");
        }
        support.saveSetting("MEDICAL_SERVICE", organisation);
        audit.record("medical.service_saved", "company", null, Map.of("organisation", organisation));
        return service();
    }

    // ---- the daily job -----------------------------------------------------------------------------------------

    /** Plans return-to-work visits after long absences and reminds HR of the visits due; the number of messages. */
    @Transactional
    public int run() {
        LocalDate today = today();
        int after = support.intSetting("MEDICAL_RETURN_AFTER_DAYS", 30);
        record Absence(long id, long userId, LocalDate endDate) {}
        List<Absence> absences = jdbc.sql("""
                select l.id, l.user_id, l.end_date from leave_request l join leave_type t on t.id = l.leave_type_id
                where l.status = 'APPROVED' and t.code in (:codes) and (l.end_date - l.start_date + 1) >= :after
                  and l.end_date between :from and :to
                  and not exists (select 1 from medical_visit v where v.leave_request_id = l.id)""")
                .param("codes", RETURN_LEAVE).param("after", after).param("from", today.minusDays(30))
                .param("to", today.plusDays(14)).query(Absence.class).list();
        for (Absence a : absences) {
            planReturn(a.userId(), a.endDate().plusDays(1), a.id(), null);
        }
        int remindDays = support.intSetting("MEDICAL_REMIND_DAYS", 30);
        List<Due> due = due().stream().filter(d -> d.daysLeft() <= remindDays).toList();
        if (due.isEmpty()) {
            return 0;
        }
        long late = due.stream().filter(d -> d.daysLeft() < 0).count();
        long soon = due.size() - late;
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        return support.remind("medical.due", monday,
                () -> Texts.t("Medical visits: %d due within %d days, %d late", soon, remindDays, late),
                () -> Texts.t("Plan the visits with the doctor or clinic; the list is on the Health and safety page."),
                "/health");
    }
}
