package cd.hrpay.payroll.report;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.Workbook;
import cd.hrpay.payroll.report.Report.Row;
import cd.hrpay.payroll.report.Report.Table;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payroll general reports. Each report is read as JSON for the screen, or downloaded with format=pdf or
 * format=xlsx; every download is written to the audit log.
 */
@RestController
@RequestMapping("/api/payroll/reports")
@PreAuthorize("hasAuthority('payroll.report')")
public class PayrollReportController {

    private final PayrollReports reports;
    private final ReportPdf pdf;
    private final AuditService audit;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    public PayrollReportController(PayrollReports reports, ReportPdf pdf, AuditService audit,
                                   org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.reports = reports;
        this.pdf = pdf;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    /** What the reports page offers: the groupings, the structure's levels, the quinzaines. */
    @GetMapping("/options")
    public Map<String, Object> options() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, String>> dims = new ArrayList<>();
        for (String d : PayrollReports.DIMENSIONS) {
            dims.add(Map.of("code", d, "label", PayrollReports.dimensionLabel(d)));
        }
        out.put("dimensions", dims);
        out.put("levels", jdbc.sql("""
                select min(rank) as rank, string_agg(name, ' / ' order by name) as name from org_level
                where active and not staff group by rank order by rank""").query().listOfRows());
        out.put("sites", jdbc.sql("select id, name from site order by name").query().listOfRows());
        out.put("categories", jdbc.sql("select id, code, name from grade_category order by code").query().listOfRows());
        out.put("quinzaines", reports.quinzaineRuns());
        out.put("months", jdbc.sql("select to_char(month, 'YYYY-MM') as month, status from payroll_run order by month desc")
                .query().listOfRows());
        return out;
    }

    @GetMapping("/{month}/{report}")
    public ResponseEntity<?> report(@PathVariable YearMonth month, @PathVariable String report,
                                    @RequestParam(required = false) Long unitId, @RequestParam(required = false) Long siteId,
                                    @RequestParam(required = false) Long categoryId,
                                    @RequestParam(defaultValue = "true") boolean complementary,
                                    @RequestParam(required = false) String currency,
                                    @RequestParam(defaultValue = "CATEGORY") String dim, @RequestParam(required = false) String then,
                                    @RequestParam(defaultValue = "0") int level, @RequestParam(defaultValue = "0") int thenLevel,
                                    @RequestParam(required = false) BigDecimal threshold,
                                    @RequestParam(required = false) Long quinzaineId,
                                    @RequestParam Map<String, String> params,
                                    @RequestParam(defaultValue = "json") String format, HttpServletResponse response) throws IOException {
        PayrollReports.Filters f = new PayrollReports.Filters(unitId, siteId, categoryId, complementary, currency);
        if (!List.of("json", "pdf", "xlsx").contains(format)) {
            throw new BusinessRuleException("Choose the format: json, pdf or xlsx.");
        }
        boolean drill = report.equals("employees");  // a drill names its grouping itself (a unit's branch too)
        if (!drill && (!PayrollReports.DIMENSIONS.contains(dim) || (then != null && !then.isBlank() && !PayrollReports.DIMENSIONS.contains(then)))) {
            throw new BusinessRuleException("Unknown grouping.");
        }
        if (report.equals("all")) {
            if (!format.equals("xlsx")) {
                throw new BusinessRuleException("The whole month is downloaded as an Excel workbook.");
            }
            List<Report> all = reports.all(month, f);
            exported(month, report, format, f, params);
            xlsx(response, "payroll-reports-" + month, all);
            return null;
        }
        Report r = switch (report) {
            case "summary" -> reports.summary(month, f);
            case "by" -> reports.byDimension(month, f, dim, then, level, thenLevel);
            case "tree" -> reports.unitTree(month, f);
            case "elements" -> reports.byElement(month, f);
            case "cross" -> reports.cross(month, f, dim, level);
            case "payment" -> reports.paymentMethods(month, f);
            case "variance" -> reports.variance(month, f, threshold);
            case "employees" -> reports.employees(month, f, drill(params));
            case "livre" -> reports.livre(month, f);
            case "quinzaine" -> {
                if (quinzaineId == null) {
                    throw new BusinessRuleException("Choose a quinzaine.");
                }
                yield reports.quinzaine(quinzaineId, f, level);
            }
            default -> throw new cd.hrpay.common.NotFoundException("Unknown report " + report + ".");
        };
        if (format.equals("json")) {
            return ResponseEntity.ok(r);
        }
        exported(month, report, format, f, params);
        String name = (report.equals("livre") ? "livre-de-paie" : "payroll-" + report) + "-" + month;
        if (format.equals("xlsx")) {
            xlsx(response, name, List.of(r));
            return null;
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "attachment; filename=\"" + name + ".pdf\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(pdf.render(r));
    }

    private static Map<String, String> drill(Map<String, String> params) {
        Map<String, String> d = new LinkedHashMap<>();
        for (String k : List.of("dim", "key", "level", "then", "thenKey", "thenLevel", "element")) {
            if (params.containsKey(k)) {
                d.put(k, params.get(k));
            }
        }
        // The grouping of a drill comes from "dim" too: an element drill must not inherit the default one.
        if (d.containsKey("element")) {
            d.remove("dim");
        }
        return d;
    }

    private void exported(YearMonth month, String report, String format, PayrollReports.Filters f, Map<String, String> params) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("month", month.toString());
        details.put("report", report);
        details.put("format", format);
        details.put("currency", f.currency());
        params.forEach((k, v) -> {
            if (!List.of("format", "currency").contains(k) && v != null && v.length() < 100) {
                details.put(k, v);
            }
        });
        audit.record("payroll_report.exported", "payroll_report", null, details);
    }

    /** One sheet per table, headed by the report's title and filters. */
    private static void xlsx(HttpServletResponse response, String name, List<Report> list) throws IOException {
        Workbook book = new Workbook();
        for (Report r : list) {
            for (Table t : r.tables()) {
                book.sheet(r.tables().size() == 1 ? r.title() : t.title());
                book.row(true, t.columns().stream().map(Report.Column::label).toList());
                for (Row row : t.rows()) {
                    List<Object> cells = new ArrayList<>(row.cells());
                    if (row.depth() > 0 && !cells.isEmpty() && cells.getFirst() instanceof String s) {
                        cells.set(0, "  ".repeat(row.depth()) + s);
                    }
                    book.row(row.strong(), cells);
                }
                book.row(false, List.of());
                book.row(false, List.of(r.title() + " · " + r.subtitle()));
                for (String n : r.notes()) {
                    book.row(false, List.of(n));
                }
            }
        }
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + name + ".xlsx\"");
        book.write(response.getOutputStream());
    }
}
