package cd.hrpay.payroll;

import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.Workbook;
import cd.hrpay.i18n.Texts;
import cd.hrpay.job.JobFile;
import cd.hrpay.payroll.report.Report;
import cd.hrpay.payroll.report.ReportPdf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simulate pay and year-end projection: read-only tools for whoever holds payroll.simulate (HR by default). Nothing they
 * do is saved, except a line in the audit log naming whose pay was looked at.
 */
@RestController
@cd.hrpay.platform.RequiresModule("PAYROLL")
@PreAuthorize("hasAuthority('payroll.simulate')")
public class SimulationController {

    public record WhatIfInput(@DecimalMin("0") BigDecimal basic, @DecimalMin("0") BigDecimal complement,
                              @DecimalMin("0") @jakarta.validation.constraints.DecimalMax("200") BigDecimal overtimeHours,
                              @DecimalMin("0") @jakarta.validation.constraints.DecimalMax("31") BigDecimal unpaidDays,
                              @DecimalMin("0") BigDecimal bonus, @Min(0) @Max(30) Integer children,
                              @Min(0) @Max(30) Integer dependants, @DecimalMin("0.0001") BigDecimal usdRate) {}

    public record SimulateInput(@NotNull Long userId, @Size(max = 20) String type, @Size(max = 7) String month,
                                @Min(2000) @Max(2100) Integer year, Long leaveRequestId, LocalDate leaveFrom,
                                LocalDate leaveTo, LocalDate lastDay, @Size(max = 30) String reason, @Valid WhatIfInput whatIf) {

        PaySimulator.Request request() {
            WhatIfInput w = whatIf;
            return new PaySimulator.Request(userId, type, month, year, leaveRequestId, leaveFrom, leaveTo, lastDay, reason,
                    w == null ? null : new PaySimulator.WhatIf(w.basic(), w.complement(), w.overtimeHours(), w.unpaidDays(),
                            w.bonus(), w.children(), w.dependants(), w.usdRate()));
        }
    }

    public record GroupInput(@NotNull @Min(2000) @Max(2100) Integer year, Long unitId, @Size(max = 10) String variable,
                             @DecimalMin("0.0001") BigDecimal usdRate) {}

    private final PaySimulator simulator;
    private final PayProjection projection;
    private final ReportPdf reportPdf;
    private final cd.hrpay.job.BackgroundJobs jobs;

    public SimulationController(PaySimulator simulator, PayProjection projection, ReportPdf reportPdf,
                                cd.hrpay.job.BackgroundJobs jobs) {
        this.simulator = simulator;
        this.projection = projection;
        this.reportPdf = reportPdf;
        this.jobs = jobs;
    }

    @GetMapping("/api/payroll/simulate/types")
    public List<String> types() {
        return PaySimulator.TYPES;
    }

    @PostMapping("/api/payroll/simulate")
    public PaySimulator.Result simulate(@Valid @RequestBody SimulateInput in) {
        return simulator.simulate(in.request());
    }

    /** The simulated payslip as a PDF marked SIMULATION (query parameters, so the browser can open it). */
    @GetMapping("/api/payroll/simulate.pdf")
    public ResponseEntity<byte[]> pdf(@RequestParam long userId, @RequestParam(required = false) String type,
                                      @RequestParam(required = false) String month, @RequestParam(required = false) Integer year,
                                      @RequestParam(required = false) Long leaveRequestId,
                                      @RequestParam(required = false) LocalDate leaveFrom,
                                      @RequestParam(required = false) LocalDate leaveTo,
                                      @RequestParam(required = false) LocalDate lastDay,
                                      @RequestParam(required = false) String reason,
                                      @RequestParam(required = false) BigDecimal basic,
                                      @RequestParam(required = false) BigDecimal complement,
                                      @RequestParam(required = false) BigDecimal overtimeHours,
                                      @RequestParam(required = false) BigDecimal unpaidDays,
                                      @RequestParam(required = false) BigDecimal bonus,
                                      @RequestParam(required = false) Integer children,
                                      @RequestParam(required = false) Integer dependants,
                                      @RequestParam(required = false) BigDecimal usdRate) {
        PaySimulator.WhatIf w = new PaySimulator.WhatIf(basic, complement, overtimeHours, unpaidDays, bonus, children,
                dependants, usdRate);
        byte[] pdf = simulator.pdf(new PaySimulator.Request(userId, type, month, year, leaveRequestId, leaveFrom, leaveTo,
                lastDay, reason, w));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"simulation-" + userId + ".pdf\"").body(pdf);
    }

    /** One person's year, month by month; format pdf or xlsx downloads it. */
    @GetMapping("/api/payroll/projection")
    public ResponseEntity<?> person(@RequestParam long userId, @RequestParam(required = false) Integer year,
                                    @RequestParam(defaultValue = "AVERAGE") String variable,
                                    @RequestParam(required = false) BigDecimal usdRate,
                                    @RequestParam(defaultValue = "json") String format) {
        int y = year == null ? LocalDate.now().getYear() : year;
        PayProjection.Person p = projection.person(userId, y, variable, usdRate);
        return switch (format) {
            case "json" -> ResponseEntity.ok(p);
            case "pdf" -> file(reportPdf.render(report(p)), "projection-" + y + "-" + userId + ".pdf", MediaType.APPLICATION_PDF_VALUE);
            case "xlsx" -> file(xlsx(report(p)), "projection-" + y + "-" + userId + ".xlsx", XLSX);
            default -> throw new BusinessRuleException("Choose the format: json, pdf or xlsx.");
        };
    }

    /** A unit's or the company's year (a background job for a large company). */
    @PostMapping("/api/payroll/projection/group")
    public ResponseEntity<Object> group(@Valid @RequestBody GroupInput in) {
        return jobs.answer(jobs.submit(Jobs.GROUP, null, params(in), null));
    }

    /** The same as an Excel workbook, from My jobs when it takes a while. */
    @PostMapping("/api/payroll/projection/group.xlsx")
    public ResponseEntity<Object> groupXlsx(@Valid @RequestBody GroupInput in) {
        return jobs.answer(jobs.submit(Jobs.GROUP_XLSX, null, params(in), null));
    }

    private static Map<String, Object> params(GroupInput in) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("year", String.valueOf(in.year()));
        p.put("unitId", in.unitId() == null ? null : String.valueOf(in.unitId()));
        p.put("variable", in.variable() == null ? "AVERAGE" : in.variable());
        p.put("usdRate", in.usdRate() == null ? null : in.usdRate().toPlainString());
        return p;
    }

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static ResponseEntity<byte[]> file(byte[] data, String name, String type) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"").body(data);
    }

    // ---- the projection as a report (PDF and Excel) ------------------------------------------------------------

    static String status(String s) {
        return switch (s) {
            case "PAID" -> Texts.t("Paid");
            case "PROJECTED" -> Texts.t("Projected");
            case "NO_CONTRACT" -> Texts.t("No contract");
            case "LEFT" -> Texts.t("Left");
            default -> Texts.t("Not paid");
        };
    }

    static Report report(PayProjection.Person p) {
        List<Report.Column> cols = List.of(Report.Column.text(Texts.t("Month")), Report.Column.text(Texts.t("Status")),
                Report.Column.text(Texts.t("Currency")), Report.Column.money(Texts.t("Basic salary")),
                Report.Column.money(Texts.t("Gross")), Report.Column.money(Texts.t("CNSS (employee)")),
                Report.Column.money(Texts.t("Income tax")), Report.Column.money(Texts.t("Net")),
                Report.Column.money(Texts.t("Employer charges")), Report.Column.money(Texts.t("Total cost")),
                Report.Column.text(Texts.t("What changes")));
        List<Report.Row> rows = new ArrayList<>();
        for (PayProjection.Month m : p.months()) {
            rows.add(Report.Row.of(java.util.Arrays.asList(m.month(), status(m.status()), m.currency(), m.basic(), m.gross(),
                    m.cnss(), m.tax(), m.net(), m.employerCharges(), m.cost(), String.join(" ", m.events()))));
        }
        for (PayProjection.Total t : p.totals()) {
            rows.add(new Report.Row(java.util.Arrays.asList(Texts.t("Total"), "", t.currency(), null, t.gross(), t.cnss(),
                    t.tax(), t.net(), t.employerCharges(), t.cost(), ""), "TOTAL", 0, null));
        }
        List<String> notes = new ArrayList<>(p.notes());
        if (p.leave() != null) {
            notes.add(Texts.t("Annual leave: %s days now, %s still to earn, %s requested; about %s days on 31 December%s.",
                    plain(p.leave().balanceNow()), plain(p.leave().earnedToCome()), plain(p.leave().requested()),
                    plain(p.leave().balanceYearEnd()), p.leave().value() == null ? ""
                            : Texts.t(", worth %s %s", p.leave().value().toPlainString(), p.leave().currency())));
        }
        notes.add(Texts.t("A projection is not a payslip: nothing was saved."));
        String who = p.name() + (p.registrationNo() == null ? "" : " (" + p.registrationNo() + ")");
        return new Report("projection", Texts.t("Pay projection %s: %s", String.valueOf(p.year()), who),
                Texts.t("Worked out on %s with the data recorded today", Texts.day(LocalDate.now())), true,
                List.of(new Report.Table(Texts.t("Months"), cols, rows)), notes);
    }

    static Report report(PayProjection.Group g) {
        List<Report.Column> cols = List.of(Report.Column.text(Texts.t("Month")), Report.Column.text(Texts.t("Currency")),
                Report.Column.number(Texts.t("People")), Report.Column.money(Texts.t("Gross")),
                Report.Column.money(Texts.t("CNSS (employee)")), Report.Column.money(Texts.t("Income tax")),
                Report.Column.money(Texts.t("Net")), Report.Column.money(Texts.t("Employer charges")),
                Report.Column.money(Texts.t("Total cost")));
        List<Report.Row> rows = new ArrayList<>();
        for (PayProjection.GroupMonth m : g.months()) {
            rows.add(Report.Row.of(List.of(m.month(), m.currency(), m.people(), m.gross(), m.cnss(), m.tax(), m.net(),
                    m.employerCharges(), m.cost())));
        }
        for (PayProjection.Total t : g.totals()) {
            rows.add(new Report.Row(java.util.Arrays.asList(Texts.t("Total"), t.currency(), null, t.gross(), t.cnss(), t.tax(),
                    t.net(), t.employerCharges(), t.cost()), "TOTAL", 0, null));
        }
        List<Report.Column> pcols = List.of(Report.Column.text(Texts.t("Employee")), Report.Column.text(Texts.t("Registration No.")),
                Report.Column.text(Texts.t("Currency")), Report.Column.money(Texts.t("Gross")), Report.Column.money(Texts.t("Net")),
                Report.Column.money(Texts.t("Total cost")), Report.Column.number(Texts.t("Raises")),
                Report.Column.money(Texts.t("Leave left on 31 Dec. (value)")));
        List<Report.Row> people = g.employees().stream().map(e -> Report.Row.of(java.util.Arrays.asList(e.name(),
                e.registrationNo(), e.currency(), e.gross(), e.net(), e.cost(), e.raises(), e.leaveValue()))).toList();
        List<String> notes = new ArrayList<>(g.skipped());
        notes.add(Texts.t("A projection is not a payslip: nothing was saved."));
        return new Report("projection-group", Texts.t("Pay projection %s: %s", String.valueOf(g.year()),
                g.unitName() == null ? Texts.t("whole company") : g.unitName()),
                Texts.t("Worked out on %s with the data recorded today", Texts.day(LocalDate.now())), true,
                List.of(new Report.Table(Texts.t("Months"), cols, rows), new Report.Table(Texts.t("Employees"), pcols, people)), notes);
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    static byte[] xlsx(Report r) {
        Workbook book = new Workbook();
        for (Report.Table t : r.tables()) {
            book.sheet(t.title());
            book.row(true, t.columns().stream().map(Report.Column::label).toList());
            for (Report.Row row : t.rows()) {
                book.row(row.strong(), row.cells());
            }
            book.row(false, List.of());
            book.row(false, List.of(r.title() + " · " + r.subtitle()));
            for (String n : r.notes()) {
                book.row(false, List.of(n));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            book.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** The group projection as background jobs: on screen, or as an Excel workbook. */
    @Component
    static class Jobs {
        static final String GROUP = "payroll.projection";
        static final String GROUP_XLSX = "payroll.projection.xlsx";

        Jobs(cd.hrpay.job.JobTypes types, PayProjection projection) {
            types.register(GROUP, "payroll.simulate", p -> Texts.t("Pay projection %s", p.path("year").asString("")),
                    job -> run(projection, job));
            types.register(GROUP_XLSX, "payroll.simulate", p -> Texts.t("Pay projection %s (Excel)", p.path("year").asString("")),
                    job -> {
                        PayProjection.Group g = run(projection, job);
                        return new JobFile("projection-" + g.year() + ".xlsx", XLSX, xlsx(report(g)));
                    });
        }

        private static PayProjection.Group run(PayProjection projection, cd.hrpay.job.JobContext job) {
            String unit = job.text("unitId");
            String rate = job.text("usdRate");
            return projection.group(Integer.parseInt(job.text("year")), unit == null ? null : Long.valueOf(unit),
                    job.text("variable"), rate == null ? null : new BigDecimal(rate));
        }
    }
}
