package cd.hrpay.position;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.org.OrgStructure;
import cd.hrpay.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The approved positions: how many posts of each job a unit may fill in a year, against who fills them (the people
 * assigned to the unit whose contract in force has that job), with the budgeted cost against the real payroll cost.
 * A company that has entered no post for a year gets no warning at all: positions are optional.
 */
@Component
public class Positions {

    public record Budget(long id, int year, long unitId, String unitName, long occupationId, String occupationName,
                         int posts, BigDecimal budgetCost, String note) {}

    public record Line(Long budgetId, long occupationId, String occupationName, int budgeted, int filled, int vacant,
                       int over, BigDecimal budgetCost, String note, LocalDate vacantSince) {}

    public record UnitLine(long unitId, String unitName, String direction, int budgeted, int filled, int vacant, int over,
                           BigDecimal budgetCost, BigDecimal realCost, List<Line> jobs) {}

    public record Headcount(int year, LocalDate countedOn, int months, int budgeted, int filled, int vacant, int over,
                            BigDecimal budgetCost, BigDecimal realCost, List<UnitLine> units) {}

    public record Vacancy(long unitId, String unitName, long occupationId, String occupationName, int vacant,
                          LocalDate since) {}

    public record UnitCount(long unitId, int budgeted, int filled) {}

    private final JdbcClient jdbc;
    private final OrgStructure org;
    private final JobCatalogue jobs;
    private final AuditService audit;

    public Positions(JdbcClient jdbc, OrgStructure org, JobCatalogue jobs, AuditService audit) {
        this.jdbc = jdbc;
        this.org = org;
        this.jobs = jobs;
        this.audit = audit;
    }

    // ---- budget --------------------------------------------------------------------------------------------------

    public List<Budget> budgets(int year) {
        return jdbc.sql("""
                select b.id, b.year, b.unit_id, u.name as unit_name, b.occupation_id, o.name as occupation_name, b.posts, b.budget_cost, b.note
                from position_budget b join org_unit u on u.id = b.unit_id join occupation o on o.id = b.occupation_id
                where b.year = ? order by u.name, o.name""").param(year).query(Budget.class).list();
    }

    /** Adds the posts of a job in a unit for a year, or changes them. */
    @Transactional
    public Budget save(Long id, int year, long unitId, long occupationId, int posts, BigDecimal cost, String note) {
        org.requireOpenUnit(unitId);
        if (jdbc.sql("select count(*) from occupation where id = ?").param(occupationId).query(Integer.class).single() == 0) {
            throw new NotFoundException("Job not found.");
        }
        Long other = jdbc.sql("select id from position_budget where year = ? and unit_id = ? and occupation_id = ?")
                .params(year, unitId, occupationId).query(Long.class).optional().orElse(null);
        if (other != null && !other.equals(id)) {
            if (id != null) {
                throw new BusinessRuleException("This unit already has a line for this job in that year: change that line.");
            }
            id = other; // adding a job already budgeted changes its line
        }
        if (id == null) {
            id = jdbc.sql("""
                    insert into position_budget (year, unit_id, occupation_id, posts, budget_cost, note, created_by)
                    values (?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(year, unitId, occupationId, posts, cost, JobCatalogue.clean(note), CurrentUser.email())
                    .query(Long.class).single();
        } else if (jdbc.sql("""
                update position_budget set year = ?, unit_id = ?, occupation_id = ?, posts = ?, budget_cost = ?, note = ?
                where id = ?""").params(year, unitId, occupationId, posts, cost, JobCatalogue.clean(note), id).update() == 0) {
            throw new NotFoundException("Position line not found.");
        }
        audit.record("position.saved", "position_budget", id, Map.of("year", year, "unit", unitId, "job", occupationId, "posts", posts));
        long saved = id;
        return budgets(year).stream().filter(b -> b.id() == saved).findFirst().orElseThrow();
    }

    @Transactional
    public void delete(long id) {
        if (jdbc.sql("delete from position_budget where id = ?").param(id).update() == 0) {
            throw new NotFoundException("Position line not found.");
        }
        audit.record("position.deleted", "position_budget", id, Map.of());
    }

    /**
     * Copies the posts of one year to another. With {@code fromFilled}, the posts are the people in place today, so a
     * company can start from what it has.
     */
    @Transactional
    public int copy(int fromYear, int toYear, boolean fromFilled) {
        if (!budgets(toYear).isEmpty()) {
            throw new BusinessRuleException(Texts.t("%d already has positions: change them there.", toYear));
        }
        int n;
        if (fromFilled) {
            n = 0;
            for (Map.Entry<String, Integer> e : filled(jobs.today()).entrySet()) {
                String[] k = e.getKey().split(":");
                jdbc.sql("""
                        insert into position_budget (year, unit_id, occupation_id, posts, created_by) values (?, ?, ?, ?, ?)""")
                        .params(toYear, Long.parseLong(k[0]), Long.parseLong(k[1]), e.getValue(), CurrentUser.email()).update();
                n++;
            }
        } else {
            n = jdbc.sql("""
                    insert into position_budget (year, unit_id, occupation_id, posts, budget_cost, note, created_by)
                    select ?, b.unit_id, b.occupation_id, b.posts, b.budget_cost, b.note, ? from position_budget b
                    join org_unit u on u.id = b.unit_id
                    where b.year = ? and (u.valid_to is null or u.valid_to >= ?)""")
                    .params(toYear, CurrentUser.email(), fromYear, LocalDate.of(toYear, 1, 1)).update();
        }
        audit.record("position.copied", "position_budget", null, Map.of("from", fromFilled ? "filled" : fromYear, "to", toYear, "lines", n));
        return n;
    }

    // ---- headcount -----------------------------------------------------------------------------------------------

    /** The day a year is counted on: today for this year and later ones, the last day for a past year. */
    public LocalDate countedOn(int year) {
        LocalDate today = jobs.today();
        return year < today.getYear() ? LocalDate.of(year, 12, 31) : today;
    }

    /** The people in place on a day, by "unit:job". */
    private Map<String, Integer> filled(LocalDate d) {
        Map<String, Integer> out = new LinkedHashMap<>();
        jdbc.sql("""
                select a.unit_id, c.occupation_id, count(*) from app_user u
                join org_assignment a on a.user_id = u.id and a.valid_from <= :d and (a.valid_to is null or a.valid_to >= :d)
                join lateral (select occupation_id, end_date from employee_contract c where c.user_id = u.id and c.start_date <= :d
                              order by c.start_date desc limit 1) c on c.occupation_id is not null
                                                                   and (c.end_date is null or c.end_date >= :d)
                where not u.not_employee
                group by a.unit_id, c.occupation_id""")
                .param("d", d)
                .query(rs -> {
                    out.put(rs.getLong(1) + ":" + rs.getLong(2), rs.getInt(3));
                });
        return out;
    }

    /** The real payroll cost (gross plus employer charges, in US dollars) of each unit over the months paid in a year. */
    private Map<Long, BigDecimal> realCost(int year, int[] months) {
        Map<Long, BigDecimal> out = new HashMap<>();
        List<Object[]> runs = jdbc.sql("select id, month, usd_cdf from payroll_run where month >= ? and month <= ?")
                .params(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
                .query((rs, i) -> new Object[] {rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getBigDecimal(3)}).list();
        months[0] = runs.size();
        for (Object[] run : runs) {
            LocalDate start = (LocalDate) run[1];
            BigDecimal rate = (BigDecimal) run[2];
            jdbc.sql("""
                    select p.currency, p.gross + p.employer_charges,
                           (select a.unit_id from org_assignment a where a.user_id = p.user_id and a.valid_from <= :e
                              and (a.valid_to is null or a.valid_to >= :s) order by a.valid_from desc limit 1)
                    from payslip p where p.run_id = :run""")
                    .param("e", YearMonth.from(start).atEndOfMonth()).param("s", start).param("run", run[0])
                    .query(rs -> {
                        BigDecimal c = rs.getBigDecimal(2);
                        if ("CDF".equals(rs.getString(1)) && rate != null && rate.signum() > 0) {
                            c = c.divide(rate, 2, RoundingMode.HALF_UP);
                        }
                        long unit = rs.getLong(3);
                        if (!rs.wasNull() && c != null) {
                            out.merge(unit, c, BigDecimal::add);
                        }
                    });
        }
        return out;
    }

    public Headcount headcount(int year) {
        LocalDate on = countedOn(year);
        Map<String, Integer> filled = filled(on);
        Map<String, Budget> budget = new LinkedHashMap<>();
        for (Budget b : budgets(year)) {
            budget.put(b.unitId() + ":" + b.occupationId(), b);
        }
        Map<Long, String> unitNames = new HashMap<>();
        Map<Long, String> directions = new HashMap<>();
        for (Map.Entry<Long, OrgStructure.Placement> e : org.placementsOfUnits().entrySet()) {
            unitNames.put(e.getKey(), e.getValue().unitName());
            directions.put(e.getKey(), e.getValue().directionName());
        }
        Map<Long, String> jobNames = new HashMap<>();
        jdbc.sql("select id, name from occupation").query(rs -> {
            jobNames.put(rs.getLong(1), rs.getString(2));
        });
        int[] months = {0};
        Map<Long, BigDecimal> real = realCost(year, months);

        java.util.Set<String> keys = new java.util.LinkedHashSet<>(budget.keySet());
        keys.addAll(filled.keySet());
        Map<Long, List<Line>> byUnit = new LinkedHashMap<>();
        for (String k : keys) {
            String[] p = k.split(":");
            long unit = Long.parseLong(p[0]);
            long job = Long.parseLong(p[1]);
            Budget b = budget.get(k);
            int budgeted = b == null ? 0 : b.posts();
            int have = filled.getOrDefault(k, 0);
            int vacant = Math.max(0, budgeted - have);
            byUnit.computeIfAbsent(unit, x -> new ArrayList<>()).add(new Line(b == null ? null : b.id(), job,
                    jobNames.getOrDefault(job, "?"), budgeted, have, vacant, Math.max(0, have - budgeted),
                    b == null ? null : b.budgetCost(), b == null ? null : b.note(), vacant > 0 ? vacantSince(unit, job, on) : null));
        }
        List<UnitLine> units = new ArrayList<>();
        int tb = 0, tf = 0, tv = 0, to = 0;
        BigDecimal tc = BigDecimal.ZERO, tr = BigDecimal.ZERO;
        for (Map.Entry<Long, List<Line>> e : byUnit.entrySet()) {
            List<Line> lines = new ArrayList<>(e.getValue());
            lines.sort(java.util.Comparator.comparing(Line::occupationName));
            int b = lines.stream().mapToInt(Line::budgeted).sum();
            int f = lines.stream().mapToInt(Line::filled).sum();
            int v = lines.stream().mapToInt(Line::vacant).sum();
            int o = lines.stream().mapToInt(Line::over).sum();
            BigDecimal cost = lines.stream().map(Line::budgetCost).filter(x -> x != null).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal r = real.getOrDefault(e.getKey(), BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            units.add(new UnitLine(e.getKey(), unitNames.getOrDefault(e.getKey(), "?"), directions.get(e.getKey()), b, f, v, o,
                    cost, r, lines));
            tb += b;
            tf += f;
            tv += v;
            to += o;
            tc = tc.add(cost);
            tr = tr.add(r);
        }
        units.sort(java.util.Comparator.comparing((UnitLine u) -> u.direction() == null ? "" : u.direction())
                .thenComparing(UnitLine::unitName));
        return new Headcount(year, on, months[0], tb, tf, tv, to, tc, tr, units);
    }

    /** The day the last person of that job left the unit; null when the post was never filled. */
    private LocalDate vacantSince(long unitId, long occupationId, LocalDate on) {
        return jdbc.sql("""
                select max(a.valid_to) from org_assignment a
                join employee_contract c on c.user_id = a.user_id and c.start_date <= a.valid_to
                                         and (c.end_date is null or c.end_date >= a.valid_to)
                where a.unit_id = ? and c.occupation_id = ? and a.valid_to <= ?""")
                .params(unitId, occupationId, on).query(LocalDate.class).optional().orElse(null);
    }

    public List<Vacancy> vacancies(int year) {
        List<Vacancy> out = new ArrayList<>();
        for (UnitLine u : headcount(year).units()) {
            for (Line l : u.jobs()) {
                if (l.vacant() > 0) {
                    out.add(new Vacancy(u.unitId(), u.unitName(), l.occupationId(), l.occupationName(), l.vacant(), l.vacantSince()));
                }
            }
        }
        return out;
    }

    /** Free posts of each job in the year, all units together (for the career ladders). */
    public Map<Long, Integer> freePostsByJob(int year) {
        Map<Long, Integer> out = new HashMap<>();
        if (budgets(year).isEmpty()) {
            return out;
        }
        for (Vacancy v : vacancies(year)) {
            out.merge(v.occupationId(), v.vacant(), Integer::sum);
        }
        return out;
    }

    /** Budgeted and filled posts of each unit, for the org chart; empty when the year has no positions. */
    public List<UnitCount> unitCounts(int year) {
        if (budgets(year).isEmpty()) {
            return List.of();
        }
        return headcount(year).units().stream().map(u -> new UnitCount(u.unitId(), u.budgeted(), u.filled())).toList();
    }

    /**
     * A warning when a person would take a post of a job in a unit with no free post that year. Nothing when the
     * company has no positions for the year, or when the person already holds that post.
     */
    public Optional<String> check(Long userId, Long unitId, Long occupationId, LocalDate on) {
        if (unitId == null || occupationId == null) {
            return Optional.empty();
        }
        int year = (on == null ? jobs.today() : on).getYear();
        if (jdbc.sql("select count(*) from position_budget where year = ?").param(year).query(Integer.class).single() == 0) {
            return Optional.empty();
        }
        LocalDate d = countedOn(year);
        if (userId != null) {
            Boolean already = jdbc.sql("""
                    select exists (select 1 from org_assignment a
                                   join lateral (select occupation_id from employee_contract c where c.user_id = a.user_id
                                                 and c.start_date <= :d order by c.start_date desc limit 1) c on true
                                   where a.user_id = :u and a.unit_id = :unit and a.valid_to is null and c.occupation_id = :job)""")
                    .param("d", d).param("u", userId).param("unit", unitId).param("job", occupationId).query(Boolean.class).single();
            if (already) {
                return Optional.empty();
            }
        }
        int budgeted = jdbc.sql("select coalesce(sum(posts), 0) from position_budget where year = ? and unit_id = ? and occupation_id = ?")
                .params(year, unitId, occupationId).query(Integer.class).single();
        int have = filled(d).getOrDefault(unitId + ":" + occupationId, 0);
        if (have < budgeted) {
            return Optional.empty();
        }
        String unit = jdbc.sql("select name from org_unit where id = ?").param(unitId).query(String.class).optional().orElse("?");
        String job = jdbc.sql("select name from occupation where id = ?").param(occupationId).query(String.class).optional().orElse("?");
        return Optional.of(budgeted == 0
                ? Texts.t("%s has no approved post of %s in %d. You can go on, or add the post on the Positions page.", unit, job, year)
                : Texts.t("%s has no free post of %s in %d: %d approved, %d filled. You can go on, or add a post on the Positions page.",
                        unit, job, year, budgeted, have));
    }
}
