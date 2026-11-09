package cd.hrpay.payroll;

import cd.hrpay.security.CurrentUser;
import java.time.YearMonth;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The checks on a calculated month, HR's acceptances, and the thresholds. */
@RestController
@RequestMapping("/api/payroll")
public class PayrollChecksController {

    private final PayrollChecks checks;

    public PayrollChecksController(PayrollChecks checks) {
        this.checks = checks;
    }

    @GetMapping("/runs/{month}/checks")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve') or hasAuthority('payroll.close')")
    public PayrollChecks.Report report(@PathVariable YearMonth month) {
        return checks.report(month);
    }

    @PostMapping("/runs/{month}/checks/accept")
    @PreAuthorize("hasAuthority('payroll.run')")
    public PayrollChecks.Report accept(@PathVariable YearMonth month, @RequestBody PayrollChecks.AcceptInput in) {
        return checks.accept(month, in, CurrentUser.email());
    }

    @GetMapping("/check-settings")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve')")
    public PayrollChecks.Settings settings() {
        return checks.settings();
    }

    @PutMapping("/check-settings")
    @PreAuthorize("hasAuthority('payroll.run')")
    public PayrollChecks.Settings saveSettings(@RequestBody PayrollChecks.Settings in) {
        return checks.saveSettings(in);
    }
}
