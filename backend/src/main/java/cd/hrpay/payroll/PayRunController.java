package cd.hrpay.payroll;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Pay runs page: every pay run of the company in one list, the types it can run, one month's runs with what holds
 * them up, and the 13th month run. Each type is still worked out, approved and closed by its own endpoints and rules.
 */
@RestController
@cd.hrpay.platform.RequiresModule("PAYROLL")
public class PayRunController {

    public record ThirteenthInput(@NotNull @Min(2000) @Max(2100) Integer year, @NotNull YearMonth month) {}

    public record ExcludeInput(@NotNull Long userId, @NotBlank @Size(max = 300) String reason) {}

    public record NoteInput(@Size(max = 300) String note) {}

    public record ThirteenthOverview(cd.hrpay.evaluation.ThirteenthMonth.Settings settings, List<Thirteenths.Run> runs) {}

    private final PayRuns payRuns;
    private final Thirteenths thirteenths;
    private final cd.hrpay.job.BackgroundJobs jobs;

    public PayRunController(PayRuns payRuns, Thirteenths thirteenths, cd.hrpay.job.BackgroundJobs jobs) {
        this.payRuns = payRuns;
        this.thirteenths = thirteenths;
        this.jobs = jobs;
    }

    @GetMapping("/api/pay-runs/types")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve') or hasAuthority('payslip.read.all')")
    public List<PayRuns.TypeInfo> types() {
        return payRuns.types();
    }

    /** The runs of the months from..to (default: the last twelve months). */
    @GetMapping("/api/pay-runs")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve') or hasAuthority('payslip.read.all')")
    public List<PayRuns.Row> list(@RequestParam(required = false) YearMonth from, @RequestParam(required = false) YearMonth to) {
        YearMonth end = to == null ? YearMonth.now().plusMonths(1) : to;
        return payRuns.list(from == null ? end.minusMonths(12) : from, end);
    }

    @GetMapping("/api/pay-runs/month/{month}")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve') or hasAuthority('payslip.read.all')")
    public PayRuns.Month month(@PathVariable YearMonth month) {
        return payRuns.month(month);
    }

    // ---- the 13th month run ---------------------------------------------------------------------------------------

    @GetMapping("/api/thirteenth-runs")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve') or hasAuthority('payslip.read.all')")
    public ThirteenthOverview thirteenthRuns() {
        return new ThirteenthOverview(thirteenths.settings(), thirteenths.runs());
    }

    @GetMapping("/api/thirteenth-runs/{id}")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve') or hasAuthority('payslip.read.all')")
    public Thirteenths.Run thirteenthRun(@PathVariable long id) {
        return thirteenths.find(id);
    }

    @PostMapping("/api/thirteenth-runs/calculate")
    @PreAuthorize("hasAuthority('payroll.run')")
    public ResponseEntity<Object> calculateThirteenth(@Valid @RequestBody ThirteenthInput b) {
        return jobs.answer(jobs.submit("thirteenth.calculate", "thirteenth:" + b.year(),
                Map.of("year", String.valueOf(b.year()), "month", b.month().toString()), null));
    }

    @PostMapping("/api/thirteenth-runs/{id}/exclusions")
    @PreAuthorize("hasAuthority('payroll.run')")
    public Thirteenths.Run exclude(@PathVariable long id, @Valid @RequestBody ExcludeInput b) {
        return thirteenths.exclude(id, b.userId(), b.reason());
    }

    @DeleteMapping("/api/thirteenth-runs/{id}/exclusions/{userId}")
    @PreAuthorize("hasAuthority('payroll.run')")
    public Thirteenths.Run include(@PathVariable long id, @PathVariable long userId) {
        return thirteenths.include(id, userId);
    }

    @PostMapping("/api/thirteenth-runs/{id}/approve")
    @PreAuthorize("hasAuthority('payroll.approve')")
    public Thirteenths.Run approveThirteenth(@PathVariable long id) {
        return thirteenths.approve(id);
    }

    @PostMapping("/api/thirteenth-runs/{id}/cancel")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve')")
    public Thirteenths.Run cancelThirteenth(@PathVariable long id, @Valid @RequestBody(required = false) NoteInput b) {
        return thirteenths.cancel(id, b == null ? null : b.note());
    }
}
