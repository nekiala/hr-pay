package cd.hrpay.filing;

import cd.hrpay.audit.AuditService;
import cd.hrpay.payroll.report.ReportPdf;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The year-to-date payroll ledger: each person's gross, taxable base, tax, CNSS and net, month by month. */
@RestController
@PreAuthorize("hasAuthority('payslip.read.all')")
public class YearLedgerController {

    private final AnnualPayFilings annualPay;
    private final ReportPdf pdf;
    private final AuditService audit;

    public YearLedgerController(AnnualPayFilings annualPay, ReportPdf pdf, AuditService audit) {
        this.annualPay = annualPay;
        this.pdf = pdf;
        this.audit = audit;
    }

    @GetMapping("/api/payroll/ledger/{year}")
    public ResponseEntity<?> ledger(@PathVariable int year, @RequestParam(defaultValue = "json") String format,
                                    HttpServletResponse response) throws IOException {
        LegalFilingController.checkYear(year);
        return LegalFilingController.send(pdf, audit, annualPay.ledger(year), format, "grand-livre-paie-" + year,
                Map.of("kind", "LEDGER", "year", year), response);
    }
}
