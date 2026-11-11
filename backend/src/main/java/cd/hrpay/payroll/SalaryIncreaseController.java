package cd.hrpay.payroll;

import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.job.JobFile;
import cd.hrpay.payroll.report.Report;
import cd.hrpay.payroll.report.ReportPdf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * General salary increases: HR (payroll.increase) prepares and simulates them, someone else
 * (payroll.increase.approve, Finance and Administrator by default) approves, rejects or undoes them.
 */
@RestController
@cd.hrpay.platform.RequiresModule("PAYROLL")
public class SalaryIncreaseController {

    public record IncreaseInput(@NotBlank @Size(max = 120) String name,
                                @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") BigDecimal percent,
                                @DecimalMin("0") BigDecimal minimumAmount, @Size(max = 3) String minimumCurrency,
                                @DecimalMin("0") BigDecimal rounding, @NotNull @Size(min = 7, max = 7) String startMonth,
                                List<Long> categoryIds, List<Long> gradeIds) {

        SalaryIncreases.Input input() {
            LocalDate start;
            try {
                start = YearMonth.parse(startMonth).atDay(1);
            } catch (java.time.format.DateTimeParseException e) {
                throw new BusinessRuleException(Texts.t("Choose the month the increase starts."));
            }
            return new SalaryIncreases.Input(name, percent, minimumAmount, minimumCurrency, rounding, start, categoryIds, gradeIds);
        }
    }

    public record Decision(@Size(max = 300) String note) {}

    private static final String EITHER = "hasAuthority('payroll.increase') or hasAuthority('payroll.increase.approve')";

    private final SalaryIncreases increases;
    private final cd.hrpay.job.BackgroundJobs jobs;

    public SalaryIncreaseController(SalaryIncreases increases, cd.hrpay.job.BackgroundJobs jobs) {
        this.increases = increases;
        this.jobs = jobs;
    }

    @GetMapping("/api/payroll/increases")
    @PreAuthorize(EITHER)
    public List<SalaryIncreases.Scenario> list() {
        return increases.list();
    }

    @GetMapping("/api/payroll/increases/{id}")
    @PreAuthorize(EITHER)
    public SalaryIncreases.Scenario get(@PathVariable long id) {
        return increases.get(id);
    }

    /** What an increase not saved would do (a background job for a large company). */
    @PostMapping("/api/payroll/increases/simulate")
    @PreAuthorize("hasAuthority('payroll.increase')")
    public ResponseEntity<Object> simulate(@Valid @RequestBody IncreaseInput in, @RequestParam(defaultValue = "json") String format) {
        in.input();
        return jobs.answer(jobs.submit(Jobs.type(format), null, Map.of("input", in, "format", format), null));
    }

    @PostMapping("/api/payroll/increases")
    @PreAuthorize("hasAuthority('payroll.increase')")
    public SalaryIncreases.Scenario create(@Valid @RequestBody IncreaseInput in) {
        return increases.create(in.input());
    }

    @PutMapping("/api/payroll/increases/{id}")
    @PreAuthorize("hasAuthority('payroll.increase')")
    public SalaryIncreases.Scenario update(@PathVariable long id, @Valid @RequestBody IncreaseInput in) {
        return increases.update(id, in.input());
    }

    @DeleteMapping("/api/payroll/increases/{id}")
    @PreAuthorize("hasAuthority('payroll.increase')")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        increases.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** A saved increase worked out again with today's data (json, xlsx or pdf). */
    @PostMapping("/api/payroll/increases/{id}/preview")
    @PreAuthorize(EITHER)
    public ResponseEntity<Object> preview(@PathVariable long id, @RequestParam(defaultValue = "json") String format) {
        increases.get(id);
        return jobs.answer(jobs.submit(Jobs.type(format), null, Map.of("id", String.valueOf(id), "format", format), null));
    }

    @PostMapping("/api/payroll/increases/{id}/submit")
    @PreAuthorize("hasAuthority('payroll.increase')")
    public SalaryIncreases.Scenario submit(@PathVariable long id) {
        return increases.submit(id);
    }

    @PostMapping("/api/payroll/increases/{id}/approve")
    @PreAuthorize("hasAuthority('payroll.increase.approve')")
    public SalaryIncreases.Scenario approve(@PathVariable long id) {
        return increases.approve(id);
    }

    @PostMapping("/api/payroll/increases/{id}/reject")
    @PreAuthorize("hasAuthority('payroll.increase.approve')")
    public SalaryIncreases.Scenario reject(@PathVariable long id, @Valid @RequestBody(required = false) Decision d) {
        return increases.reject(id, d == null ? null : d.note());
    }

    @PostMapping("/api/payroll/increases/{id}/undo")
    @PreAuthorize("hasAuthority('payroll.increase.approve')")
    public SalaryIncreases.Scenario undo(@PathVariable long id) {
        return increases.undo(id);
    }

    // ---- the result as a report --------------------------------------------------------------------------------

    static Report report(SalaryIncreases.Result r) {
        List<Report.Column> tcols = List.of(Report.Column.text(Texts.t("Currency")), Report.Column.number(Texts.t("People")),
                Report.Column.money(Texts.t("Monthly cost now")), Report.Column.money(Texts.t("Gross per month")),
                Report.Column.money(Texts.t("Net per month")), Report.Column.money(Texts.t("Employer charges per month")),
                Report.Column.money(Texts.t("Cost per month")), Report.Column.number(Texts.t("% of the cost")),
                Report.Column.money(Texts.t("Cost to 31 December")), Report.Column.money(Texts.t("Cost over 12 months")),
                Report.Column.money(Texts.t("Back pay")));
        List<Report.Row> totals = r.totals().stream().map(t -> Report.Row.of(Arrays.asList(t.currency(), t.people(),
                t.payrollCost(), t.gross(), t.net(), t.employerCharges(), t.cost(), t.percentOfCost(), t.costToYearEnd(),
                t.costTwelveMonths(), t.arrears()))).toList();
        List<Report.Column> gcols = List.of(Report.Column.text(Texts.t("Grade")), Report.Column.text(Texts.t("Category")),
                Report.Column.text(Texts.t("Currency")), Report.Column.money(Texts.t("Base now")),
                Report.Column.money(Texts.t("New base")), Report.Column.money(Texts.t("Increase")),
                Report.Column.money(Texts.t("Legal minimum")), Report.Column.number(Texts.t("People")));
        List<Report.Row> grades = r.grades().stream().map(g -> Report.Row.of(Arrays.asList(g.code() + " · " + g.name(),
                g.categoryName(), g.currency(), g.oldBase(), g.newBase(), g.raise(), g.legalMinimum(), g.people()))).toList();
        List<Report.Column> pcols = List.of(Report.Column.text(Texts.t("Employee")), Report.Column.text(Texts.t("Registration No.")),
                Report.Column.text(Texts.t("Grade")), Report.Column.text(Texts.t("Currency")),
                Report.Column.money(Texts.t("Basic now")), Report.Column.money(Texts.t("New basic")),
                Report.Column.money(Texts.t("Gross now")), Report.Column.money(Texts.t("New gross")),
                Report.Column.money(Texts.t("Net now")), Report.Column.money(Texts.t("New net")),
                Report.Column.money(Texts.t("Cost now")), Report.Column.money(Texts.t("New cost")),
                Report.Column.money(Texts.t("Back pay")), Report.Column.text(Texts.t("Problem")));
        List<Report.Row> people = r.people().stream().map(p -> Report.Row.of(Arrays.asList(p.name(), p.registrationNo(),
                p.gradeCode(), p.currency(), p.oldBasic(), p.newBasic(), p.oldGross(), p.newGross(), p.oldNet(), p.newNet(),
                p.oldCost(), p.newCost(), p.arrears(), p.problem()))).toList();
        List<String> notes = new ArrayList<>(r.warnings());
        notes.addAll(r.notes());
        notes.add(Texts.t("A simulation: nothing was saved."));
        return new Report("salary-increase", Texts.t("General increase \"%s\": %s%% from %s", r.name(),
                r.percent().stripTrailingZeros().toPlainString(), Texts.month(YearMonth.from(r.startMonth()))),
                Texts.t("Worked out on %s with the data recorded today", Texts.day(LocalDate.now())), true,
                List.of(new Report.Table(Texts.t("Totals"), tcols, totals), new Report.Table(Texts.t("Grades"), gcols, grades),
                        new Report.Table(Texts.t("Employees"), pcols, people)), notes);
    }

    @Component
    static class Jobs {
        static final String JSON = "payroll.increase";
        static final String FILE = "payroll.increase.file";

        static String type(String format) {
            return switch (format) {
                case "json" -> JSON;
                case "xlsx", "pdf" -> FILE;
                default -> throw new BusinessRuleException("Choose the format: json, pdf or xlsx.");
            };
        }

        Jobs(cd.hrpay.job.JobTypes types, SalaryIncreases increases, ReportPdf pdf, JsonMapper json) {
            // Permission checked by the controller: HR prepares, the approver looks at it too.
            types.register(JSON, null, p -> Texts.t("General salary increase"), job -> run(increases, json, job));
            types.register(FILE, null, p -> Texts.t("General salary increase (%s)", p.path("format").asString("").toUpperCase()),
                    job -> {
                        SalaryIncreases.Result r = run(increases, json, job);
                        Report report = report(r);
                        boolean excel = "xlsx".equals(job.text("format"));
                        return new JobFile("salary-increase." + (excel ? "xlsx" : "pdf"),
                                excel ? SimulationController.XLSX : "application/pdf",
                                excel ? SimulationController.xlsx(report) : pdf.render(report));
                    });
        }

        private static SalaryIncreases.Result run(SalaryIncreases increases, JsonMapper json, cd.hrpay.job.JobContext job) {
            String id = job.text("id");
            if (id != null) {
                return increases.preview(Long.parseLong(id));
            }
            IncreaseInput in = json.treeToValue(job.params().get("input"), IncreaseInput.class);
            return increases.simulate(in.input());
        }
    }
}
