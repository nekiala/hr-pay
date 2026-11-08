package cd.hrpay.payroll;

import cd.hrpay.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The leave pay run: allowances of approved annual leave, worked out by HR, approved by Finance, paid before the leave. */
@RestController
@RequestMapping("/api/leave-pay")
public class LeaveAllowanceController {

    public record CalculateInput(List<Long> leaveRequestIds) {}

    public record PaidInput(@NotNull LocalDate paidOn, @Size(max = 100) String reference) {}

    public record NoteInput(@Size(max = 300) String note) {}

    public record Overview(boolean annualPaid, List<LeaveAllowances.Due> due, List<LeaveAllowances.Allowance> allowances) {}

    private final LeaveAllowances allowances;
    private final cd.hrpay.leave.LeaveService leave;
    private final PayslipPdf pdf;
    private final cd.hrpay.job.BackgroundJobs jobs;

    public LeaveAllowanceController(LeaveAllowances allowances, cd.hrpay.leave.LeaveService leave, PayslipPdf pdf,
                                    cd.hrpay.job.BackgroundJobs jobs) {
        this.jobs = jobs;
        this.allowances = allowances;
        this.leave = leave;
        this.pdf = pdf;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve') or hasAuthority('payroll.pay')")
    public Overview overview(@RequestParam(required = false) String status) {
        return new Overview(leave.annualPaid(), allowances.due(), allowances.list(status));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAuthority('leave.request')")
    public List<LeaveAllowances.Allowance> mine() {
        return allowances.forUser(CurrentUser.id());
    }

    @GetMapping("/{id}.pdf")
    @PreAuthorize("isAuthenticated()")
    public org.springframework.http.ResponseEntity<byte[]> pdf(@PathVariable long id) {
        PayrollRuns.Payslip p = allowances.payslip(id);
        String name = ("allocation-conge-" + (p.number() == null ? "draft-" + id : p.number())).replaceAll("[^A-Za-z0-9_-]", "") + ".pdf";
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .header("Content-Disposition", org.springframework.http.ContentDisposition.inline().filename(name).build().toString())
                .body(pdf.render(p));
    }

    @PostMapping("/calculate")
    @PreAuthorize("hasAuthority('payroll.run')")
    public org.springframework.http.ResponseEntity<Object> calculate(@RequestBody(required = false) CalculateInput b) {
        List<Long> ids = b == null || b.leaveRequestIds() == null ? List.of() : b.leaveRequestIds();
        return jobs.answer(jobs.submit("leave-pay.calculate", "leave-pay", java.util.Map.of("leaveRequestIds", ids), null));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('payroll.approve')")
    public LeaveAllowances.Allowance approve(@PathVariable long id) {
        return allowances.approve(id);
    }

    @PostMapping("/{id}/paid")
    @PreAuthorize("hasAuthority('payroll.pay') or hasAuthority('payroll.approve')")
    public LeaveAllowances.Allowance paid(@PathVariable long id, @Valid @RequestBody PaidInput b) {
        return allowances.markPaid(id, b.paidOn(), b.reference());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve')")
    public LeaveAllowances.Allowance cancel(@PathVariable long id, @Valid @RequestBody(required = false) NoteInput b) {
        return allowances.cancel(id, b == null ? null : b.note());
    }
}
