package cd.hrpay.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Management dashboards, month by month: headcount at month end, hires and departures, turnover, unpaid absence and
 * payroll cost by direction. Directions are the units right under the top of the structure. Costs are gross pay plus
 * employer charges of approved and closed payrolls, in US dollars at each month's payroll rate.
 */
@RestController
@PreAuthorize("hasAuthority('dashboard.read')")
public class Dashboards {

    /** absenceRate: share of scheduled time not paid (unpaid absence, lateness), from the month's payslips; null without payroll. */
    public record Month(String month, int headcount, int hires, int departures, BigDecimal turnover, BigDecimal absenceRate,
                        BigDecimal cost, boolean payrollFinal, BigDecimal leavePay, int leavePayslips) {}

    public record Cost(String month, String direction, BigDecimal cost) {}

    public record Direction(String name, int headcount) {}

    public record Board(List<Month> months, List<Cost> costByDirection, List<Direction> directions, String currency) {}

    private final JdbcClient jdbc;

    public Dashboards(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The direction each unit belongs to: the unit under the top that it is in, or the top itself. */
    private static final String DIRECTION_OF = """
            with recursive up (unit_id, id, parent_id, name) as (
                select u.id, u.id, u.parent_id, u.name from org_unit u
                union all
                select up.unit_id, p.id, p.parent_id, p.name from up join org_unit p on p.id = up.parent_id
                where up.parent_id is not null and p.parent_id is not null
            )
            select unit_id, name from up where parent_id = (select id from org_unit where parent_id is null order by id limit 1)
            union
            select id, name from org_unit where parent_id is null""";

    /** People on the staff at a date: assigned to a unit and under contract then, not gone by a confirmed departure. */
    private static final String STAFF_AT = """
            select a.user_id, a.unit_id from org_assignment a join app_user u on u.id = a.user_id
            where a.valid_from <= :d and (a.valid_to is null or a.valid_to >= :d)
              and exists (select 1 from employee_contract c where c.user_id = a.user_id and c.start_date <= :d
                          and (c.end_date is null or c.end_date >= :d))
              and not exists (select 1 from departure x where x.user_id = a.user_id and x.status = 'CONFIRMED' and x.last_day < :d)""";

    @GetMapping("/api/dashboards")
    @Transactional(readOnly = true)
    public Board board(@RequestParam(defaultValue = "12") int months) {
        int n = Math.max(3, Math.min(36, months));
        YearMonth last = YearMonth.now();
        List<Month> rows = new ArrayList<>();
        List<Cost> costs = new ArrayList<>();
        Map<Long, String> direction = new LinkedHashMap<>();
        jdbc.sql(DIRECTION_OF).query(rs -> {
            direction.putIfAbsent(rs.getLong(1), rs.getString(2));
        });
        for (YearMonth ym = last.minusMonths(n - 1); !ym.isAfter(last); ym = ym.plusMonths(1)) {
            LocalDate start = ym.atDay(1);
            LocalDate end = ym.equals(last) ? LocalDate.now() : ym.atEndOfMonth();
            int headcount = count("select count(distinct user_id) from (" + STAFF_AT + ") s", end);
            int before = count("select count(distinct user_id) from (" + STAFF_AT + ") s", start.minusDays(1));
            int hires = jdbc.sql("""
                    select count(*) from app_user u where u.hire_date between ? and ?
                      and exists (select 1 from employee_contract c where c.user_id = u.id)""")
                    .params(start, end).query(Integer.class).single();
            int departures = jdbc.sql("select count(*) from departure where status = 'CONFIRMED' and last_day between ? and ?")
                    .params(start, end).query(Integer.class).single();
            BigDecimal average = BigDecimal.valueOf(headcount + before).divide(BigDecimal.TWO, 2, RoundingMode.HALF_UP);
            BigDecimal turnover = average.signum() == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(departures * 100L).divide(average, 1, RoundingMode.HALF_UP);

            Map<String, Object> run = jdbc.sql("select id, status, usd_cdf from payroll_run where month = ?").param(start)
                    .query((rs, i) -> Map.<String, Object>of("id", rs.getLong(1), "status", rs.getString(2), "rate", rs.getBigDecimal(3)))
                    .optional().orElse(null);
            BigDecimal absence = null;
            BigDecimal cost = null;
            boolean fin = false;
            if (run != null) {
                long runId = (Long) run.get("id");
                fin = !"CALCULATED".equals(run.get("status"));
                absence = jdbc.sql("""
                        select case when sum(scheduled_minutes) > 0
                               then round(100.0 * sum(greatest(scheduled_minutes - paid_minutes, 0)) / sum(scheduled_minutes), 1) end
                        from payslip where run_id = ?""").param(runId).query(BigDecimal.class).optional().orElse(null);
                BigDecimal rate = (BigDecimal) run.get("rate");
                Map<String, BigDecimal> byDirection = new LinkedHashMap<>();
                jdbc.sql("""
                        select p.user_id, p.currency, p.gross + p.employer_charges as cost,
                               (select a.unit_id from org_assignment a where a.user_id = p.user_id and a.valid_from <= :d
                                  and (a.valid_to is null or a.valid_to >= :m) order by a.valid_from desc limit 1) as unit_id
                        from payslip p where p.run_id = :run""")
                        .param("d", ym.atEndOfMonth()).param("m", start).param("run", runId)
                        .query(rs -> {
                            BigDecimal c = rs.getBigDecimal("cost");
                            if ("CDF".equals(rs.getString("currency")) && rate.signum() > 0) {
                                c = c.divide(rate, 2, RoundingMode.HALF_UP);
                            }
                            long unit = rs.getLong("unit_id");
                            String name = rs.wasNull() ? "—" : direction.getOrDefault(unit, "—");
                            byDirection.merge(name, c, BigDecimal::add);
                        });
                cost = byDirection.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
                for (Map.Entry<String, BigDecimal> e : byDirection.entrySet()) {
                    costs.add(new Cost(ym.toString(), e.getKey(), e.getValue().setScale(2, RoundingMode.HALF_UP)));
                }
            }
            // Allocations de congé approved in the month (their own payslips), in US dollars at their rate.
            BigDecimal[] leave = {BigDecimal.ZERO};
            int[] leaveSlips = {0};
            jdbc.sql("""
                    select trim(currency) as currency, gross, usd_cdf from leave_allowance
                    where status in ('APPROVED', 'PAID') and approved_at >= ? and approved_at < ?""")
                    .params(start.atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime(),
                            ym.plusMonths(1).atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime())
                    .query(rs -> {
                        BigDecimal g = rs.getBigDecimal("gross");
                        BigDecimal r = rs.getBigDecimal("usd_cdf");
                        if (!"USD".equals(rs.getString("currency")) && r != null && r.signum() > 0) {
                            g = g.divide(r, 2, RoundingMode.HALF_UP);
                        }
                        leave[0] = leave[0].add(g);
                        leaveSlips[0]++;
                    });
            rows.add(new Month(ym.toString(), headcount, hires, departures, turnover, absence, cost, fin,
                    leave[0].setScale(2, RoundingMode.HALF_UP), leaveSlips[0]));
        }
        Map<String, Integer> now = new LinkedHashMap<>();
        jdbc.sql(STAFF_AT).param("d", LocalDate.now()).query(rs -> {
            now.merge(direction.getOrDefault(rs.getLong("unit_id"), "—"), 1, Integer::sum);
        });
        List<Direction> directions = now.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> new Direction(e.getKey(), e.getValue())).toList();
        return new Board(rows, costs, directions, "USD");
    }

    private int count(String sql, LocalDate d) {
        return jdbc.sql(sql).param("d", d).query(Integer.class).single();
    }
}
