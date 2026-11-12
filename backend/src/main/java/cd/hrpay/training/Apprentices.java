package cd.hrpay.training;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.Notifications;
import cd.hrpay.security.CurrentUser;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Apprentices (DRC Code du travail art. 7, 18-35): the contract of at most four years, the ONEM's visa without which it
 * is an ordinary employment contract (art. 21), the medical certificate (art. 22), the age (18, or 15 with the Tribunal de
 * paix's waiver: art. 6 and 133), the master, the yearly review of the allowance (art. 25), the end certificate (art. 24)
 * and the end reported to the inspector and ONEM (art. 33), and the stay of up to two years afterwards (art. 27).
 */
@Service
public class Apprentices {

    public static final int MAX_YEARS = 4;
    public static final int MAX_STAY_MONTHS = 24;

    public record Apprentice(long contractId, long userId, String name, String registrationNo, LocalDate startDate,
                             LocalDate endDate, String gradeCode, String occupationName, String trade, Long masterUserId,
                             String masterName, java.time.LocalDate onemVisaOn, String onemVisaNo, LocalDate medicalOn,
                             LocalDate waiverOn, LocalDate birthDate, Integer ageAtStart, LocalDate allowanceReviewOn,
                             LocalDate endCertificateOn, LocalDate endReportedOn, Integer stayMonths, LocalDate stayUntil,
                             String note, List<String> warnings) {}

    public record Input(String trade, Long masterUserId, LocalDate onemVisaOn, String onemVisaNo, LocalDate medicalOn,
                        LocalDate waiverOn, LocalDate allowanceReviewOn, LocalDate endCertificateOn,
                        LocalDate endReportedOn, Integer stayMonths, String note) {}

    private record Row(long contractId, long userId, String name, String registrationNo, LocalDate startDate,
                       LocalDate endDate, String gradeCode, String occupationName, String trade, Long masterUserId,
                       String masterName, LocalDate onemVisaOn, String onemVisaNo, LocalDate medicalOn, LocalDate waiverOn,
                       LocalDate birthDate, LocalDate masterBirthDate, LocalDate allowanceReviewOn,
                       LocalDate endCertificateOn, LocalDate endReportedOn, Integer stayMonths, String note,
                       LocalDate remindedOn) {}

    private static final String SELECT = """
            select c.id as contract_id, c.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, u.registration_no,
                   c.start_date, c.end_date, g.code as grade_code, o.name as occupation_name, a.trade, a.master_user_id,
                   person_name(m.first_name, m.middle_name, m.last_name) as master_name, a.onem_visa_on, a.onem_visa_no,
                   a.medical_on, a.waiver_on, p.birth_date, mp.birth_date as master_birth_date, a.allowance_review_on,
                   a.end_certificate_on, a.end_reported_on, a.stay_months, a.note, a.reminded_on
            from employee_contract c join apprenticeship a on a.contract_id = c.id
            join app_user u on u.id = c.user_id join grade g on g.id = c.grade_id
            left join occupation o on o.id = c.occupation_id left join employee_profile p on p.user_id = c.user_id
            left join app_user m on m.id = a.master_user_id left join employee_profile mp on mp.user_id = a.master_user_id
            """;

    private static final ZoneId ZONE = ZoneId.of("Africa/Kinshasa");

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final Notifications notifications;
    private final Clock clock;

    public Apprentices(JdbcClient jdbc, AuditService audit, Notifications notifications, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.notifications = notifications;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    private static void requireHr() {
        if (!CurrentUser.has("training.manage") && !CurrentUser.has("salary.write")) {
            throw new AccessDeniedException("Only HR can do this.");
        }
    }

    /** The checks of a contract about to start: at most four years, and never under 15 (art. 6, 19, 133). */
    public static void check(LocalDate start, LocalDate end, LocalDate birthDate) {
        if (end == null) {
            throw new BusinessRuleException("An apprenticeship contract needs an end date (four years at most).");
        }
        if (end.isAfter(start.plusYears(MAX_YEARS))) {
            throw new BusinessRuleException("An apprenticeship lasts four years at most (Code du travail art. 19).");
        }
        if (birthDate != null && Period.between(birthDate, start).getYears() < 15) {
            throw new BusinessRuleException("Nobody under 15 may be an apprentice (Code du travail art. 133).");
        }
    }

    /** Records the apprenticeship beside a contract just started. */
    public void created(long contractId, long userId) {
        jdbc.sql("insert into apprenticeship (contract_id, user_id, updated_by) values (?, ?, ?) on conflict do nothing")
                .params(contractId, userId, CurrentUser.email()).update();
    }

    public List<Apprentice> list(boolean ended) {
        requireHr();
        LocalDate today = today();
        return jdbc.sql(SELECT + (ended ? "" : " where (c.end_date is null or c.end_date >= :today or a.end_reported_on is null)")
                        + " order by c.start_date desc")
                .param("today", today).query(Row.class).list().stream().map(r -> view(r, today)).toList();
    }

    public Apprentice find(long contractId) {
        requireHr();
        return jdbc.sql(SELECT + " where c.id = ?").param(contractId).query(Row.class).optional()
                .map(r -> view(r, today())).orElseThrow(() -> new NotFoundException("Apprenticeship not found."));
    }

    @Transactional
    public Apprentice save(long contractId, Input in) {
        requireHr();
        Apprentice a = find(contractId);
        if (in.stayMonths() != null && (in.stayMonths() < 0 || in.stayMonths() > MAX_STAY_MONTHS)) {
            throw new BusinessRuleException("The apprentice may be bound to stay two years at most (Code du travail art. 27).");
        }
        if (in.masterUserId() != null && in.masterUserId() == a.userId()) {
            throw new BusinessRuleException("The apprentice cannot be their own master.");
        }
        LocalDate today = today();
        for (LocalDate d : new LocalDate[] {in.onemVisaOn(), in.medicalOn(), in.waiverOn(), in.endCertificateOn(),
                in.endReportedOn(), in.allowanceReviewOn()}) {
            if (d != null && d.isAfter(today)) {
                throw new BusinessRuleException("A date recorded here cannot be in the future.");
            }
        }
        jdbc.sql("""
                update apprenticeship set trade = ?, master_user_id = ?, onem_visa_on = ?, onem_visa_no = ?, medical_on = ?,
                       waiver_on = ?, allowance_review_on = ?, end_certificate_on = ?, end_reported_on = ?, stay_months = ?,
                       note = ?, updated_by = ?, updated_at = now() where contract_id = ?""")
                .params(clean(in.trade()), in.masterUserId(), in.onemVisaOn(), clean(in.onemVisaNo()), in.medicalOn(),
                        in.waiverOn(), in.allowanceReviewOn(), in.endCertificateOn(), in.endReportedOn(), in.stayMonths(),
                        clean(in.note()), CurrentUser.email(), contractId).update();
        audit.record("apprenticeship.updated", "user", a.userId(), Map.of("contract", contractId));
        return find(contractId);
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private Apprentice view(Row r, LocalDate today) {
        Integer age = r.birthDate() == null ? null : Period.between(r.birthDate(), r.startDate()).getYears();
        LocalDate stayUntil = r.stayMonths() == null || r.stayMonths() == 0 || r.endDate() == null ? null
                : r.endDate().plusMonths(r.stayMonths());
        return new Apprentice(r.contractId(), r.userId(), r.name(), r.registrationNo(), r.startDate(), r.endDate(),
                r.gradeCode(), r.occupationName(), r.trade(), r.masterUserId(), r.masterName(), r.onemVisaOn(),
                r.onemVisaNo(), r.medicalOn(), r.waiverOn(), r.birthDate(), age, r.allowanceReviewOn(),
                r.endCertificateOn(), r.endReportedOn(), r.stayMonths(), stayUntil, r.note(), warnings(r, age, today));
    }

    static List<String> warnings(Row r, Integer age, LocalDate today) {
        List<String> w = new ArrayList<>();
        boolean ended = r.endDate() != null && r.endDate().isBefore(today);
        if (r.onemVisaOn() == null) {
            w.add(Texts.t("No ONEM visa recorded: without it the contract counts as an ordinary employment contract (art. 21)."));
        }
        if (r.medicalOn() == null) {
            w.add(Texts.t("No medical certificate recorded (art. 22)."));
        } else if (r.medicalOn().isBefore(r.startDate().minusMonths(3))) {
            w.add(Texts.t("The medical certificate is more than three months older than the start (art. 22)."));
        }
        if (r.birthDate() == null) {
            w.add(Texts.t("No birth date in the employee file: the age cannot be checked."));
        } else if (age != null && age < 18 && r.waiverOn() == null) {
            w.add(Texts.t("Under 18 at the start: record the waiver of the Tribunal de paix (art. 6)."));
        }
        if (r.masterUserId() == null) {
            w.add(Texts.t("Name the master who trains the apprentice."));
        } else if (r.masterBirthDate() != null && Period.between(r.masterBirthDate(), today).getYears() < 18) {
            w.add(Texts.t("The master must be at least 18."));
        }
        LocalDate reviewFrom = r.allowanceReviewOn() != null ? r.allowanceReviewOn() : r.startDate();
        if (!ended && !reviewFrom.plusYears(1).isAfter(today)) {
            w.add(Texts.t("The allowance is reviewed each year (art. 25): last on %s.", Texts.day(reviewFrom)));
        }
        if (ended && r.endCertificateOn() == null) {
            w.add(Texts.t("Give the end certificate (art. 24)."));
        }
        if (ended && r.endReportedOn() == null) {
            w.add(Texts.t("Report the end of the apprenticeship to the labour inspector and ONEM (art. 33)."));
        }
        return w;
    }

    /** Reminds HR, at most once a month for each apprentice, of what is missing. */
    @Transactional
    public int remind() {
        LocalDate today = today();
        List<Row> rows = jdbc.sql(SELECT + """
                 where (a.reminded_on is null or a.reminded_on <= ?) and (a.end_reported_on is null)
                   and c.start_date <= ?""").params(today.minusDays(30), today.minusDays(15)).query(Row.class).list();
        int sent = 0;
        for (Row r : rows) {
            Integer age = r.birthDate() == null ? null : Period.between(r.birthDate(), r.startDate()).getYears();
            List<String> w = warnings(r, age, today);
            if (w.isEmpty()) {
                continue;
            }
            notifications.sendToHolders("training.manage", "apprentice.followup",
                    () -> Texts.t("Apprentice to follow: %s", r.name()), () -> String.join(" ", warnings(r, age, today)),
                    "/training?tab=apprentices");
            jdbc.sql("update apprenticeship set reminded_on = ? where contract_id = ?").params(today, r.contractId()).update();
            sent++;
        }
        return sent;
    }
}
