package cd.hrpay.skills;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.Notifications;
import cd.hrpay.org.OrgStructure;
import cd.hrpay.security.CurrentUser;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Skills: the catalogue with four levels, the skills each job needs, each person's levels (the employee rates
 * themselves, the manager confirms, HR may set a level directly), the gap matrix that feeds training needs, the
 * search for a skill across the company, and licences and certifications with their expiry.
 */
@Service
public class Skills {

    public static final Set<String> FAMILIES = Set.of("TECHNICAL", "BEHAVIOURAL", "LANGUAGE", "TOOL");
    static final String CERTIFICATION = "CERTIFICATION";

    public record Skill(long id, String family, String name, String description, String level1, String level2,
                        String level3, String level4, boolean active, int jobs, int people, int courses) {}

    public record SkillInput(String family, String name, String description, String level1, String level2,
                             String level3, String level4, boolean active) {}

    public record JobSkill(long skillId, String name, String family, int level) {}

    public record Job(long id, String name, String familyName, String freeText, int skills, int holders) {}

    public record JobView(long id, String name, String freeText, List<JobSkill> skills) {}

    public record Need(long skillId, int level) {}

    public record PersonSkill(long skillId, String name, String family, Integer required, Integer selfLevel,
                              String selfNote, OffsetDateTime selfAt, Integer level, String confirmedByName,
                              String confirmedNote, OffsetDateTime confirmedAt, String status, Integer gap,
                              String level1, String level2, String level3, String level4) {}

    public record Certification(long documentId, long userId, String name, String fileName, LocalDate issuedOn,
                                LocalDate expiresOn, String note, String state) {}

    public record Profile(long userId, String name, Long occupationId, String occupationName, String unitName,
                          boolean self, boolean canConfirm, List<PersonSkill> skills, List<Certification> certifications,
                          int gaps, int waiting) {}

    public record LevelInput(long skillId, Integer level, String note) {}

    public record Review(long userId, String name, String unitName, String occupationName, int waiting, OffsetDateTime since) {}

    public record Cell(long skillId, Integer required, Integer level, Integer selfLevel) {}

    public record Row(long userId, String name, String registrationNo, String unitName, String occupationName,
                      List<Cell> cells, int gaps) {}

    public record Matrix(List<JobSkill> skills, List<Row> rows, int gaps, int people) {}

    public record Gap(long userId, long skillId) {}

    public record Holder(long userId, String name, String registrationNo, String unitName, String occupationName,
                         int level, OffsetDateTime confirmedAt) {}

    public record CourseSkill(long courseId, long skillId) {}

    private static final ZoneId ZONE = ZoneId.of("Africa/Kinshasa");

    private final JdbcClient jdbc;
    private final OrgStructure org;
    private final Notifications notifications;
    private final AuditService audit;
    private final Clock clock;

    public Skills(JdbcClient jdbc, OrgStructure org, Notifications notifications, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.org = org;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    // ---- who may do what -------------------------------------------------------------------------------------

    static boolean isHr() {
        return CurrentUser.has("skills.manage");
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

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ---- catalogue ---------------------------------------------------------------------------------------------

    private static final String SKILL_SELECT = """
            select k.id, k.family, k.name, k.description, k.level1, k.level2, k.level3, k.level4, k.active,
                   (select count(*) from occupation_skill os where os.skill_id = k.id) as jobs,
                   (select count(*) from person_skill ps join app_user u on u.id = ps.user_id
                     where ps.skill_id = k.id and ps.level is not null and u.status = 'ACTIVE') as people,
                   (select count(*) from training_course_skill cs where cs.skill_id = k.id) as courses
            from skill k
            """;

    public List<Skill> skills(boolean all) {
        return jdbc.sql(SKILL_SELECT + (all ? "" : " where k.active") + """
                 order by k.active desc, case k.family when 'TECHNICAL' then 0 when 'BEHAVIOURAL' then 1 when 'LANGUAGE' then 2 else 3 end,
                 k.name""").query(Skill.class).list();
    }

    public Skill skill(long id) {
        return jdbc.sql(SKILL_SELECT + " where k.id = ?").param(id).query(Skill.class).optional()
                .orElseThrow(() -> new NotFoundException("Skill not found."));
    }

    @Transactional
    public Skill save(Long id, SkillInput in) {
        requireHr();
        if (!FAMILIES.contains(in.family())) {
            throw new BusinessRuleException("Unknown skill family.");
        }
        String name = in.name().trim();
        Long same = jdbc.sql("select id from skill where upper(name) = upper(?)").param(name).query(Long.class).optional().orElse(null);
        if (same != null && !same.equals(id)) {
            throw new BusinessRuleException("A skill with this name already exists.");
        }
        long saved;
        if (id == null) {
            saved = jdbc.sql("""
                    insert into skill (family, name, description, level1, level2, level3, level4, active)
                    values (?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(in.family(), name, clean(in.description()), clean(in.level1()), clean(in.level2()),
                            clean(in.level3()), clean(in.level4()), in.active())
                    .query(Long.class).single();
        } else {
            skill(id);
            jdbc.sql("""
                    update skill set family = ?, name = ?, description = ?, level1 = ?, level2 = ?, level3 = ?, level4 = ?,
                           active = ? where id = ?""")
                    .params(in.family(), name, clean(in.description()), clean(in.level1()), clean(in.level2()),
                            clean(in.level3()), clean(in.level4()), in.active(), id).update();
            saved = id;
        }
        audit.record(id == null ? "skill.created" : "skill.updated", "skill", saved, Map.of("name", name));
        return skill(saved);
    }

    // ---- skills per job ----------------------------------------------------------------------------------------

    public List<Job> jobs() {
        requireHr();
        return jdbc.sql("""
                select o.id, o.name, f.name as family_name, o.skills as free_text,
                       (select count(*) from occupation_skill os where os.occupation_id = o.id) as skills,
                       (select count(*) from (select distinct on (c.user_id) c.user_id, c.occupation_id
                                              from employee_contract c join app_user u on u.id = c.user_id
                                              where c.start_date <= ? and u.status = 'ACTIVE' and not u.not_employee
                                              order by c.user_id, c.start_date desc) cur
                         where cur.occupation_id = o.id) as holders
                from occupation o left join job_family f on f.id = o.family_id
                where o.active order by o.name""").param(today()).query(Job.class).list();
    }

    private List<JobSkill> jobSkills(long occupationId) {
        return jdbc.sql("""
                select k.id as skill_id, k.name, k.family, os.level from occupation_skill os join skill k on k.id = os.skill_id
                where os.occupation_id = ? order by os.level desc, k.name""").param(occupationId).query(JobSkill.class).list();
    }

    public JobView job(long occupationId) {
        requireHr();
        record Row(long id, String name, String skills) {}
        Row o = jdbc.sql("select id, name, skills from occupation where id = ?").param(occupationId).query(Row.class).optional()
                .orElseThrow(() -> new NotFoundException("Job not found."));
        return new JobView(o.id(), o.name(), o.skills(), jobSkills(occupationId));
    }

    /** Replaces the skills a job needs. */
    @Transactional
    public JobView setJob(long occupationId, List<Need> needs) {
        requireHr();
        job(occupationId);
        Set<Long> seen = new LinkedHashSet<>();
        for (Need n : needs) {
            if (n.level() < 1 || n.level() > 4) {
                throw new BusinessRuleException("A level goes from 1 to 4.");
            }
            if (!seen.add(n.skillId())) {
                throw new BusinessRuleException("Each skill only once.");
            }
            skill(n.skillId());
        }
        jdbc.sql("delete from occupation_skill where occupation_id = ?").param(occupationId).update();
        for (Need n : needs) {
            jdbc.sql("insert into occupation_skill (occupation_id, skill_id, level) values (?, ?, ?)")
                    .params(occupationId, n.skillId(), n.level()).update();
        }
        audit.record("skill.job_set", "occupation", occupationId, Map.of("skills", needs.size()));
        return job(occupationId);
    }

    // ---- courses that build a skill ----------------------------------------------------------------------------

    public List<CourseSkill> courseSkills() {
        return jdbc.sql("select course_id, skill_id from training_course_skill").query(CourseSkill.class).list();
    }

    @Transactional
    public List<CourseSkill> setCourse(long courseId, List<Long> skillIds) {
        if (!isHr() && !CurrentUser.has("training.manage")) {
            throw new AccessDeniedException("Only HR can do this.");
        }
        if (jdbc.sql("select count(*) from training_course where id = ?").param(courseId).query(Integer.class).single() == 0) {
            throw new NotFoundException("Course not found.");
        }
        jdbc.sql("delete from training_course_skill where course_id = ?").param(courseId).update();
        for (Long s : new LinkedHashSet<>(skillIds)) {
            skill(s);
            jdbc.sql("insert into training_course_skill (course_id, skill_id) values (?, ?)").params(courseId, s).update();
        }
        return courseSkills();
    }

    // ---- people ------------------------------------------------------------------------------------------------

    private record Current(long userId, Long occupationId, String occupationName) {}

    /** Each active person's current job, from their latest contract. */
    private Map<Long, Current> currentJobs() {
        Map<Long, Current> out = new HashMap<>();
        jdbc.sql("""
                select distinct on (c.user_id) c.user_id, c.occupation_id, o.name as occupation_name
                from employee_contract c left join occupation o on o.id = c.occupation_id
                where c.start_date <= ? order by c.user_id, c.start_date desc""")
                .param(today()).query(Current.class).list().forEach(c -> out.put(c.userId(), c));
        return out;
    }

    private Current currentJob(long userId) {
        return jdbc.sql("""
                select c.user_id, c.occupation_id, o.name as occupation_name
                from employee_contract c left join occupation o on o.id = c.occupation_id
                where c.user_id = ? and c.start_date <= ? order by c.start_date desc limit 1""")
                .params(userId, today()).query(Current.class).optional().orElse(new Current(userId, null, null));
    }

    private record Person(long id, String name, String unitName) {}

    private Person person(long userId) {
        return jdbc.sql("""
                select u.id, person_name(u.first_name, u.middle_name, u.last_name) as name,
                       (select ou.name from org_assignment a join org_unit ou on ou.id = a.unit_id
                        where a.user_id = u.id and a.valid_to is null order by a.valid_from desc limit 1) as unit_name
                from app_user u where u.id = ?""").param(userId).query(Person.class).optional()
                .orElseThrow(() -> new NotFoundException("User not found."));
    }

    /** A person's skills: what their job needs, their own rating, the confirmed level and the gap. */
    public Profile profile(long userId) {
        Long me = CurrentUser.id();
        boolean self = Objects.equals(me, userId);
        boolean canConfirm = !self && (isHr() || manages(userId));
        if (!self && !canConfirm) {
            throw new AccessDeniedException("You cannot see this person's skills.");
        }
        Person p = person(userId);
        Current job = currentJob(userId);
        List<PersonSkill> skills = jdbc.sql("""
                select k.id as skill_id, k.name, k.family, os.level as required, ps.self_level, ps.self_note, ps.self_at,
                       ps.level, person_name(b.first_name, b.middle_name, b.last_name) as confirmed_by_name, ps.confirmed_note,
                       ps.confirmed_at, ps.status,
                       case when os.level is null then null else greatest(os.level - coalesce(ps.level, 0), 0) end as gap,
                       k.level1, k.level2, k.level3, k.level4
                from skill k
                left join occupation_skill os on os.skill_id = k.id and os.occupation_id = :job
                left join person_skill ps on ps.skill_id = k.id and ps.user_id = :user
                left join app_user b on b.id = ps.confirmed_by
                where os.id is not null or ps.id is not null
                order by (os.level is null), case k.family when 'TECHNICAL' then 0 when 'BEHAVIOURAL' then 1 when 'LANGUAGE' then 2 else 3 end,
                         k.name""")
                .param("job", job.occupationId() == null ? -1L : job.occupationId()).param("user", userId)
                .query(PersonSkill.class).list();
        int gaps = (int) skills.stream().filter(s -> s.gap() != null && s.gap() > 0).count();
        int waiting = (int) skills.stream().filter(s -> "SELF".equals(s.status())).count();
        return new Profile(userId, p.name(), job.occupationId(), job.occupationName(), p.unitName(), self, canConfirm, skills,
                certifications(userId), gaps, waiting);
    }

    /** The employee rates themselves; the manager (or HR when there is none) is asked to confirm. */
    @Transactional
    public Profile selfAssess(List<LevelInput> levels) {
        Long me = CurrentUser.id();
        if (me == null) {
            throw new AccessDeniedException("Sign in first.");
        }
        int changed = 0;
        for (LevelInput in : levels) {
            if (in.level() == null) {
                continue;
            }
            check(in.level());
            skill(in.skillId());
            changed += jdbc.sql("""
                    insert into person_skill (user_id, skill_id, self_level, self_note, self_at, status)
                    values (?, ?, ?, ?, now(), 'SELF')
                    on conflict (user_id, skill_id) do update set self_level = excluded.self_level, self_note = excluded.self_note,
                           self_at = now(),
                           status = case when person_skill.level = excluded.self_level then person_skill.status else 'SELF' end
                    where person_skill.self_level is distinct from excluded.self_level
                       or person_skill.self_note is distinct from excluded.self_note""")
                    .params(me, in.skillId(), in.level(), clean(in.note())).update();
        }
        if (changed > 0) {
            audit.record("skill.self_assessed", "user", me, Map.of("skills", changed));
            int waiting = jdbc.sql("select count(*) from person_skill where user_id = ? and status = 'SELF'").param(me)
                    .query(Integer.class).single();
            if (waiting > 0) {
                String name = person(me).name();
                org.managerOf(me).ifPresentOrElse(
                        b -> notifications.send(b.userId(), "skills.review", () -> Texts.t("Skills to confirm: %s", name),
                                () -> Texts.t("%s rated their skills. Confirm or correct the levels.", name), "/users/" + me),
                        () -> notifications.sendToHolders("skills.manage", "skills.review",
                                () -> Texts.t("Skills to confirm: %s", name),
                                () -> Texts.t("%s rated their skills. Confirm or correct the levels.", name), "/users/" + me));
            }
        }
        return profile(me);
    }

    /** The manager (or HR) confirms or corrects levels; HR may set a level directly, for example after a test. */
    @Transactional
    public Profile confirm(long userId, List<LevelInput> levels) {
        Long me = CurrentUser.id();
        if (Objects.equals(me, userId)) {
            throw new AccessDeniedException("You cannot confirm your own skills.");
        }
        if (!isHr() && !manages(userId)) {
            throw new AccessDeniedException("Only the manager or HR confirms skills.");
        }
        person(userId);
        int changed = 0;
        for (LevelInput in : levels) {
            if (in.level() == null) {
                continue;
            }
            check(in.level());
            skill(in.skillId());
            changed += jdbc.sql("""
                    insert into person_skill (user_id, skill_id, level, confirmed_by, confirmed_note, confirmed_at, status)
                    values (?, ?, ?, ?, ?, now(), 'CONFIRMED')
                    on conflict (user_id, skill_id) do update set level = excluded.level, confirmed_by = excluded.confirmed_by,
                           confirmed_note = excluded.confirmed_note, confirmed_at = now(), status = 'CONFIRMED'""")
                    .params(userId, in.skillId(), in.level(), me, clean(in.note())).update();
        }
        if (changed > 0) {
            audit.record("skill.confirmed", "user", userId, Map.of("skills", changed));
            notifications.send(userId, "skills.confirmed", () -> Texts.t("Your skills were reviewed"),
                    () -> Texts.t("Your levels were confirmed or corrected. See them in My skills."), "/my/skills");
        }
        return profile(userId);
    }

    private static void check(int level) {
        if (level < 1 || level > 4) {
            throw new BusinessRuleException("A level goes from 1 to 4.");
        }
    }

    /** People who rated themselves and wait for a confirmation: a manager's team, or for HR everyone. */
    public List<Review> reviews() {
        Long me = CurrentUser.id();
        if (me == null) {
            return List.of();
        }
        Map<Long, Current> jobs = currentJobs();
        List<Review> all = jdbc.sql("""
                select u.id as user_id, person_name(u.first_name, u.middle_name, u.last_name) as name,
                       (select ou.name from org_assignment a join org_unit ou on ou.id = a.unit_id
                        where a.user_id = u.id and a.valid_to is null order by a.valid_from desc limit 1) as unit_name,
                       null as occupation_name, count(*) as waiting, min(ps.self_at) as since
                from person_skill ps join app_user u on u.id = ps.user_id
                where ps.status = 'SELF' and u.status = 'ACTIVE' and u.id <> ?
                group by u.id order by min(ps.self_at)""").param(me).query(Review.class).list();
        boolean hr = isHr();
        Set<Long> team = org.teamOf(me);
        return all.stream().filter(r -> hr || team.contains(r.userId()))
                .map(r -> new Review(r.userId(), r.name(), r.unitName(),
                        jobs.containsKey(r.userId()) ? jobs.get(r.userId()).occupationName() : null, r.waiting(), r.since()))
                .toList();
    }

    // ---- the gap matrix ----------------------------------------------------------------------------------------

    /** People (of a unit and the units under it, or a manager's team) by the skills their jobs need. */
    public Matrix matrix(Long unitId) {
        Long me = CurrentUser.id();
        boolean hr = isHr();
        if (!hr && me == null) {
            throw new AccessDeniedException("You cannot see this.");
        }
        Set<Long> branch = unitId == null ? null : org.branch(unitId);
        Set<Long> team = hr ? null : org.teamOf(me);
        if (!hr && team.isEmpty()) {
            throw new AccessDeniedException("You cannot see this.");
        }
        List<OrgStructure.Member> people = org.people().stream()
                .filter(m -> branch == null || branch.contains(m.unitId()))
                .filter(m -> team == null || team.contains(m.userId()))
                .toList();
        Map<Long, Current> jobs = currentJobs();
        Map<Long, Map<Long, Integer>> required = new HashMap<>();
        Map<Long, JobSkill> skills = new LinkedHashMap<>();
        jdbc.sql("""
                select os.occupation_id, k.id, k.name, k.family, os.level from occupation_skill os join skill k on k.id = os.skill_id
                where k.active order by case k.family when 'TECHNICAL' then 0 when 'BEHAVIOURAL' then 1 when 'LANGUAGE' then 2 else 3 end, k.name""")
                .query(rs -> {
                    required.computeIfAbsent(rs.getLong(1), k -> new HashMap<>()).put(rs.getLong(2), rs.getInt(5));
                });
        Set<Long> usedSkills = new LinkedHashSet<>();
        for (OrgStructure.Member m : people) {
            Current c = jobs.get(m.userId());
            if (c != null && c.occupationId() != null && required.containsKey(c.occupationId())) {
                usedSkills.addAll(required.get(c.occupationId()).keySet());
            }
        }
        jdbc.sql("""
                select k.id as skill_id, k.name, k.family, 0 as level from skill k where k.active
                order by case k.family when 'TECHNICAL' then 0 when 'BEHAVIOURAL' then 1 when 'LANGUAGE' then 2 else 3 end, k.name""")
                .query(JobSkill.class).list().stream().filter(s -> usedSkills.contains(s.skillId()))
                .forEach(s -> skills.put(s.skillId(), s));
        Map<Long, Map<Long, Integer[]>> levels = new HashMap<>();
        jdbc.sql("select user_id, skill_id, level, self_level from person_skill").query(rs -> {
            int l = rs.getInt(3);
            Integer level = rs.wasNull() ? null : l;
            int s = rs.getInt(4);
            Integer selfLevel = rs.wasNull() ? null : s;
            levels.computeIfAbsent(rs.getLong(1), k -> new HashMap<>()).put(rs.getLong(2), new Integer[] {level, selfLevel});
        });
        List<Row> rows = new ArrayList<>();
        int gaps = 0;
        for (OrgStructure.Member m : people) {
            Current c = jobs.get(m.userId());
            Map<Long, Integer> req = c == null || c.occupationId() == null ? Map.of() : required.getOrDefault(c.occupationId(), Map.of());
            Map<Long, Integer[]> mine = levels.getOrDefault(m.userId(), Map.of());
            List<Cell> cells = new ArrayList<>();
            int personGaps = 0;
            for (Long s : skills.keySet()) {
                Integer r = req.get(s);
                Integer[] l = mine.get(s);
                Integer level = l == null ? null : l[0];
                if (r != null && (level == null || level < r)) {
                    personGaps++;
                }
                cells.add(new Cell(s, r, level, l == null ? null : l[1]));
            }
            gaps += personGaps;
            rows.add(new Row(m.userId(), m.name(), m.registrationNo(), m.unitName(), c == null ? null : c.occupationName(),
                    cells, personGaps));
        }
        rows.sort((a, b) -> Integer.compare(b.gaps(), a.gaps()) != 0 ? Integer.compare(b.gaps(), a.gaps())
                : a.name().compareToIgnoreCase(b.name()));
        return new Matrix(new ArrayList<>(skills.values()), rows, gaps, rows.size());
    }

    /**
     * Turns gaps into training needs for the year (source SKILL_GAP), with a course that builds the skill when the
     * catalogue has one. A gap already turned into a need this year is skipped.
     */
    @Transactional
    public int toNeeds(List<Gap> gaps) {
        if (!isHr() && !CurrentUser.has("training.manage")) {
            throw new AccessDeniedException("Only HR can do this.");
        }
        int year = today().getYear();
        int added = 0;
        for (Gap g : gaps) {
            Current c = currentJob(g.userId());
            if (c.occupationId() == null) {
                continue;
            }
            record Req(String name, int required, Integer level) {}
            Req r = jdbc.sql("""
                    select k.name, os.level as required, ps.level
                    from occupation_skill os join skill k on k.id = os.skill_id
                    left join person_skill ps on ps.skill_id = k.id and ps.user_id = ?
                    where os.occupation_id = ? and k.id = ?""").params(g.userId(), c.occupationId(), g.skillId())
                    .query(Req.class).optional().orElse(null);
            if (r == null || (r.level() != null && r.level() >= r.required())) {
                continue;
            }
            int gap = r.required() - (r.level() == null ? 0 : r.level());
            Long course = jdbc.sql("""
                    select c.id from training_course c join training_course_skill cs on cs.course_id = c.id
                    where cs.skill_id = ? and c.active order by c.id limit 1""").param(g.skillId()).query(Long.class)
                    .optional().orElse(null);
            String reason = Texts.t("Level %s, the job needs %s.", r.level() == null ? "0" : r.level().toString(), r.required());
            added += jdbc.sql("""
                    insert into training_need (year, user_id, topic, course_id, source, source_ref, priority, status, reason, requested_by)
                    select ?, ?, ?, ?, 'SKILL_GAP', ?, ?, 'OPEN', ?, ?
                    where not exists (select 1 from training_need n where n.year = ? and n.user_id = ? and n.source = 'SKILL_GAP'
                                      and n.source_ref = ? and n.status not in ('DONE', 'REFUSED', 'DROPPED'))
                    on conflict do nothing""")
                    .params(year, g.userId(), r.name(), course, g.skillId(), gap >= 2 ? "HIGH" : "MEDIUM", reason,
                            CurrentUser.id(), year, g.userId(), g.skillId())
                    .update();
        }
        if (added > 0) {
            audit.record("skill.gaps_to_needs", "training_need", null, Map.of("needs", added));
        }
        return added;
    }

    // ---- search and certifications -----------------------------------------------------------------------------

    /** Who has a skill at a level or more (confirmed levels only). */
    public List<Holder> search(long skillId, int minLevel) {
        requireHr();
        skill(skillId);
        Map<Long, Current> jobs = currentJobs();
        return jdbc.sql("""
                select u.id as user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, u.registration_no,
                       (select ou.name from org_assignment a join org_unit ou on ou.id = a.unit_id
                        where a.user_id = u.id and a.valid_to is null order by a.valid_from desc limit 1) as unit_name,
                       null as occupation_name, ps.level, ps.confirmed_at
                from person_skill ps join app_user u on u.id = ps.user_id
                where ps.skill_id = ? and ps.level >= ? and u.status = 'ACTIVE' and not u.not_employee
                order by ps.level desc, name""").params(skillId, minLevel).query(Holder.class).list().stream()
                .map(h -> new Holder(h.userId(), h.name(), h.registrationNo(), h.unitName(),
                        jobs.containsKey(h.userId()) ? jobs.get(h.userId()).occupationName() : null, h.level(), h.confirmedAt()))
                .toList();
    }

    private static final String CERT_SELECT = """
            select d.id as document_id, d.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, d.file_name,
                   d.issued_on, d.expires_on, d.note,
                   case when d.expires_on is null then 'VALID' when d.expires_on < :today then 'EXPIRED'
                        when d.expires_on <= :today + t.remind_days then 'EXPIRING' else 'VALID' end as state
            from employee_document d join document_type t on t.id = d.type_id join app_user u on u.id = d.user_id
            where t.code = 'CERTIFICATION' and d.deleted_at is null
            """;

    private List<Certification> certifications(long userId) {
        return jdbc.sql(CERT_SELECT + " and d.user_id = :user order by d.expires_on nulls last")
                .param("today", today()).param("user", userId).query(Certification.class).list();
    }

    /** Every licence and certification of active people, the expired and expiring first. */
    public List<Certification> certifications() {
        requireHr();
        return jdbc.sql(CERT_SELECT + """
                 and u.status = 'ACTIVE'
                 order by case when d.expires_on is null then 2 when d.expires_on < :today then 0 else 1 end, d.expires_on, name""")
                .param("today", today()).query(Certification.class).list();
    }
}
