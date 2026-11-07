package cd.hrpay.report;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.Csv;
import cd.hrpay.org.OrgStructure;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Headcount and payroll cost per Direction (and per unit) for a month. Headcount is the active people in each unit
 * at the end of the month; cost comes from that month's payslips, kept with the unit they were made for, and is
 * stated in USD and CDF at the payroll's rate.
 */
@RestController
@RequestMapping("/api/reports")
public class StructureReport {

    public record Row(long id, String name, String costCentre, int headcount, int payslips, BigDecimal grossUsd,
                      BigDecimal netUsd, BigDecimal employerUsd, BigDecimal costUsd, BigDecimal costCdf) {}

    public record Result(String month, String runStatus, BigDecimal usdCdf, List<Row> directions, List<Row> units, Row total) {}

    private record Slip(Long unitId, String currency, BigDecimal gross, BigDecimal net, BigDecimal employer) {}

    private static final class Acc {
        final long id;
        final String name;
        final String costCentre;
        int headcount;
        int payslips;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal employer = BigDecimal.ZERO;

        Acc(long id, String name, String costCentre) {
            this.id = id;
            this.name = name;
            this.costCentre = costCentre;
        }

        Row row(BigDecimal rate) {
            BigDecimal cost = gross.add(employer);
            return new Row(id, name, costCentre, headcount, payslips, r(gross), r(net), r(employer), r(cost),
                    rate == null ? null : r(cost.multiply(rate)));
        }
    }

    private static BigDecimal r(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private final OrgStructure org;
    private final JdbcClient jdbc;
    private final AuditService audit;

    public StructureReport(OrgStructure org, JdbcClient jdbc, AuditService audit) {
        this.org = org;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping("/structure")
    @PreAuthorize("hasAuthority('payslip.read.all')")
    public Result structure(@RequestParam YearMonth month) {
        LocalDate last = month.atEndOfMonth();
        LocalDate today = org.today();
        Map<Long, OrgStructure.Placement> units = org.placementsOfUnits();
        Map<Long, Acc> byDirection = new LinkedHashMap<>();
        Map<Long, Acc> byUnit = new LinkedHashMap<>();
        Acc total = new Acc(0, "Total", null);

        List<Long> active = jdbc.sql("select id from app_user where status = 'ACTIVE'").query(Long.class).list();
        Map<Long, Long> unitOf = org.unitsOn(last.isAfter(today) ? today : last);
        for (long user : active) {
            OrgStructure.Placement p = unitOf.containsKey(user) ? units.get(unitOf.get(user)) : null;
            if (p != null) {
                acc(byDirection, p.directionId(), p.directionName(), null).headcount++;
                acc(byUnit, p.unitId(), p.path(), p.costCentre()).headcount++;
                total.headcount++;
            }
        }

        var run = jdbc.sql("select id, status, usd_cdf from payroll_run where month = ?").param(month.atDay(1))
                .query((rs, i) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getBigDecimal(3)}).optional();
        BigDecimal rate = run.map(x -> (BigDecimal) x[2]).orElse(null);
        if (run.isPresent()) {
            List<Slip> slips = jdbc.sql("""
                    select unit_id, trim(currency) as currency, gross, net, employer_charges as employer
                    from payslip where run_id = ? and (kind = 'REGULAR' or number is not null)""").param(run.get()[0]).query(Slip.class).list();
            for (Slip s : slips) {
                BigDecimal toUsd = s.currency().equals("USD") ? BigDecimal.ONE
                        : BigDecimal.ONE.divide(rate, 10, RoundingMode.HALF_UP);
                OrgStructure.Placement p = s.unitId() == null ? null : units.get(s.unitId());
                List<Acc> targets = new ArrayList<>(List.of(total));
                if (p != null) {
                    targets.add(acc(byDirection, p.directionId(), p.directionName(), null));
                    targets.add(acc(byUnit, p.unitId(), p.path(), p.costCentre()));
                } else {
                    targets.add(acc(byDirection, -1, "No unit", null));
                }
                for (Acc a : targets) {
                    a.payslips++;
                    a.gross = a.gross.add(s.gross().multiply(toUsd));
                    a.net = a.net.add(s.net().multiply(toUsd));
                    a.employer = a.employer.add(s.employer().multiply(toUsd));
                }
            }
        }
        return new Result(month.toString(), run.map(x -> (String) x[1]).orElse(null), rate,
                byDirection.values().stream().map(a -> a.row(rate)).sorted(Comparator.comparing(Row::name)).toList(),
                byUnit.values().stream().map(a -> a.row(rate)).sorted(Comparator.comparing(Row::name)).toList(),
                total.row(rate));
    }

    private static Acc acc(Map<Long, Acc> map, long id, String name, String costCentre) {
        return map.computeIfAbsent(id, k -> new Acc(id, name, costCentre));
    }

    @GetMapping({"/structure.csv", "/structure.xlsx"})
    @PreAuthorize("hasAuthority('payslip.read.all')")
    public void structureCsv(@RequestParam YearMonth month, jakarta.servlet.http.HttpServletRequest request,
                             HttpServletResponse response) throws IOException {
        Result r = structure(month);
        cd.hrpay.common.Sheet out = cd.hrpay.common.Sheet.open(request, response, "cost-by-unit-" + month);
        out.row("Level", "Direction or unit", "Cost centre", "Headcount", "Payslips", "Gross (USD)", "Net (USD)",
                "Employer charges (USD)", "Total cost (USD)", "Total cost (CDF)");
        for (Row x : r.directions()) {
            write(out, "Direction", x);
        }
        for (Row x : r.units()) {
            write(out, "Unit", x);
        }
        write(out, "Total", r.total());
        out.finish();
        audit.record("report.structure_exported", "report", null, Map.of("month", month.toString()));
    }

    private static void write(cd.hrpay.common.Sheet out, String level, Row x) {
        out.row(level, x.name(), x.costCentre(), x.headcount(), x.payslips(), x.grossUsd(), x.netUsd(),
                x.employerUsd(), x.costUsd(), x.costCdf());
    }
}
