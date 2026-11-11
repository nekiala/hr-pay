package cd.hrpay.position;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The job catalogue: the occupations with their description (fiche de poste), kept in versions, the job families, and
 * the career ladders between jobs with who meets their conditions.
 *
 * <p>DRC Code du travail art. 43: the trial of an unskilled labourer is one month at most, so an unskilled job refuses
 * a longer trial. Ordonnance 67-442bis annexe II puts every job in a legal category; the grade range of a job gives it.
 */
@Component
public class JobCatalogue {

    private static final ZoneId ZONE = ZoneId.of("Africa/Kinshasa");

    public record Family(long id, String code, String name, boolean active, int jobs) {}

    public record Job(long id, String code, String name, boolean active, Long familyId, String familyName, String purpose,
                      String duties, String education, String experience, String skills, String conditions,
                      Long minGradeId, String minGrade, Long maxGradeId, String maxGrade, String category,
                      Long reportsToId, String reportsTo, boolean unskilled, int version, String updatedBy,
                      OffsetDateTime updatedAt, int holders) {}

    public record Input(String code, String name, boolean active, Long familyId, String purpose, String duties,
                        String education, String experience, String skills, String conditions, Long minGradeId,
                        Long maxGradeId, Long reportsToId, boolean unskilled) {}

    public record Version(long id, long occupationId, int version, String name, String purpose, String duties,
                          String education, String experience, String skills, String conditions, String minGrade,
                          String maxGrade, boolean unskilled, String savedBy, OffsetDateTime savedAt) {}

    public record Path(long id, long fromId, String fromName, long toId, String toName, BigDecimal minYears,
                       BigDecimal minScore, String requirement) {}

    /** One next job for a person, with the conditions met or not. */
    public record Step(long pathId, long toId, String toName, BigDecimal minYears, BigDecimal years, boolean yearsMet,
                       BigDecimal minScore, BigDecimal score, boolean scoreMet, String requirement, boolean eligible,
                       int freePosts) {}

    public record NextSteps(Long occupationId, String occupationName, LocalDate inJobSince, BigDecimal years,
                            BigDecimal score, int evaluations, List<Step> steps) {}

    public record Ready(long userId, String name, String registrationNo, String unit, String occupation, BigDecimal years,
                        BigDecimal score, long toId, String toName, String requirement, int freePosts) {}

    /** A legal point on a contract, like {@code ContractTerms.Issue}. */
    public record Issue(String level, String code, String message) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final Clock clock;
    private Positions positions;

    public JobCatalogue(JdbcClient jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setPositions(@org.springframework.context.annotation.Lazy Positions positions) {
        this.positions = positions;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    // ---- families ------------------------------------------------------------------------------------------------

    public List<Family> families() {
        return jdbc.sql("""
                select f.id, f.code, f.name, f.active, (select count(*) from occupation o where o.family_id = f.id) as jobs
                from job_family f order by f.name""").query(Family.class).list();
    }

    @Transactional
    public Family saveFamily(Long id, String code, String name, boolean active) {
        code = code.trim().toUpperCase();
        name = name.trim();
        if (jdbc.sql("select count(*) from job_family where upper(code) = ? and id <> coalesce(?, 0)").params(code, id)
                .query(Integer.class).single() > 0) {
            throw new ConflictException(Texts.t("The job family code %s is already used.", code));
        }
        if (id == null) {
            id = jdbc.sql("insert into job_family (code, name, active) values (?, ?, ?) returning id").params(code, name, active)
                    .query(Long.class).single();
        } else if (jdbc.sql("update job_family set code = ?, name = ?, active = ? where id = ?").params(code, name, active, id)
                .update() == 0) {
            throw new NotFoundException("Job family not found.");
        }
        audit.record("job.family_saved", "job_family", id, Map.of("code", code));
        long saved = id;
        return families().stream().filter(f -> f.id() == saved).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteFamily(long id) {
        jdbc.sql("update occupation set family_id = null where family_id = ?").param(id).update();
        if (jdbc.sql("delete from job_family where id = ?").param(id).update() == 0) {
            throw new NotFoundException("Job family not found.");
        }
        audit.record("job.family_deleted", "job_family", id, Map.of());
    }

    // ---- jobs ----------------------------------------------------------------------------------------------------

    private static final String SELECT = """
            select o.id, o.code, o.name, o.active, o.family_id, f.name as family_name, o.purpose, o.duties, o.education,
                   o.experience, o.skills, o.conditions, o.min_grade_id, mn.code || ' · ' || mn.name as min_grade,
                   o.max_grade_id, mx.code || ' · ' || mx.name as max_grade,
                   case when cn.id is null and cx.id is null then null
                        when cx.id is null or cn.id = cx.id then coalesce(cn.name, cx.name)
                        when cn.id is null then cx.name
                        else cn.name || ' – ' || cx.name end as category,
                   o.reports_to_id, b.name as reports_to, o.unskilled, o.version, o.updated_by, o.updated_at,
                   (select count(*) from employee_contract c join app_user u on u.id = c.user_id
                     where c.occupation_id = o.id and not u.not_employee and c.start_date <= :today
                       and (c.end_date is null or c.end_date >= :today)
                       and c.start_date = (select max(c2.start_date) from employee_contract c2
                                           where c2.user_id = c.user_id and c2.start_date <= :today)) as holders
            from occupation o
            left join job_family f on f.id = o.family_id
            left join grade mn on mn.id = o.min_grade_id left join grade_category cn on cn.id = mn.category_id
            left join grade mx on mx.id = o.max_grade_id left join grade_category cx on cx.id = mx.category_id
            left join occupation b on b.id = o.reports_to_id
            """;

    public List<Job> jobs() {
        return jdbc.sql(SELECT + " order by o.name").param("today", today()).query(Job.class).list();
    }

    public Job job(long id) {
        return jdbc.sql(SELECT + " where o.id = :id").param("today", today()).param("id", id).query(Job.class).optional()
                .orElseThrow(() -> new NotFoundException("Job not found."));
    }

    /** Creates a job (an occupation) or saves its description; each save is a new version. */
    @Transactional
    public Job save(Long id, Input in) {
        String code = in.code() == null ? null : in.code().trim().toUpperCase();
        String name = clean(in.name());
        if (code == null || code.isEmpty() || name == null) {
            throw new BusinessRuleException("Give the job's code and name.");
        }
        if (jdbc.sql("select count(*) from occupation where upper(code) = ? and id <> coalesce(?, 0)").params(code, id)
                .query(Integer.class).single() > 0) {
            throw new ConflictException(Texts.t("The occupation code %s is already used.", code));
        }
        if (in.minGradeId() != null && in.maxGradeId() != null) {
            BigDecimal[] bases = jdbc.sql("select (select base_salary from grade where id = ?), (select base_salary from grade where id = ?)")
                    .params(in.minGradeId(), in.maxGradeId())
                    .query((rs, i) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)}).single();
            if (bases[0] != null && bases[1] != null && bases[0].compareTo(bases[1]) > 0) {
                throw new BusinessRuleException("The lowest grade of the job pays more than its highest grade: swap them.");
            }
        }
        if (in.reportsToId() != null) {
            if (in.reportsToId().equals(id)) {
                throw new BusinessRuleException("A job cannot report to itself.");
            }
            Set<Long> seen = new HashSet<>();
            Long up = in.reportsToId();
            while (up != null && seen.add(up)) {
                if (up.equals(id)) {
                    throw new BusinessRuleException("This would make the jobs report to each other in a circle.");
                }
                up = jdbc.sql("select reports_to_id from occupation where id = ?").param(up).query(Long.class).optional().orElse(null);
            }
        }
        boolean described = clean(in.purpose()) != null || clean(in.duties()) != null;
        if (id == null) {
            id = jdbc.sql("""
                    insert into occupation (code, name, active, family_id, purpose, duties, education, experience, skills,
                                            conditions, min_grade_id, max_grade_id, reports_to_id, unskilled, version,
                                            updated_by, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()) returning id""")
                    .params(code, name, in.active(), in.familyId(), clean(in.purpose()), clean(in.duties()),
                            clean(in.education()), clean(in.experience()), clean(in.skills()), clean(in.conditions()),
                            in.minGradeId(), in.maxGradeId(), in.reportsToId(), in.unskilled(), described ? 1 : 0,
                            CurrentUser.email())
                    .query(Long.class).single();
        } else {
            Job before = job(id);
            boolean changed = described || before.version() > 0;
            jdbc.sql("""
                    update occupation set code = ?, name = ?, active = ?, family_id = ?, purpose = ?, duties = ?,
                           education = ?, experience = ?, skills = ?, conditions = ?, min_grade_id = ?, max_grade_id = ?,
                           reports_to_id = ?, unskilled = ?, version = version + ?, updated_by = ?, updated_at = now()
                    where id = ?""")
                    .params(code, name, in.active(), in.familyId(), clean(in.purpose()), clean(in.duties()),
                            clean(in.education()), clean(in.experience()), clean(in.skills()), clean(in.conditions()),
                            in.minGradeId(), in.maxGradeId(), in.reportsToId(), in.unskilled(), changed ? 1 : 0,
                            CurrentUser.email(), id)
                    .update();
        }
        Job saved = job(id);
        if (saved.version() > 0) {
            jdbc.sql("""
                    insert into occupation_version (occupation_id, version, name, purpose, duties, education, experience,
                                                    skills, conditions, min_grade, max_grade, unskilled, saved_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (occupation_id, version) do nothing""")
                    .params(id, saved.version(), saved.name(), saved.purpose(), saved.duties(), saved.education(),
                            saved.experience(), saved.skills(), saved.conditions(), saved.minGrade(), saved.maxGrade(),
                            saved.unskilled(), CurrentUser.email())
                    .update();
        }
        audit.record("job.saved", "occupation", id, Map.of("code", code, "version", saved.version()));
        return saved;
    }

    public List<Version> versions(long occupationId) {
        return jdbc.sql("""
                select id, occupation_id, version, name, purpose, duties, education, experience, skills, conditions,
                       min_grade, max_grade, unskilled, saved_by, saved_at
                from occupation_version where occupation_id = ? order by version desc""")
                .param(occupationId).query(Version.class).list();
    }

    /** The description a contract was signed with, or the current one when the contract has none. */
    public Optional<Version> version(long occupationId, Integer version) {
        String sql = """
                select id, occupation_id, version, name, purpose, duties, education, experience, skills, conditions,
                       min_grade, max_grade, unskilled, saved_by, saved_at
                from occupation_version where occupation_id = ? and version = """;
        if (version != null) {
            Optional<Version> v = jdbc.sql(sql + "?").params(occupationId, version).query(Version.class).optional();
            if (v.isPresent()) {
                return v;
            }
        }
        return jdbc.sql(sql + "(select version from occupation where id = ?)").params(occupationId, occupationId)
                .query(Version.class).optional();
    }

    /** The job of a person's contract in force today, with the version it was signed with. */
    public Optional<long[]> jobOf(long userId) {
        return jdbc.sql("""
                select occupation_id, coalesce(occupation_version, 0) from employee_contract
                where user_id = ? and start_date <= ? and occupation_id is not null order by start_date desc limit 1""")
                .params(userId, today()).query((rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}).optional();
    }

    // ---- checks on a contract ------------------------------------------------------------------------------------

    /** The grade range and the trial of an unskilled job (call inside Texts.in). */
    public List<Issue> contractIssues(Long occupationId, long gradeId, int trialMonths) {
        List<Issue> out = new ArrayList<>();
        if (occupationId == null) {
            return out;
        }
        Job j = job(occupationId);
        if (j.unskilled() && trialMonths > 1) {
            out.add(new Issue("BLOCK", "TRIAL_UNSKILLED", Texts.t("%s is an unskilled job: the trial is one month at most "
                    + "(Labour Code art. 43).", j.name())));
        }
        gradeOutside(occupationId, gradeId).ifPresent(m -> out.add(new Issue("WARN", "GRADE_RANGE", m)));
        return out;
    }

    /** A warning when a grade falls outside the grade range of a job. */
    public Optional<String> gradeOutside(Long occupationId, long gradeId) {
        if (occupationId == null) {
            return Optional.empty();
        }
        return jdbc.sql("""
                select o.name, g.code, mn.code, mx.code,
                       (mn.id is not null and g.base_salary < mn.base_salary) or (mx.id is not null and g.base_salary > mx.base_salary)
                from occupation o join grade g on g.id = ?
                left join grade mn on mn.id = o.min_grade_id left join grade mx on mx.id = o.max_grade_id
                where o.id = ?""")
                .params(gradeId, occupationId)
                .query((rs, i) -> rs.getBoolean(5) ? Texts.t("Grade %s is outside the range of the job %s (%s to %s).",
                        rs.getString(2), rs.getString(1), rs.getString(3) == null ? "—" : rs.getString(3),
                        rs.getString(4) == null ? "—" : rs.getString(4)) : null)
                .optional().filter(m -> m != null);
    }

    /** Refuses a trial over one month for an unskilled job. */
    public void requireTrialAllowed(Long occupationId, int trialMonths) {
        if (occupationId == null || trialMonths <= 1) {
            return;
        }
        Boolean unskilled = jdbc.sql("select unskilled from occupation where id = ?").param(occupationId).query(Boolean.class)
                .optional().orElse(false);
        if (unskilled) {
            throw new BusinessRuleException("The trial of an unskilled labourer is one month at most (Labour Code art. 43).");
        }
    }

    // ---- career ladders ------------------------------------------------------------------------------------------

    public List<Path> paths() {
        return jdbc.sql("""
                select p.id, p.from_occupation_id as from_id, f.name as from_name, p.to_occupation_id as to_id, t.name as to_name,
                       p.min_years, p.min_score, p.requirement
                from career_path p join occupation f on f.id = p.from_occupation_id join occupation t on t.id = p.to_occupation_id
                order by f.name, t.name""").query(Path.class).list();
    }

    @Transactional
    public Path savePath(Long id, long fromId, long toId, BigDecimal minYears, BigDecimal minScore, String requirement) {
        if (fromId == toId) {
            throw new BusinessRuleException("A job cannot lead to itself.");
        }
        BigDecimal years = minYears == null ? BigDecimal.ZERO : minYears;
        if (id == null) {
            if (jdbc.sql("select count(*) from career_path where from_occupation_id = ? and to_occupation_id = ?")
                    .params(fromId, toId).query(Integer.class).single() > 0) {
                throw new ConflictException("This step of the ladder already exists.");
            }
            id = jdbc.sql("""
                    insert into career_path (from_occupation_id, to_occupation_id, min_years, min_score, requirement)
                    values (?, ?, ?, ?, ?) returning id""")
                    .params(fromId, toId, years, minScore, clean(requirement)).query(Long.class).single();
        } else if (jdbc.sql("""
                update career_path set from_occupation_id = ?, to_occupation_id = ?, min_years = ?, min_score = ?, requirement = ?
                where id = ?""").params(fromId, toId, years, minScore, clean(requirement), id).update() == 0) {
            throw new NotFoundException("Step not found.");
        }
        audit.record("job.path_saved", "career_path", id, Map.of("from", fromId, "to", toId));
        long saved = id;
        return paths().stream().filter(p -> p.id() == saved).findFirst().orElseThrow();
    }

    @Transactional
    public void deletePath(long id) {
        if (jdbc.sql("delete from career_path where id = ?").param(id).update() == 0) {
            throw new NotFoundException("Step not found.");
        }
        audit.record("job.path_deleted", "career_path", id, Map.of());
    }

    private record Holder(long userId, String name, String registrationNo, Long unitId, String unit, long occupationId,
                          String occupation, LocalDate since) {}

    /** Who holds which job today, and since when they hold it without a break in the job. */
    private List<Holder> holders(Long onlyUser) {
        LocalDate today = today();
        Map<Long, List<Object[]>> contracts = new LinkedHashMap<>();
        jdbc.sql("""
                select c.user_id, c.start_date, c.end_date, c.occupation_id from employee_contract c
                join app_user u on u.id = c.user_id
                where not u.not_employee and c.start_date <= ? and (cast(? as bigint) is null or c.user_id = ?)
                order by c.user_id, c.start_date desc""")
                .params(today, onlyUser, onlyUser)
                .query(rs -> {
                    long occ = rs.getLong(4);
                    Long job = rs.wasNull() ? null : occ;
                    contracts.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new Object[] {
                            rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class), job});
                });
        Map<Long, Object[]> people = new HashMap<>();
        jdbc.sql("""
                select u.id, person_name(u.first_name, u.middle_name, u.last_name), u.registration_no,
                       a.unit_id, ou.name
                from app_user u
                left join org_assignment a on a.user_id = u.id and a.valid_to is null
                left join org_unit ou on ou.id = a.unit_id
                where not u.not_employee and u.status = 'ACTIVE' and (cast(? as bigint) is null or u.id = ?)""")
                .params(onlyUser, onlyUser)
                .query(rs -> {
                    long unit = rs.getLong(4);
                    Long unitId = rs.wasNull() ? null : unit;
                    people.put(rs.getLong(1), new Object[] {rs.getString(2), rs.getString(3), unitId, rs.getString(5)});
                });
        Map<Long, String> names = new HashMap<>();
        jdbc.sql("select id, name from occupation").query(rs -> {
            names.put(rs.getLong(1), rs.getString(2));
        });
        List<Holder> out = new ArrayList<>();
        for (Map.Entry<Long, List<Object[]>> e : contracts.entrySet()) {
            Object[] person = people.get(e.getKey());
            List<Object[]> list = e.getValue();
            Object[] current = list.get(0);
            LocalDate end = (LocalDate) current[1];
            Long occ = (Long) current[2];
            if (person == null || occ == null || (end != null && end.isBefore(today))) {
                continue;
            }
            LocalDate since = (LocalDate) current[0];
            for (Object[] c : list.subList(1, list.size())) {
                if (!occ.equals(c[2])) {
                    break;
                }
                since = (LocalDate) c[0];
            }
            out.add(new Holder(e.getKey(), (String) person[0], (String) person[1], (Long) person[2], (String) person[3],
                    occ, names.get(occ), since));
        }
        return out;
    }

    /** The average of the approved evaluations of the last two years, and how many there are. */
    private Map<Long, Object[]> scores(Long onlyUser) {
        Map<Long, Object[]> out = new HashMap<>();
        jdbc.sql("""
                select user_id, round(avg(total_percent), 1), count(*) from evaluation
                where status = 'APPROVED' and total_percent is not null and period_end >= ?
                  and (cast(? as bigint) is null or user_id = ?)
                group by user_id""")
                .params(today().minusYears(2), onlyUser, onlyUser)
                .query(rs -> {
                    out.put(rs.getLong(1), new Object[] {rs.getBigDecimal(2), rs.getInt(3)});
                });
        return out;
    }

    private Step step(Path p, LocalDate since, BigDecimal score, Map<Long, Integer> free) {
        BigDecimal years = years(since);
        boolean yearsMet = years.compareTo(p.minYears()) >= 0;
        boolean scoreMet = p.minScore() == null || (score != null && score.compareTo(p.minScore()) >= 0);
        return new Step(p.id(), p.toId(), p.toName(), p.minYears(), years, yearsMet, p.minScore(), score, scoreMet,
                p.requirement(), yearsMet && scoreMet, free.getOrDefault(p.toId(), 0));
    }

    private BigDecimal years(LocalDate since) {
        return since == null ? BigDecimal.ZERO
                : BigDecimal.valueOf(ChronoUnit.DAYS.between(since, today())).divide(BigDecimal.valueOf(365.25), 1, RoundingMode.DOWN);
    }

    /** The ladder from a person's job: the next jobs and which conditions are met. */
    public NextSteps nextSteps(long userId) {
        List<Holder> h = holders(userId);
        Object[] sc = scores(userId).get(userId);
        BigDecimal score = sc == null ? null : (BigDecimal) sc[0];
        int count = sc == null ? 0 : (Integer) sc[1];
        if (h.isEmpty()) {
            return new NextSteps(null, null, null, null, score, count, List.of());
        }
        Holder me = h.get(0);
        Map<Long, Integer> free = positions.freePostsByJob(today().getYear());
        List<Step> steps = paths().stream().filter(p -> p.fromId() == me.occupationId())
                .map(p -> step(p, me.since(), score, free)).toList();
        return new NextSteps(me.occupationId(), me.occupation(), me.since(), years(me.since()), score, count, steps);
    }

    /** Everyone who meets all the conditions of a next job, by unit then name. */
    public List<Ready> readyForPromotion() {
        List<Path> paths = paths();
        if (paths.isEmpty()) {
            return List.of();
        }
        Map<Long, Object[]> scores = scores(null);
        Map<Long, Integer> free = positions.freePostsByJob(today().getYear());
        List<Ready> out = new ArrayList<>();
        for (Holder h : holders(null)) {
            Object[] sc = scores.get(h.userId());
            BigDecimal score = sc == null ? null : (BigDecimal) sc[0];
            for (Path p : paths) {
                if (p.fromId() != h.occupationId()) {
                    continue;
                }
                Step s = step(p, h.since(), score, free);
                if (s.eligible()) {
                    out.add(new Ready(h.userId(), h.name(), h.registrationNo(), h.unit(), h.occupation(), s.years(), score,
                            p.toId(), p.toName(), p.requirement(), s.freePosts()));
                }
            }
        }
        out.sort(java.util.Comparator.comparing((Ready r) -> r.unit() == null ? "" : r.unit()).thenComparing(Ready::name));
        return out;
    }

    static String clean(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
