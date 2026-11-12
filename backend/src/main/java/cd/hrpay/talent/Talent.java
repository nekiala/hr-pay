package cd.hrpay.talent;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.Notifications;
import cd.hrpay.org.OrgStructure;
import cd.hrpay.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
 * Talent review and succession: critical posts and who holds them, the yearly review in a 9-box grid, successors
 * with their readiness and development actions, and the coverage report. Confidential: HR (talent.manage) and the
 * head of the Direction générale see it; a manager only proposes the potential of their team; employees see nothing.
 * Every look at it is written to the audit log.
 */
@Service
public class Talent {

    public static final Set<String> READINESS = Set.of("NOW", "ONE_TWO", "THREE_PLUS");
    public static final Set<String> RISKS = Set.of("LOW", "MEDIUM", "HIGH");
    public static final Set<String> MOBILITY = Set.of("NOW", "LATER", "NO");
    public static final Set<String> ACTIONS = Set.of("COURSE", "MISSION", "MENTOR", "ACTING", "OTHER");
    static final int MAX_SUCCESSORS = 3;

    public record Person(long userId, String name) {}

    public record Action(long id, long successorId, String kind, String title, Long courseId, String courseTitle,
                         Long mentorUserId, String mentorName, LocalDate dueOn, LocalDate doneOn, Long needId) {}

    public record Successor(long id, long postId, long userId, String name, String occupationName, String readiness,
                            String note, Integer box, List<Action> actions) {}

    public record Post(long id, long unitId, String unitName, boolean head, Long occupationId, String occupationName,
                       String title, String reason, List<Person> holders, LocalDate retiresOn, String flightRisk,
                       List<Successor> successors, List<String> warnings) {}

    public record PostInput(long unitId, boolean head, Long occupationId, String title, String reason) {}

    public record Candidate(long userId, String name, String occupationName, String unitName, boolean onLadder,
                            boolean eligible, String note, Integer skillMatch, Integer box, boolean successorElsewhere) {}

    public record Review(long id, int year, String name, Long categoryId, String categoryName, Long unitId,
                         String unitName, String status, String note, String openedBy, OffsetDateTime openedAt,
                         int members, int proposed, int settled) {}

    public record Member(long id, long reviewId, long userId, String name, String occupationName, String unitName,
                         String managerName, BigDecimal score, Integer performanceAuto, Integer performance,
                         String performanceReason, Integer potentialProposed, String potentialReason,
                         String proposedByName, Integer potential, String flightRisk, String mobility, String note,
                         String settledBy, Integer box) {}

    public record ReviewView(Review review, List<Member> members, Map<Integer, Integer> grid) {}

    public record Proposal(long memberId, String reviewName, int year, long userId, String name, String occupationName,
                           Integer potentialProposed, String potentialReason) {}

    public record Settle(Integer performance, String performanceReason, Integer potential, String flightRisk,
                         String mobility, String note) {}

    public record Coverage(int posts, int covered, int withoutReady, int holderAtRisk, int holderRetiring,
                           int vacant, List<Post> atRisk) {}

    /** What the talent viewers see on a person's page. */
    public record PersonView(long userId, Integer box, Integer performance, Integer potential, String flightRisk,
                             String mobility, String reviewName, List<String> successorOf, List<String> holds) {}

    private static final ZoneId ZONE = ZoneId.of("Africa/Kinshasa");

    private final JdbcClient jdbc;
    private final OrgStructure org;
    private final Notifications notifications;
    private final AuditService audit;
    private final Clock clock;

    public Talent(JdbcClient jdbc, OrgStructure org, Notifications notifications, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.org = org;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    // ---- who may see it ----------------------------------------------------------------------------------------

    /** HR with talent.manage, or the head of the Direction générale. */
    public boolean canSee() {
        if (CurrentUser.has("talent.manage")) {
            return true;
        }
        Long me = CurrentUser.id();
        return me != null && jdbc.sql("""
                select count(*) from org_head h join org_unit u on u.id = h.unit_id join org_level l on l.id = u.level_id
                where h.user_id = ? and h.role = 'HEAD' and h.valid_to is null and l.code = 'DG'""")
                .param(me).query(Integer.class).single() > 0;
    }

    private void require(String what, Long ref) {
        if (!canSee()) {
            throw new AccessDeniedException("Only HR and the Direction générale see the talent review and succession.");
        }
        audit.record("talent.viewed", "talent", ref, Map.of("what", what));
    }

    private void requireChange() {
        if (!canSee()) {
            throw new AccessDeniedException("Only HR and the Direction générale see the talent review and succession.");
        }
    }

    public record Settings(int retirementAge, int performanceLow, int performanceHigh) {}

    /** The retirement age behind the "retires within 2 years" warning, and the evaluation % that split performance 1-2-3. */
    public Settings settings() {
        requireChange();
        return new Settings(setting("TALENT_RETIREMENT_AGE", 65), setting("TALENT_PERF_LOW", 60), setting("TALENT_PERF_HIGH", 80));
    }

    @Transactional
    public Settings saveSettings(Settings s) {
        requireChange();
        if (s.performanceLow() >= s.performanceHigh()) {
            throw new BusinessRuleException("The high threshold must be above the low one.");
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("TALENT_RETIREMENT_AGE", String.valueOf(s.retirementAge()));
        values.put("TALENT_PERF_LOW", String.valueOf(s.performanceLow()));
        values.put("TALENT_PERF_HIGH", String.valueOf(s.performanceHigh()));
        values.forEach((k, v) -> jdbc.sql("""
                insert into payroll_setting (key, value) values (?, ?)
                on conflict (company_id, key) do update set value = excluded.value""").params(k, v).update());
        audit.record("talent.settings", "company", null, Map.copyOf(values));
        return settings();
    }

    private int setting(String key, int fallback) {
        return jdbc.sql("select value from payroll_setting where key = ?").param(key).query(String.class).optional()
                .map(String::trim).filter(s -> s.matches("\\d{1,3}")).map(Integer::parseInt).orElse(fallback);
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static Integer box(Integer performance, Integer potential) {
        return performance == null || potential == null ? null : (potential - 1) * 3 + performance;
    }

    // ---- people, jobs and the last review ----------------------------------------------------------------------

    private record Job(long userId, Long occupationId, String occupationName, LocalDate since) {}

    private Map<Long, Job> currentJobs() {
        Map<Long, Job> out = new HashMap<>();
        jdbc.sql("""
                select distinct on (c.user_id) c.user_id, c.occupation_id, o.name as occupation_name,
                       (select min(c2.start_date) from employee_contract c2 where c2.user_id = c.user_id and c2.occupation_id = c.occupation_id) as since
                from employee_contract c left join occupation o on o.id = c.occupation_id
                where c.start_date <= ? and (c.end_date is null or c.end_date >= ?) order by c.user_id, c.start_date desc""")
                .params(today(), today()).query(Job.class).list().forEach(j -> out.put(j.userId(), j));
        return out;
    }

    private record Latest(long userId, Integer performance, Integer potential, String flightRisk, String mobility,
                          String reviewName) {}

    /** Each person's settled place in their latest review. */
    private Map<Long, Latest> latest() {
        Map<Long, Latest> out = new HashMap<>();
        jdbc.sql("""
                select distinct on (m.user_id) m.user_id, m.performance, m.potential, m.flight_risk, m.mobility, r.name as review_name
                from talent_review_member m join talent_review r on r.id = m.review_id
                where m.performance is not null and m.potential is not null
                order by m.user_id, r.year desc, m.settled_at desc nulls last""").query(Latest.class).list()
                .forEach(l -> out.put(l.userId(), l));
        return out;
    }

    // ---- critical posts ----------------------------------------------------------------------------------------

    private record PostRow(long id, long unitId, String unitName, boolean head, Long occupationId, String occupationName,
                           String title, String reason) {}

    private static final String POST_SELECT = """
            select p.id, p.unit_id, u.name as unit_name, p.head, p.occupation_id, o.name as occupation_name, p.title, p.reason
            from critical_post p join org_unit u on u.id = p.unit_id left join occupation o on o.id = p.occupation_id
            where p.active
            """;

    /** Who holds a post: the head of the unit, or the people of the unit whose current job is the post's. */
    private List<Person> holders(PostRow p, Map<Long, Job> jobs) {
        if (p.head()) {
            return jdbc.sql("""
                    select h.user_id, person_name(a.first_name, a.middle_name, a.last_name) as name
                    from org_head h join app_user a on a.id = h.user_id
                    where h.unit_id = ? and h.role = 'HEAD' and h.valid_to is null""").param(p.unitId()).query(Person.class).list();
        }
        List<Person> in = jdbc.sql("""
                select x.user_id, person_name(a.first_name, a.middle_name, a.last_name) as name
                from org_assignment x join app_user a on a.id = x.user_id
                where x.unit_id = ? and x.valid_to is null and a.status = 'ACTIVE' order by name""").param(p.unitId())
                .query(Person.class).list();
        return in.stream().filter(h -> jobs.containsKey(h.userId()) && Objects.equals(jobs.get(h.userId()).occupationId(), p.occupationId()))
                .toList();
    }

    private List<Post> posts(Long onlyId) {
        Map<Long, Job> jobs = currentJobs();
        Map<Long, Latest> latest = latest();
        int retirementAge = setting("TALENT_RETIREMENT_AGE", 65);
        LocalDate horizon = today().plusYears(2);
        List<PostRow> rows = jdbc.sql(POST_SELECT + (onlyId == null ? "" : " and p.id = " + onlyId) + " order by u.name, p.head desc, o.name")
                .query(PostRow.class).list();
        Map<Long, List<Successor>> successors = successors(jobs, latest);
        List<Post> out = new ArrayList<>();
        for (PostRow p : rows) {
            List<Person> holders = holders(p, jobs);
            LocalDate retires = null;
            String risk = null;
            for (Person h : holders) {
                LocalDate birth = jdbc.sql("select birth_date from employee_profile where user_id = ?").param(h.userId())
                        .query(LocalDate.class).optional().orElse(null);
                if (birth != null) {
                    LocalDate r = birth.plusYears(retirementAge);
                    retires = retires == null || r.isBefore(retires) ? r : retires;
                }
                Latest l = latest.get(h.userId());
                if (l != null && l.flightRisk() != null && (risk == null || rank(l.flightRisk()) > rank(risk))) {
                    risk = l.flightRisk();
                }
            }
            List<Successor> mine = successors.getOrDefault(p.id(), List.of());
            List<String> warnings = new ArrayList<>();
            if (holders.isEmpty()) {
                warnings.add(Texts.t("Nobody holds this post now."));
            }
            if (mine.stream().noneMatch(s -> !s.readiness().equals("THREE_PLUS"))) {
                warnings.add(Texts.t("No successor ready now or within two years."));
            }
            if ("HIGH".equals(risk)) {
                warnings.add(Texts.t("The holder may leave (high flight risk)."));
            }
            if (retires != null && !retires.isAfter(horizon)) {
                warnings.add(Texts.t("The holder reaches retirement age on %s.", retires.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))));
            }
            out.add(new Post(p.id(), p.unitId(), p.unitName(), p.head(), p.occupationId(), p.occupationName(), p.title(),
                    p.reason(), holders, retires, risk, mine, warnings));
        }
        return out;
    }

    private static int rank(String risk) {
        return switch (risk) {
            case "HIGH" -> 2;
            case "MEDIUM" -> 1;
            default -> 0;
        };
    }

    public List<Post> posts() {
        require("posts", null);
        return posts(null);
    }

    public Post post(long id) {
        return posts(id).stream().findFirst().orElseThrow(() -> new NotFoundException("Critical post not found."));
    }

    @Transactional
    public Post savePost(Long id, PostInput in) {
        requireChange();
        if (!in.head() && in.occupationId() == null) {
            throw new BusinessRuleException("Choose the head of the unit or a job.");
        }
        org.unit(in.unitId());
        Long occupation = in.head() ? null : in.occupationId();
        Long same = jdbc.sql("""
                select id from critical_post where active and unit_id = ? and head = ? and coalesce(occupation_id, 0) = coalesce(cast(? as bigint), 0)""")
                .params(in.unitId(), in.head(), occupation).query(Long.class).optional().orElse(null);
        if (same != null && !same.equals(id)) {
            throw new BusinessRuleException("This post is already on the list.");
        }
        long saved;
        if (id == null) {
            saved = jdbc.sql("""
                    insert into critical_post (unit_id, head, occupation_id, title, reason, created_by) values (?, ?, ?, ?, ?, ?)
                    returning id""").params(in.unitId(), in.head(), occupation, clean(in.title()), clean(in.reason()), CurrentUser.email())
                    .query(Long.class).single();
        } else {
            post(id);
            jdbc.sql("update critical_post set unit_id = ?, head = ?, occupation_id = ?, title = ?, reason = ? where id = ?")
                    .params(in.unitId(), in.head(), occupation, clean(in.title()), clean(in.reason()), id).update();
            saved = id;
        }
        audit.record(id == null ? "talent.post_created" : "talent.post_updated", "critical_post", saved, Map.of("unit", in.unitId()));
        return post(saved);
    }

    @Transactional
    public void removePost(long id) {
        requireChange();
        post(id);
        jdbc.sql("update critical_post set active = false where id = ?").param(id).update();
        audit.record("talent.post_removed", "critical_post", id, Map.of());
    }

    /** The head of the Direction générale and of each direction, when not on the list yet. */
    @Transactional
    public int suggest() {
        requireChange();
        int added = jdbc.sql("""
                insert into critical_post (unit_id, head, created_by)
                select u.id, true, ? from org_unit u join org_level l on l.id = u.level_id
                where u.valid_to is null and l.rank <= 2 and not l.staff
                  and not exists (select 1 from critical_post p where p.active and p.unit_id = u.id and p.head)""")
                .param(CurrentUser.email()).update();
        if (added > 0) {
            audit.record("talent.posts_suggested", "critical_post", null, Map.of("added", added));
        }
        return added;
    }

    // ---- successors and their development ----------------------------------------------------------------------

    private Map<Long, List<Successor>> successors(Map<Long, Job> jobs, Map<Long, Latest> latest) {
        Map<Long, List<Action>> actions = new HashMap<>();
        jdbc.sql("""
                select a.id, a.successor_id, a.kind, a.title, a.course_id, c.title as course_title, a.mentor_user_id,
                       person_name(m.first_name, m.middle_name, m.last_name) as mentor_name, a.due_on, a.done_on, a.need_id
                from successor_action a left join training_course c on c.id = a.course_id left join app_user m on m.id = a.mentor_user_id
                order by a.done_on nulls first, a.due_on nulls last, a.id""").query(Action.class).list()
                .forEach(a -> actions.computeIfAbsent(a.successorId(), k -> new ArrayList<>()).add(a));
        record Row(long id, long postId, long userId, String name, String readiness, String note) {}
        Map<Long, List<Successor>> out = new LinkedHashMap<>();
        jdbc.sql("""
                select s.id, s.post_id, s.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, s.readiness, s.note
                from successor s join app_user u on u.id = s.user_id
                order by case s.readiness when 'NOW' then 0 when 'ONE_TWO' then 1 else 2 end, s.created_at""")
                .query(Row.class).list().forEach(r -> {
                    Latest l = latest.get(r.userId());
                    Job j = jobs.get(r.userId());
                    out.computeIfAbsent(r.postId(), k -> new ArrayList<>()).add(new Successor(r.id(), r.postId(), r.userId(), r.name(),
                            j == null ? null : j.occupationName(), r.readiness(), r.note(),
                            l == null ? null : box(l.performance(), l.potential()), actions.getOrDefault(r.id(), List.of())));
                });
        return out;
    }

    /**
     * People to consider for a post: those whose job leads to the post's job on a career ladder (and whether they meet
     * its conditions), and how close their confirmed skills are to what the job needs. The holders are left out.
     */
    public List<Candidate> candidates(long postId) {
        require("candidates", postId);
        Post post = post(postId);
        Map<Long, Job> jobs = currentJobs();
        Map<Long, Latest> latest = latest();
        Long target = post.occupationId();
        if (target == null && !post.holders().isEmpty()) {
            Job j = jobs.get(post.holders().getFirst().userId());
            target = j == null ? null : j.occupationId();
        }
        Set<Long> holders = new HashSet<>();
        post.holders().forEach(h -> holders.add(h.userId()));
        Set<Long> already = new HashSet<>();
        post.successors().forEach(s -> already.add(s.userId()));
        Set<Long> elsewhere = new HashSet<>(jdbc.sql("select user_id from successor where post_id <> ?").param(postId)
                .query(Long.class).list());
        record Path(long fromId, BigDecimal minYears, BigDecimal minScore, String requirement) {}
        Map<Long, Path> ladder = new HashMap<>();
        Map<Long, Integer> required = new HashMap<>();
        if (target != null) {
            jdbc.sql("select from_occupation_id as from_id, min_years, min_score, requirement from career_path where to_occupation_id = ?")
                    .param(target).query(Path.class).list().forEach(p -> ladder.put(p.fromId(), p));
            jdbc.sql("select skill_id, level from occupation_skill where occupation_id = ?").param(target)
                    .query(rs -> {
                        required.put(rs.getLong(1), rs.getInt(2));
                    });
        }
        Map<Long, Map<Long, Integer>> levels = new HashMap<>();
        jdbc.sql("select user_id, skill_id, level from person_skill where level is not null").query(rs -> {
            levels.computeIfAbsent(rs.getLong(1), k -> new HashMap<>()).put(rs.getLong(2), rs.getInt(3));
        });
        Map<Long, BigDecimal> scores = new HashMap<>();
        jdbc.sql("""
                select distinct on (user_id) user_id, total_percent from evaluation
                where status = 'APPROVED' and total_percent is not null order by user_id, period_end desc""")
                .query(rs -> {
                    scores.put(rs.getLong(1), rs.getBigDecimal(2));
                });
        List<Candidate> out = new ArrayList<>();
        for (OrgStructure.Member m : org.people()) {
            if (holders.contains(m.userId()) || already.contains(m.userId())) {
                continue;
            }
            Job j = jobs.get(m.userId());
            Path path = j == null || j.occupationId() == null ? null : ladder.get(j.occupationId());
            boolean eligible = false;
            String note = null;
            if (path != null) {
                double years = j.since() == null ? 0 : (today().toEpochDay() - j.since().toEpochDay()) / 365.25;
                BigDecimal score = scores.get(m.userId());
                boolean yearsOk = years >= path.minYears().doubleValue();
                boolean scoreOk = path.minScore() == null || (score != null && score.compareTo(path.minScore()) >= 0);
                eligible = yearsOk && scoreOk;
                List<String> missing = new ArrayList<>();
                if (!yearsOk) {
                    missing.add(Texts.t("%s years in the job of %s", String.format(Texts.locale(), "%.1f", years), path.minYears().stripTrailingZeros().toPlainString()));
                }
                if (!scoreOk) {
                    String needed = path.minScore().stripTrailingZeros().toPlainString();
                    missing.add(score == null ? Texts.t("no approved evaluation (%s needed)", needed)
                            : Texts.t("evaluation %s of %s", score.stripTrailingZeros().toPlainString(), needed));
                }
                note = missing.isEmpty() ? clean(path.requirement()) : String.join(" · ", missing);
            }
            Integer match = null;
            if (!required.isEmpty()) {
                Map<Long, Integer> mine = levels.getOrDefault(m.userId(), Map.of());
                int need = 0;
                int have = 0;
                for (Map.Entry<Long, Integer> r : required.entrySet()) {
                    need += r.getValue();
                    have += Math.min(r.getValue(), mine.getOrDefault(r.getKey(), 0));
                }
                match = need == 0 ? null : Math.round(100f * have / need);
            }
            Latest l = latest.get(m.userId());
            Integer box = l == null ? null : box(l.performance(), l.potential());
            if (path == null && (match == null || match < 50) && (box == null || box < 8)) {
                continue;   // nothing points to this person
            }
            out.add(new Candidate(m.userId(), m.name(), j == null ? null : j.occupationName(), m.unitName(), path != null,
                    eligible, note, match, box, elsewhere.contains(m.userId())));
        }
        out.sort(Comparator.comparing((Candidate c) -> !c.eligible()).thenComparing(c -> !c.onLadder())
                .thenComparing(c -> -(c.skillMatch() == null ? 0 : c.skillMatch()))
                .thenComparing(c -> -(c.box() == null ? 0 : c.box())).thenComparing(Candidate::name));
        return out.size() > 15 ? out.subList(0, 15) : out;
    }

    @Transactional
    public Post addSuccessor(long postId, long userId, String readiness, String note) {
        requireChange();
        Post post = post(postId);
        if (!READINESS.contains(readiness)) {
            throw new BusinessRuleException("Unknown readiness.");
        }
        if (post.holders().stream().anyMatch(h -> h.userId() == userId)) {
            throw new BusinessRuleException("The holder cannot be their own successor.");
        }
        if (post.successors().stream().anyMatch(s -> s.userId() == userId)) {
            throw new BusinessRuleException("This person is already a successor for this post.");
        }
        if (post.successors().size() >= MAX_SUCCESSORS) {
            throw new BusinessRuleException(Texts.t("A post has %s successors at most.", MAX_SUCCESSORS));
        }
        long id = jdbc.sql("insert into successor (post_id, user_id, readiness, note, created_by) values (?, ?, ?, ?, ?) returning id")
                .params(postId, userId, readiness, clean(note), CurrentUser.email()).query(Long.class).single();
        audit.record("talent.successor_added", "critical_post", postId, Map.of("user", userId, "readiness", readiness));
        return post(postId);
    }

    private record SuccessorRow(long id, long postId, long userId) {}

    private SuccessorRow successor(long id) {
        return jdbc.sql("select id, post_id, user_id from successor where id = ?").param(id).query(SuccessorRow.class).optional()
                .orElseThrow(() -> new NotFoundException("Successor not found."));
    }

    @Transactional
    public Post updateSuccessor(long id, String readiness, String note) {
        requireChange();
        SuccessorRow s = successor(id);
        if (!READINESS.contains(readiness)) {
            throw new BusinessRuleException("Unknown readiness.");
        }
        jdbc.sql("update successor set readiness = ?, note = ? where id = ?").params(readiness, clean(note), id).update();
        audit.record("talent.successor_updated", "critical_post", s.postId(), Map.of("user", s.userId(), "readiness", readiness));
        return post(s.postId());
    }

    @Transactional
    public Post removeSuccessor(long id) {
        requireChange();
        SuccessorRow s = successor(id);
        jdbc.sql("delete from successor where id = ?").param(id).update();
        audit.record("talent.successor_removed", "critical_post", s.postId(), Map.of("user", s.userId()));
        return post(s.postId());
    }

    /** A development action; a course becomes a training need (source SUCCESSION) so HR plans it with the rest. */
    @Transactional
    public Post addAction(long successorId, String kind, String title, Long courseId, Long mentorUserId, LocalDate dueOn) {
        requireChange();
        SuccessorRow s = successor(successorId);
        if (!ACTIONS.contains(kind)) {
            throw new BusinessRuleException("Unknown kind of action.");
        }
        String courseTitle = null;
        if (courseId != null) {
            courseTitle = jdbc.sql("select title from training_course where id = ?").param(courseId).query(String.class).optional()
                    .orElseThrow(() -> new NotFoundException("Course not found."));
        }
        String text = clean(title) != null ? title.trim() : courseTitle;
        if (text == null) {
            throw new BusinessRuleException("Describe the action.");
        }
        if (mentorUserId != null && mentorUserId == s.userId()) {
            throw new BusinessRuleException("A person cannot be their own mentor.");
        }
        long id = jdbc.sql("""
                insert into successor_action (successor_id, kind, title, course_id, mentor_user_id, due_on) values (?, ?, ?, ?, ?, ?)
                returning id""").params(successorId, kind, text, courseId, mentorUserId, dueOn).query(Long.class).single();
        if (kind.equals("COURSE")) {
            int year = dueOn == null ? today().getYear() : dueOn.getYear();
            Long need = jdbc.sql("""
                    insert into training_need (year, user_id, topic, course_id, source, source_ref, priority, status, reason, requested_by)
                    values (?, ?, ?, ?, 'SUCCESSION', ?, 'HIGH', 'OPEN', ?, ?) returning id""")
                    .params(year, s.userId(), text, courseId, id, Texts.t("Preparation for a key post"), CurrentUser.id())
                    .query(Long.class).single();
            jdbc.sql("update successor_action set need_id = ? where id = ?").params(need, id).update();
        }
        audit.record("talent.action_added", "critical_post", s.postId(), Map.of("user", s.userId(), "kind", kind));
        return post(s.postId());
    }

    @Transactional
    public Post doneAction(long actionId, boolean done) {
        requireChange();
        Long successorId = jdbc.sql("select successor_id from successor_action where id = ?").param(actionId).query(Long.class)
                .optional().orElseThrow(() -> new NotFoundException("Action not found."));
        jdbc.sql("update successor_action set done_on = ? where id = ?").params(done ? today() : null, actionId).update();
        return post(successor(successorId).postId());
    }

    // ---- the talent review -------------------------------------------------------------------------------------

    private static final String REVIEW_SELECT = """
            select r.id, r.year, r.name, r.category_id, gc.name as category_name, r.unit_id, u.name as unit_name, r.status, r.note,
                   r.opened_by, r.opened_at,
                   (select count(*) from talent_review_member m where m.review_id = r.id) as members,
                   (select count(*) from talent_review_member m where m.review_id = r.id and m.potential_proposed is not null) as proposed,
                   (select count(*) from talent_review_member m where m.review_id = r.id and m.potential is not null and m.performance is not null) as settled
            from talent_review r left join grade_category gc on gc.id = r.category_id left join org_unit u on u.id = r.unit_id
            """;

    public List<Review> reviews() {
        require("reviews", null);
        return jdbc.sql(REVIEW_SELECT + " order by r.year desc, r.opened_at desc").query(Review.class).list();
    }

    private Review reviewRow(long id) {
        return jdbc.sql(REVIEW_SELECT + " where r.id = ?").param(id).query(Review.class).optional()
                .orElseThrow(() -> new NotFoundException("Talent review not found."));
    }

    /** Opens a review for a staff category and/or a unit; performance comes from each person's last approved evaluation. */
    @Transactional
    public ReviewView open(int year, String name, Long categoryId, Long unitId, String note) {
        requireChange();
        Set<Long> branch = unitId == null ? null : org.branch(unitId);
        int low = setting("TALENT_PERF_LOW", 60);
        int high = setting("TALENT_PERF_HIGH", 80);
        long id = jdbc.sql("""
                insert into talent_review (year, name, category_id, unit_id, note, opened_by) values (?, ?, ?, ?, ?, ?) returning id""")
                .params(year, name.trim(), categoryId, unitId, clean(note), CurrentUser.email()).query(Long.class).single();
        List<Long> people = org.people().stream().filter(m -> branch == null || branch.contains(m.unitId()))
                .map(OrgStructure.Member::userId).toList();
        int added = 0;
        for (Long userId : people) {
            if (categoryId != null && jdbc.sql("select count(*) from app_user where id = ? and grade_category_id = ? and not not_employee")
                    .params(userId, categoryId).query(Integer.class).single() == 0) {
                continue;
            }
            if (jdbc.sql("select count(*) from app_user where id = ? and not not_employee").param(userId).query(Integer.class).single() == 0) {
                continue;
            }
            BigDecimal score = jdbc.sql("""
                    select total_percent from evaluation where user_id = ? and status = 'APPROVED' and total_percent is not null
                    and period_end <= ? order by period_end desc limit 1""").params(userId, LocalDate.of(year, 12, 31))
                    .query(BigDecimal.class).optional().orElse(null);
            Integer perf = score == null ? null : score.doubleValue() < low ? 1 : score.doubleValue() < high ? 2 : 3;
            jdbc.sql("""
                    insert into talent_review_member (review_id, user_id, score, performance_auto, performance) values (?, ?, ?, ?, ?)""")
                    .params(id, userId, score, perf, perf).update();
            added++;
        }
        if (added == 0) {
            throw new BusinessRuleException("Nobody in this group.");
        }
        audit.record("talent.review_opened", "talent_review", id, Map.of("name", name.trim(), "people", added));
        // Each manager is asked once for the potential of their people.
        Set<Long> managers = new HashSet<>();
        for (Long userId : people) {
            org.managerOf(userId).ifPresent(b -> managers.add(b.userId()));
        }
        String reviewName = name.trim();
        for (Long m : managers) {
            notifications.send(m, "talent.proposal", () -> Texts.t("Talent review: %s", reviewName),
                    () -> Texts.t("Propose the potential of the people of your team for the talent review."), "/talent/proposals");
        }
        return review(id);
    }

    public ReviewView review(long id) {
        require("review", id);
        Review r = reviewRow(id);
        Map<Long, Job> jobs = currentJobs();
        List<Member> rows = jdbc.sql("""
                select m.id, m.review_id, m.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, null as occupation_name,
                       (select ou.name from org_assignment a join org_unit ou on ou.id = a.unit_id
                        where a.user_id = u.id and a.valid_to is null order by a.valid_from desc limit 1) as unit_name,
                       null as manager_name, m.score, m.performance_auto, m.performance, m.performance_reason, m.potential_proposed,
                       m.potential_reason, person_name(p.first_name, p.middle_name, p.last_name) as proposed_by_name, m.potential,
                       m.flight_risk, m.mobility, m.note, m.settled_by, null as box
                from talent_review_member m join app_user u on u.id = m.user_id left join app_user p on p.id = m.proposed_by
                where m.review_id = ? order by name""").param(id).query(Member.class).list();
        List<Member> members = new ArrayList<>();
        Map<Integer, Integer> grid = new LinkedHashMap<>();
        for (int b = 1; b <= 9; b++) {
            grid.put(b, 0);
        }
        for (Member m : rows) {
            Job j = jobs.get(m.userId());
            String manager = org.managerOf(m.userId()).map(OrgStructure.Boss::userName).orElse(null);
            Integer box = box(m.performance(), m.potential());
            if (box != null) {
                grid.merge(box, 1, Integer::sum);
            }
            members.add(new Member(m.id(), m.reviewId(), m.userId(), m.name(), j == null ? null : j.occupationName(), m.unitName(),
                    manager, m.score(), m.performanceAuto(), m.performance(), m.performanceReason(), m.potentialProposed(),
                    m.potentialReason(), m.proposedByName(), m.potential(), m.flightRisk(), m.mobility(), m.note(), m.settledBy(), box));
        }
        return new ReviewView(r, members, grid);
    }

    private record MemberRow(long id, long reviewId, long userId, Integer performanceAuto, String status) {}

    private MemberRow member(long id) {
        return jdbc.sql("""
                select m.id, m.review_id, m.user_id, m.performance_auto, r.status from talent_review_member m
                join talent_review r on r.id = m.review_id where m.id = ?""").param(id).query(MemberRow.class).optional()
                .orElseThrow(() -> new NotFoundException("Talent review not found."));
    }

    /** HR and the Direction settle performance, potential, flight risk and mobility. */
    @Transactional
    public ReviewView settle(long memberId, Settle in) {
        requireChange();
        MemberRow m = member(memberId);
        if (!m.status().equals("OPEN")) {
            throw new BusinessRuleException("This review is closed.");
        }
        check(in.performance());
        check(in.potential());
        if (in.flightRisk() != null && !RISKS.contains(in.flightRisk()) || in.mobility() != null && !MOBILITY.contains(in.mobility())) {
            throw new BusinessRuleException("Unknown flight risk or mobility.");
        }
        if (in.performance() != null && m.performanceAuto() != null && !in.performance().equals(m.performanceAuto())
                && clean(in.performanceReason()) == null) {
            throw new BusinessRuleException("Say why the performance differs from the evaluation.");
        }
        jdbc.sql("""
                update talent_review_member set performance = ?, performance_reason = ?, potential = ?, flight_risk = ?, mobility = ?,
                       note = ?, settled_by = ?, settled_at = now() where id = ?""")
                .params(in.performance(), clean(in.performanceReason()), in.potential(), in.flightRisk(), in.mobility(),
                        clean(in.note()), CurrentUser.email(), memberId).update();
        audit.record("talent.settled", "talent_review", m.reviewId(), Map.of("user", m.userId()));
        return review(m.reviewId());
    }

    private static void check(Integer n) {
        if (n != null && (n < 1 || n > 3)) {
            throw new BusinessRuleException("A rating goes from 1 to 3.");
        }
    }

    @Transactional
    public ReviewView close(long id) {
        requireChange();
        reviewRow(id);
        jdbc.sql("update talent_review set status = 'CLOSED', closed_at = now() where id = ?").param(id).update();
        audit.record("talent.review_closed", "talent_review", id, Map.of());
        return review(id);
    }

    // ---- the manager's proposals -------------------------------------------------------------------------------

    /** A manager sees only the people of their team in open reviews, and only the potential they propose. */
    public List<Proposal> proposals() {
        Long me = CurrentUser.id();
        if (me == null) {
            return List.of();
        }
        Set<Long> team = org.teamOf(me);
        Map<Long, Job> jobs = currentJobs();
        return jdbc.sql("""
                select m.id as member_id, r.name as review_name, r.year, m.user_id,
                       person_name(u.first_name, u.middle_name, u.last_name) as name, null as occupation_name,
                       m.potential_proposed, m.potential_reason
                from talent_review_member m join talent_review r on r.id = m.review_id join app_user u on u.id = m.user_id
                where r.status = 'OPEN' and m.user_id <> ? order by r.year desc, name""").param(me).query(Proposal.class).list()
                .stream().filter(p -> team.contains(p.userId()))
                .map(p -> new Proposal(p.memberId(), p.reviewName(), p.year(), p.userId(), p.name(),
                        jobs.containsKey(p.userId()) ? jobs.get(p.userId()).occupationName() : null, p.potentialProposed(), p.potentialReason()))
                .toList();
    }

    @Transactional
    public List<Proposal> propose(long memberId, int potential, String reason) {
        MemberRow m = member(memberId);
        Long me = CurrentUser.id();
        if (me == null || Objects.equals(me, m.userId()) || !org.inTeam(me, m.userId())) {
            throw new AccessDeniedException("Only the person's manager proposes their potential.");
        }
        if (!m.status().equals("OPEN")) {
            throw new BusinessRuleException("This review is closed.");
        }
        check(potential);
        if (clean(reason) == null) {
            throw new BusinessRuleException("Give a short reason.");
        }
        jdbc.sql("""
                update talent_review_member set potential_proposed = ?, potential_reason = ?, proposed_by = ?, proposed_at = now(),
                       potential = coalesce(potential, ?) where id = ?""")
                .params(potential, reason.trim(), me, potential, memberId).update();
        audit.record("talent.potential_proposed", "talent_review", m.reviewId(), Map.of("user", m.userId()));
        return proposals();
    }

    // ---- coverage and the person -------------------------------------------------------------------------------

    public Coverage coverage() {
        require("coverage", null);
        List<Post> all = posts(null);
        LocalDate horizon = today().plusYears(2);
        int withoutReady = 0;
        int atRisk = 0;
        int retiring = 0;
        int vacant = 0;
        List<Post> flagged = new ArrayList<>();
        for (Post p : all) {
            boolean noReady = p.successors().stream().noneMatch(s -> !s.readiness().equals("THREE_PLUS"));
            boolean risk = "HIGH".equals(p.flightRisk());
            boolean retire = p.retiresOn() != null && !p.retiresOn().isAfter(horizon);
            boolean empty = p.holders().isEmpty();
            withoutReady += noReady ? 1 : 0;
            atRisk += risk ? 1 : 0;
            retiring += retire ? 1 : 0;
            vacant += empty ? 1 : 0;
            if (noReady || risk || retire || empty) {
                flagged.add(p);
            }
        }
        return new Coverage(all.size(), all.size() - withoutReady, withoutReady, atRisk, retiring, vacant, flagged);
    }

    public PersonView person(long userId) {
        require("person", userId);
        Latest l = latest().get(userId);
        List<Post> all = posts(null);
        List<String> successorOf = all.stream().filter(p -> p.successors().stream().anyMatch(x -> x.userId() == userId))
                .map(Talent::label).toList();
        List<String> holds = all.stream().filter(p -> p.holders().stream().anyMatch(h -> h.userId() == userId))
                .map(Talent::label).toList();
        return new PersonView(userId, l == null ? null : box(l.performance(), l.potential()), l == null ? null : l.performance(),
                l == null ? null : l.potential(), l == null ? null : l.flightRisk(), l == null ? null : l.mobility(),
                l == null ? null : l.reviewName(), successorOf, holds);
    }

    static String label(Post p) {
        if (p.title() != null) {
            return p.title();
        }
        return p.head() ? Texts.t("Head of %s", p.unitName()) : p.occupationName() + " · " + p.unitName();
    }
}
