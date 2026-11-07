package cd.hrpay.evaluation;

import cd.hrpay.attendance.AttendanceDays;
import cd.hrpay.attendance.DayCalculator;
import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evaluation criteria and templates, and the evaluation of one employee for one period: the direct manager scores
 * (DRAFT, then SUBMITTED), the employee acknowledges or contests, HR approves. A contested evaluation goes back to
 * the manager, who may revise and resubmit; HR makes the final decision.
 */
@Service
public class Evaluations {

    public record Criterion(long id, String code, String name, String description, String source, boolean active) {}

    public record TemplateCriterion(long criterionId, String code, String name, String source, BigDecimal weight) {}

    public record Template(long id, String name, String frequency, Long gradeCategoryId, boolean active,
                           List<TemplateCriterion> criteria, Long orgUnitId) {}

    public record Score(long criterionId, String code, String name, String source, BigDecimal weight,
                        BigDecimal score, String comment) {}

    public record Evaluation(long id, long userId, String userName, long templateId, String templateName,
                             LocalDate periodStart, LocalDate periodEnd, long evaluatorId, String evaluatorName,
                             String status, BigDecimal totalPercent, String managerComment, String employeeComment,
                             String hrComment, OffsetDateTime submittedAt, OffsetDateTime answeredAt,
                             String approvedBy, OffsetDateTime approvedAt, List<Score> scores) {}

    public record ScoreInput(long criterionId, BigDecimal score, String comment) {}

    private static final String EVAL_SELECT = """
            select e.id, e.user_id, u.first_name || ' ' || u.last_name as user_name, e.template_id, t.name as template_name,
                   e.period_start, e.period_end, e.evaluator_id, v.first_name || ' ' || v.last_name as evaluator_name,
                   e.status, e.total_percent, e.manager_comment, e.employee_comment, e.hr_comment, e.submitted_at,
                   e.answered_at, e.approved_by, e.approved_at, null as scores
            from evaluation e join app_user u on u.id = e.user_id join app_user v on v.id = e.evaluator_id
            join evaluation_template t on t.id = e.template_id
            """;

    private static final ZoneId ZONE = ZoneId.of("Africa/Kinshasa");

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final AttendanceDays attendance;
    private final AuditService audit;
    private final Clock clock;
    private cd.hrpay.org.OrgStructure org;

    @org.springframework.beans.factory.annotation.Autowired
    void setOrg(cd.hrpay.org.OrgStructure org) {
        this.org = org;
    }

    public Evaluations(JdbcClient jdbc, UserRepository users, AttendanceDays attendance, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.attendance = attendance;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- criteria and templates ------------------------------------------------------------------------------

    public List<Criterion> criteria() {
        return jdbc.sql("select id, code, name, description, source, active from evaluation_criterion order by name")
                .query(Criterion.class).list();
    }

    @Transactional
    public Criterion saveCriterion(Long id, String code, String name, String description, String source, boolean active) {
        if (id == null) {
            if (jdbc.sql("select count(*) from evaluation_criterion where upper(code) = upper(?)").param(code)
                    .query(Integer.class).single() > 0) {
                throw new ConflictException("The criterion code " + code + " is already used.");
            }
            id = jdbc.sql("""
                    insert into evaluation_criterion (code, name, description, source, active) values (?, ?, ?, ?, ?)
                    returning id""").params(code.toUpperCase(), name, description, source, active).query(Long.class).single();
        } else if (jdbc.sql("update evaluation_criterion set name = ?, description = ?, source = ?, active = ? where id = ?")
                .params(name, description, source, active, id).update() == 0) {
            throw new NotFoundException("Criterion not found.");
        }
        audit.record("evaluation_criterion.saved", "evaluation_criterion", id, Map.of("code", code, "source", source));
        long saved = id;
        return criteria().stream().filter(c -> c.id() == saved).findFirst().orElseThrow();
    }

    public List<Template> templates() {
        List<Template> heads = jdbc.sql("""
                select id, name, frequency, grade_category_id, active, org_unit_id from evaluation_template
                order by org_unit_id nulls first, grade_category_id nulls first, name""")
                .query((rs, i) -> new Template(rs.getLong("id"), rs.getString("name"), rs.getString("frequency"),
                        (Long) rs.getObject("grade_category_id", Long.class), rs.getBoolean("active"), List.of(),
                        (Long) rs.getObject("org_unit_id", Long.class)))
                .list();
        return heads.stream().map(t -> new Template(t.id(), t.name(), t.frequency(), t.gradeCategoryId(), t.active(),
                jdbc.sql("""
                        select c.id as criterion_id, c.code, c.name, c.source, tc.weight
                        from evaluation_template_criterion tc join evaluation_criterion c on c.id = tc.criterion_id
                        where tc.template_id = ? order by tc.weight desc, c.name""").param(t.id())
                        .query(TemplateCriterion.class).list(), t.orgUnitId())).toList();
    }

    /** Creates or replaces a template; the weights must add up to 100. Running evaluations keep their weights. */
    @Transactional
    public Template saveTemplate(Long id, String name, String frequency, Long gradeCategoryId, Long orgUnitId,
                                 boolean active, Map<Long, BigDecimal> weights) {
        BigDecimal sum = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (weights.isEmpty() || sum.compareTo(BigDecimal.valueOf(100)) != 0) {
            throw new BusinessRuleException("The weights must add up to 100% (now " + sum.stripTrailingZeros().toPlainString() + "%).");
        }
        if (active && jdbc.sql("""
                select count(*) from evaluation_template where active and id <> coalesce(?, -1)
                  and grade_category_id is not distinct from ? and org_unit_id is not distinct from ?""")
                .params(id, gradeCategoryId, orgUnitId).query(Integer.class).single() > 0) {
            throw new ConflictException(gradeCategoryId == null && orgUnitId == null
                    ? "Another active template already applies to everyone. Deactivate it first."
                    : "Another active template already applies to the same people. Deactivate it first.");
        }
        if (id == null) {
            id = jdbc.sql("insert into evaluation_template (name, frequency, grade_category_id, org_unit_id, active) values (?, ?, ?, ?, ?) returning id")
                    .params(name, frequency, gradeCategoryId, orgUnitId, active).query(Long.class).single();
        } else {
            if (jdbc.sql("update evaluation_template set name = ?, frequency = ?, grade_category_id = ?, org_unit_id = ?, active = ? where id = ?")
                    .params(name, frequency, gradeCategoryId, orgUnitId, active, id).update() == 0) {
                throw new NotFoundException("Template not found.");
            }
            jdbc.sql("delete from evaluation_template_criterion where template_id = ?").param(id).update();
        }
        for (var w : weights.entrySet()) {
            jdbc.sql("insert into evaluation_template_criterion (template_id, criterion_id, weight) values (?, ?, ?)")
                    .params(id, w.getKey(), w.getValue()).update();
        }
        audit.record("evaluation_template.saved", "evaluation_template", id, Map.of("name", name, "frequency", frequency));
        long saved = id;
        return templates().stream().filter(t -> t.id() == saved).findFirst().orElseThrow();
    }

    /**
     * The template for an employee, most specific first: their unit's (or the nearest unit above with one), for their
     * grade category, then for anyone in that unit; then their grade category's; then the one for everyone.
     */
    public Template templateFor(AppUser user) {
        List<Template> active = templates().stream().filter(Template::active).toList();
        Long cat = user.getGradeCategoryId();
        Long unit = org.unitsOn(org.today()).get(user.getId());
        if (unit != null) {
            for (long u : org.ancestors(unit)) {
                Optional<Template> t = active.stream().filter(x -> Objects.equals(x.orgUnitId(), u))
                        .filter(x -> x.gradeCategoryId() != null && x.gradeCategoryId().equals(cat)).findFirst()
                        .or(() -> active.stream().filter(x -> Objects.equals(x.orgUnitId(), u) && x.gradeCategoryId() == null).findFirst());
                if (t.isPresent()) {
                    return t.get();
                }
            }
        }
        return active.stream().filter(t -> t.orgUnitId() == null && t.gradeCategoryId() != null && t.gradeCategoryId().equals(cat))
                .findFirst()
                .or(() -> active.stream().filter(t -> t.orgUnitId() == null && t.gradeCategoryId() == null).findFirst())
                .orElseThrow(() -> new BusinessRuleException("No active evaluation template applies to this employee."));
    }

    /** The period of the given frequency that contains the date. */
    static LocalDate[] period(String frequency, LocalDate date) {
        int months = switch (frequency) {
            case "MONTHLY" -> 1;
            case "QUARTERLY" -> 3;
            case "SEMI_ANNUAL" -> 6;
            default -> throw new IllegalArgumentException(frequency);
        };
        int startMonth = (date.getMonthValue() - 1) / months * months + 1;
        LocalDate start = LocalDate.of(date.getYear(), startMonth, 1);
        return new LocalDate[] {start, YearMonth.from(start).plusMonths(months - 1).atEndOfMonth()};
    }

    // ---- evaluations -----------------------------------------------------------------------------------------

    public Evaluation get(long id) {
        Evaluation e = head(id);
        requireCanSee(e);
        return withScores(e);
    }

    private Evaluation head(long id) {
        return jdbc.sql(EVAL_SELECT + "where e.id = ?").param(id).query(Evaluation.class).optional()
                .orElseThrow(() -> new NotFoundException("Evaluation not found."));
    }

    private Evaluation withScores(Evaluation e) {
        List<Score> scores = jdbc.sql("""
                select s.criterion_id, c.code, c.name, c.source, s.weight, s.score, s.comment
                from evaluation_score s join evaluation_criterion c on c.id = s.criterion_id
                where s.evaluation_id = ? order by s.weight desc, c.name""").param(e.id()).query(Score.class).list();
        return new Evaluation(e.id(), e.userId(), e.userName(), e.templateId(), e.templateName(), e.periodStart(),
                e.periodEnd(), e.evaluatorId(), e.evaluatorName(), e.status(), e.totalPercent(), e.managerComment(),
                e.employeeComment(), e.hrComment(), e.submittedAt(), e.answeredAt(), e.approvedBy(), e.approvedAt(), scores);
    }

    /** Evaluations the current user may see, newest first: their own (once submitted), their team's, or all for HR. */
    public List<Evaluation> list(String scope, String status) {
        long me = CurrentUser.id();
        String where = switch (scope) {
            case "mine" -> "where e.user_id = " + me + " and e.status <> 'DRAFT'";
            case "team" -> "where e.user_id <> " + me;
            case "all" -> {
                if (!isHr()) {
                    throw new AccessDeniedException("Only HR sees every evaluation.");
                }
                yield "where true";
            }
            default -> throw new BusinessRuleException("Unknown list " + scope + ".");
        };
        List<Evaluation> list = jdbc.sql(EVAL_SELECT + where + " and (cast(? as varchar) is null or e.status = ?) order by e.period_start desc, u.last_name")
                .params(status, status).query(Evaluation.class).list();
        if (scope.equals("team")) {
            java.util.Set<Long> team = org.teamOf(me);
            return list.stream().filter(e -> e.evaluatorId() == me || team.contains(e.userId())).toList();
        }
        return list;
    }

    /** Starts the evaluation of an employee for the period containing the date. */
    @Transactional
    public Evaluation start(long userId, LocalDate inPeriod) {
        AppUser user = users.findById(userId).orElseThrow(() -> new NotFoundException("Employee not found."));
        long me = CurrentUser.id();
        if (userId == me) {
            throw new AccessDeniedException("You cannot evaluate yourself.");
        }
        if (!isHr() && !(CurrentUser.has("evaluation.score.team") && org.inTeam(me, userId))) {
            throw new AccessDeniedException("Only the employee's manager or HR can evaluate them.");
        }
        Template t = templateFor(user);
        LocalDate[] p = period(t.frequency(), inPeriod);
        if (p[0].isAfter(LocalDate.now(clock.withZone(ZONE)))) {
            throw new BusinessRuleException("This period has not started yet.");
        }
        if (jdbc.sql("select count(*) from evaluation where user_id = ? and period_start = ?").params(userId, p[0])
                .query(Integer.class).single() > 0) {
            throw new ConflictException(user.getFirstName() + " already has an evaluation for the period starting " + p[0] + ".");
        }
        long id = jdbc.sql("""
                insert into evaluation (user_id, template_id, period_start, period_end, evaluator_id) values (?, ?, ?, ?, ?)
                returning id""").params(userId, t.id(), p[0], p[1], me).query(Long.class).single();
        for (TemplateCriterion c : t.criteria()) {
            jdbc.sql("insert into evaluation_score (evaluation_id, criterion_id, weight) values (?, ?, ?)")
                    .params(id, c.criterionId(), c.weight()).update();
        }
        fillAttendanceScores(id, user, p[0], p[1]);
        audit.record("evaluation.started", "user", userId, Map.of("evaluation", id, "period", p[0] + ".." + p[1]));
        return withScores(head(id));
    }

    /** Punctuality from attendance: 100 less 5 points per late day and 10 per absent day, never below 0. */
    private void fillAttendanceScores(long evaluationId, AppUser user, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        LocalDate until = to.isAfter(today) ? today : to;
        var days = attendance.days(user, from, until);
        long late = days.stream().filter(d -> d.lateMinutes() > 0).count();
        long absent = days.stream().filter(d -> d.status() == DayCalculator.Status.ABSENT).count();
        BigDecimal score = BigDecimal.valueOf(Math.max(0, 100 - 5 * late - 10 * absent));
        String comment = late + " late day(s), " + absent + " absent day(s) from " + from + " to " + until;
        jdbc.sql("""
                update evaluation_score s set score = ?, comment = ? from evaluation_criterion c
                where s.criterion_id = c.id and c.source = 'ATTENDANCE' and s.evaluation_id = ?""")
                .params(score, comment, evaluationId).update();
    }

    /** The evaluator (or HR) saves scores; with submit, every manager score is required and the employee is asked. */
    @Transactional
    public Evaluation saveScores(long id, List<ScoreInput> scores, String managerComment, boolean submit) {
        Evaluation e = head(id);
        if (!Objects.equals(e.evaluatorId(), CurrentUser.id()) && !isHr()) {
            throw new AccessDeniedException("Only the evaluator or HR can change the scores.");
        }
        if (Objects.equals(e.userId(), CurrentUser.id())) {
            throw new AccessDeniedException("You cannot score yourself.");
        }
        if (!List.of("DRAFT", "SUBMITTED", "CONTESTED").contains(e.status())) {
            throw new BusinessRuleException("This evaluation is " + e.status().toLowerCase() + " and can no longer be changed.");
        }
        Map<Long, Score> current = new LinkedHashMap<>();
        withScores(e).scores().forEach(s -> current.put(s.criterionId(), s));
        for (ScoreInput s : scores) {
            Score c = current.get(s.criterionId());
            if (c == null) {
                throw new BusinessRuleException("This criterion is not part of the evaluation.");
            }
            if (c.source().equals("ATTENDANCE")) {
                continue;   // computed from attendance
            }
            jdbc.sql("update evaluation_score set score = ?, comment = ? where evaluation_id = ? and criterion_id = ?")
                    .params(s.score(), s.comment(), id, s.criterionId()).update();
        }
        jdbc.sql("update evaluation set manager_comment = ? where id = ?").params(managerComment, id).update();
        if (submit) {
            AppUser user = users.findById(e.userId()).orElseThrow();
            fillAttendanceScores(id, user, e.periodStart(), e.periodEnd());
            List<Score> all = withScores(head(id)).scores();
            if (all.stream().anyMatch(s -> s.score() == null)) {
                throw new BusinessRuleException("Score every criterion before submitting.");
            }
            BigDecimal total = all.stream().map(s -> s.score().multiply(s.weight()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            jdbc.sql("""
                    update evaluation set status = 'SUBMITTED', total_percent = ?, submitted_at = now(),
                           employee_comment = case when status = 'CONTESTED' then employee_comment else null end
                    where id = ?""").params(total, id).update();
            audit.record("evaluation.submitted", "user", e.userId(), Map.of("evaluation", id, "total", total.toPlainString()));
        }
        return withScores(head(id));
    }

    /** The employee acknowledges, or contests with a comment. */
    @Transactional
    public Evaluation answer(long id, boolean acknowledge, String comment) {
        Evaluation e = head(id);
        if (!Objects.equals(e.userId(), CurrentUser.id())) {
            throw new AccessDeniedException("Only the evaluated employee can answer.");
        }
        if (!e.status().equals("SUBMITTED")) {
            throw new BusinessRuleException("There is nothing to answer on this evaluation.");
        }
        if (!acknowledge && (comment == null || comment.isBlank())) {
            throw new BusinessRuleException("Say what you disagree with.");
        }
        jdbc.sql("update evaluation set status = ?, employee_comment = ?, answered_at = now() where id = ?")
                .params(acknowledge ? "ACKNOWLEDGED" : "CONTESTED", comment, id).update();
        audit.record(acknowledge ? "evaluation.acknowledged" : "evaluation.contested", "user", e.userId(),
                Map.of("evaluation", id));
        return withScores(head(id));
    }

    /** HR's final decision. A contested evaluation needs HR's comment. */
    @Transactional
    public Evaluation approve(long id, String hrComment) {
        Evaluation e = head(id);
        if (Objects.equals(e.userId(), CurrentUser.id())) {
            throw new AccessDeniedException("You cannot approve your own evaluation.");
        }
        if (!List.of("SUBMITTED", "ACKNOWLEDGED", "CONTESTED").contains(e.status())) {
            throw new BusinessRuleException("Only a submitted evaluation can be approved.");
        }
        if (e.status().equals("CONTESTED") && (hrComment == null || hrComment.isBlank())) {
            throw new BusinessRuleException("The employee contested this evaluation: explain HR's decision.");
        }
        jdbc.sql("update evaluation set status = 'APPROVED', hr_comment = ?, approved_by = ?, approved_at = now() where id = ?")
                .params(hrComment, CurrentUser.email(), id).update();
        audit.record("evaluation.approved", "user", e.userId(), Map.of("evaluation", id,
                "total", e.totalPercent() == null ? "" : e.totalPercent().toPlainString(), "wasContested", e.status().equals("CONTESTED")));
        return withScores(head(id));
    }

    /** Employees the current user can evaluate now, with the period and whether it is already started. */
    public List<Map<String, Object>> toEvaluate(LocalDate on) {
        long me = CurrentUser.id();
        List<AppUser> people = isHr()
                ? users.findAll().stream().filter(AppUser::isActive).filter(u -> u.getId() != me).toList()
                : CurrentUser.has("evaluation.score.team") ? users.findAllById(org.teamOf(me)).stream().filter(AppUser::isActive).toList()
                : List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AppUser u : people) {
            if (jdbc.sql("select count(*) from employee_contract where user_id = ?").param(u.getId()).query(Integer.class).single() == 0) {
                continue;   // not on payroll (for example the administrator)
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", u.getId());
            row.put("name", u.getFirstName() + " " + u.getLastName());
            try {
                Template t = templateFor(u);
                LocalDate[] p = period(t.frequency(), on);
                row.put("template", t.name());
                row.put("periodStart", p[0]);
                row.put("periodEnd", p[1]);
                row.put("evaluationId", jdbc.sql("select id from evaluation where user_id = ? and period_start = ?")
                        .params(u.getId(), p[0]).query(Long.class).optional().orElse(null));
            } catch (BusinessRuleException ex) {
                row.put("problem", ex.getMessage());
            }
            rows.add(row);
        }
        rows.sort(java.util.Comparator.comparing(r -> (String) r.get("name")));
        return rows;
    }

    private void requireCanSee(Evaluation e) {
        long me = CurrentUser.id();
        boolean own = e.userId() == me && !e.status().equals("DRAFT");
        boolean evaluator = e.evaluatorId() == me;
        boolean manager = org.inTeam(me, e.userId());
        if (!own && !evaluator && !manager && !isHr()) {
            throw new AccessDeniedException("You cannot see this evaluation.");
        }
    }

    private static boolean isHr() {
        return CurrentUser.has("evaluation.manage") || CurrentUser.has("evaluation.approve");
    }
}
