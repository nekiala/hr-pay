package cd.hrpay.recruitment;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.lifecycle.Checklists;
import cd.hrpay.notify.NotificationService;
import cd.hrpay.notify.Notifications;
import cd.hrpay.org.OrgStructure;
import cd.hrpay.payment.Company;
import cd.hrpay.payroll.MasterData;
import cd.hrpay.payroll.NegotiatedPay;
import cd.hrpay.position.Positions;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Recruitment: a request to hire, HR's decision, the vacancy and its ad, the candidates and their stages, interviews
 * with scorecards, the offer and the hire. HR (recruitment.manage) does everything; a unit head asks to hire for the
 * units they run, follows those vacancies and scores the interviews they sit on.
 */
@Service
public class Recruitment {

    public static final Set<String> REASONS = Set.of("NEW_POST", "REPLACEMENT", "TEMPORARY");
    public static final Set<String> CONTRACT_TYPES = Set.of("PERMANENT", "FIXED_TERM", "INTERN", "CONSULTANT", "DAILY");
    public static final Set<String> VISIBILITY = Set.of("INTERNAL", "PUBLIC", "INTERNAL_FIRST");
    public static final Set<String> SOURCES = Set.of("CAREERS", "INTERNAL", "REFERRAL", "AGENCY", "OTHER");
    /** Stages HR moves a candidate to by hand; OFFER comes with the offer, HIRED with the hire. */
    public static final Set<String> MOVES = Set.of("APPLIED", "SCREENED", "INTERVIEW", "REJECTED", "WITHDRAWN", "ON_HOLD");
    static final Set<String> ACTIVE = Set.of("APPLIED", "SCREENED", "INTERVIEW", "OFFER", "ON_HOLD");
    /** DRC: a foreigner is hired only when the post stayed unfilled 30 days after it was declared (AD 87/005 art. 4). */
    static final int FOREIGN_WAIT_DAYS = 30;
    /** Candidate files are kept this long after their last vacancy closes, unless they agreed to be kept. */
    static final int KEEP_MONTHS = 12;
    static final int MAX_CV = 5 * 1024 * 1024;
    static final Set<String> CV_TYPES = Set.of("application/pdf", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    public record Request(long id, long unitId, String unitName, long occupationId, String occupationName, int posts,
                          String reason, Long replacesUserId, String replacesName, String contractType, LocalDate wantedOn,
                          Long gradeId, String gradeCode, String note, String postWarning, String status, long requestedBy,
                          String requestedByName, OffsetDateTime requestedAt, String decidedBy, OffsetDateTime decidedAt,
                          String decisionComment, Long vacancyId) {}

    public record VacancyRow(long id, Long requestId, long unitId, String unitName, long occupationId, String occupationName,
                             int posts, String title, String contractType, Long gradeId, String gradeCode, String place,
                             LocalDate closesOn, String visibility, int internalDays, String status, LocalDate onemDeclaredOn,
                             LocalDate openedOn, LocalDate closedOn, int applications, int active, int interviews,
                             int offers, int hired) {}

    public record Vacancy(VacancyRow row, String adText, List<AdCheck.Flag> flags, List<ApplicationRow> applications,
                          List<String> skills, boolean onemReminder) {}

    public record ApplicationRow(long id, long vacancyId, long candidateId, String name, String email, String phone,
                                 String source, String stage, OffsetDateTime stageAt, String nationalityCode,
                                 boolean hasCv, Double score, int scorecards, String offerAnswer, Long hiredUserId,
                                 Long userId) {}

    public record Candidate(long id, String firstName, String middleName, String lastName, String email, String phone,
                            LocalDate birthDate, String gender, String nationalityCode, String city, String currentJob,
                            String cvName, boolean keepForFuture, OffsetDateTime consentAt, LocalDate deleteAfter,
                            Long userId, String note) {}

    public record Event(OffsetDateTime at, String byName, String stage, String note) {}

    public record SkillScore(String skill, int score) {}

    public record Scorecard(long id, long userId, String name, List<SkillScore> scores, int overall, String recommendation,
                            String comment, OffsetDateTime savedAt) {}

    public record Interview(long id, long applicationId, OffsetDateTime startsAt, String place, String note,
                            List<Panelist> panel, List<Scorecard> scorecards) {}

    public record Panelist(long userId, String name) {}

    public record Offer(Long gradeId, String gradeCode, BigDecimal net, LocalDate start, LocalDate end, Integer trialMonths,
                        String body, LocalDate sentOn, String answer, LocalDate answerOn, LocalDate minorWaiverOn,
                        LocalDate earliestHire) {}

    public record Warning(String level, String code, String message) {}

    public record Application(ApplicationRow row, Candidate candidate, String vacancyTitle, String vacancyStatus,
                              String coverLetter, String rejectReason, boolean noRefusalEmail, List<Event> events,
                              List<Interview> interviews, Offer offer, List<Warning> warnings, List<String> skills,
                              boolean canHire) {}

    public record MyInterview(long id, long applicationId, String candidate, String vacancyTitle, OffsetDateTime startsAt,
                              String place, boolean scored) {}

    public record Overview(int openVacancies, int pendingRequests, int activeApplications, Map<String, Integer> sources,
                           Double averageDaysToHire, int hiredThisYear, ForeignShare foreignShare) {}

    public record ForeignShare(int foreign, int staff) {}

    public record CandidateInput(String firstName, String middleName, String lastName, String email, String phone,
                                 LocalDate birthDate, String gender, String nationalityCode, String city, String currentJob,
                                 String cvName, String cvType, byte[] cv, boolean keepForFuture, String note) {}

    public record VacancyInput(long unitId, long occupationId, int posts, String title, String adText, String contractType,
                               Long gradeId, String place, LocalDate closesOn, String visibility, int internalDays) {}

    public record OfferInput(long gradeId, BigDecimal net, LocalDate start, LocalDate end, Integer trialMonths, String body) {}

    public record HireInput(String email, String phone, String registrationNo, Long siteId, LocalDate minorWaiverOn) {}

    private static final ZoneId ZONE = ZoneId.of("Africa/Kinshasa");

    private final JdbcClient jdbc;
    private final OrgStructure org;
    private final Positions positions;
    private final Notifications notifications;
    private final NotificationService mail;
    private final AuditService audit;
    private final Company company;
    private final MasterData masterData;
    private final UserService users;
    private final NegotiatedPay pay;
    private final Checklists checklists;
    private final JsonMapper json;
    private final Clock clock;

    public Recruitment(JdbcClient jdbc, OrgStructure org, Positions positions, Notifications notifications,
                       NotificationService mail, AuditService audit, Company company, MasterData masterData,
                       UserService users, NegotiatedPay pay, Checklists checklists, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.org = org;
        this.positions = positions;
        this.notifications = notifications;
        this.mail = mail;
        this.audit = audit;
        this.company = company;
        this.masterData = masterData;
        this.users = users;
        this.pay = pay;
        this.checklists = checklists;
        this.json = json;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    // ---- who may do what -------------------------------------------------------------------------------------

    static boolean isHr() {
        return CurrentUser.has("recruitment.manage");
    }

    /** The units the current user runs: the ones they head and every unit below. */
    Set<Long> unitsIRun() {
        Set<Long> out = new HashSet<>();
        for (OrgStructure.Head h : org.headedBy(CurrentUser.id())) {
            out.addAll(org.branch(h.unitId()));
        }
        return out;
    }

    private void requireHr() {
        if (!isHr()) {
            throw new AccessDeniedException("Only HR can do this.");
        }
    }

    private void requireSeeUnit(long unitId) {
        if (!isHr() && !unitsIRun().contains(unitId)) {
            throw new AccessDeniedException("You do not run this unit.");
        }
    }

    private boolean onPanel(long applicationId) {
        return jdbc.sql("""
                select exists (select 1 from interview_panel p join interview i on i.id = p.interview_id
                               where i.application_id = ? and p.user_id = ?)""")
                .params(applicationId, CurrentUser.id()).query(Boolean.class).single();
    }

    private static String me() {
        return CurrentUser.email();
    }

    private String myName() {
        return jdbc.sql("select person_name(first_name, middle_name, last_name) from app_user where id = ?")
                .param(CurrentUser.id()).query(String.class).optional().orElse(me());
    }

    // ---- requests to hire --------------------------------------------------------------------------------------

    private static final String REQUEST_SELECT = """
            select r.id, r.unit_id, u.name as unit_name, r.occupation_id, o.name as occupation_name, r.posts, r.reason,
                   r.replaces_user_id, person_name(ru.first_name, ru.middle_name, ru.last_name) as replaces_name,
                   r.contract_type, r.wanted_on, r.grade_id, g.code as grade_code, r.note, r.post_warning, r.status,
                   r.requested_by, person_name(q.first_name, q.middle_name, q.last_name) as requested_by_name,
                   r.requested_at, r.decided_by, r.decided_at, r.decision_comment,
                   (select v.id from vacancy v where v.request_id = r.id order by v.id limit 1) as vacancy_id
            from hiring_request r join org_unit u on u.id = r.unit_id join occupation o on o.id = r.occupation_id
            left join app_user ru on ru.id = r.replaces_user_id left join grade g on g.id = r.grade_id
            join app_user q on q.id = r.requested_by
            """;

    public List<Request> requests() {
        List<Request> all = jdbc.sql(REQUEST_SELECT + " order by r.status = 'PENDING' desc, r.requested_at desc limit 500")
                .query(Request.class).list();
        if (isHr()) {
            return all;
        }
        Set<Long> mine = unitsIRun();
        long me = CurrentUser.id();
        return all.stream().filter(r -> r.requestedBy() == me || mine.contains(r.unitId())).toList();
    }

    public Request request(long id) {
        return jdbc.sql(REQUEST_SELECT + " where r.id = ?").param(id).query(Request.class).optional()
                .orElseThrow(() -> new NotFoundException("Request not found."));
    }

    @Transactional
    public Request ask(long unitId, long occupationId, int posts, String reason, Long replacesUserId, String contractType,
                       LocalDate wantedOn, Long gradeId, String note) {
        requireSeeUnit(unitId);
        org.requireOpenUnit(unitId);
        if (!REASONS.contains(reason)) {
            throw new BusinessRuleException("Choose why the post is needed.");
        }
        if (!CONTRACT_TYPES.contains(contractType)) {
            throw new BusinessRuleException("Unknown contract type.");
        }
        requireJob(occupationId);
        if ("REPLACEMENT".equals(reason) && replacesUserId == null) {
            throw new BusinessRuleException("Choose the person being replaced.");
        }
        String warning = "REPLACEMENT".equals(reason) ? null
                : positions.check(null, unitId, occupationId, wantedOn == null ? today() : wantedOn).orElse(null);
        long id = jdbc.sql("""
                insert into hiring_request (unit_id, occupation_id, posts, reason, replaces_user_id, contract_type, wanted_on,
                                            grade_id, note, post_warning, requested_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(unitId, occupationId, posts, reason, "REPLACEMENT".equals(reason) ? replacesUserId : null, contractType,
                        wantedOn, gradeId, blank(note), warning, CurrentUser.id())
                .query(Long.class).single();
        audit.record("recruitment.requested", "hiring_request", id, Map.of("unit", unitId, "job", occupationId, "posts", posts));
        Request r = request(id);
        String who = r.requestedByName();
        notifications.sendToHolders("recruitment.manage", "recruitment.request",
                () -> Texts.t("Request to hire: %s", r.occupationName()),
                () -> Texts.t("%s asks to hire %d × %s in %s.", who, r.posts(), r.occupationName(), r.unitName()),
                "/recruitment?tab=requests");
        return r;
    }

    /** HR approves (which opens a draft vacancy with the ad written from the job description) or refuses. */
    @Transactional
    public Request decide(long id, boolean approve, String comment) {
        requireHr();
        Request r = request(id);
        if (!"PENDING".equals(r.status())) {
            throw new ConflictException("This request was already decided.");
        }
        if (!approve && (comment == null || comment.isBlank())) {
            throw new BusinessRuleException("Say why the request is refused.");
        }
        jdbc.sql("update hiring_request set status = ?, decided_by = ?, decided_at = now(), decision_comment = ? where id = ?")
                .params(approve ? "APPROVED" : "REFUSED", me(), blank(comment), id).update();
        if (approve) {
            Job job = job(r.occupationId());
            String place = null;
            jdbc.sql("""
                    insert into vacancy (request_id, unit_id, occupation_id, posts, title, ad_text, contract_type, grade_id, place,
                                         created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, r.unitId(), r.occupationId(), r.posts(), job.name(), adFrom(job, r.unitName(), r.contractType()),
                            r.contractType(), r.gradeId(), place, me())
                    .update();
        }
        audit.record(approve ? "recruitment.approved" : "recruitment.refused", "hiring_request", id, Map.of());
        Request done = request(id);
        notifications.send(r.requestedBy(), "recruitment.decision",
                () -> approve ? Texts.t("Request to hire approved: %s", r.occupationName())
                        : Texts.t("Request to hire refused: %s", r.occupationName()),
                () -> comment == null || comment.isBlank() ? Texts.t("%s in %s.", r.occupationName(), r.unitName()) : comment,
                "/recruitment?tab=requests");
        return done;
    }

    @Transactional
    public Request cancelRequest(long id) {
        Request r = request(id);
        if (!isHr() && r.requestedBy() != CurrentUser.id()) {
            throw new AccessDeniedException("Only the person who asked, or HR, can cancel this request.");
        }
        if (!"PENDING".equals(r.status())) {
            throw new ConflictException("Only a request waiting for a decision can be cancelled.");
        }
        jdbc.sql("update hiring_request set status = 'CANCELLED', decided_by = ?, decided_at = now() where id = ?")
                .params(me(), id).update();
        return request(id);
    }

    // ---- jobs --------------------------------------------------------------------------------------------------

    record Job(long id, String name, String purpose, String duties, String education, String experience, String skills,
               String conditions, boolean unskilled) {}

    private void requireJob(long occupationId) {
        if (jdbc.sql("select count(*) from occupation where id = ?").param(occupationId).query(Integer.class).single() == 0) {
            throw new NotFoundException("Job not found.");
        }
    }

    Job job(long occupationId) {
        return jdbc.sql("""
                select id, name, purpose, duties, education, experience, skills, conditions, unskilled from occupation where id = ?""")
                .param(occupationId).query(Job.class).optional().orElseThrow(() -> new NotFoundException("Job not found."));
    }

    /** The skills a panel scores: the job description's, one per line or separated by ";" or ","; three general ones without. */
    static List<String> skills(String text) {
        List<String> out = new ArrayList<>();
        if (text != null) {
            for (String s : text.split("[\\n;,•]")) {
                String clean = s.replaceFirst("^[\\s\\-*·]+", "").strip();
                if (!clean.isEmpty() && clean.length() <= 120 && out.size() < 12) {
                    out.add(clean);
                }
            }
        }
        if (out.isEmpty()) {
            out.addAll(List.of(Texts.t("Knowledge of the job"), Texts.t("Communication"), Texts.t("Motivation")));
        }
        return out;
    }

    private String adFrom(Job j, String unit, String contractType) {
        boolean fr = "fr".equals(company.language());
        String type = contractLabel(contractType, fr);
        StringBuilder b = new StringBuilder();
        String name = company.get().name();
        b.append(fr ? "%s recrute un(e) %s (%s). Unité : %s.".formatted(name, j.name(), type, unit)
                : "%s is hiring a %s (%s). Unit: %s.".formatted(name, j.name(), type, unit));
        section(b, fr ? "Mission" : "Purpose", j.purpose());
        section(b, fr ? "Tâches principales" : "Main duties", j.duties());
        section(b, fr ? "Formation" : "Education", j.education());
        section(b, fr ? "Expérience" : "Experience", j.experience());
        section(b, fr ? "Compétences" : "Skills", j.skills());
        section(b, fr ? "Conditions de travail" : "Working conditions", j.conditions());
        b.append("\n\n").append(fr
                ? "Toutes les candidatures sont étudiées sans distinction de sexe, d'âge, d'état civil, de religion, d'origine ou de handicap. Aucun frais n'est demandé aux candidats."
                : "Every application is considered regardless of sex, age, marital status, religion, origin or disability. Candidates are never asked to pay anything.");
        return b.toString();
    }

    private static void section(StringBuilder b, String title, String text) {
        if (text != null && !text.isBlank()) {
            b.append("\n\n## ").append(title).append('\n').append(text.strip());
        }
    }

    static String contractLabel(String type, boolean fr) {
        return switch (type) {
            case "PERMANENT" -> fr ? "CDI" : "permanent contract";
            case "FIXED_TERM" -> fr ? "CDD" : "fixed-term contract";
            case "INTERN" -> fr ? "stage" : "internship";
            case "CONSULTANT" -> fr ? "consultance" : "consultancy";
            case "DAILY" -> fr ? "journalier" : "day work";
            default -> type;
        };
    }

    // ---- vacancies ---------------------------------------------------------------------------------------------

    private static final String VACANCY_SELECT = """
            select v.id, v.request_id, v.unit_id, u.name as unit_name, v.occupation_id, o.name as occupation_name, v.posts,
                   v.title, v.contract_type, v.grade_id, g.code as grade_code, v.place, v.closes_on, v.visibility,
                   v.internal_days, v.status, v.onem_declared_on, v.opened_on, v.closed_on,
                   (select count(*) from application a where a.vacancy_id = v.id) as applications,
                   (select count(*) from application a where a.vacancy_id = v.id
                      and a.stage in ('APPLIED', 'SCREENED', 'INTERVIEW', 'OFFER', 'ON_HOLD')) as active,
                   (select count(*) from application a where a.vacancy_id = v.id and a.stage = 'INTERVIEW') as interviews,
                   (select count(*) from application a where a.vacancy_id = v.id and a.stage = 'OFFER') as offers,
                   (select count(*) from application a where a.vacancy_id = v.id and a.stage = 'HIRED') as hired
            from vacancy v join org_unit u on u.id = v.unit_id join occupation o on o.id = v.occupation_id
            left join grade g on g.id = v.grade_id
            """;

    public List<VacancyRow> vacancies(boolean closedToo) {
        List<VacancyRow> all = jdbc.sql(VACANCY_SELECT
                        + (closedToo ? "" : " where v.status in ('DRAFT', 'OPEN', 'ON_HOLD')")
                        + " order by v.status = 'OPEN' desc, v.created_at desc limit 500")
                .query(VacancyRow.class).list();
        if (isHr()) {
            return all;
        }
        Set<Long> mine = unitsIRun();
        return all.stream().filter(v -> mine.contains(v.unitId())).toList();
    }

    VacancyRow row(long id) {
        return jdbc.sql(VACANCY_SELECT + " where v.id = ?").param(id).query(VacancyRow.class).optional()
                .orElseThrow(() -> new NotFoundException("Vacancy not found."));
    }

    public Vacancy vacancy(long id) {
        VacancyRow v = row(id);
        requireSeeUnit(v.unitId());
        String ad = jdbc.sql("select ad_text from vacancy where id = ?").param(id).query(String.class).single();
        Job j = job(v.occupationId());
        boolean remind = "OPEN".equals(v.status()) && v.onemDeclaredOn() == null;
        return new Vacancy(v, ad, AdCheck.check(v.title(), ad), applications(id), skills(j.skills()), remind);
    }

    @Transactional
    public Vacancy saveVacancy(Long id, VacancyInput in) {
        requireHr();
        org.requireOpenUnit(in.unitId());
        requireJob(in.occupationId());
        if (!CONTRACT_TYPES.contains(in.contractType())) {
            throw new BusinessRuleException("Unknown contract type.");
        }
        if (!VISIBILITY.contains(in.visibility())) {
            throw new BusinessRuleException("Choose who sees the offer.");
        }
        int days = "INTERNAL_FIRST".equals(in.visibility()) ? Math.max(1, in.internalDays()) : 0;
        if (id == null) {
            id = jdbc.sql("""
                    insert into vacancy (unit_id, occupation_id, posts, title, ad_text, contract_type, grade_id, place, closes_on,
                                         visibility, internal_days, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(in.unitId(), in.occupationId(), in.posts(), in.title().strip(), in.adText().strip(),
                            in.contractType(), in.gradeId(), blank(in.place()), in.closesOn(), in.visibility(), days, me())
                    .query(Long.class).single();
            audit.record("vacancy.created", "vacancy", id, Map.of("title", in.title()));
        } else {
            VacancyRow v = row(id);
            if (Set.of("FILLED", "CLOSED", "CANCELLED").contains(v.status())) {
                throw new ConflictException("This vacancy is closed.");
            }
            jdbc.sql("""
                    update vacancy set unit_id = ?, occupation_id = ?, posts = ?, title = ?, ad_text = ?, contract_type = ?,
                           grade_id = ?, place = ?, closes_on = ?, visibility = ?, internal_days = ? where id = ?""")
                    .params(in.unitId(), in.occupationId(), in.posts(), in.title().strip(), in.adText().strip(),
                            in.contractType(), in.gradeId(), blank(in.place()), in.closesOn(), in.visibility(), days, id)
                    .update();
        }
        return vacancy(id);
    }

    /** Opens (publishes) the vacancy, puts it on hold, closes or cancels it. Closing tells the remaining candidates. */
    @Transactional
    public Vacancy setStatus(long id, String status) {
        requireHr();
        VacancyRow v = row(id);
        switch (status) {
            case "OPEN" -> {
                if (!Set.of("DRAFT", "ON_HOLD").contains(v.status())) {
                    throw new ConflictException("Only a draft or a vacancy on hold can be opened.");
                }
                jdbc.sql("update vacancy set status = 'OPEN', opened_on = coalesce(opened_on, ?) where id = ?").params(today(), id).update();
            }
            case "ON_HOLD" -> {
                if (!"OPEN".equals(v.status())) {
                    throw new ConflictException("Only an open vacancy can be put on hold.");
                }
                jdbc.sql("update vacancy set status = 'ON_HOLD' where id = ?").param(id).update();
            }
            case "CLOSED", "CANCELLED" -> close(id, status);
            default -> throw new BusinessRuleException("Unknown vacancy status.");
        }
        audit.record("vacancy." + status.toLowerCase(), "vacancy", id, Map.of());
        return vacancy(id);
    }

    @Transactional
    public Vacancy declaredToOnem(long id, LocalDate on) {
        requireHr();
        row(id);
        if (on != null && on.isAfter(today())) {
            throw new BusinessRuleException("The date cannot be in the future.");
        }
        jdbc.sql("update vacancy set onem_declared_on = ? where id = ?").params(on, id).update();
        return vacancy(id);
    }

    private void close(long id, String status) {
        VacancyRow v = row(id);
        if (Set.of("FILLED", "CLOSED", "CANCELLED").contains(v.status())) {
            throw new ConflictException("This vacancy is already closed.");
        }
        jdbc.sql("update vacancy set status = ?, closed_on = ? where id = ?").params(status, today(), id).update();
        for (ApplicationRow a : applications(id)) {
            if (ACTIVE.contains(a.stage())) {
                moveTo(a.id(), "REJECTED", Texts.t("The vacancy is closed."));
                jdbc.sql("update application set reject_reason = coalesce(reject_reason, ?) where id = ?")
                        .params(Texts.t("The vacancy is closed."), a.id()).update();
                sendRefusal(a.id());
            }
        }
        // Each candidate's file is kept 12 months after their last vacancy closes.
        jdbc.sql("""
                update candidate c set delete_after = ? where c.id in (select candidate_id from application where vacancy_id = ?)
                  and not exists (select 1 from application a join vacancy w on w.id = a.vacancy_id
                                  where a.candidate_id = c.id and w.status in ('DRAFT', 'OPEN', 'ON_HOLD'))""")
                .params(today().plusMonths(KEEP_MONTHS), id).update();
    }

    // ---- candidates and applications ---------------------------------------------------------------------------

    private static final String APPLICATION_SELECT = """
            select a.id, a.vacancy_id, a.candidate_id, person_name(c.first_name, c.middle_name, c.last_name) as name, c.email,
                   c.phone, a.source, a.stage, a.stage_at, c.nationality_code, c.cv is not null as has_cv,
                   (select round(avg(s.overall)::numeric, 1)::float8 from scorecard s join interview i on i.id = s.interview_id
                    where i.application_id = a.id) as score,
                   (select count(*) from scorecard s join interview i on i.id = s.interview_id where i.application_id = a.id) as scorecards,
                   a.offer_answer, a.hired_user_id, c.user_id
            from application a join candidate c on c.id = a.candidate_id
            """;

    List<ApplicationRow> applications(long vacancyId) {
        return jdbc.sql(APPLICATION_SELECT + " where a.vacancy_id = ? order by a.stage in ('REJECTED', 'WITHDRAWN'), a.created_at")
                .param(vacancyId).query(ApplicationRow.class).list();
    }

    ApplicationRow appRow(long id) {
        return jdbc.sql(APPLICATION_SELECT + " where a.id = ?").param(id).query(ApplicationRow.class).optional()
                .orElseThrow(() -> new NotFoundException("Application not found."));
    }

    Candidate candidate(long id) {
        return jdbc.sql("""
                select id, first_name, middle_name, last_name, email, phone, birth_date, gender, nationality_code, city,
                       current_job, cv_name, keep_for_future, consent_at, delete_after, user_id, note
                from candidate where id = ?""").param(id).query(Candidate.class).optional()
                .orElseThrow(() -> new NotFoundException("Candidate not found."));
    }

    /** The same person applying again is found by email, then by phone, and their details are brought up to date. */
    @Transactional
    public long saveCandidate(CandidateInput in, boolean consent) {
        checkCv(in.cv(), in.cvType());
        String email = blank(in.email()) == null ? null : in.email().strip().toLowerCase();
        String phone = blank(in.phone()) == null ? null : in.phone().replaceAll("\\s", "");
        if (email == null && phone == null) {
            throw new BusinessRuleException("Give an email or a phone number.");
        }
        Long id = email == null ? null : jdbc.sql("select id from candidate where lower(email) = ? order by id limit 1")
                .param(email).query(Long.class).optional().orElse(null);
        if (id == null && phone != null) {
            id = jdbc.sql("select id from candidate where replace(phone, ' ', '') = ? order by id limit 1").param(phone)
                    .query(Long.class).optional().orElse(null);
        }
        if (id == null) {
            id = jdbc.sql("""
                    insert into candidate (first_name, middle_name, last_name, email, phone, birth_date, gender, nationality_code, city,
                                           current_job, cv, cv_name, cv_type, keep_for_future, consent_at, note)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(in.firstName().strip(), blank(in.middleName()), in.lastName().strip(), email, phone, in.birthDate(),
                            in.gender(), upper(in.nationalityCode()), blank(in.city()), blank(in.currentJob()), in.cv(),
                            in.cv() == null ? null : in.cvName(), in.cv() == null ? null : in.cvType(), in.keepForFuture(),
                            consent ? OffsetDateTime.now(clock) : null, blank(in.note()))
                    .query(Long.class).single();
        } else {
            jdbc.sql("""
                    update candidate set first_name = ?, middle_name = ?, last_name = ?, email = coalesce(?, email),
                           phone = coalesce(?, phone), birth_date = coalesce(?, birth_date), gender = coalesce(?, gender),
                           nationality_code = coalesce(?, nationality_code), city = coalesce(?, city),
                           current_job = coalesce(?, current_job), keep_for_future = keep_for_future or ?,
                           consent_at = coalesce(?, consent_at), note = coalesce(?, note), delete_after = null where id = ?""")
                    .params(in.firstName().strip(), blank(in.middleName()), in.lastName().strip(), email, phone, in.birthDate(),
                            in.gender(), upper(in.nationalityCode()), blank(in.city()), blank(in.currentJob()), in.keepForFuture(),
                            consent ? OffsetDateTime.now(clock) : null, blank(in.note()), id)
                    .update();
            if (in.cv() != null) {
                jdbc.sql("update candidate set cv = ?, cv_name = ?, cv_type = ? where id = ?")
                        .params(in.cv(), in.cvName(), in.cvType(), id).update();
            }
        }
        return id;
    }

    static void checkCv(byte[] cv, String type) {
        if (cv == null) {
            return;
        }
        if (cv.length > MAX_CV) {
            throw new BusinessRuleException("The CV is larger than 5 MB.");
        }
        if (type == null || !CV_TYPES.contains(type)) {
            throw new BusinessRuleException("Send the CV as a PDF or Word file.");
        }
        boolean pdf = cv.length > 4 && cv[0] == '%' && cv[1] == 'P' && cv[2] == 'D' && cv[3] == 'F';
        boolean zip = cv.length > 4 && cv[0] == 'P' && cv[1] == 'K';
        boolean doc = cv.length > 4 && (cv[0] & 0xff) == 0xD0 && (cv[1] & 0xff) == 0xCF;
        if (!(pdf || zip || doc)) {
            throw new BusinessRuleException("Send the CV as a PDF or Word file.");
        }
    }

    /** HR adds a candidate to a vacancy by hand (a CV received by email, a referral, an agency). */
    @Transactional
    public Application addCandidate(long vacancyId, CandidateInput in, String source) {
        requireHr();
        VacancyRow v = row(vacancyId);
        if (Set.of("FILLED", "CLOSED", "CANCELLED").contains(v.status())) {
            throw new ConflictException("This vacancy is closed.");
        }
        long candidateId = saveCandidate(in, false);
        return apply(vacancyId, candidateId, source != null && SOURCES.contains(source) ? source : "OTHER", null);
    }

    @Transactional
    public Application apply(long vacancyId, long candidateId, String source, String coverLetter) {
        if (jdbc.sql("select count(*) from application where vacancy_id = ? and candidate_id = ?").params(vacancyId, candidateId)
                .query(Integer.class).single() > 0) {
            throw new ConflictException("This person has already applied to this vacancy.");
        }
        long id = jdbc.sql("""
                insert into application (vacancy_id, candidate_id, source, cover_letter) values (?, ?, ?, ?) returning id""")
                .params(vacancyId, candidateId, source, blank(coverLetter)).query(Long.class).single();
        event(id, "APPLIED", null);
        return application(id);
    }

    private void event(long applicationId, String stage, String note) {
        String by = CurrentUser.id() == null ? null : myName();
        jdbc.sql("insert into application_event (application_id, by_name, stage, note) values (?, ?, ?, ?)")
                .params(applicationId, by, stage, blank(note)).update();
    }

    public Application application(long id) {
        ApplicationRow a = appRow(id);
        VacancyRow v = row(a.vacancyId());
        if (!isHr() && !unitsIRun().contains(v.unitId()) && !onPanel(id)) {
            throw new AccessDeniedException("You cannot see this application.");
        }
        record Extra(String coverLetter, String rejectReason, boolean noRefusalEmail, Long offerGradeId, String gradeCode,
                     BigDecimal offerNet, LocalDate offerStart, LocalDate offerEnd, Integer offerTrialMonths, String offerBody,
                     LocalDate offerSentOn, String offerAnswer, LocalDate offerAnswerOn, LocalDate minorWaiverOn) {}
        Extra x = jdbc.sql("""
                select a.cover_letter, a.reject_reason, a.no_refusal_email, a.offer_grade_id, g.code as grade_code, a.offer_net,
                       a.offer_start, a.offer_end, a.offer_trial_months, a.offer_body, a.offer_sent_on, a.offer_answer,
                       a.offer_answer_on, a.minor_waiver_on
                from application a left join grade g on g.id = a.offer_grade_id where a.id = ?""")
                .param(id).query(Extra.class).single();
        List<Event> events = jdbc.sql("select at, by_name, stage, note from application_event where application_id = ? order by at, id")
                .param(id).query(Event.class).list();
        Offer offer = x.offerStart() == null ? null : new Offer(x.offerGradeId(), x.gradeCode(), x.offerNet(), x.offerStart(),
                x.offerEnd(), x.offerTrialMonths(), x.offerBody(), x.offerSentOn(), x.offerAnswer(), x.offerAnswerOn(),
                x.minorWaiverOn(), x.offerSentOn() == null ? null : earliestSigning(x.offerSentOn()));
        Candidate c = candidate(a.candidateId());
        List<Warning> warnings = warnings(a, v, c, x.offerStart(), x.minorWaiverOn());
        boolean canHire = isHr() && "OFFER".equals(a.stage()) && "ACCEPTED".equals(x.offerAnswer())
                && warnings.stream().noneMatch(w -> "BLOCK".equals(w.level()));
        return new Application(a, c, v.title(), v.status(), x.coverLetter(), x.rejectReason(), x.noRefusalEmail(), events,
                interviews(id), offer, warnings, skills(job(v.occupationId()).skills()), canHire);
    }

    /** The day the person may sign the contract: two working days after the draft reached them (Code art. 46). */
    static LocalDate earliestSigning(LocalDate draftGiven) {
        LocalDate d = draftGiven;
        int left = 2;
        while (left > 0) {
            d = d.plusDays(1);
            if (d.getDayOfWeek().getValue() <= 5) {
                left--;
            }
        }
        return d;
    }

    /** What the law asks before this person can be hired: age (art. 6, 133), foreign workers (AD 87/005). */
    List<Warning> warnings(ApplicationRow a, VacancyRow v, Candidate c, LocalDate start, LocalDate waiver) {
        List<Warning> out = new ArrayList<>();
        LocalDate on = start == null ? today() : start;
        if (c.birthDate() == null) {
            out.add(new Warning("WARN", "NO_BIRTH_DATE", Texts.t("The birth date is missing: the age cannot be checked.")));
        } else {
            int age = Period.between(c.birthDate(), on).getYears();
            if (age < 15) {
                out.add(new Warning("BLOCK", "UNDER_15", Texts.t("This person is %d: nobody under 15 can be hired (Labour Code art. 133).", age)));
            } else if (age < 18 && waiver == null) {
                out.add(new Warning("BLOCK", "MINOR", Texts.t("This person is %d: hiring at 15 to 17 needs the waiver of the Président du Tribunal de paix, and only for light work (art. 6). Enter the date of the waiver.", age)));
            } else if (age < 18) {
                out.add(new Warning("WARN", "MINOR", Texts.t("This person is %d: light and healthy work only (art. 6).", age)));
            }
        }
        String country = company.country();
        if (c.nationalityCode() != null && !c.nationalityCode().equalsIgnoreCase(country) && "CD".equals(country)) {
            LocalDate from = v.onemDeclaredOn() != null ? v.onemDeclaredOn() : v.openedOn();
            long days = from == null ? 0 : ChronoUnit.DAYS.between(from, today());
            if (days < FOREIGN_WAIT_DAYS) {
                out.add(new Warning("WARN", "FOREIGN_WAIT", Texts.t("A foreign worker is hired only when the post stayed unfilled %d days after it was declared (AD 87/005 art. 4): %d days so far.", FOREIGN_WAIT_DAYS, days)));
            }
            out.add(new Warning("WARN", "WORK_PERMIT", Texts.t("A foreign worker needs a work card (carte de travail pour étrangers) from the Commission nationale de l'emploi des étrangers before starting.")));
        }
        if ("CD".equals(country) && v.onemDeclaredOn() == null) {
            out.add(new Warning("WARN", "ONEM", Texts.t("The vacancy has no date of declaration to ONEM.")));
        }
        return out;
    }

    public byte[] cv(long candidateId) {
        record Cv(byte[] cv, String cvName, String cvType) {}
        Cv cv = jdbc.sql("select cv, cv_name, cv_type from candidate where id = ?").param(candidateId).query(Cv.class).optional()
                .orElseThrow(() -> new NotFoundException("Candidate not found."));
        boolean allowed = isHr() || jdbc.sql("""
                select a.id from application a where a.candidate_id = ?""").param(candidateId).query(Long.class).list()
                .stream().anyMatch(this::onPanel);
        if (!allowed) {
            throw new AccessDeniedException("You cannot see this CV.");
        }
        if (cv.cv() == null) {
            throw new NotFoundException("No CV was given.");
        }
        return cv.cv();
    }

    public String cvName(long candidateId) {
        return jdbc.sql("select coalesce(cv_name, 'cv') from candidate where id = ?").param(candidateId).query(String.class).single();
    }

    public String cvType(long candidateId) {
        return jdbc.sql("select coalesce(cv_type, 'application/octet-stream') from candidate where id = ?").param(candidateId)
                .query(String.class).single();
    }

    @Transactional
    public Application updateCandidate(long applicationId, CandidateInput in) {
        requireHr();
        ApplicationRow a = appRow(applicationId);
        checkCv(in.cv(), in.cvType());
        jdbc.sql("""
                update candidate set first_name = ?, middle_name = ?, last_name = ?, email = ?, phone = ?, birth_date = ?, gender = ?,
                       nationality_code = ?, city = ?, current_job = ?, keep_for_future = ?, note = ? where id = ?""")
                .params(in.firstName().strip(), blank(in.middleName()), in.lastName().strip(),
                        blank(in.email()) == null ? null : in.email().strip().toLowerCase(), blank(in.phone()), in.birthDate(),
                        in.gender(), upper(in.nationalityCode()), blank(in.city()), blank(in.currentJob()), in.keepForFuture(),
                        blank(in.note()), a.candidateId())
                .update();
        if (in.cv() != null) {
            jdbc.sql("update candidate set cv = ?, cv_name = ?, cv_type = ? where id = ?")
                    .params(in.cv(), in.cvName(), in.cvType(), a.candidateId()).update();
        }
        return application(applicationId);
    }

    /** HR moves a candidate to another stage. A refusal is told to the candidate by email unless HR says not to. */
    @Transactional
    public Application move(long id, String stage, String note, String rejectReason, boolean tell) {
        requireHr();
        if (!MOVES.contains(stage)) {
            throw new BusinessRuleException("Unknown stage.");
        }
        ApplicationRow a = appRow(id);
        if ("HIRED".equals(a.stage())) {
            throw new ConflictException("This person is already hired.");
        }
        moveTo(id, stage, note);
        if ("REJECTED".equals(stage)) {
            jdbc.sql("update application set reject_reason = ?, no_refusal_email = ? where id = ?")
                    .params(blank(rejectReason), !tell, id).update();
            if (tell) {
                sendRefusal(id);
            }
        }
        return application(id);
    }

    private void moveTo(long id, String stage, String note) {
        jdbc.sql("update application set stage = ?, stage_at = now() where id = ?").params(stage, id).update();
        event(id, stage, note);
    }

    @Transactional
    public Application note(long id, String note) {
        requireHr();
        ApplicationRow a = appRow(id);
        event(id, a.stage(), note);
        return application(id);
    }

    /** A short, polite refusal in the company's language, once. */
    void sendRefusal(long applicationId) {
        record To(String email, String firstName, String title, boolean noRefusalEmail, OffsetDateTime refusalSentAt) {}
        To to = jdbc.sql("""
                select c.email, c.first_name, v.title, a.no_refusal_email, a.refusal_sent_at
                from application a join candidate c on c.id = a.candidate_id join vacancy v on v.id = a.vacancy_id where a.id = ?""")
                .param(applicationId).query(To.class).single();
        if (to.email() == null || to.noRefusalEmail() || to.refusalSentAt() != null) {
            return;
        }
        String name = company.get().name();
        Texts.run(company.language(), () -> mail.emailAddress(to.email(),
                Texts.t("Your application: %s", to.title()),
                Texts.t("Dear %s,\n\nThank you for your interest in the post of %s at %s. After careful review, we will not go further with your application this time.\n\nWe wish you every success in your search.\n\n%s", to.firstName(), to.title(), name, name)));
        jdbc.sql("update application set refusal_sent_at = now() where id = ?").param(applicationId).update();
    }

    // ---- interviews --------------------------------------------------------------------------------------------

    List<Interview> interviews(long applicationId) {
        record Row(long id, long applicationId, OffsetDateTime startsAt, String place, String note) {}
        List<Interview> out = new ArrayList<>();
        boolean hr = isHr();
        long me = CurrentUser.id();
        for (Row r : jdbc.sql("select id, application_id, starts_at, place, note from interview where application_id = ? order by starts_at")
                .param(applicationId).query(Row.class).list()) {
            List<Panelist> panel = jdbc.sql("""
                    select p.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name
                    from interview_panel p join app_user u on u.id = p.user_id where p.interview_id = ? order by name""")
                    .param(r.id()).query(Panelist.class).list();
            record S(long id, long userId, String name, String scores, int overall, String recommendation, String comment,
                     OffsetDateTime savedAt) {}
            List<Scorecard> cards = jdbc.sql("""
                    select s.id, s.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, s.scores::text as scores,
                           s.overall, s.recommendation, s.comment, s.saved_at
                    from scorecard s join app_user u on u.id = s.user_id where s.interview_id = ? order by s.saved_at""")
                    .param(r.id()).query(S.class).list().stream()
                    // A panel member sees the others' scores only once they gave their own, so nobody follows the crowd.
                    .map(s -> new Scorecard(s.id(), s.userId(), s.name(), Arrays.asList(json.readValue(s.scores(), SkillScore[].class)),
                            s.overall(), s.recommendation(), s.comment(), s.savedAt()))
                    .toList();
            boolean scored = cards.stream().anyMatch(s -> s.userId() == me);
            List<Scorecard> shown = hr || scored ? cards : List.of();
            out.add(new Interview(r.id(), r.applicationId(), r.startsAt(), r.place(), r.note(), panel, shown));
        }
        return out;
    }

    @Transactional
    public Application planInterview(long applicationId, OffsetDateTime startsAt, String place, String note, List<Long> panel) {
        requireHr();
        ApplicationRow a = appRow(applicationId);
        if (!ACTIVE.contains(a.stage())) {
            throw new ConflictException("This application is closed.");
        }
        if (panel == null || panel.isEmpty()) {
            throw new BusinessRuleException("Choose at least one panel member.");
        }
        long id = jdbc.sql("insert into interview (application_id, starts_at, place, note, created_by) values (?, ?, ?, ?, ?) returning id")
                .params(applicationId, startsAt, blank(place), blank(note), me()).query(Long.class).single();
        for (Long u : new java.util.LinkedHashSet<>(panel)) {
            jdbc.sql("insert into interview_panel (interview_id, user_id) values (?, ?)").params(id, u).update();
        }
        if (!"INTERVIEW".equals(a.stage()) && !"OFFER".equals(a.stage())) {
            moveTo(applicationId, "INTERVIEW", null);
        }
        String title = row(a.vacancyId()).title();
        String when = startsAt.atZoneSameInstant(ZONE).toLocalDateTime().toString().replace('T', ' ');
        for (Long u : new java.util.LinkedHashSet<>(panel)) {
            notifications.send(u, "recruitment.interview", () -> Texts.t("Interview: %s", title),
                    () -> Texts.t("%s, %s. Fill in your scorecard after the interview.", a.name(), when),
                    "/recruitment?application=" + applicationId);
        }
        return application(applicationId);
    }

    @Transactional
    public Application cancelInterview(long interviewId) {
        requireHr();
        Long app = jdbc.sql("select application_id from interview where id = ?").param(interviewId).query(Long.class).optional()
                .orElseThrow(() -> new NotFoundException("Interview not found."));
        jdbc.sql("delete from interview where id = ?").param(interviewId).update();
        return application(app);
    }

    /** A panel member's scorecard; HR can score too. Each skill and the overall view from 1 to 5. */
    @Transactional
    public Application score(long interviewId, List<SkillScore> scores, int overall, String recommendation, String comment) {
        Long app = jdbc.sql("select application_id from interview where id = ?").param(interviewId).query(Long.class).optional()
                .orElseThrow(() -> new NotFoundException("Interview not found."));
        boolean panelist = jdbc.sql("select count(*) from interview_panel where interview_id = ? and user_id = ?")
                .params(interviewId, CurrentUser.id()).query(Integer.class).single() > 0;
        if (!panelist && !isHr()) {
            throw new AccessDeniedException("Only the panel scores this interview.");
        }
        if (overall < 1 || overall > 5 || scores.stream().anyMatch(s -> s.score() < 1 || s.score() > 5)) {
            throw new BusinessRuleException("Scores go from 1 to 5.");
        }
        if (!Set.of("YES", "MAYBE", "NO").contains(recommendation)) {
            throw new BusinessRuleException("Say whether you recommend this person.");
        }
        jdbc.sql("""
                insert into scorecard (interview_id, user_id, scores, overall, recommendation, comment) values (?, ?, ?::jsonb, ?, ?, ?)
                on conflict (interview_id, user_id) do update set scores = excluded.scores, overall = excluded.overall,
                    recommendation = excluded.recommendation, comment = excluded.comment, saved_at = now()""")
                .params(interviewId, CurrentUser.id(), json.writeValueAsString(scores), overall, recommendation, blank(comment))
                .update();
        return application(app);
    }

    public List<MyInterview> myInterviews() {
        return jdbc.sql("""
                select i.id, i.application_id, person_name(c.first_name, c.middle_name, c.last_name) as candidate, v.title as vacancy_title,
                       i.starts_at, i.place,
                       exists (select 1 from scorecard s where s.interview_id = i.id and s.user_id = :me) as scored
                from interview_panel p join interview i on i.id = p.interview_id join application a on a.id = i.application_id
                join candidate c on c.id = a.candidate_id join vacancy v on v.id = a.vacancy_id
                where p.user_id = :me and i.starts_at > now() - interval '60 days' order by i.starts_at desc""")
                .param("me", CurrentUser.id()).query(MyInterview.class).list();
    }

    // ---- offer and hire ----------------------------------------------------------------------------------------

    @Transactional
    public Application saveOffer(long applicationId, OfferInput in) {
        requireHr();
        ApplicationRow a = appRow(applicationId);
        if (!ACTIVE.contains(a.stage())) {
            throw new ConflictException("This application is closed.");
        }
        VacancyRow v = row(a.vacancyId());
        Job j = job(v.occupationId());
        MasterData.Grade grade = masterData.grade(in.gradeId());
        if (in.start() == null) {
            throw new BusinessRuleException("Give the start date.");
        }
        if ("FIXED_TERM".equals(v.contractType()) && in.end() == null) {
            throw new BusinessRuleException("A fixed-term contract needs its end date.");
        }
        if (in.end() != null && !in.end().isAfter(in.start())) {
            throw new BusinessRuleException("The end date must come after the start date.");
        }
        int maxTrial = j.unskilled() ? 1 : 6;
        if (in.trialMonths() != null && in.trialMonths() > maxTrial) {
            throw new BusinessRuleException(Texts.t("The trial period is at most %d month(s) for this job (Labour Code art. 43).", maxTrial));
        }
        String body = blank(in.body()) != null ? in.body().strip() : offerText(a, v, grade, in);
        jdbc.sql("""
                update application set offer_grade_id = ?, offer_net = ?, offer_start = ?, offer_end = ?, offer_trial_months = ?,
                       offer_body = ?, offer_answer = null, offer_answer_on = null where id = ?""")
                .params(in.gradeId(), in.net(), in.start(), in.end(), in.trialMonths(), body, applicationId).update();
        if (!"OFFER".equals(a.stage())) {
            moveTo(applicationId, "OFFER", null);
        }
        return application(applicationId);
    }

    private String offerText(ApplicationRow a, VacancyRow v, MasterData.Grade grade, OfferInput in) {
        boolean fr = "fr".equals(company.language());
        String name = company.get().name();
        String start = Texts.in(company.language(), () -> Texts.day(in.start()));
        String end = in.end() == null ? null : Texts.in(company.language(), () -> Texts.day(in.end()));
        String net = in.net() == null ? null : in.net().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " " + grade.currency();
        String type = contractLabel(v.contractType(), fr);
        StringBuilder b = new StringBuilder();
        if (fr) {
            b.append("Madame, Monsieur %s,\n\n".formatted(a.name()));
            b.append("Suite à nos entretiens, nous avons le plaisir de vous proposer le poste de %s au sein de %s, %s.".formatted(v.title(), v.unitName(), name));
            b.append("\n\n## Conditions proposées\n");
            b.append("Contrat : %s%s\n".formatted(type, end == null ? "" : ", jusqu'au " + end));
            b.append("Date d'entrée en fonction : %s\n".formatted(start));
            b.append("Grade : %s\n".formatted(grade.code()));
            if (net != null) {
                b.append("Salaire net mensuel : %s\n".formatted(net));
            }
            if (in.trialMonths() != null && in.trialMonths() > 0) {
                b.append("Période d'essai : %d mois\n".formatted(in.trialMonths()));
            }
            b.append("\nCette offre est faite sous réserve de votre aptitude médicale (Code du travail, art. 38). Le projet de contrat vous sera remis au moins deux jours ouvrables avant sa signature (art. 46).");
            b.append("\n\nNous vous remercions de nous faire part de votre réponse.\n\nVeuillez agréer, Madame, Monsieur, l'expression de nos salutations distinguées.");
        } else {
            b.append("Dear %s,\n\n".formatted(a.name()));
            b.append("Following our interviews, we are pleased to offer you the post of %s in %s, %s.".formatted(v.title(), v.unitName(), name));
            b.append("\n\n## Terms offered\n");
            b.append("Contract: %s%s\n".formatted(type, end == null ? "" : ", until " + end));
            b.append("Start date: %s\n".formatted(start));
            b.append("Grade: %s\n".formatted(grade.code()));
            if (net != null) {
                b.append("Monthly net salary: %s\n".formatted(net));
            }
            if (in.trialMonths() != null && in.trialMonths() > 0) {
                b.append("Trial period: %d month(s)\n".formatted(in.trialMonths()));
            }
            b.append("\nThis offer depends on your medical fitness for the job (Labour Code art. 38). The draft contract will reach you at least two working days before you sign it (art. 46).");
            b.append("\n\nPlease let us know your answer.\n\nYours sincerely,");
        }
        return b.toString();
    }

    @Transactional
    public Application offerSent(long applicationId, LocalDate on) {
        requireHr();
        requireOffer(applicationId);
        jdbc.sql("update application set offer_sent_on = ? where id = ?").params(on == null ? today() : on, applicationId).update();
        event(applicationId, "OFFER", Texts.t("Offer sent"));
        return application(applicationId);
    }

    @Transactional
    public Application offerAnswer(long applicationId, String answer, LocalDate on) {
        requireHr();
        requireOffer(applicationId);
        if (!Set.of("ACCEPTED", "DECLINED").contains(answer)) {
            throw new BusinessRuleException("Say whether the offer was accepted or declined.");
        }
        jdbc.sql("update application set offer_answer = ?, offer_answer_on = ? where id = ?")
                .params(answer, on == null ? today() : on, applicationId).update();
        event(applicationId, "OFFER", "ACCEPTED".equals(answer) ? Texts.t("Offer accepted") : Texts.t("Offer declined"));
        return application(applicationId);
    }

    private void requireOffer(long applicationId) {
        ApplicationRow a = appRow(applicationId);
        if (!"OFFER".equals(a.stage())) {
            throw new ConflictException("Make the offer first.");
        }
    }

    public String offerBody(long applicationId) {
        requireHr();
        return jdbc.sql("select offer_body from application where id = ?").param(applicationId).query(String.class).optional()
                .filter(s -> !s.isBlank()).orElseThrow(() -> new NotFoundException("No offer yet."));
    }

    /**
     * Hires the person: creates their account in the vacancy's unit, their first contract with the offered grade and net,
     * and opens the hiring checklist. The hire declaration to ONEM then starts as for any hire.
     */
    @Transactional
    public long hire(long applicationId, HireInput in) {
        requireHr();
        if (in.minorWaiverOn() != null) {
            jdbc.sql("update application set minor_waiver_on = ? where id = ?").params(in.minorWaiverOn(), applicationId).update();
        }
        Application app = application(applicationId);
        ApplicationRow a = app.row();
        if (!"OFFER".equals(a.stage()) || app.offer() == null || !"ACCEPTED".equals(app.offer().answer())) {
            throw new ConflictException("The offer must be accepted before hiring.");
        }
        for (Warning w : app.warnings()) {
            if ("BLOCK".equals(w.level())) {
                throw new BusinessRuleException(w.message());
            }
        }
        if (a.userId() != null) {
            throw new ConflictException("This person already works here: move them with a promotion or a transfer from their Career tab.");
        }
        VacancyRow v = row(a.vacancyId());
        Candidate c = app.candidate();
        String email = blank(in.email()) != null ? in.email().strip() : c.email();
        String phone = blank(in.phone()) != null ? in.phone().strip() : c.phone();
        if (email == null || phone == null) {
            throw new BusinessRuleException("Give the email and the phone number of the new employee.");
        }
        AppUser user = users.create(new UserService.UserData(email, blank(in.registrationNo()), phone, c.firstName(), c.lastName(),
                in.siteId(), c.middleName()), List.of(), null);
        long userId = user.getId();
        org.assign(userId, v.unitId(), null, "HIRE", Texts.t("Hired for vacancy %s", v.title()));
        jdbc.sql("""
                insert into employee_profile (user_id, birth_date, gender, nationality_code) values (?, ?, ?, ?)
                on conflict (user_id) do update set birth_date = coalesce(excluded.birth_date, employee_profile.birth_date),
                    gender = coalesce(excluded.gender, employee_profile.gender),
                    nationality_code = coalesce(excluded.nationality_code, employee_profile.nationality_code)""")
                .params(userId, c.birthDate(), c.gender(), c.nationalityCode()).update();
        Offer o = app.offer();
        pay.startContract(userId, v.contractType(), o.start(), o.end(), o.gradeId(), v.occupationId(), false, null, o.net(),
                Texts.t("Hired for vacancy %s", v.title()), null);
        if (o.trialMonths() != null) {
            jdbc.sql("""
                    insert into contract_terms (contract_id, trial_months)
                    select id, ? from employee_contract where user_id = ? order by start_date desc limit 1
                    on conflict (contract_id) do update set trial_months = excluded.trial_months""")
                    .params(o.trialMonths(), userId).update();
        }
        try {
            checklists.start(userId, "HIRING", o.start());
        } catch (ConflictException ignored) {
            // already open
        }
        jdbc.sql("update application set hired_user_id = ? where id = ?").params(userId, applicationId).update();
        jdbc.sql("update candidate set user_id = ?, delete_after = null where id = ?").params(userId, c.id()).update();
        moveTo(applicationId, "HIRED", null);
        audit.record("recruitment.hired", "user", userId, Map.of("vacancy", v.id(), "application", applicationId));
        int hired = jdbc.sql("select count(*) from application where vacancy_id = ? and stage = 'HIRED'").param(v.id())
                .query(Integer.class).single();
        if (hired >= v.posts()) {
            close(v.id(), "FILLED");
        }
        return userId;
    }

    // ---- overview and housekeeping -----------------------------------------------------------------------------

    public Overview overview() {
        requireHr();
        int open = jdbc.sql("select count(*) from vacancy where status = 'OPEN'").query(Integer.class).single();
        int pending = jdbc.sql("select count(*) from hiring_request where status = 'PENDING'").query(Integer.class).single();
        int active = jdbc.sql("select count(*) from application where stage in ('APPLIED', 'SCREENED', 'INTERVIEW', 'OFFER', 'ON_HOLD')")
                .query(Integer.class).single();
        Map<String, Integer> sources = new LinkedHashMap<>();
        for (String s : List.of("CAREERS", "INTERNAL", "REFERRAL", "AGENCY", "OTHER")) {
            sources.put(s, 0);
        }
        jdbc.sql("select source, count(*)::int as n from application where created_at > now() - interval '365 days' group by source")
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getInt(2))).list().forEach(e -> sources.put(e.getKey(), e.getValue()));
        Double days = jdbc.sql("""
                select avg(e.at::date - v.opened_on)::float8 from application a join vacancy v on v.id = a.vacancy_id
                join application_event e on e.application_id = a.id and e.stage = 'HIRED'
                where v.opened_on is not null and e.at > now() - interval '365 days'""").query(Double.class).optional().orElse(null);
        int hired = jdbc.sql("""
                select count(*) from application_event where stage = 'HIRED' and extract(year from at) = ?""")
                .param(today().getYear()).query(Integer.class).single();
        return new Overview(open, pending, active, sources, days, hired, foreignShare());
    }

    /** Foreign staff against all staff under contract today (quotas: AM 70/0010, by sector and grade, to check by HR). */
    ForeignShare foreignShare() {
        String country = company.country();
        record N(int foreign, int staff) {}
        N n = jdbc.sql("""
                select count(*) filter (where p.nationality_code is not null and p.nationality_code <> :country)::int as foreign,
                       count(*)::int as staff
                from app_user u left join employee_profile p on p.user_id = u.id
                where u.status = 'ACTIVE' and exists (select 1 from employee_contract c where c.user_id = u.id and c.start_date <= :d
                                                      and (c.end_date is null or c.end_date >= :d))""")
                .param("country", country).param("d", today()).query(N.class).single();
        return new ForeignShare(n.foreign(), n.staff());
    }

    /** Candidate files past their keeping date are deleted, with their applications. */
    @Transactional
    public int purge() {
        return jdbc.sql("""
                delete from candidate where delete_after is not null and delete_after < ? and not keep_for_future and user_id is null""")
                .param(today()).update();
    }

    /** HR is reminded once when a vacancy has been open two days without its declaration to ONEM (DRC only). */
    @Transactional
    public int remindOnem() {
        if (!"CD".equals(company.country())) {
            return 0;
        }
        record V(long id, String title) {}
        List<V> late = jdbc.sql("""
                select id, title from vacancy where status = 'OPEN' and onem_declared_on is null and onem_reminded_at is null
                  and opened_on <= ?""").param(today().minusDays(2)).query(V.class).list();
        for (V v : late) {
            notifications.sendToHolders("recruitment.manage", "recruitment.onem",
                    () -> Texts.t("Declare the vacancy to ONEM: %s", v.title()),
                    () -> Texts.t("Vacant posts are declared to the placement office (ONEM). Enter the date once it is done."),
                    "/recruitment?vacancy=" + v.id());
            jdbc.sql("update vacancy set onem_reminded_at = now() where id = ?").param(v.id()).update();
        }
        return late.size();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    static String upper(String s) {
        return blank(s) == null ? null : s.strip().toUpperCase();
    }
}
