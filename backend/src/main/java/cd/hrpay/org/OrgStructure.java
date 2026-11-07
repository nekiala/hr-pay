package cd.hrpay.org;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.security.CurrentUser;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The organizational structure: levels (data), a tree of dated units, who heads each unit, and where each employee
 * works over time. An employee's manager is the head of their unit, or of the nearest unit above when it is vacant
 * or when they head it themself.
 */
@Service
public class OrgStructure {

    public record Level(long id, String code, String name, int rank, String headTitle, boolean staff, boolean active) {}

    public record Head(long id, long unitId, long userId, String userName, String role, boolean interim, String title,
                       LocalDate validFrom, LocalDate validTo) {}

    public record Unit(long id, String code, String name, long levelId, String levelName, int rank, boolean staff,
                       Long parentId, String costCentre, LocalDate validFrom, LocalDate validTo, boolean open,
                       Head head, Head deputy, Integer members, Integer totalMembers) {}

    public record Member(long userId, String name, String registrationNo, long unitId, String unitName,
                         LocalDate since, Long reportsToId, String reportsToName, List<Long> heads) {}

    public record Assignment(long id, long unitId, String unitCode, String unitName, LocalDate validFrom,
                             LocalDate validTo, String reason, String note, String createdBy) {}

    public static final List<String> REASONS = List.of("HIRE", "TRANSFER", "PROMOTION", "REORGANISATION", "CORRECTION");

    private record UnitRow(long id, String code, String name, long levelId, String levelName, int rank, boolean staff,
                           Long parentId, String costCentre, LocalDate validFrom, LocalDate validTo) {}

    private static final String UNIT_SELECT = """
            select u.id, u.code, u.name, u.level_id, l.name as level_name, l.rank, l.staff, u.parent_id, u.cost_centre,
                   u.valid_from, u.valid_to
            from org_unit u join org_level l on l.id = u.level_id""";

    private static final String HEAD_SELECT = """
            select h.id, h.unit_id, h.user_id, a.first_name || ' ' || a.last_name as user_name, h.role, h.interim,
                   h.title, h.valid_from, h.valid_to
            from org_head h join app_user a on a.id = h.user_id""";

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final Clock clock;

    public OrgStructure(JdbcClient jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneId.of("Africa/Kinshasa")));
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ---- levels ----------------------------------------------------------------------------------------------

    public List<Level> levels() {
        return jdbc.sql("select id, code, name, rank, head_title, staff, active from org_level order by rank, staff, name")
                .query(Level.class).list();
    }

    private Level level(long id) {
        return jdbc.sql("select id, code, name, rank, head_title, staff, active from org_level where id = ?").param(id)
                .query(Level.class).optional().orElseThrow(() -> new NotFoundException("Level not found."));
    }

    @Transactional
    public Level saveLevel(Long id, String code, String name, int rank, String headTitle, boolean staff, boolean active) {
        if (id == null) {
            if (jdbc.sql("select count(*) from org_level where code = ?").param(code.toUpperCase()).query(Integer.class).single() > 0) {
                throw new ConflictException("A level with code " + code.toUpperCase() + " already exists.");
            }
            id = jdbc.sql("insert into org_level (code, name, rank, head_title, staff, active) values (?, ?, ?, ?, ?, ?) returning id")
                    .params(code.toUpperCase(), name.trim(), rank, headTitle.trim(), staff, active).query(Long.class).single();
            audit.record("org_level.created", "org_level", id, Map.of("code", code.toUpperCase(), "rank", rank));
            return level(id);
        }
        Level old = level(id);
        if (old.rank() != rank || old.staff() != staff) {
            // Moving a level must keep every open unit below its parent and above its children.
            for (UnitRow u : openUnits().values()) {
                if (u.levelId() != id) {
                    continue;
                }
                checkPlacement(u.id(), u.parentId(), rank, staff);
            }
        }
        if (!active && jdbc.sql("select count(*) from org_unit where level_id = ? and valid_to is null").param(id)
                .query(Integer.class).single() > 0) {
            throw new BusinessRuleException("Close or move the open units of this level first.");
        }
        jdbc.sql("update org_level set name = ?, rank = ?, head_title = ?, staff = ?, active = ? where id = ?")
                .params(name.trim(), rank, headTitle.trim(), staff, active, id).update();
        audit.record("org_level.updated", "org_level", id, Map.of("name", name.trim(), "rank", rank, "active", active));
        return level(id);
    }

    // ---- units -----------------------------------------------------------------------------------------------

    private Map<Long, UnitRow> allUnits() {
        Map<Long, UnitRow> m = new LinkedHashMap<>();
        jdbc.sql(UNIT_SELECT + " order by l.rank, u.name").query(UnitRow.class).list().forEach(u -> m.put(u.id(), u));
        return m;
    }

    private Map<Long, UnitRow> openUnits() {
        Map<Long, UnitRow> m = new LinkedHashMap<>();
        allUnits().values().stream().filter(u -> u.validTo() == null).forEach(u -> m.put(u.id(), u));
        return m;
    }

    private UnitRow unitRow(long id) {
        return jdbc.sql(UNIT_SELECT + " where u.id = ?").param(id).query(UnitRow.class).optional()
                .orElseThrow(() -> new NotFoundException("Unit not found."));
    }

    private Map<Long, Head> openHeads(String role) {
        Map<Long, Head> m = new HashMap<>();
        jdbc.sql(HEAD_SELECT + " where h.valid_to is null and h.role = ?").param(role).query(Head.class).list()
                .forEach(h -> m.put(h.unitId(), h));
        return m;
    }

    /** Open assignments of active employees: user id → unit id. */
    private Map<Long, Long> placements() {
        Map<Long, Long> m = new LinkedHashMap<>();
        jdbc.sql("""
                select a.user_id, a.unit_id from org_assignment a join app_user u on u.id = a.user_id
                where a.valid_to is null and u.status = 'ACTIVE'""")
                .query((rs, i) -> m.put(rs.getLong(1), rs.getLong(2))).list();
        return m;
    }

    /** The whole tree, top first; closed units only on request. Headcounts only for those who may see them. */
    public List<Unit> units(boolean includeClosed, boolean withCounts) {
        Map<Long, UnitRow> rows = includeClosed ? allUnits() : openUnits();
        Map<Long, Head> heads = openHeads("HEAD");
        Map<Long, Head> deputies = openHeads("DEPUTY");
        Map<Long, Integer> direct = new HashMap<>();
        placements().values().forEach(u -> direct.merge(u, 1, Integer::sum));
        Map<Long, List<Long>> children = new HashMap<>();
        rows.values().forEach(u -> {
            if (u.parentId() != null) {
                children.computeIfAbsent(u.parentId(), k -> new ArrayList<>()).add(u.id());
            }
        });
        Map<Long, Integer> total = new HashMap<>();
        for (Long id : rows.keySet()) {
            subtotal(id, children, direct, total);
        }
        return rows.values().stream().map(u -> new Unit(u.id(), u.code(), u.name(), u.levelId(), u.levelName(), u.rank(),
                u.staff(), u.parentId(), u.costCentre(), u.validFrom(), u.validTo(), u.validTo() == null,
                heads.get(u.id()), deputies.get(u.id()),
                withCounts ? direct.getOrDefault(u.id(), 0) : null, withCounts ? total.get(u.id()) : null)).toList();
    }

    private static int subtotal(long id, Map<Long, List<Long>> children, Map<Long, Integer> direct, Map<Long, Integer> total) {
        Integer known = total.get(id);
        if (known != null) {
            return known;
        }
        int n = direct.getOrDefault(id, 0);
        for (long c : children.getOrDefault(id, List.of())) {
            n += subtotal(c, children, direct, total);
        }
        total.put(id, n);
        return n;
    }

    /** A unit may sit under its parent only if the parent ranks higher, unless it is a staff unit (Cellule). */
    private void checkPlacement(Long unitId, Long parentId, int rank, boolean staff) {
        if (parentId == null) {
            if (rank != 1) {
                throw new BusinessRuleException("Only a unit of the top level can have no parent.");
            }
            return;
        }
        UnitRow parent = unitRow(parentId);
        if (parent.validTo() != null) {
            throw new BusinessRuleException(parent.name() + " is closed.");
        }
        if (!staff && parent.rank() >= rank) {
            throw new BusinessRuleException("A " + parent.levelName() + " cannot contain a unit of this level: the parent must be a higher level.");
        }
        if (unitId != null) {
            // No cycles: the new parent cannot be the unit itself or below it.
            Map<Long, UnitRow> all = allUnits();
            Long p = parentId;
            Set<Long> seen = new HashSet<>();
            while (p != null && seen.add(p)) {
                if (p.equals(unitId)) {
                    throw new BusinessRuleException("A unit cannot be moved under itself or one of its own sub-units.");
                }
                UnitRow r = all.get(p);
                p = r == null ? null : r.parentId();
            }
            // Its open children must still rank below it.
            for (UnitRow c : openUnits().values()) {
                if (unitId.equals(c.parentId()) && !c.staff() && c.rank() <= rank) {
                    throw new BusinessRuleException(c.name() + " (" + c.levelName() + ") could no longer sit under this unit.");
                }
            }
        }
    }

    @Transactional
    public Unit createUnit(String code, String name, long levelId, Long parentId, String costCentre, LocalDate validFrom) {
        String c = code.trim().toUpperCase();
        if (jdbc.sql("select count(*) from org_unit where code = ?").param(c).query(Integer.class).single() > 0) {
            throw new ConflictException("A unit with code " + c + " already exists.");
        }
        Level level = level(levelId);
        if (!level.active()) {
            throw new BusinessRuleException("The level " + level.name() + " is not in use.");
        }
        checkPlacement(null, parentId, level.rank(), level.staff());
        if (parentId == null && !openUnits().values().stream().filter(u -> u.parentId() == null).toList().isEmpty()) {
            throw new BusinessRuleException("The structure already has a top unit; choose a parent.");
        }
        LocalDate from = validFrom != null ? validFrom : today();
        long id = jdbc.sql("""
                insert into org_unit (code, name, level_id, parent_id, cost_centre, valid_from, created_by)
                values (?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(c, name.trim(), levelId, parentId, clean(costCentre), from, CurrentUser.email()).query(Long.class).single();
        audit.record("org_unit.created", "org_unit", id, Map.of("code", c, "name", name.trim(), "level", level.code()));
        return unit(id);
    }

    public Unit unit(long id) {
        return units(true, true).stream().filter(u -> u.id() == id).findFirst()
                .orElseThrow(() -> new NotFoundException("Unit not found."));
    }

    @Transactional
    public Unit updateUnit(long id, String name, long levelId, Long parentId, String costCentre) {
        UnitRow u = unitRow(id);
        if (u.validTo() != null) {
            throw new BusinessRuleException("A closed unit cannot be changed.");
        }
        Level level = level(levelId);
        if (parentId == null && u.parentId() != null) {
            throw new BusinessRuleException("Choose a parent unit.");
        }
        checkPlacement(id, parentId, level.rank(), level.staff());
        jdbc.sql("update org_unit set name = ?, level_id = ?, parent_id = ?, cost_centre = ? where id = ?")
                .params(name.trim(), levelId, parentId, clean(costCentre), id).update();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", name.trim());
        d.put("level", level.code());
        d.put("parent", parentId);
        audit.record("org_unit.updated", "org_unit", id, d);
        return unit(id);
    }

    /** Closes a unit that no longer has people or open sub-units. Its heads' terms end with it. */
    @Transactional
    public Unit closeUnit(long id, LocalDate on) {
        UnitRow u = unitRow(id);
        if (u.validTo() != null) {
            throw new ConflictException("This unit is already closed.");
        }
        if (u.parentId() == null) {
            throw new BusinessRuleException("The top unit cannot be closed.");
        }
        if (on.isBefore(u.validFrom())) {
            throw new BusinessRuleException("A unit cannot close before it opened (" + u.validFrom() + ").");
        }
        if (jdbc.sql("select count(*) from org_unit where parent_id = ? and valid_to is null").param(id).query(Integer.class).single() > 0) {
            throw new BusinessRuleException("Move or close its sub-units first.");
        }
        if (jdbc.sql("select count(*) from org_assignment where unit_id = ? and valid_to is null").param(id).query(Integer.class).single() > 0) {
            throw new BusinessRuleException("Move its people to another unit first.");
        }
        jdbc.sql("update org_head set valid_to = greatest(valid_from, ?) where unit_id = ? and valid_to is null").params(on, id).update();
        jdbc.sql("update org_unit set valid_to = ? where id = ?").params(on, id).update();
        audit.record("org_unit.closed", "org_unit", id, Map.of("on", on.toString()));
        return unit(id);
    }

    // ---- heads -----------------------------------------------------------------------------------------------

    @Transactional
    public Unit setHead(long unitId, long userId, String role, boolean interim, String title, LocalDate from) {
        UnitRow u = unitRow(unitId);
        if (u.validTo() != null) {
            throw new BusinessRuleException(u.name() + " is closed.");
        }
        String status = jdbc.sql("select status from app_user where id = ?").param(userId).query(String.class).optional()
                .orElseThrow(() -> new NotFoundException("User not found."));
        if (!status.equals("ACTIVE")) {
            throw new BusinessRuleException("Only an active employee can head a unit.");
        }
        LocalDate start = from != null ? from : today();
        Map<Long, Head> current = openHeads(role);
        Head now = current.get(unitId);
        if (now != null && now.userId() == userId && now.interim() == interim && java.util.Objects.equals(now.title(), clean(title))) {
            throw new ConflictException("Nothing changes: this person already holds this post.");
        }
        Head other = openHeads(role.equals("HEAD") ? "DEPUTY" : "HEAD").get(unitId);
        if (other != null && other.userId() == userId) {
            throw new BusinessRuleException("The head and the deputy of a unit must be two different people.");
        }
        if (now != null) {
            endTerm(now, start);
        }
        long id = jdbc.sql("""
                insert into org_head (unit_id, user_id, role, interim, title, valid_from, created_by)
                values (?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(unitId, userId, role, interim, clean(title), start, CurrentUser.email()).query(Long.class).single();
        audit.record("org_head.set", "org_unit", unitId, Map.of("user", userId, "role", role, "interim", interim,
                "from", start.toString(), "term", id));
        return unit(unitId);
    }

    /** Ends a term the day before the next one starts; a term ended on its own first day is removed as a correction. */
    private void endTerm(Head h, LocalDate nextStart) {
        if (!nextStart.isAfter(h.validFrom())) {
            jdbc.sql("delete from org_head where id = ?").param(h.id()).update();
        } else {
            jdbc.sql("update org_head set valid_to = ? where id = ?").params(nextStart.minusDays(1), h.id()).update();
        }
    }

    @Transactional
    public Unit endHead(long unitId, String role, LocalDate on) {
        Head h = openHeads(role).get(unitId);
        if (h == null) {
            throw new NotFoundException("This unit has no " + (role.equals("HEAD") ? "head" : "deputy") + ".");
        }
        LocalDate end = on != null ? on : today();
        if (end.isBefore(h.validFrom())) {
            throw new BusinessRuleException("The term started on " + h.validFrom() + ".");
        }
        jdbc.sql("update org_head set valid_to = ? where id = ?").params(end, h.id()).update();
        audit.record("org_head.ended", "org_unit", unitId, Map.of("user", h.userId(), "role", role, "on", end.toString()));
        return unit(unitId);
    }

    public List<Head> headHistory(long unitId) {
        return jdbc.sql(HEAD_SELECT + " where h.unit_id = ? order by h.valid_from desc, h.id desc").param(unitId)
                .query(Head.class).list();
    }

    /** Units a person heads (or deputises) today. */
    public List<Head> headedBy(long userId) {
        return jdbc.sql(HEAD_SELECT + " where h.user_id = ? and h.valid_to is null order by h.role, h.valid_from").param(userId)
                .query(Head.class).list();
    }

    // ---- assignments -----------------------------------------------------------------------------------------

    private record Open(long id, long unitId, LocalDate validFrom) {}

    private Optional<Open> openAssignment(long userId) {
        return jdbc.sql("select id, unit_id, valid_from from org_assignment where user_id = ? and valid_to is null")
                .param(userId).query(Open.class).optional();
    }

    public void requireOpenUnit(long unitId) {
        if (unitRow(unitId).validTo() != null) {
            throw new BusinessRuleException("That unit is closed.");
        }
    }

    /**
     * Puts someone in a unit from a date. The previous assignment ends the day before. A CORRECTION on or before
     * the current start fixes the current row instead of adding history.
     */
    @Transactional
    public List<Assignment> assign(long userId, long unitId, LocalDate from, String reason, String note) {
        if (!REASONS.contains(reason)) {
            throw new BusinessRuleException("Unknown reason " + reason + ".");
        }
        UnitRow unit = unitRow(unitId);
        if (unit.validTo() != null) {
            throw new BusinessRuleException(unit.name() + " is closed.");
        }
        jdbc.sql("select id from app_user where id = ? for update").param(userId).query(Long.class).optional()
                .orElseThrow(() -> new NotFoundException("User not found."));
        LocalDate start = from != null ? from : today();
        if (start.isBefore(unit.validFrom())) {
            throw new BusinessRuleException(unit.name() + " only exists from " + unit.validFrom() + ".");
        }
        Optional<Open> current = openAssignment(userId);
        if (current.isPresent()) {
            Open c = current.get();
            if (!start.isAfter(c.validFrom())) {
                if (!reason.equals("CORRECTION")) {
                    throw new BusinessRuleException("The move must start after " + c.validFrom()
                            + ", the start of the current assignment. To fix the current unit, use the reason Correction.");
                }
                jdbc.sql("update org_assignment set unit_id = ?, note = ? where id = ?").params(unitId, clean(note), c.id()).update();
                audit.record("org_assignment.corrected", "user", userId, Map.of("unit", unitId));
                return history(userId);
            }
            if (c.unitId() == unitId) {
                throw new ConflictException("This person is already in " + unit.name() + ".");
            }
            jdbc.sql("update org_assignment set valid_to = ? where id = ?").params(start.minusDays(1), c.id()).update();
        }
        jdbc.sql("""
                insert into org_assignment (user_id, unit_id, valid_from, reason, note, created_by)
                values (?, ?, ?, ?, ?, ?)""")
                .params(userId, unitId, start, reason, clean(note), CurrentUser.email()).update();
        audit.record("org_assignment.created", "user", userId, Map.of("unit", unitId, "from", start.toString(), "reason", reason));
        return history(userId);
    }

    public List<Assignment> history(long userId) {
        return jdbc.sql("""
                select a.id, a.unit_id, u.code as unit_code, u.name as unit_name, a.valid_from, a.valid_to, a.reason, a.note, a.created_by
                from org_assignment a join org_unit u on u.id = a.unit_id
                where a.user_id = ? order by a.valid_from desc, a.id desc""").param(userId).query(Assignment.class).list();
    }

    /** Everyone without a unit goes to the top unit (for instance the first administrator). Returns how many. */
    @Transactional
    public int placeUnassigned() {
        Optional<Long> top = jdbc.sql("select id from org_unit where parent_id is null and valid_to is null order by id limit 1")
                .query(Long.class).optional();
        if (top.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                insert into org_assignment (user_id, unit_id, valid_from, reason, note, created_by)
                select u.id, ?, coalesce(u.hire_date, u.created_at::date), 'HIRE', 'No unit given; placed at the top', 'system'
                from app_user u where not exists (select 1 from org_assignment a where a.user_id = u.id and a.valid_to is null)""")
                .param(top.get()).update();
    }

    // ---- people and managers ---------------------------------------------------------------------------------

    /**
     * Everyone active, with their unit and who they report to: the head of their unit, or of the nearest unit above
     * when it is vacant or headed by themself.
     */
    public List<Member> people() {
        Map<Long, UnitRow> units = openUnits();
        Map<Long, Head> heads = openHeads("HEAD");
        Map<Long, List<Long>> headsBy = new HashMap<>();
        heads.values().forEach(h -> headsBy.computeIfAbsent(h.userId(), k -> new ArrayList<>()).add(h.unitId()));
        return jdbc.sql("""
                select a.user_id, u.first_name || ' ' || u.last_name as name, u.registration_no, a.unit_id, a.valid_from
                from org_assignment a join app_user u on u.id = a.user_id
                where a.valid_to is null and u.status = 'ACTIVE' order by u.last_name, u.first_name""")
                .query((rs, i) -> {
                    long userId = rs.getLong("user_id");
                    long unitId = rs.getLong("unit_id");
                    Head boss = reportsTo(userId, unitId, units, heads);
                    UnitRow unit = units.get(unitId);
                    return new Member(userId, rs.getString("name"), rs.getString("registration_no"), unitId,
                            unit == null ? null : unit.name(), rs.getObject("valid_from", LocalDate.class),
                            boss == null ? null : boss.userId(), boss == null ? null : boss.userName(),
                            headsBy.getOrDefault(userId, List.of()));
                }).list();
    }

    private static Head reportsTo(long userId, long unitId, Map<Long, UnitRow> units, Map<Long, Head> heads) {
        Long u = unitId;
        Set<Long> seen = new HashSet<>();
        while (u != null && seen.add(u)) {
            Head h = heads.get(u);
            if (h != null && h.userId() != userId) {
                return h;
            }
            UnitRow row = units.get(u);
            u = row == null ? null : row.parentId();
        }
        return null;
    }

    /** Who an employee reports to today, from the structure. Empty for the top of the company. */
    public Optional<Head> managerOf(long userId) {
        return openAssignment(userId).map(a -> reportsTo(userId, a.unitId(), openUnits(), openHeads("HEAD")));
    }

    public Optional<Assignment> currentAssignment(long userId) {
        return history(userId).stream().filter(a -> a.validTo() == null).findFirst();
    }
}
