package cd.hrpay.evaluation;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.Notifications;
import cd.hrpay.org.OrgStructure;
import cd.hrpay.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
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
 * Individual objectives: the direct manager (or HR) writes a person's objectives for a year with a weight each, shares
 * them once the weights add up to 100, and the person acknowledges them. Around mid-year the manager records a
 * check-in. A unit's own objectives can be set by HR or the unit's head, and a person's objective may follow one.
 * The evaluation scores the shared objectives (see {@link Evaluations}).
 */
@Service
public class Objectives {

    /** From the second half of the year, objectives with no check-in are late. */
    static final int CHECKIN_MONTH = 7;

    /** An objective counts as reached at 100% progress or a score of 75 or more in an evaluation. */
    static final BigDecimal REACHED = BigDecimal.valueOf(75);

    public record Objective(long id, Long userId, Long orgUnitId, String unitName, int year, Long parentId,
                            String parentTitle, String title, String measure, String target, BigDecimal weight,
                            LocalDate dueDate, String status, Long setBy, String setByName, String employeeComment,
                            OffsetDateTime acknowledgedAt, Integer progress, String checkinComment,
                            OffsetDateTime checkinAt, String developmentNeed, BigDecimal lastScore) {}

    public record Person(long userId, String name, int year, boolean canEdit, boolean mine, BigDecimal weightTotal,
                         boolean late, List<Objective> objectives, List<Objective> unitObjectives) {}

    public record TeamRow(long userId, String name, String unit, int objectives, BigDecimal weightTotal, int drafts,
                          int shared, int acknowledged, int checkedIn, Integer averageProgress, boolean late) {}

    public record UnitRow(long unitId, String unitName, int people, int withObjectives, int acknowledged,
                          int checkedIn, int objectives, int reached) {}

    public record Gap(long userId, String name, String unit, String detail) {}

    public record Overview(int year, boolean checkinDue, List<UnitRow> units, List<Gap> noObjectives,
                           List<Gap> lateCheckins, List<Gap> developmentNeeds) {}

    public record Input(Long userId, Long orgUnitId, int year, Long parentId, String title, String measure,
                        String target, BigDecimal weight, LocalDate dueDate) {}

    private static final String SELECT = """
            select o.id, o.user_id, o.org_unit_id, ou.name as unit_name, o.year, o.parent_id, p.title as parent_title,
                   o.title, o.measure, o.target, o.weight, o.due_date, o.status, o.set_by,
                   person_name(s.first_name, s.middle_name, s.last_name) as set_by_name, o.employee_comment,
                   o.acknowledged_at, o.progress, o.checkin_comment, o.checkin_at, o.development_need,
                   (select eo.score from evaluation_objective eo join evaluation e on e.id = eo.evaluation_id
                    where eo.objective_id = o.id and eo.score is not null order by e.period_end desc limit 1) as last_score
            from objective o left join org_unit ou on ou.id = o.org_unit_id left join objective p on p.id = o.parent_id
            left join app_user s on s.id = o.set_by
            """;

    private static final ZoneId ZONE = ZoneId.of("Africa/Kinshasa");

    private final JdbcClient jdbc;
    private final OrgStructure org;
    private final Notifications notifications;
    private final AuditService audit;
    private final Clock clock;

    public Objectives(JdbcClient jdbc, OrgStructure org, Notifications notifications, AuditService audit, Clock clock) {
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
        return CurrentUser.has("evaluation.manage");
    }

    private static boolean seesAll() {
        return CurrentUser.has("evaluation.manage") || CurrentUser.has("evaluation.approve");
    }

    /** HR, or the person's manager; never the person. */
    public boolean canEdit(long userId) {
        long me = CurrentUser.id();
        if (userId == me) {
            return false;
        }
        return isHr() || (CurrentUser.has("evaluation.score.team") && org.inTeam(me, userId));
    }

    private void requireEdit(long userId) {
        if (!canEdit(userId)) {
            throw new AccessDeniedException("Only the person's manager or HR can change their objectives.");
        }
    }

    private void requireSee(long userId) {
        if (userId != CurrentUser.id() && !seesAll() && !canEdit(userId)) {
            throw new AccessDeniedException("You cannot see this person's objectives.");
        }
    }

    /** The units whose objectives the current user may set: all for HR, else the units they head and those below. */
    private Set<Long> unitsIRun() {
        Set<Long> out = new HashSet<>();
        for (OrgStructure.Head h : org.headedBy(CurrentUser.id())) {
            out.addAll(org.branch(h.unitId()));
        }
        return out;
    }

    private void requireUnitEdit(long unitId) {
        if (!isHr() && !(CurrentUser.has("evaluation.score.team") && unitsIRun().contains(unitId))) {
            throw new AccessDeniedException("Only HR or the unit's head can change the unit's objectives.");
        }
    }

    // ---- reading ---------------------------------------------------------------------------------------------

    public Objective get(long id) {
        return jdbc.sql(SELECT + "where o.id = ?").param(id).query(Objective.class).optional()
                .orElseThrow(() -> new NotFoundException("Objective not found."));
    }

    private List<Objective> ofPerson(long userId, int year) {
        return jdbc.sql(SELECT + "where o.user_id = ? and o.year = ? order by o.status = 'CANCELLED', o.weight desc, o.id")
                .params(userId, year).query(Objective.class).list();
    }

    /** A person's objectives for the year, with the unit objectives they can follow. */
    public Person person(long userId, int year) {
        requireSee(userId);
        String name = jdbc.sql("select person_name(first_name, middle_name, last_name) from app_user where id = ?")
                .param(userId).query(String.class).optional().orElseThrow(() -> new NotFoundException("Employee not found."));
        boolean mine = userId == CurrentUser.id();
        List<Objective> list = ofPerson(userId, year).stream()
                .filter(o -> !mine || !o.status().equals("DRAFT")).toList();
        Long unit = org.unitsOn(today()).get(userId);
        List<Objective> units = unit == null ? List.of() : jdbc.sql(SELECT + """
                where o.org_unit_id in (%s) and o.year = ? and o.status <> 'CANCELLED' order by o.title"""
                .formatted(joined(org.ancestors(unit)))).param(year).query(Objective.class).list();
        return new Person(userId, name, year, canEdit(userId), mine, total(list), late(list, year), list, units);
    }

    private static String joined(List<Long> ids) {
        return ids.isEmpty() ? "-1" : String.join(",", ids.stream().map(String::valueOf).toList());
    }

    private static BigDecimal total(List<Objective> list) {
        return list.stream().filter(o -> !o.status().equals("CANCELLED")).map(Objective::weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean late(List<Objective> list, int year) {
        LocalDate t = today();
        boolean due = year < t.getYear() || (year == t.getYear() && t.getMonthValue() >= CHECKIN_MONTH);
        return due && list.stream().anyMatch(o -> List.of("SHARED", "ACKNOWLEDGED").contains(o.status()) && o.checkinAt() == null);
    }

    /** The people the current user writes objectives for (their team, or everyone for HR), with where each stands. */
    public List<TeamRow> team(int year) {
        long me = CurrentUser.id();
        Set<Long> ids = seesAll() ? null : org.teamOf(me);
        if (ids != null && ids.isEmpty()) {
            return List.of();
        }
        Map<Long, String> unitNames = new HashMap<>();
        org.placementsOn(today()).forEach((u, p) -> unitNames.put(u, p.unitName()));
        Map<Long, List<Objective>> by = new HashMap<>();
        jdbc.sql(SELECT + "where o.user_id is not null and o.year = ? and o.status <> 'CANCELLED'").param(year)
                .query(Objective.class).list().forEach(o -> by.computeIfAbsent(o.userId(), k -> new ArrayList<>()).add(o));
        Map<Long, String> names = names();
        List<TeamRow> out = new ArrayList<>();
        for (long[] p : employees()) {
            long id = p[0];
            if (id == me || (ids != null && !ids.contains(id))) {
                continue;
            }
            List<Objective> list = by.getOrDefault(id, List.of());
            List<Integer> progress = list.stream().map(Objective::progress).filter(Objects::nonNull).toList();
            out.add(new TeamRow(id, names.get(id), unitNames.get(id), list.size(), total(list),
                    (int) list.stream().filter(o -> o.status().equals("DRAFT")).count(),
                    (int) list.stream().filter(o -> o.status().equals("SHARED")).count(),
                    (int) list.stream().filter(o -> o.status().equals("ACKNOWLEDGED")).count(),
                    (int) list.stream().filter(o -> o.checkinAt() != null).count(),
                    progress.isEmpty() ? null : (int) Math.round(progress.stream().mapToInt(Integer::intValue).average().orElse(0)),
                    late(list, year)));
        }
        out.sort(java.util.Comparator.comparing(TeamRow::name));
        return out;
    }

    private Map<Long, String> names() {
        Map<Long, String> out = new HashMap<>();
        jdbc.sql("select id, person_name(first_name, middle_name, last_name) from app_user").query(rs -> {
            out.put(rs.getLong(1), rs.getString(2));
        });
        return out;
    }

    /** Active employees with a contract in force today. */
    private List<long[]> employees() {
        LocalDate t = today();
        return jdbc.sql("""
                select distinct u.id from app_user u join employee_contract c on c.user_id = u.id
                where u.status = 'ACTIVE' and not u.not_employee and c.start_date <= ? and (c.end_date is null or c.end_date >= ?)""")
                .params(t, t).query((rs, i) -> new long[] {rs.getLong(1)}).list();
    }

    /** The unit objectives the current user can see: all for HR, else those of the units they run. */
    public List<Objective> unitObjectives(int year) {
        List<Objective> all = jdbc.sql(SELECT + "where o.org_unit_id is not null and o.year = ? order by ou.name, o.title")
                .param(year).query(Objective.class).list();
        if (seesAll()) {
            return all;
        }
        Set<Long> mine = unitsIRun();
        return all.stream().filter(o -> mine.contains(o.orgUnitId())).toList();
    }

    /** By unit: who has objectives, acknowledged, checked in, and how many were reached; plus the gaps. */
    public Overview overview(int year) {
        if (!seesAll()) {
            throw new AccessDeniedException("Only HR sees the objectives of everyone.");
        }
        LocalDate t = today();
        boolean due = year < t.getYear() || (year == t.getYear() && t.getMonthValue() >= CHECKIN_MONTH);
        Map<Long, OrgStructure.Placement> places = org.placementsOn(t);
        Map<Long, List<Objective>> by = new HashMap<>();
        jdbc.sql(SELECT + "where o.user_id is not null and o.year = ? and o.status <> 'CANCELLED'").param(year)
                .query(Objective.class).list().forEach(o -> by.computeIfAbsent(o.userId(), k -> new ArrayList<>()).add(o));
        Map<Long, int[]> units = new LinkedHashMap<>();
        Map<Long, String> unitNames = new HashMap<>();
        List<Gap> none = new ArrayList<>();
        List<Gap> late = new ArrayList<>();
        List<Gap> needs = new ArrayList<>();
        Map<Long, String> names = names();
        for (long[] p : employees()) {
            long id = p[0];
            OrgStructure.Placement place = places.get(id);
            long unit = place == null ? 0 : place.unitId();
            unitNames.putIfAbsent(unit, place == null ? null : place.unitName());
            String unitName = place == null ? null : place.unitName();
            int[] row = units.computeIfAbsent(unit, k -> new int[6]);
            row[0]++;
            List<Objective> list = by.getOrDefault(id, List.of());
            List<Objective> live = list.stream().filter(o -> !o.status().equals("DRAFT")).toList();
            if (live.isEmpty()) {
                none.add(new Gap(id, names.get(id), unitName, list.isEmpty() ? null
                        : Texts.t("%d objectives not shared yet", list.size())));
                continue;
            }
            row[1]++;
            if (live.stream().allMatch(o -> o.status().equals("ACKNOWLEDGED"))) {
                row[2]++;
            }
            if (live.stream().allMatch(o -> o.checkinAt() != null)) {
                row[3]++;
            } else if (due) {
                late.add(new Gap(id, names.get(id), unitName, Texts.t("%d of %d objectives without a check-in",
                        live.stream().filter(o -> o.checkinAt() == null).count(), live.size())));
            }
            row[4] += live.size();
            row[5] += (int) live.stream().filter(Objectives::reached).count();
            live.stream().filter(o -> o.developmentNeed() != null && !o.developmentNeed().isBlank())
                    .forEach(o -> needs.add(new Gap(id, names.get(id), unitName, o.title() + ": " + o.developmentNeed())));
        }
        List<UnitRow> rows = new ArrayList<>();
        units.forEach((u, r) -> rows.add(new UnitRow(u, unitNames.get(u), r[0], r[1], r[2], r[3], r[4], r[5])));
        rows.sort(java.util.Comparator.comparing(r -> r.unitName() == null ? "~" : r.unitName()));
        java.util.Comparator<Gap> byName = java.util.Comparator.comparing(Gap::name);
        none.sort(byName);
        late.sort(byName);
        needs.sort(byName);
        return new Overview(year, due, rows, none, late, needs);
    }

    static boolean reached(Objective o) {
        return (o.progress() != null && o.progress() >= 100) || (o.lastScore() != null && o.lastScore().compareTo(REACHED) >= 0);
    }

    // ---- writing ---------------------------------------------------------------------------------------------

    @Transactional
    public Objective save(Long id, Input in) {
        Objective old = id == null ? null : get(id);
        Long userId = old != null ? old.userId() : in.userId();
        Long unitId = old != null ? old.orgUnitId() : in.orgUnitId();
        int year = old != null ? old.year() : in.year();
        if ((userId == null) == (unitId == null)) {
            throw new BusinessRuleException("An objective is for one person or for one unit.");
        }
        if (userId != null) {
            requireEdit(userId);
        } else {
            requireUnitEdit(unitId);
        }
        if (old != null && old.status().equals("CANCELLED")) {
            throw new BusinessRuleException("This objective was cancelled.");
        }
        if (in.parentId() != null) {
            Objective parent = get(in.parentId());
            if (parent.orgUnitId() == null || parent.year() != year || Objects.equals(parent.id(), id)) {
                throw new BusinessRuleException("An objective can only follow a unit objective of the same year.");
            }
        }
        BigDecimal weight = userId == null ? BigDecimal.ZERO : in.weight() == null ? BigDecimal.ZERO : in.weight();
        if (id == null) {
            id = jdbc.sql("""
                    insert into objective (user_id, org_unit_id, year, parent_id, title, measure, target, weight, due_date,
                                           status, set_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(userId, unitId, year, in.parentId(), in.title().strip(), clean(in.measure()), clean(in.target()),
                            weight, in.dueDate(), userId == null ? "SHARED" : "DRAFT", CurrentUser.id())
                    .query(Long.class).single();
            audit.record("objective.added", userId != null ? "user" : "org_unit", userId != null ? userId : unitId,
                    Map.of("objective", id, "year", year));
        } else {
            // A change to a shared objective asks the person to acknowledge it again.
            jdbc.sql("""
                    update objective set parent_id = ?, title = ?, measure = ?, target = ?, weight = ?, due_date = ?,
                           status = case when status = 'ACKNOWLEDGED' then 'SHARED' else status end,
                           acknowledged_at = case when status = 'ACKNOWLEDGED' then null else acknowledged_at end,
                           updated_at = now()
                    where id = ?""")
                    .params(in.parentId(), in.title().strip(), clean(in.measure()), clean(in.target()), weight, in.dueDate(), id)
                    .update();
            audit.record("objective.changed", userId != null ? "user" : "org_unit", userId != null ? userId : unitId,
                    Map.of("objective", id));
            if (userId != null && !old.status().equals("DRAFT")) {
                long person = userId;
                notifications.send(person, "objective.changed", () -> Texts.t("An objective was changed: %s", in.title().strip()),
                        () -> Texts.t("Read it and acknowledge it again."), "/objectives");
            }
        }
        return get(id);
    }

    /** A draft is deleted; a shared objective is cancelled, so its history stays. */
    @Transactional
    public void remove(long id) {
        Objective o = get(id);
        if (o.userId() != null) {
            requireEdit(o.userId());
        } else {
            requireUnitEdit(o.orgUnitId());
        }
        if (o.status().equals("DRAFT")) {
            jdbc.sql("update objective set parent_id = null where parent_id = ?").param(id).update();
            jdbc.sql("delete from objective where id = ?").param(id).update();
        } else {
            jdbc.sql("update objective set status = 'CANCELLED', updated_at = now() where id = ?").param(id).update();
        }
        audit.record("objective.removed", o.userId() != null ? "user" : "org_unit",
                o.userId() != null ? o.userId() : o.orgUnitId(), Map.of("objective", id, "status", o.status()));
    }

    /** Shares the drafts of a person's year; the weights of the year's objectives must add up to 100. */
    @Transactional
    public Person share(long userId, int year) {
        requireEdit(userId);
        List<Objective> list = ofPerson(userId, year);
        if (list.stream().noneMatch(o -> o.status().equals("DRAFT"))) {
            throw new BusinessRuleException("There is no new objective to share.");
        }
        BigDecimal sum = total(list);
        if (sum.compareTo(BigDecimal.valueOf(100)) != 0) {
            throw new BusinessRuleException(Texts.t("The weights must add up to 100%% (now %s%%).", sum.stripTrailingZeros().toPlainString()));
        }
        int n = jdbc.sql("update objective set status = 'SHARED', updated_at = now() where user_id = ? and year = ? and status = 'DRAFT'")
                .params(userId, year).update();
        audit.record("objective.shared", "user", userId, Map.of("year", year, "objectives", n));
        notifications.send(userId, "objective.shared", () -> Texts.t("Your objectives for %d", year),
                () -> Texts.t("Your manager shared %d objectives. Read them and acknowledge them.", n), "/objectives");
        return person(userId, year);
    }

    /** The person acknowledges the shared objectives of the year, with a comment if they wish. */
    @Transactional
    public Person acknowledge(int year, String comment) {
        long me = CurrentUser.id();
        List<Objective> shared = ofPerson(me, year).stream().filter(o -> o.status().equals("SHARED")).toList();
        if (shared.isEmpty()) {
            throw new BusinessRuleException("There is nothing to acknowledge.");
        }
        jdbc.sql("""
                update objective set status = 'ACKNOWLEDGED', acknowledged_at = now(), employee_comment = ?, updated_at = now()
                where user_id = ? and year = ? and status = 'SHARED'""").params(clean(comment), me, year).update();
        audit.record("objective.acknowledged", "user", me, Map.of("year", year));
        Set<Long> told = new HashSet<>();
        String who = names().get(me);
        for (Objective o : shared) {
            if (o.setBy() != null && o.setBy() != me && told.add(o.setBy())) {
                notifications.send(o.setBy(), "objective.acknowledged", () -> Texts.t("%s acknowledged the objectives", who),
                        comment == null || comment.isBlank() ? null : () -> comment.strip(), "/objectives?person=" + me);
            }
        }
        return person(me, year);
    }

    /** The manager's check-in: progress, a comment, and a development need if any. */
    @Transactional
    public Objective checkin(long id, int progress, String comment, String developmentNeed) {
        Objective o = get(id);
        if (o.userId() == null) {
            requireUnitEdit(o.orgUnitId());
        } else {
            requireEdit(o.userId());
        }
        if (!List.of("SHARED", "ACKNOWLEDGED").contains(o.status())) {
            throw new BusinessRuleException("Share the objective before the check-in.");
        }
        jdbc.sql("""
                update objective set progress = ?, checkin_comment = ?, development_need = ?, checkin_at = now(),
                       checkin_by = ?, updated_at = now() where id = ?""")
                .params(progress, clean(comment), clean(developmentNeed), CurrentUser.id(), id).update();
        audit.record("objective.checkin", o.userId() != null ? "user" : "org_unit",
                o.userId() != null ? o.userId() : o.orgUnitId(), Map.of("objective", id, "progress", progress));
        return get(id);
    }

    // ---- mid-year reminder -----------------------------------------------------------------------------------

    /** From July, tells each manager once a year how many of their people still have objectives without a check-in. */
    public int remindCheckins() {
        LocalDate t = today();
        if (t.getMonthValue() < CHECKIN_MONTH) {
            return 0;
        }
        LocalDate key = LocalDate.of(t.getYear(), CHECKIN_MONTH, 1);
        Map<Long, Integer> byManager = new HashMap<>();
        for (Long user : jdbc.sql("""
                select distinct user_id from objective where user_id is not null and year = ?
                  and status in ('SHARED', 'ACKNOWLEDGED') and checkin_at is null""").param(t.getYear()).query(Long.class).list()) {
            org.managerOf(user).ifPresent(b -> byManager.merge(b.userId(), 1, Integer::sum));
        }
        int n = 0;
        for (Map.Entry<Long, Integer> e : byManager.entrySet()) {
            boolean first = jdbc.sql("insert into reminder_sent (user_id, kind, on_day) values (?, 'objectives.checkin', ?) on conflict do nothing")
                    .params(e.getKey(), key).update() > 0;
            if (first) {
                int people = e.getValue();
                notifications.send(e.getKey(), "objectives.checkin", () -> Texts.t("Mid-year check-in of objectives"),
                        () -> Texts.t("%d people in your team have objectives with no check-in yet: record their progress.", people),
                        "/objectives");
                n++;
            }
        }
        return n;
    }

    // ---- for the evaluation ----------------------------------------------------------------------------------

    /** Shared or acknowledged objectives of a person's year, for the evaluation. */
    List<Objective> forEvaluation(long userId, int year) {
        return jdbc.sql(SELECT + "where o.user_id = ? and o.year = ? and o.status in ('SHARED', 'ACKNOWLEDGED') order by o.weight desc, o.id")
                .params(userId, year).query(Objective.class).list();
    }

    static BigDecimal weighted(List<BigDecimal[]> scoreAndWeight) {
        BigDecimal weights = scoreAndWeight.stream().map(s -> s[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (weights.signum() == 0) {
            BigDecimal sum = scoreAndWeight.stream().map(s -> s[0]).reduce(BigDecimal.ZERO, BigDecimal::add);
            return sum.divide(BigDecimal.valueOf(scoreAndWeight.size()), 2, RoundingMode.HALF_UP);
        }
        return scoreAndWeight.stream().map(s -> s[0].multiply(s[1])).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(weights, 2, RoundingMode.HALF_UP);
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
