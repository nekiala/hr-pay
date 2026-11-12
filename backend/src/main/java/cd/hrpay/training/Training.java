package cd.hrpay.training;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.lifecycle.Documents;
import cd.hrpay.notify.Notifications;
import cd.hrpay.org.OrgStructure;
import cd.hrpay.payment.Company;
import cd.hrpay.payroll.ExchangeRates;
import cd.hrpay.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Training (DRC Code du travail art. 8; Ordonnance 71-055 art. 17-22): the courses, the needs and requests, the annual
 * plan with the workers' representatives' opinion, the sessions with attendance, cost and feedback, and the effect the
 * manager sees three months later. HR (training.manage) runs it; a manager asks for and follows their team; everyone
 * sees and rates their own training.
 */
@Service
public class Training {

    public static final Set<String> PROVIDERS = Set.of("INTERNAL", "INPP", "EXTERNAL");
    public static final Set<String> MODES = Set.of("CLASSROOM", "ON_THE_JOB", "ONLINE");
    public static final Set<String> PRIORITIES = Set.of("HIGH", "MEDIUM", "LOW");
    static final String DOCUMENT_TYPE = "TRAINING";

    public record Course(long id, String title, String providerKind, String providerName, String mode, BigDecimal hours,
                         BigDecimal costPerPerson, String currency, String description, boolean active, int sessions,
                         int trained) {}

    public record CourseInput(String title, String providerKind, String providerName, String mode, BigDecimal hours,
                              BigDecimal costPerPerson, String currency, String description, boolean active) {}

    public record Need(long id, int year, Long userId, String userName, Long unitId, String unitName, String topic,
                       Long courseId, String courseTitle, String source, Long sourceRef, String priority, String status,
                       String reason, String managerOpinion, String managerComment, OffsetDateTime managerAt,
                       String decidedBy, OffsetDateTime decidedAt, String decisionComment, Long planLineId,
                       String planLineTopic, Long requestedBy, String requestedByName, OffsetDateTime createdAt) {}

    public record NeedInput(Long userId, Long unitId, String topic, Long courseId, String reason, String priority,
                            Integer year) {}

    public record Plan(long id, int year, String status, BigDecimal budget, String currency, LocalDate consultedOn,
                       String representativesOpinion, String approvedBy, OffsetDateTime approvedAt, String note) {}

    public record Line(long id, Long courseId, String courseTitle, String topic, Long categoryId, String categoryName,
                       Long unitId, String unitName, Integer quarter, int people, BigDecimal budget,
                       boolean nationalManagers, String note, int needs, int sessions, int trained) {}

    public record LineInput(Long courseId, String topic, Long categoryId, Long unitId, Integer quarter, int people,
                            BigDecimal budget, boolean nationalManagers, String note) {}

    /** Art. 17: no category of staff left out. */
    public record Coverage(long categoryId, String code, String name, int staff, int lines, int trained) {}

    /** The INPP levy on closed payrolls of the year, in US dollars and in the local currency. */
    public record Levy(BigDecimal usd, BigDecimal local, String localCurrency, int months) {}

    public record PlanView(int year, Plan plan, List<Line> lines, List<Coverage> coverage, Levy levy,
                           BigDecimal plannedBudget, BigDecimal spent, BigDecimal committed, String currency,
                           int openNeeds, String lead, List<String> warnings) {}

    public record Session(long id, long courseId, String courseTitle, String providerKind, String mode, BigDecimal hours,
                          Long planLineId, String planLineTopic, LocalDate startsOn, LocalDate endsOn, String place,
                          String trainer, String status, BigDecimal actualCost, BigDecimal expectedCost, String currency,
                          String note, int enrolled, int attended, Double rating) {}

    public record SessionInput(long courseId, Long planLineId, LocalDate startsOn, LocalDate endsOn, String place,
                               String trainer, BigDecimal actualCost, String currency, String note) {}

    public record Attendee(long id, long sessionId, long userId, String name, String registrationNo, String unitName,
                           Long needId, Boolean attended, Integer rating, String ratingComment, LocalDate effectDueOn,
                           String effect, String effectComment, String effectByName, Long documentId) {}

    public record SessionView(Session session, List<Attendee> attendees, List<Need> openNeeds) {}

    /** One training of a person: the session, how it went, and its effect. */
    public record History(long attendeeId, long sessionId, String courseTitle, String providerKind, BigDecimal hours,
                          LocalDate startsOn, LocalDate endsOn, String status, Boolean attended, Integer rating,
                          String ratingComment, String effect, String effectComment, Long documentId) {}

    public record Mine(List<History> history, List<Need> requests, BigDecimal hoursThisYear, List<Course> courses) {}

    public record EffectDue(long attendeeId, long userId, String name, String courseTitle, LocalDate endsOn,
                            LocalDate effectDueOn) {}

    public record ReportRow(String key, String label, int people, int sessions, BigDecimal hours, BigDecimal cost) {}

    public record Report(int year, int trained, int staff, BigDecimal hours, BigDecimal cost, String currency, Levy levy,
                         List<ReportRow> byCategory, List<ReportRow> byUnit, List<ReportRow> byPerson,
                         int apprentices, int womenTrained) {}

    private static final ZoneId ZONE = ZoneId.of("Africa/Kinshasa");

    private final JdbcClient jdbc;
    private final OrgStructure org;
    private final Notifications notifications;
    private final AuditService audit;
    private final Documents documents;
    private final Company company;
    private final ExchangeRates exchange;
    private final Clock clock;

    public Training(JdbcClient jdbc, OrgStructure org, Notifications notifications, AuditService audit,
                    Documents documents, Company company, ExchangeRates exchange, Clock clock) {
        this.jdbc = jdbc;
        this.org = org;
        this.notifications = notifications;
        this.audit = audit;
        this.documents = documents;
        this.company = company;
        this.exchange = exchange;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    // ---- who may do what -------------------------------------------------------------------------------------

    static boolean isHr() {
        return CurrentUser.has("training.manage");
    }

    private static void requireHr() {
        if (!isHr()) {
            throw new AccessDeniedException("Only HR can do this.");
        }
    }

    private boolean manages(long userId) {
        Long me = CurrentUser.id();
        return me != null && org.inTeam(me, userId);
    }

    private void requireSee(long userId) {
        if (!isHr() && !Objects.equals(CurrentUser.id(), userId) && !manages(userId)) {
            throw new AccessDeniedException("You cannot see this person's training.");
        }
    }

    private String setting(String key, String fallback) {
        return jdbc.sql("select value from payroll_setting where key = ?").param(key).query(String.class).optional()
                .map(String::trim).filter(s -> !s.isEmpty()).orElse(fallback);
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ---- courses -----------------------------------------------------------------------------------------------

    private static final String COURSE_SELECT = """
            select c.id, c.title, c.provider_kind, c.provider_name, c.mode, c.hours, c.cost_per_person, trim(c.currency) as currency,
                   c.description, c.active,
                   (select count(*) from training_session s where s.course_id = c.id and s.status <> 'CANCELLED') as sessions,
                   (select count(distinct a.user_id) from training_attendee a join training_session s on s.id = a.session_id
                    where s.course_id = c.id and s.status = 'DONE' and a.attended) as trained
            from training_course c
            """;

    public List<Course> courses(boolean all) {
        return jdbc.sql(COURSE_SELECT + (all ? "" : " where c.active") + " order by c.active desc, c.title")
                .query(Course.class).list();
    }

    public Course course(long id) {
        return jdbc.sql(COURSE_SELECT + " where c.id = ?").param(id).query(Course.class).optional()
                .orElseThrow(() -> new NotFoundException("Course not found."));
    }

    @Transactional
    public Course saveCourse(Long id, CourseInput in) {
        requireHr();
        if (!PROVIDERS.contains(in.providerKind()) || !MODES.contains(in.mode())) {
            throw new BusinessRuleException("Unknown provider or mode.");
        }
        String currency = currency(in.currency());
        BigDecimal cost = in.costPerPerson() == null ? BigDecimal.ZERO : in.costPerPerson();
        long saved;
        if (id == null) {
            saved = jdbc.sql("""
                    insert into training_course (title, provider_kind, provider_name, mode, hours, cost_per_person, currency,
                                                 description, active)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(in.title().trim(), in.providerKind(), clean(in.providerName()), in.mode(), in.hours(), cost,
                            currency, clean(in.description()), in.active())
                    .query(Long.class).single();
        } else {
            course(id);
            jdbc.sql("""
                    update training_course set title = ?, provider_kind = ?, provider_name = ?, mode = ?, hours = ?,
                           cost_per_person = ?, currency = ?, description = ?, active = ? where id = ?""")
                    .params(in.title().trim(), in.providerKind(), clean(in.providerName()), in.mode(), in.hours(), cost,
                            currency, clean(in.description()), in.active(), id).update();
            saved = id;
        }
        audit.record(id == null ? "training.course_created" : "training.course_updated", "training_course", saved,
                Map.of("title", in.title().trim()));
        return course(saved);
    }

    private String currency(String c) {
        String local = company.localCurrency();
        if (c == null || c.isBlank()) {
            return "USD";
        }
        if (!c.equals("USD") && !c.equals(local)) {
            throw new BusinessRuleException(Texts.t("Choose USD or %s.", local));
        }
        return c;
    }

    // ---- needs and requests ------------------------------------------------------------------------------------

    private static final String NEED_SELECT = """
            select n.id, n.year, n.user_id, person_name(u.first_name, u.middle_name, u.last_name) as user_name, n.unit_id,
                   coalesce(ou.name, (select x.name from org_unit x join org_assignment a on a.unit_id = x.id
                                      where a.user_id = n.user_id and a.valid_to is null order by a.valid_from desc limit 1)) as unit_name,
                   n.topic, n.course_id, c.title as course_title, n.source, n.source_ref, n.priority, n.status, n.reason,
                   n.manager_opinion, n.manager_comment, n.manager_at, n.decided_by, n.decided_at, n.decision_comment,
                   n.plan_line_id, pl.topic as plan_line_topic, n.requested_by,
                   person_name(q.first_name, q.middle_name, q.last_name) as requested_by_name, n.created_at
            from training_need n left join app_user u on u.id = n.user_id left join org_unit ou on ou.id = n.unit_id
            left join training_course c on c.id = n.course_id left join training_plan_line pl on pl.id = n.plan_line_id
            left join app_user q on q.id = n.requested_by
            """;

    public Need need(long id) {
        return jdbc.sql(NEED_SELECT + " where n.id = ?").param(id).query(Need.class).optional()
                .orElseThrow(() -> new NotFoundException("Training need not found."));
    }

    /** HR sees every need of the year; a manager those of their team. */
    public List<Need> needs(int year) {
        List<Need> all = jdbc.sql(NEED_SELECT + """
                 where n.year = ? order by case n.status when 'REQUESTED' then 0 when 'OPEN' then 1 when 'PLANNED' then 2 else 3 end,
                 case n.priority when 'HIGH' then 0 when 'MEDIUM' then 1 else 2 end, n.created_at desc""")
                .param(year).query(Need.class).list();
        if (isHr()) {
            return all;
        }
        Long me = CurrentUser.id();
        if (me == null) {
            return List.of();
        }
        Set<Long> team = org.teamOf(me);
        return all.stream().filter(n -> n.userId() != null && team.contains(n.userId())).toList();
    }

    /**
     * A need or a request: an employee asks for themselves (their manager gives an opinion, then HR decides); a manager
     * asks for someone of their team (HR decides); HR records a need directly, for a person or a whole unit.
     */
    @Transactional
    public Need ask(NeedInput in) {
        Long me = CurrentUser.id();
        Long userId = in.userId() == null && in.unitId() == null ? me : in.userId();
        int year = in.year() == null ? today().getYear() : in.year();
        String priority = in.priority() == null ? "MEDIUM" : in.priority();
        if (!PRIORITIES.contains(priority)) {
            throw new BusinessRuleException("Unknown priority.");
        }
        if (in.courseId() != null) {
            course(in.courseId());
        }
        String source;
        String status;
        String opinion = null;
        if (isHr()) {
            source = Objects.equals(userId, me) ? "EMPLOYEE" : "HR";
            status = "OPEN";
        } else if (userId != null && Objects.equals(userId, me)) {
            source = "EMPLOYEE";
            status = "REQUESTED";
        } else if (userId != null && manages(userId)) {
            source = "MANAGER";
            status = "REQUESTED";
            opinion = "SUPPORT";
        } else {
            throw new AccessDeniedException("You can ask for training for yourself or your team.");
        }
        if (in.unitId() != null && !isHr()) {
            throw new AccessDeniedException("Only HR records a need for a whole unit.");
        }
        long id = jdbc.sql("""
                insert into training_need (year, user_id, unit_id, topic, course_id, source, priority, status, reason,
                                           manager_opinion, manager_at, requested_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, case when cast(? as varchar) is null then null else now() end, ?) returning id""")
                .params(year, userId, in.unitId(), in.topic().trim(), in.courseId(), source, priority, status,
                        clean(in.reason()), opinion, opinion, me)
                .query(Long.class).single();
        audit.record("training.need_created", "training_need", id, Map.of("source", source, "topic", in.topic().trim()));
        Need n = need(id);
        if (status.equals("REQUESTED") && source.equals("EMPLOYEE")) {
            org.managerOf(userId).ifPresentOrElse(
                    b -> notifications.send(b.userId(), "training.request",
                            () -> Texts.t("Training request: %s", n.userName()),
                            () -> Texts.t("%s asks for training: %s. Give your opinion; HR decides.", n.userName(), n.topic()),
                            "/training?tab=needs"),
                    () -> toHr(n));
        } else if (status.equals("REQUESTED")) {
            toHr(n);
        }
        return n;
    }

    private void toHr(Need n) {
        notifications.sendToHolders("training.manage", "training.request",
                () -> Texts.t("Training request: %s", n.userName()),
                () -> Texts.t("A training request waits for your decision: %s.", n.topic()), "/training?tab=needs");
    }

    /** The manager's opinion on a request from their team. */
    @Transactional
    public Need opinion(long id, String opinion, String comment) {
        Need n = need(id);
        if (n.userId() == null || !manages(n.userId())) {
            throw new AccessDeniedException("Only the person's manager gives this opinion.");
        }
        if (!n.status().equals("REQUESTED")) {
            throw new BusinessRuleException("HR already decided on this request.");
        }
        if (!Set.of("SUPPORT", "OPPOSE").contains(opinion)) {
            throw new BusinessRuleException("Unknown opinion.");
        }
        jdbc.sql("update training_need set manager_opinion = ?, manager_comment = ?, manager_at = now() where id = ?")
                .params(opinion, clean(comment), id).update();
        audit.record("training.need_opinion", "training_need", id, Map.of("opinion", opinion));
        toHr(n);
        return need(id);
    }

    /** HR's decision on a request: it becomes a need to plan, or it is refused with a reason. */
    @Transactional
    public Need decide(long id, boolean accept, String comment, String priority) {
        requireHr();
        Need n = need(id);
        if (!n.status().equals("REQUESTED")) {
            throw new BusinessRuleException("This request was already decided.");
        }
        if (!accept && clean(comment) == null) {
            throw new BusinessRuleException("Give the reason of the refusal.");
        }
        String p = priority == null ? n.priority() : priority;
        if (!PRIORITIES.contains(p)) {
            throw new BusinessRuleException("Unknown priority.");
        }
        jdbc.sql("""
                update training_need set status = ?, priority = ?, decided_by = ?, decided_at = now(), decision_comment = ?
                where id = ?""").params(accept ? "OPEN" : "REFUSED", p, CurrentUser.email(), clean(comment), id).update();
        audit.record("training.need_decided", "training_need", id, Map.of("accepted", accept));
        if (n.userId() != null) {
            notifications.send(n.userId(), "training.decision",
                    () -> accept ? Texts.t("Training request accepted: %s", n.topic())
                            : Texts.t("Training request refused: %s", n.topic()),
                    () -> accept ? Texts.t("HR will plan it in a session; you will be told the dates.")
                            : Texts.t("Reason: %s", clean(comment)),
                    "/my/training");
        }
        return need(id);
    }

    /** HR drops a need, opens it again, or ties it to a line of the plan. */
    @Transactional
    public Need setNeed(long id, String status, Long planLineId) {
        requireHr();
        Need n = need(id);
        if (planLineId != null) {
            line(planLineId);
            jdbc.sql("update training_need set plan_line_id = ?, status = case when status in ('OPEN', 'PLANNED') then 'PLANNED' else status end where id = ?")
                    .params(planLineId, id).update();
        } else if (status != null) {
            if (!Set.of("OPEN", "DROPPED").contains(status)) {
                throw new BusinessRuleException("A need is opened again or dropped here.");
            }
            if (n.status().equals("REQUESTED") || n.status().equals("DONE")) {
                throw new BusinessRuleException("Decide on the request first.");
            }
            jdbc.sql("update training_need set status = ?, plan_line_id = case when ? = 'DROPPED' then null else plan_line_id end where id = ?")
                    .params(status, status, id).update();
        } else {
            jdbc.sql("update training_need set plan_line_id = null, status = case when status = 'PLANNED' then 'OPEN' else status end where id = ?")
                    .param(id).update();
        }
        audit.record("training.need_updated", "training_need", id, Map.of("status", Objects.toString(status, ""),
                "line", Objects.toString(planLineId, "")));
        return need(id);
    }

    /**
     * Gathers the needs already written elsewhere: the development need of each person's objectives of the year, and
     * each criterion scored below half in an evaluation approved this year or last. Each is added once.
     */
    @Transactional
    public int collect(int year) {
        requireHr();
        int added = jdbc.sql("""
                insert into training_need (year, user_id, topic, source, source_ref, status, reason, requested_by)
                select o.year, o.user_id, left(trim(o.development_need), 300), 'OBJECTIVE', o.id, 'OPEN', o.title, ?
                from objective o
                where o.year = ? and o.user_id is not null and o.status <> 'CANCELLED'
                  and o.development_need is not null and trim(o.development_need) <> ''
                on conflict do nothing""").params(CurrentUser.id(), year).update();
        added += jdbc.sql("""
                insert into training_need (year, user_id, topic, source, source_ref, status, reason, requested_by)
                select ?, e.user_id, left(c.name, 300), 'EVALUATION', e.id, 'OPEN',
                       concat(c.name, ': ', round(s.score), ' %'), ?
                from evaluation e join evaluation_score s on s.evaluation_id = e.id
                join evaluation_criterion c on c.id = s.criterion_id
                join app_user u on u.id = e.user_id
                where e.status = 'APPROVED' and extract(year from e.period_end) between ? and ? and s.score < 50
                  and u.status = 'ACTIVE' and c.source = 'MANAGER'
                on conflict do nothing""").params(year, CurrentUser.id(), year - 1, year).update();
        audit.record("training.needs_collected", "training_need", null, Map.of("year", year, "added", added));
        return added;
    }

    // ---- the plan ------------------------------------------------------------------------------------------------

    private Plan planRow(int year) {
        return jdbc.sql("""
                select id, year, status, budget, trim(currency) as currency, consulted_on, representatives_opinion, approved_by,
                       approved_at, note from training_plan where year = ?""").param(year).query(Plan.class).optional().orElse(null);
    }

    private Plan requirePlan(int year) {
        Plan p = planRow(year);
        if (p == null) {
            throw new NotFoundException(Texts.t("There is no training plan for %s yet.", year));
        }
        return p;
    }

    private static final String LINE_SELECT = """
            select l.id, l.course_id, c.title as course_title, l.topic, l.category_id, gc.name as category_name, l.unit_id,
                   u.name as unit_name, l.quarter, l.people, l.budget, l.national_managers, l.note,
                   (select count(*) from training_need n where n.plan_line_id = l.id) as needs,
                   (select count(*) from training_session s where s.plan_line_id = l.id and s.status <> 'CANCELLED') as sessions,
                   (select count(distinct a.user_id) from training_attendee a join training_session s on s.id = a.session_id
                    where s.plan_line_id = l.id and s.status = 'DONE' and a.attended) as trained
            from training_plan_line l left join training_course c on c.id = l.course_id
            left join grade_category gc on gc.id = l.category_id left join org_unit u on u.id = l.unit_id
            """;

    private Line line(long id) {
        return jdbc.sql(LINE_SELECT + " where l.id = ?").param(id).query(Line.class).optional()
                .orElseThrow(() -> new NotFoundException("Plan line not found."));
    }

    public PlanView plan(int year) {
        requireHr();
        Plan p = planRow(year);
        List<Line> lines = p == null ? List.of()
                : jdbc.sql(LINE_SELECT + " where l.plan_id = ? order by l.quarter nulls last, l.topic").param(p.id())
                        .query(Line.class).list();
        String cur = p == null ? "USD" : p.currency();
        List<Coverage> coverage = jdbc.sql("""
                select gc.id as category_id, gc.code, gc.name,
                       (select count(*) from app_user u where u.status = 'ACTIVE' and not u.not_employee and u.grade_category_id = gc.id) as staff,
                       (select count(*) from training_plan_line l join training_plan p on p.id = l.plan_id
                        where p.year = :year and (l.category_id = gc.id or l.category_id is null)) as lines,
                       (select count(distinct a.user_id) from training_attendee a join training_session s on s.id = a.session_id
                        join app_user u on u.id = a.user_id
                        where s.status = 'DONE' and a.attended and extract(year from s.starts_on) = :year
                          and u.grade_category_id = gc.id) as trained
                from grade_category gc where gc.active order by gc.code""").param("year", year).query(Coverage.class).list();
        List<String> warnings = new ArrayList<>();
        for (Coverage c : coverage) {
            if (c.staff() > 0 && c.lines() == 0) {
                warnings.add(Texts.t("No line of the plan covers the category %s (%d people): the plan must leave no category out (O. 71-055 art. 17).",
                        c.name(), c.staff()));
            }
        }
        if (p != null && lines.stream().noneMatch(Line::nationalManagers)) {
            warnings.add(Texts.t("No line trains national managers, who come first (O. 71-055 art. 18)."));
        }
        if (p != null && p.consultedOn() == null) {
            warnings.add(Texts.t("The workers' representatives have not been consulted on the plan yet (O. 71-055 art. 19)."));
        }
        String lead = setting("TRAINING_LEAD", "");
        if (lead.isBlank()) {
            warnings.add(Texts.t("Name the person or department in charge of training in the settings (O. 71-055 art. 22)."));
        }
        BigDecimal planned = lines.stream().map(Line::budget).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal spent = BigDecimal.ZERO;
        BigDecimal committed = BigDecimal.ZERO;
        for (Session s : sessions(year)) {
            BigDecimal cost = s.actualCost() != null ? s.actualCost() : s.expectedCost();
            BigDecimal inPlan = convert(cost, s.currency(), cur);
            if (s.status().equals("DONE")) {
                spent = spent.add(inPlan);
            } else if (s.status().equals("PLANNED")) {
                committed = committed.add(inPlan);
            }
        }
        if (p != null && p.budget() != null && spent.add(committed).compareTo(p.budget()) > 0) {
            warnings.add(Texts.t("Sessions spent and planned go over the plan's budget."));
        }
        int openNeeds = jdbc.sql("select count(*) from training_need where year = ? and status in ('REQUESTED', 'OPEN')")
                .param(year).query(Integer.class).single();
        return new PlanView(year, p, lines, coverage, levy(year), planned, spent, committed, cur, openNeeds, lead, warnings);
    }

    BigDecimal convert(BigDecimal amount, String from, String to) {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        if (from.equals(to)) {
            return amount;
        }
        try {
            BigDecimal rate = exchange.local(company.localCurrency(), today()).cdfPerUnit();
            return ExchangeRates.convert(amount, from, to, rate);
        } catch (RuntimeException e) {
            return BigDecimal.ZERO;
        }
    }

    /** The INPP levy on the payslips of the year's closed months (Code art. 11-17; AI of 14 February 2006). */
    public Levy levy(int year) {
        String local = company.localCurrency();
        record R(BigDecimal usd, BigDecimal local, int months) {}
        R r = jdbc.sql("""
                select coalesce(sum(case when trim(p.currency) = 'USD' then l.amount else l.amount / r.usd_cdf end), 0) as usd,
                       coalesce(sum(case when trim(p.currency) = 'USD' then l.amount * r.usd_cdf else l.amount end), 0) as local,
                       count(distinct r.id) as months
                from payslip_line l join payslip p on p.id = l.payslip_id join payroll_run r on r.id = p.run_id
                where l.element_code = 'INPP' and r.status = 'CLOSED' and extract(year from r.month) = ?""")
                .param(year).query(R.class).single();
        return new Levy(r.usd().setScale(2, RoundingMode.HALF_UP), r.local().setScale(2, RoundingMode.HALF_UP), local, r.months());
    }

    @Transactional
    public PlanView savePlan(int year, BigDecimal budget, String currency, String note) {
        requireHr();
        Plan p = planRow(year);
        String cur = currency(currency);
        if (p == null) {
            jdbc.sql("insert into training_plan (year, budget, currency, note) values (?, ?, ?, ?)")
                    .params(year, budget, cur, clean(note)).update();
        } else {
            requireOpenPlan(p);
            jdbc.sql("update training_plan set budget = ?, currency = ?, note = ? where id = ?")
                    .params(budget, cur, clean(note), p.id()).update();
        }
        audit.record("training.plan_saved", "training_plan", null, Map.of("year", year));
        return plan(year);
    }

    private static void requireOpenPlan(Plan p) {
        if (p.status().equals("CLOSED")) {
            throw new BusinessRuleException("This plan is closed.");
        }
    }

    /** The workers' representatives' opinion (art. 19): recorded, it does not block the plan. */
    @Transactional
    public PlanView consult(int year, LocalDate on, String opinion) {
        requireHr();
        Plan p = requirePlan(year);
        requireOpenPlan(p);
        if (on == null || on.isAfter(today())) {
            throw new BusinessRuleException("Give the date of the consultation, today or before.");
        }
        jdbc.sql("""
                update training_plan set consulted_on = ?, representatives_opinion = ?,
                       status = case when status = 'DRAFT' then 'CONSULTED' else status end where id = ?""")
                .params(on, clean(opinion), p.id()).update();
        audit.record("training.plan_consulted", "training_plan", p.id(), Map.of("year", year, "on", on.toString()));
        return plan(year);
    }

    @Transactional
    public PlanView approve(int year) {
        requireHr();
        Plan p = requirePlan(year);
        if (!Set.of("DRAFT", "CONSULTED").contains(p.status())) {
            throw new BusinessRuleException("This plan is already approved.");
        }
        int lines = jdbc.sql("select count(*) from training_plan_line where plan_id = ?").param(p.id()).query(Integer.class).single();
        if (lines == 0) {
            throw new BusinessRuleException("Add at least one line to the plan first.");
        }
        jdbc.sql("update training_plan set status = 'APPROVED', approved_by = ?, approved_at = now() where id = ?")
                .params(CurrentUser.email(), p.id()).update();
        audit.record("training.plan_approved", "training_plan", p.id(), Map.of("year", year,
                "consulted", p.consultedOn() != null));
        return plan(year);
    }

    @Transactional
    public PlanView close(int year) {
        requireHr();
        Plan p = requirePlan(year);
        jdbc.sql("update training_plan set status = 'CLOSED' where id = ?").param(p.id()).update();
        audit.record("training.plan_closed", "training_plan", p.id(), Map.of("year", year));
        return plan(year);
    }

    @Transactional
    public PlanView saveLine(int year, Long lineId, LineInput in) {
        requireHr();
        Plan p = requirePlan(year);
        requireOpenPlan(p);
        if (in.courseId() != null) {
            course(in.courseId());
        }
        String topic = clean(in.topic());
        if (topic == null && in.courseId() != null) {
            topic = course(in.courseId()).title();
        }
        if (topic == null) {
            throw new BusinessRuleException("Give the topic or choose a course.");
        }
        if (lineId == null) {
            jdbc.sql("""
                    insert into training_plan_line (plan_id, course_id, topic, category_id, unit_id, quarter, people, budget,
                                                    national_managers, note)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(p.id(), in.courseId(), topic, in.categoryId(), in.unitId(), in.quarter(), in.people(), in.budget(),
                            in.nationalManagers(), clean(in.note())).update();
        } else {
            Line l = line(lineId);
            jdbc.sql("""
                    update training_plan_line set course_id = ?, topic = ?, category_id = ?, unit_id = ?, quarter = ?, people = ?,
                           budget = ?, national_managers = ?, note = ? where id = ? and plan_id = ?""")
                    .params(in.courseId(), topic, in.categoryId(), in.unitId(), in.quarter(), in.people(), in.budget(),
                            in.nationalManagers(), clean(in.note()), l.id(), p.id()).update();
        }
        audit.record("training.plan_line_saved", "training_plan", p.id(), Map.of("topic", topic));
        return plan(year);
    }

    @Transactional
    public PlanView deleteLine(long lineId) {
        requireHr();
        Integer year = jdbc.sql("select p.year from training_plan_line l join training_plan p on p.id = l.plan_id where l.id = ?")
                .param(lineId).query(Integer.class).optional().orElseThrow(() -> new NotFoundException("Plan line not found."));
        requireOpenPlan(requirePlan(year));
        jdbc.sql("update training_need set status = 'OPEN', plan_line_id = null where plan_line_id = ? and status = 'PLANNED'")
                .param(lineId).update();
        jdbc.sql("delete from training_plan_line where id = ?").param(lineId).update();
        audit.record("training.plan_line_deleted", "training_plan", null, Map.of("line", lineId));
        return plan(year);
    }

    // ---- sessions ------------------------------------------------------------------------------------------------

    private static final String SESSION_SELECT = """
            select s.id, s.course_id, c.title as course_title, c.provider_kind, c.mode, c.hours, s.plan_line_id,
                   l.topic as plan_line_topic, s.starts_on, s.ends_on, s.place, s.trainer, s.status, s.actual_cost,
                   c.cost_per_person * (select count(*) from training_attendee a where a.session_id = s.id) as expected_cost,
                   trim(s.currency) as currency, s.note,
                   (select count(*) from training_attendee a where a.session_id = s.id) as enrolled,
                   (select count(*) from training_attendee a where a.session_id = s.id and a.attended) as attended,
                   (select avg(a.rating) from training_attendee a where a.session_id = s.id) as rating
            from training_session s join training_course c on c.id = s.course_id
            left join training_plan_line l on l.id = s.plan_line_id
            """;

    public List<Session> sessions(int year) {
        return jdbc.sql(SESSION_SELECT + " where extract(year from s.starts_on) = ? order by s.starts_on desc")
                .param(year).query(Session.class).list();
    }

    private Session sessionRow(long id) {
        return jdbc.sql(SESSION_SELECT + " where s.id = ?").param(id).query(Session.class).optional()
                .orElseThrow(() -> new NotFoundException("Session not found."));
    }

    private static final String ATTENDEE_SELECT = """
            select a.id, a.session_id, a.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, u.registration_no,
                   (select x.name from org_unit x join org_assignment g on g.unit_id = x.id
                    where g.user_id = a.user_id and g.valid_to is null order by g.valid_from desc limit 1) as unit_name,
                   a.need_id, a.attended, a.rating, a.rating_comment, a.effect_due_on, a.effect, a.effect_comment,
                   person_name(e.first_name, e.middle_name, e.last_name) as effect_by_name, a.document_id
            from training_attendee a join app_user u on u.id = a.user_id left join app_user e on e.id = a.effect_by
            """;

    public SessionView session(long id) {
        requireHr();
        Session s = sessionRow(id);
        List<Attendee> attendees = jdbc.sql(ATTENDEE_SELECT + " where a.session_id = ? order by u.last_name, u.first_name")
                .param(id).query(Attendee.class).list();
        // The open needs this session can answer: same course, or tied to its plan line.
        List<Need> open = jdbc.sql(NEED_SELECT + """
                 where n.status in ('OPEN', 'PLANNED') and n.user_id is not null
                   and (n.course_id = ? or (? is not null and n.plan_line_id = ?))
                   and not exists (select 1 from training_attendee a where a.session_id = ? and a.user_id = n.user_id)
                 order by n.priority, n.created_at""")
                .params(s.courseId(), s.planLineId(), s.planLineId(), id).query(Need.class).list();
        return new SessionView(s, attendees, open);
    }

    @Transactional
    public SessionView saveSession(Long id, SessionInput in) {
        requireHr();
        course(in.courseId());
        if (in.endsOn().isBefore(in.startsOn())) {
            throw new BusinessRuleException("The session must end on or after its first day.");
        }
        if (in.planLineId() != null) {
            line(in.planLineId());
        }
        String cur = currency(in.currency() == null ? course(in.courseId()).currency() : in.currency());
        long saved;
        if (id == null) {
            saved = jdbc.sql("""
                    insert into training_session (course_id, plan_line_id, starts_on, ends_on, place, trainer, actual_cost,
                                                  currency, note, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(in.courseId(), in.planLineId(), in.startsOn(), in.endsOn(), clean(in.place()), clean(in.trainer()),
                            in.actualCost(), cur, clean(in.note()), CurrentUser.email()).query(Long.class).single();
        } else {
            Session s = sessionRow(id);
            if (s.status().equals("CANCELLED")) {
                throw new BusinessRuleException("This session was cancelled.");
            }
            jdbc.sql("""
                    update training_session set course_id = ?, plan_line_id = ?, starts_on = ?, ends_on = ?, place = ?, trainer = ?,
                           actual_cost = ?, currency = ?, note = ? where id = ?""")
                    .params(in.courseId(), in.planLineId(), in.startsOn(), in.endsOn(), clean(in.place()), clean(in.trainer()),
                            in.actualCost(), cur, clean(in.note()), id).update();
            saved = id;
        }
        audit.record(id == null ? "training.session_created" : "training.session_updated", "training_session", saved,
                Map.of("course", in.courseId(), "starts", in.startsOn().toString()));
        return session(saved);
    }

    /** Enrols people, each told the dates; a need given with a person is marked planned. */
    @Transactional
    public SessionView enrol(long sessionId, List<Long> userIds, List<Long> needIds) {
        requireHr();
        Session s = sessionRow(sessionId);
        if (!s.status().equals("PLANNED")) {
            throw new BusinessRuleException("People are enrolled before the session is done.");
        }
        Map<Long, Long> needOf = new LinkedHashMap<>();
        for (Long n : needIds == null ? List.<Long>of() : needIds) {
            Need need = need(n);
            if (need.userId() != null) {
                needOf.put(need.userId(), need.id());
            }
        }
        List<Long> people = new ArrayList<>(userIds == null ? List.of() : userIds);
        for (Long u : needOf.keySet()) {
            if (!people.contains(u)) {
                people.add(u);
            }
        }
        for (Long u : people) {
            int added = jdbc.sql("insert into training_attendee (session_id, user_id, need_id) values (?, ?, ?) on conflict do nothing")
                    .params(sessionId, u, needOf.get(u)).update();
            if (needOf.containsKey(u)) {
                jdbc.sql("update training_need set status = 'PLANNED' where id = ? and status in ('OPEN', 'PLANNED')")
                        .param(needOf.get(u)).update();
            }
            if (added > 0) {
                notifications.send(u, "training.enrolled", () -> Texts.t("Training: %s", s.courseTitle()),
                        () -> Texts.t("You are enrolled from %s to %s%s.", Texts.day(s.startsOn()), Texts.day(s.endsOn()),
                                s.place() == null ? "" : ", " + s.place()),
                        "/my/training");
            }
        }
        audit.record("training.enrolled", "training_session", sessionId, Map.of("people", people.size()));
        return session(sessionId);
    }

    @Transactional
    public SessionView unenrol(long sessionId, long userId) {
        requireHr();
        Session s = sessionRow(sessionId);
        if (!s.status().equals("PLANNED")) {
            throw new BusinessRuleException("The session is over: its list of people stays.");
        }
        jdbc.sql("""
                update training_need set status = case when plan_line_id is null then 'OPEN' else 'PLANNED' end
                where id = (select need_id from training_attendee where session_id = ? and user_id = ?) and status = 'PLANNED'""")
                .params(sessionId, userId).update();
        jdbc.sql("delete from training_attendee where session_id = ? and user_id = ?").params(sessionId, userId).update();
        return session(sessionId);
    }

    /**
     * Closes a session: who attended, its real cost. The attendees' needs are met, each attendee is asked for their
     * view, and their manager will be asked about the effect some months later.
     */
    @Transactional
    public SessionView done(long sessionId, List<Long> present, BigDecimal actualCost) {
        requireHr();
        Session s = sessionRow(sessionId);
        if (!s.status().equals("PLANNED")) {
            throw new BusinessRuleException("This session is already closed.");
        }
        if (s.endsOn().isAfter(today())) {
            throw new BusinessRuleException("The session is not over yet.");
        }
        int months = Integer.parseInt(setting("TRAINING_EFFECT_MONTHS", "3"));
        LocalDate effectOn = s.endsOn().plusMonths(months);
        Set<Long> there = Set.copyOf(present == null ? List.of() : present);
        List<Attendee> list = session(sessionId).attendees();
        for (Attendee a : list) {
            boolean came = there.contains(a.userId());
            jdbc.sql("update training_attendee set attended = ?, effect_due_on = ? where id = ?")
                    .params(came, came ? effectOn : null, a.id()).update();
            if (a.needId() != null) {
                jdbc.sql("update training_need set status = ? where id = ?").params(came ? "DONE" : "OPEN", a.needId()).update();
            }
            if (came) {
                notifications.send(a.userId(), "training.rate", () -> Texts.t("How was the training %s?", s.courseTitle()),
                        () -> Texts.t("Give your view of the session in My training: it helps HR choose the next ones."),
                        "/my/training");
            }
        }
        jdbc.sql("update training_session set status = 'DONE', actual_cost = coalesce(?, actual_cost) where id = ?")
                .params(actualCost, sessionId).update();
        audit.record("training.session_done", "training_session", sessionId, Map.of("attended", there.size()));
        return session(sessionId);
    }

    @Transactional
    public SessionView cancel(long sessionId) {
        requireHr();
        Session s = sessionRow(sessionId);
        if (!s.status().equals("PLANNED")) {
            throw new BusinessRuleException("Only a planned session can be cancelled.");
        }
        jdbc.sql("""
                update training_need set status = case when plan_line_id is null then 'OPEN' else 'PLANNED' end
                where id in (select need_id from training_attendee where session_id = ?) and status = 'PLANNED'""")
                .param(sessionId).update();
        jdbc.sql("update training_session set status = 'CANCELLED' where id = ?").param(sessionId).update();
        for (Attendee a : session(sessionId).attendees()) {
            notifications.send(a.userId(), "training.cancelled", () -> Texts.t("Training cancelled: %s", s.courseTitle()),
                    () -> Texts.t("The session of %s is cancelled.", Texts.day(s.startsOn())), "/my/training");
        }
        audit.record("training.session_cancelled", "training_session", sessionId, Map.of());
        return session(sessionId);
    }

    /** The certificate of an attendee, filed in their employee file. */
    @Transactional
    public SessionView certificate(long attendeeId, String fileName, byte[] data) {
        requireHr();
        record A(long id, long sessionId, long userId, Boolean attended, String courseTitle, LocalDate endsOn) {}
        A a = jdbc.sql("""
                select a.id, a.session_id, a.user_id, a.attended, c.title as course_title, s.ends_on
                from training_attendee a join training_session s on s.id = a.session_id join training_course c on c.id = s.course_id
                where a.id = ?""").param(attendeeId).query(A.class).optional()
                .orElseThrow(() -> new NotFoundException("Attendee not found."));
        if (!Boolean.TRUE.equals(a.attended())) {
            throw new BusinessRuleException("A certificate is filed for someone who attended.");
        }
        long type = documents.typeId(DOCUMENT_TYPE);
        Documents.Doc doc = documents.add(a.userId(), type, fileName, data, a.endsOn(), null, a.courseTitle(), "HR");
        jdbc.sql("update training_attendee set document_id = ? where id = ?").params(doc.id(), attendeeId).update();
        return session(a.sessionId());
    }

    // ---- the employee and the manager ----------------------------------------------------------------------------

    private static final String HISTORY_SELECT = """
            select a.id as attendee_id, s.id as session_id, c.title as course_title, c.provider_kind, c.hours, s.starts_on,
                   s.ends_on, s.status, a.attended, a.rating, a.rating_comment, a.effect, a.effect_comment, a.document_id
            from training_attendee a join training_session s on s.id = a.session_id join training_course c on c.id = s.course_id
            where a.user_id = ? and s.status <> 'CANCELLED' order by s.starts_on desc
            """;

    public List<History> history(long userId) {
        requireSee(userId);
        return jdbc.sql(HISTORY_SELECT).param(userId).query(History.class).list();
    }

    public Mine mine() {
        long me = CurrentUser.id();
        List<History> history = jdbc.sql(HISTORY_SELECT).param(me).query(History.class).list();
        List<Need> requests = jdbc.sql(NEED_SELECT + " where n.user_id = ? and n.source in ('EMPLOYEE', 'MANAGER') order by n.created_at desc")
                .param(me).query(Need.class).list();
        int year = today().getYear();
        BigDecimal hours = history.stream()
                .filter(h -> Boolean.TRUE.equals(h.attended()) && h.startsOn().getYear() == year)
                .map(History::hours).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Mine(history, requests, hours, courses(false));
    }

    /** The attendee's view of the session. */
    @Transactional
    public Mine rate(long attendeeId, int rating, String comment) {
        record A(long userId, Boolean attended) {}
        A a = jdbc.sql("select user_id, attended from training_attendee where id = ?").param(attendeeId).query(A.class)
                .optional().orElseThrow(() -> new NotFoundException("Attendee not found."));
        if (!Objects.equals(a.userId(), CurrentUser.id())) {
            throw new AccessDeniedException("You rate your own training only.");
        }
        if (!Boolean.TRUE.equals(a.attended())) {
            throw new BusinessRuleException("You can rate a session once it is done and you attended it.");
        }
        jdbc.sql("update training_attendee set rating = ?, rating_comment = ?, rated_at = now() where id = ?")
                .params(rating, clean(comment), attendeeId).update();
        return mine();
    }

    /** The trainings whose effect the manager is asked about: those of their team, due now. HR sees them all. */
    public List<EffectDue> effectsDue() {
        List<EffectDue> all = jdbc.sql("""
                select a.id as attendee_id, a.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name,
                       c.title as course_title, s.ends_on, a.effect_due_on
                from training_attendee a join training_session s on s.id = a.session_id join training_course c on c.id = s.course_id
                join app_user u on u.id = a.user_id
                where a.attended and a.effect is null and a.effect_due_on <= ? and u.status = 'ACTIVE'
                order by a.effect_due_on""").param(today()).query(EffectDue.class).list();
        if (isHr()) {
            return all;
        }
        Long me = CurrentUser.id();
        Set<Long> team = me == null ? Set.of() : org.teamOf(me);
        return all.stream().filter(e -> team.contains(e.userId())).toList();
    }

    /** Did the training change the person's work? The manager says (or HR, when there is no manager). */
    @Transactional
    public List<EffectDue> effect(long attendeeId, String effect, String comment) {
        record A(long userId, Boolean attended) {}
        A a = jdbc.sql("select user_id, attended from training_attendee where id = ?").param(attendeeId).query(A.class)
                .optional().orElseThrow(() -> new NotFoundException("Attendee not found."));
        if (!isHr() && !manages(a.userId())) {
            throw new AccessDeniedException("The person's manager says whether the training changed their work.");
        }
        if (!Set.of("YES", "PARTLY", "NO").contains(effect)) {
            throw new BusinessRuleException("Unknown answer.");
        }
        jdbc.sql("update training_attendee set effect = ?, effect_comment = ?, effect_by = ?, effect_at = now() where id = ?")
                .params(effect, clean(comment), CurrentUser.id(), attendeeId).update();
        audit.record("training.effect", "training_attendee", attendeeId, Map.of("effect", effect));
        return effectsDue();
    }

    // ---- reports -------------------------------------------------------------------------------------------------

    public Report report(int year) {
        requireHr();
        Plan p = planRow(year);
        String cur = p == null ? "USD" : p.currency();
        record Row(long userId, String name, String registrationNo, String unitName, String categoryCode, String categoryName,
                   String gender, BigDecimal hours, int sessions, BigDecimal cost, String currency) {}
        // Each attendee's share of the session's cost: its real cost split among those who came, or the course's price.
        List<Row> rows = jdbc.sql("""
                select a.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, u.registration_no,
                       (select x.name from org_unit x join org_assignment g on g.unit_id = x.id
                        where g.user_id = a.user_id and g.valid_to is null order by g.valid_from desc limit 1) as unit_name,
                       gc.code as category_code, gc.name as category_name, ep.gender,
                       c.hours, 1 as sessions,
                       coalesce(s.actual_cost / nullif((select count(*) from training_attendee b where b.session_id = s.id and b.attended), 0),
                                c.cost_per_person) as cost,
                       trim(s.currency) as currency
                from training_attendee a join training_session s on s.id = a.session_id join training_course c on c.id = s.course_id
                join app_user u on u.id = a.user_id left join grade_category gc on gc.id = u.grade_category_id
                left join employee_profile ep on ep.user_id = a.user_id
                where s.status = 'DONE' and a.attended and extract(year from s.starts_on) = ?""")
                .param(year).query(Row.class).list();
        Map<String, ReportRow> byCategory = new LinkedHashMap<>();
        Map<String, ReportRow> byUnit = new LinkedHashMap<>();
        Map<String, ReportRow> byPerson = new LinkedHashMap<>();
        Map<String, Set<Long>> peopleIn = new java.util.HashMap<>();
        BigDecimal hours = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        Set<Long> women = new java.util.HashSet<>();
        for (Row r : rows) {
            BigDecimal c = convert(r.cost(), r.currency(), cur);
            hours = hours.add(r.hours());
            cost = cost.add(c);
            if ("FEMALE".equals(r.gender())) {
                women.add(r.userId());
            }
            add(byCategory, peopleIn, "c:" + Objects.toString(r.categoryCode(), "-"),
                    r.categoryName() == null ? Texts.t("No category") : r.categoryName(), r.userId(), r.hours(), c);
            add(byUnit, peopleIn, "u:" + Objects.toString(r.unitName(), "-"),
                    r.unitName() == null ? Texts.t("No unit") : r.unitName(), r.userId(), r.hours(), c);
            add(byPerson, peopleIn, "p:" + r.userId(), r.name() + " (" + r.registrationNo() + ")", r.userId(), r.hours(), c);
        }
        int staff = jdbc.sql("select count(*) from app_user where status = 'ACTIVE' and not not_employee and hire_date is not null")
                .query(Integer.class).single();
        int apprentices = jdbc.sql("""
                select count(distinct c.user_id) from employee_contract c
                where c.contract_type = 'APPRENTICE' and extract(year from c.start_date) <= ?
                  and (c.end_date is null or extract(year from c.end_date) >= ?)""").params(year, year).query(Integer.class).single();
        List<ReportRow> persons = new ArrayList<>(byPerson.values());
        persons.sort((a, b) -> b.hours().compareTo(a.hours()));
        return new Report(year, byPerson.size(), staff, hours, cost.setScale(2, RoundingMode.HALF_UP), cur, levy(year),
                List.copyOf(byCategory.values()), List.copyOf(byUnit.values()), persons, apprentices, women.size());
    }

    private static void add(Map<String, ReportRow> map, Map<String, Set<Long>> peopleIn, String key, String label, long userId,
                            BigDecimal hours, BigDecimal cost) {
        Set<Long> people = peopleIn.computeIfAbsent(key, k -> new java.util.HashSet<>());
        people.add(userId);
        ReportRow r = map.get(key);
        map.put(key, r == null ? new ReportRow(key, label, 1, 1, hours, cost.setScale(2, RoundingMode.HALF_UP))
                : new ReportRow(key, label, people.size(), r.sessions() + 1, r.hours().add(hours),
                        r.cost().add(cost).setScale(2, RoundingMode.HALF_UP)));
    }

    // ---- settings and reminders ----------------------------------------------------------------------------------

    public record Settings(String lead, int effectMonths, String apprenticeContributions, int workerEducationDays,
                           int workerEducationNoticeDays) {}

    public Settings settings() {
        return new Settings(setting("TRAINING_LEAD", ""), Integer.parseInt(setting("TRAINING_EFFECT_MONTHS", "3")),
                setting("APPRENTICE_CONTRIBUTIONS", "NONE"), Integer.parseInt(setting("WORKER_EDUCATION_DAYS", "12")),
                Integer.parseInt(setting("WORKER_EDUCATION_NOTICE_DAYS", "30")));
    }

    @Transactional
    public Settings saveSettings(Settings s) {
        requireHr();
        if (!Set.of("NONE", "SALARY").contains(s.apprenticeContributions())) {
            throw new BusinessRuleException("Unknown choice.");
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("TRAINING_LEAD", s.lead() == null ? "" : s.lead().trim());
        values.put("TRAINING_EFFECT_MONTHS", String.valueOf(s.effectMonths()));
        values.put("APPRENTICE_CONTRIBUTIONS", s.apprenticeContributions());
        values.put("WORKER_EDUCATION_DAYS", String.valueOf(s.workerEducationDays()));
        values.put("WORKER_EDUCATION_NOTICE_DAYS", String.valueOf(s.workerEducationNoticeDays()));
        values.forEach((k, v) -> jdbc.sql("""
                insert into payroll_setting (key, value) values (?, ?)
                on conflict (company_id, key) do update set value = excluded.value""").params(k, v).update());
        audit.record("training.settings", "company", null, Map.copyOf(values));
        return settings();
    }

    /** Asks each manager, once, whether the training of their team changed their work. */
    @Transactional
    public int remind() {
        LocalDate today = today();
        record Due(long id, long userId, String name, String courseTitle) {}
        List<Due> due = jdbc.sql("""
                select a.id, a.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, c.title as course_title
                from training_attendee a join training_session s on s.id = a.session_id join training_course c on c.id = s.course_id
                join app_user u on u.id = a.user_id
                where a.attended and a.effect is null and a.effect_asked_on is null and a.effect_due_on <= ? and u.status = 'ACTIVE'""")
                .param(today).query(Due.class).list();
        for (Due d : due) {
            java.util.function.Supplier<String> title = () -> Texts.t("Did the training help %s?", d.name());
            java.util.function.Supplier<String> body = () -> Texts.t("%s attended %s. Say whether it changed their work.",
                    d.name(), d.courseTitle());
            org.managerOf(d.userId()).ifPresentOrElse(
                    b -> notifications.send(b.userId(), "training.effect", title, body, "/training?tab=effects"),
                    () -> notifications.sendToHolders("training.manage", "training.effect", title, body, "/training?tab=effects"));
            jdbc.sql("update training_attendee set effect_asked_on = ? where id = ?").params(today, d.id()).update();
        }
        return due.size();
    }
}
