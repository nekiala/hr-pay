package cd.hrpay.payroll;

import cd.hrpay.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PayrollController {

    public record ReasonInput(@NotBlank @Size(max = 300) String reason) {}

    public record LoanInput(@NotNull Long userId, @NotNull @Pattern(regexp = "LOAN|ADVANCE") String kind,
                            @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal principal,
                            @NotNull @Pattern(regexp = "USD|CDF") String currency,
                            @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal installment,
                            @NotNull YearMonth startMonth, @NotNull LocalDate grantedOn, @Size(max = 300) String note) {}

    public record PauseInput(@NotNull YearMonth month, @NotBlank @Size(max = 300) String reason) {}

    public record LoanView(Loans.Loan loan, List<Loans.Pause> pauses) {}

    private final PayrollRuns runs;
    private final Loans loans;

    public PayrollController(PayrollRuns runs, Loans loans) {
        this.runs = runs;
        this.loans = loans;
    }

    // ---- runs ------------------------------------------------------------------------------------------------

    @GetMapping("/payroll/runs")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve') or hasAuthority('payslip.read.all')")
    public List<PayrollRuns.Run> runs() {
        return runs.runs();
    }

    @GetMapping("/payroll/runs/{month}")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve') or hasAuthority('payslip.read.all')")
    public PayrollRuns.RunView run(@PathVariable YearMonth month) {
        return runs.view(month, List.of());
    }

    @PostMapping("/payroll/runs/{month}/calculate")
    @PreAuthorize("hasAuthority('payroll.run')")
    public PayrollRuns.RunView calculate(@PathVariable YearMonth month) {
        return runs.calculate(month);
    }

    @PostMapping("/payroll/runs/{month}/approve")
    @PreAuthorize("hasAuthority('payroll.approve')")
    public PayrollRuns.RunView approve(@PathVariable YearMonth month) {
        return runs.approve(month);
    }

    @PostMapping("/payroll/runs/{month}/send-back")
    @PreAuthorize("hasAuthority('payroll.approve') or hasAuthority('payroll.run')")
    public PayrollRuns.RunView sendBack(@PathVariable YearMonth month, @Valid @RequestBody ReasonInput b) {
        return runs.sendBack(month, b.reason());
    }

    @PostMapping("/payroll/runs/{month}/close")
    @PreAuthorize("hasAuthority('payroll.close')")
    public PayrollRuns.RunView close(@PathVariable YearMonth month) {
        return runs.close(month);
    }

    private PayslipPdf pdf;
    private HolidayAdjustments holidayAdjustments;

    @org.springframework.beans.factory.annotation.Autowired
    void setHolidayAdjustments(HolidayAdjustments holidayAdjustments) {
        this.holidayAdjustments = holidayAdjustments;
    }

    /** Pay carried into a later payroll because a holiday moved inside a closed month. */
    @GetMapping("/payroll/holiday-adjustments")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve') or hasAuthority('payslip.read.all')")
    public java.util.List<HolidayAdjustments.Adjustment> holidayAdjustments() {
        return holidayAdjustments.recent();
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setPdf(PayslipPdf pdf) {
        this.pdf = pdf;
    }

    // ---- payslips ----------------------------------------------------------------------------------------------

    @GetMapping("/payslips/{id}.pdf")
    @PreAuthorize("isAuthenticated()")
    public org.springframework.http.ResponseEntity<byte[]> payslipPdf(@PathVariable long id,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String template) {
        if (template != null && !template.matches("CLASSIC|COMPACT|LEDGER")) {
            throw new cd.hrpay.common.BusinessRuleException("Choose the CLASSIC, COMPACT or LEDGER layout.");
        }
        PayrollRuns.Payslip p = payslip(id);
        String name = "payslip-" + p.month().toString().substring(0, 7) + "-" + p.snapshot().getOrDefault("registrationNo", p.userId())
                .toString().replaceAll("[^A-Za-z0-9_-]", "") + ".pdf";
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "inline; filename=\"" + name + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(template == null ? pdf.render(p) : pdf.render(p, template));
    }

    @GetMapping("/payslips/{id}/year-to-date")
    @PreAuthorize("isAuthenticated()")
    public PayslipPdf.YearToDate yearToDate(@PathVariable long id) {
        return pdf.yearToDate(payslip(id));
    }

    @GetMapping("/payslips/{id}")
    @PreAuthorize("isAuthenticated()")
    public PayrollRuns.Payslip payslip(@PathVariable long id) {
        PayrollRuns.Payslip p = runs.payslip(id);
        boolean all = CurrentUser.has("payslip.read.all") || CurrentUser.has("payroll.run") || CurrentUser.has("payroll.approve");
        boolean own = Objects.equals(p.userId(), CurrentUser.id()) && CurrentUser.has("payslip.read.own")
                && p.runStatus().equals("CLOSED");
        if (!all && !own) {
            throw new AccessDeniedException("You cannot see this payslip.");
        }
        return p;
    }

    @GetMapping("/my/payslips")
    @PreAuthorize("hasAuthority('payslip.read.own')")
    public List<PayrollRuns.PayslipSummary> myPayslips() {
        return runs.closedPayslipsOf(CurrentUser.id());
    }

    // ---- loans -------------------------------------------------------------------------------------------------

    @GetMapping("/loans")
    @PreAuthorize("hasAuthority('loan.manage') or hasAuthority('payslip.read.all')")
    public List<Loans.Loan> loans(@RequestParam(required = false) String status) {
        return loans.list(status);
    }

    @GetMapping("/loans/{id}")
    @PreAuthorize("hasAuthority('loan.manage') or hasAuthority('payslip.read.all')")
    public LoanView loan(@PathVariable long id) {
        return new LoanView(loans.loan(id), loans.pauses(id));
    }

    @PostMapping("/loans")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('loan.manage')")
    public Loans.Loan grant(@Valid @RequestBody LoanInput b) {
        return loans.grant(b.userId(), b.kind(), b.principal(), b.currency(), b.installment(), b.startMonth(),
                b.grantedOn(), b.note());
    }

    @PostMapping("/loans/{id}/cancel")
    @PreAuthorize("hasAuthority('loan.manage')")
    public Loans.Loan cancel(@PathVariable long id, @Valid @RequestBody ReasonInput b) {
        return loans.cancel(id, b.reason());
    }

    @PostMapping("/loans/{id}/pauses")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('loan.manage')")
    public Loans.Pause pause(@PathVariable long id, @Valid @RequestBody PauseInput b) {
        return loans.pause(id, b.month(), b.reason());
    }

    @GetMapping("/users/{userId}/loans")
    @PreAuthorize("hasAuthority('loan.manage') or hasAuthority('salary.read')")
    public List<Loans.Loan> userLoans(@PathVariable long userId) {
        return loans.forUser(userId);
    }
}
