package cd.hrpay.payment;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.Csv;
import cd.hrpay.security.CurrentUser;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@cd.hrpay.platform.RequiresModule("PAYROLL")
@RequestMapping("/api")
public class PaymentController {

    public record CompanyInput(@NotBlank @Size(max = 150) String name, @Size(max = 300) String address,
                               @Size(max = 40) String cnssNumber, @Size(max = 40) String taxNumber,
                               @Size(max = 40) String nationalId, @Size(max = 60) String rccm,
                               @Size(max = 40) String inppNumber, @Size(max = 40) String onemNumber,
                               @Size(max = 100) String bankName, @Size(max = 60) String bankAccount, Long bankId) {}

    public record PayslipSettingsInput(@Size(max = 40) String phone, @Size(max = 150) String email,
                                       @Size(max = 150) String website, @Size(max = 200) String legalForm,
                                       @Size(max = 300) String footer,
                                       @NotNull @Pattern(regexp = "CLASSIC|COMPACT|LEDGER") String template,
                                       @Size(max = 150) String activity) {}

    public record LogoInput(@NotBlank String contentType, @NotBlank @Size(max = 800_000) String base64) {}

    public record PrepareInput(@NotNull @Pattern(regexp = "BANK|MOBILE_MONEY|CASH") String method,
                               @NotBlank @Size(max = 100) String provider, @NotNull @Pattern(regexp = "USD|CDF|XAF") String currency) {}

    public record PaidInput(@NotNull LocalDate paidOn, @Size(max = 100) String reference) {}

    public record CancelInput(@NotBlank @Size(max = 300) String reason) {}

    public record DeclarationPaidInput(@NotNull LocalDate paidOn, @NotNull java.math.BigDecimal amount, @Size(max = 100) String reference,
                            @Size(max = 8) String mode) {}

    public record FiledInput(@NotNull LocalDate filedOn, @Size(max = 100) String reference, @Size(max = 300) String note) {}

    private final Company company;
    private final Payments payments;
    private final Declarations declarations;
    private final YearSummaries years;
    private final AuditService audit;
    private final Clock clock;
    private cd.hrpay.payroll.PayslipPdf payslipPdf;
    private DeclarationCalendar calendar;
    private CnssForms cnssForms;

    @org.springframework.beans.factory.annotation.Autowired
    void setDeclarationParts(DeclarationCalendar calendar, CnssForms cnssForms) {
        this.calendar = calendar;
        this.cnssForms = cnssForms;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setPayslipPdf(cd.hrpay.payroll.PayslipPdf payslipPdf) {
        this.payslipPdf = payslipPdf;
    }

    public PaymentController(Company company, Payments payments, Declarations declarations, YearSummaries years,
                             AuditService audit, Clock clock) {
        this.company = company;
        this.payments = payments;
        this.declarations = declarations;
        this.years = years;
        this.audit = audit;
        this.clock = clock;
    }

    private int thisYear() {
        return LocalDate.now(clock.withZone(ZoneId.of("Africa/Kinshasa"))).getYear();
    }

    // ---- employer --------------------------------------------------------------------------------------------

    @GetMapping("/company")
    @PreAuthorize("hasAuthority('company.manage') or hasAuthority('declaration.manage') or hasAuthority('payroll.pay') or hasAuthority('payslip.read.own')")
    public Company.Details company() {
        return company.get();
    }

    @PutMapping("/company")
    @PreAuthorize("hasAuthority('company.manage')")
    public Company.Details saveCompany(@Valid @RequestBody CompanyInput b) {
        return company.save(new Company.Details(b.name(), b.address(), b.cnssNumber(), b.taxNumber(), b.nationalId(),
                b.rccm(), b.inppNumber(), b.onemNumber(), b.bankName(), b.bankAccount(), b.bankId()));
    }

    public record RepresentativeInput(@jakarta.validation.constraints.Size(max = 150) String name,
                                      @jakarta.validation.constraints.Size(max = 100) String title,
                                      @jakarta.validation.constraints.Size(max = 100) String signingPlace) {}

    @GetMapping("/company/representative")
    @PreAuthorize("hasAuthority('company.manage') or hasAuthority('lifecycle.manage')")
    public Company.Representative representative() {
        return company.representative();
    }

    @PutMapping("/company/representative")
    @PreAuthorize("hasAuthority('company.manage')")
    public Company.Representative saveRepresentative(@Valid @RequestBody RepresentativeInput b) {
        return company.saveRepresentative(new Company.Representative(b.name(), b.title(), b.signingPlace()));
    }

    @GetMapping("/company/payslip-settings")
    @PreAuthorize("isAuthenticated()")
    public Company.PayslipSettings payslipSettings() {
        return company.payslipSettings();
    }

    @PutMapping("/company/payslip-settings")
    @PreAuthorize("hasAuthority('company.manage')")
    public Company.PayslipSettings savePayslipSettings(@Valid @RequestBody PayslipSettingsInput b) {
        return company.savePayslipSettings(new Company.PayslipSettings(b.phone(), b.email(), b.website(), b.legalForm(),
                b.footer(), b.template(), false, b.activity()));
    }

    /** A made-up payslip in one of the layouts, with the company's real header, to compare the layouts. */
    @GetMapping("/company/payslip-preview.pdf")
    @PreAuthorize("hasAuthority('company.manage')")
    public org.springframework.http.ResponseEntity<byte[]> payslipPreview(@RequestParam String template) {
        if (!template.matches("CLASSIC|COMPACT|LEDGER")) {
            throw new cd.hrpay.common.BusinessRuleException("Choose the CLASSIC, COMPACT or LEDGER layout.");
        }
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "inline; filename=\"payslip-sample-" + template.toLowerCase() + ".pdf\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(payslipPdf.sample(template));
    }

    /** The logo, for the payslip screen. Every signed-in person may see it. */
    @GetMapping("/company/logo")
    @PreAuthorize("isAuthenticated()")
    public org.springframework.http.ResponseEntity<byte[]> logo() {
        return company.logo().map(l -> org.springframework.http.ResponseEntity.ok()
                        .contentType(org.springframework.http.MediaType.parseMediaType(l.contentType()))
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Cache-Control", "private, max-age=300")
                        .body(l.data()))
                .orElseGet(() -> org.springframework.http.ResponseEntity.notFound().build());
    }

    @PutMapping("/company/logo")
    @PreAuthorize("hasAuthority('company.manage')")
    public Company.PayslipSettings saveLogo(@Valid @RequestBody LogoInput b) {
        byte[] data;
        try {
            data = java.util.Base64.getDecoder().decode(b.base64().replaceFirst("^data:[^,]*,", ""));
        } catch (IllegalArgumentException e) {
            throw new cd.hrpay.common.BusinessRuleException("The file could not be read.");
        }
        company.saveLogo(b.contentType(), data);
        return company.payslipSettings();
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/company/logo")
    @PreAuthorize("hasAuthority('company.manage')")
    public Company.PayslipSettings removeLogo() {
        company.removeLogo();
        return company.payslipSettings();
    }

    // ---- payments ----------------------------------------------------------------------------------------------

    @GetMapping("/payments/{month}")
    @PreAuthorize("hasAuthority('payroll.pay') or hasAuthority('payslip.read.all')")
    public Payments.Overview payments(@PathVariable YearMonth month,
                                      @org.springframework.web.bind.annotation.RequestParam(defaultValue = "PAYROLL") String kind) {
        return payments.overview(month, kind.toUpperCase(java.util.Locale.ROOT));
    }

    @PostMapping("/payments/{month}/batches")
    @PreAuthorize("hasAuthority('payroll.pay')")
    public Payments.BatchView prepare(@PathVariable YearMonth month, @Valid @RequestBody PrepareInput b,
                                      @org.springframework.web.bind.annotation.RequestParam(defaultValue = "PAYROLL") String kind) {
        return payments.prepare(month, kind.toUpperCase(java.util.Locale.ROOT), b.method(), b.provider(), b.currency());
    }

    @GetMapping("/payment-batches/{id}")
    @PreAuthorize("hasAuthority('payroll.pay') or hasAuthority('payslip.read.all')")
    public Payments.BatchView batch(@PathVariable long id) {
        return payments.batch(id);
    }

    @GetMapping("/payment-batches/{id}/file.csv")
    @PreAuthorize("hasAuthority('payroll.pay')")
    public void batchFile(@PathVariable long id, HttpServletResponse response) throws IOException {
        Payments.BatchView v = payments.batch(id);
        Payments.Batch b = v.batch();
        boolean quinzaine = Payments.QUINZAINE.equals(v.kind());
        boolean advance = Payments.ADVANCE.equals(v.kind());
        String name = (quinzaine ? "quinzaine-" : advance ? "advances-" : "salaries-") + v.month() + "-" + b.provider().replaceAll("[^A-Za-z0-9]+", "-").toLowerCase()
                + "-" + b.currency().toLowerCase() + "-" + b.id() + ".csv";
        PrintWriter out = Csv.start(response, name);
        String narration = (quinzaine ? "Quinzaine " : advance ? "Salary advance " : "Salary ") + v.month();
        int n = 0;
        switch (b.method()) {
            case "BANK" -> {
                Company.Details c = company.get();
                Csv.row(out, "No", "Registration no", "Beneficiary", "Bank", "Account", "Amount", "Currency", "Narration",
                        "Ordering account");
                for (Payments.Line l : v.lines()) {
                    Csv.row(out, ++n, l.registrationNo(), l.beneficiary(), b.provider(), l.account(), l.amount(),
                            l.currency(), narration, c.bankAccount());
                }
            }
            case "MOBILE_MONEY" -> {
                Csv.row(out, "No", "MSISDN", "Beneficiary", "Amount", "Currency", "Reference");
                for (Payments.Line l : v.lines()) {
                    Csv.row(out, ++n, l.account(), l.beneficiary(), l.amount(), l.currency(),
                            narration + " " + (l.registrationNo() == null ? "" : l.registrationNo()));
                }
            }
            default -> {
                Csv.row(out, "No", "Registration no", "Beneficiary", "Amount", "Currency", "Signature");
                for (Payments.Line l : v.lines()) {
                    Csv.row(out, ++n, l.registrationNo(), l.beneficiary(), l.amount(), l.currency(), "");
                }
            }
        }
        if (b.method().equals("BANK")) {
            // The total sits under Amount, as in the lines above.
            Csv.row(out, "", "", "Total", "", "", b.total(), b.currency());
        } else {
            Csv.row(out, "", "", "Total", b.total(), b.currency());
        }
        out.flush();
        audit.record("payment.file_downloaded", "payment_batch", id, Map.of("lines", v.lines().size()));
    }

    @PostMapping("/payment-batches/{id}/paid")
    @PreAuthorize("hasAuthority('payroll.pay')")
    public Payments.BatchView paid(@PathVariable long id, @Valid @RequestBody PaidInput b) {
        return payments.markPaid(id, b.paidOn(), b.reference());
    }

    @PostMapping("/payment-batches/{id}/cancel")
    @PreAuthorize("hasAuthority('payroll.pay')")
    public Payments.BatchView cancel(@PathVariable long id, @Valid @RequestBody CancelInput b) {
        return payments.cancel(id, b.reason());
    }

    // ---- declarations ------------------------------------------------------------------------------------------

    /** The calendar: each declaration of the last months, when it is due, where it stands, what lateness costs. */
    @GetMapping("/declarations/calendar")
    @PreAuthorize("hasAuthority('declaration.manage')")
    public DeclarationCalendar.Page calendar(@RequestParam(defaultValue = "12") int months) {
        return calendar.page(Math.min(36, Math.max(1, months)));
    }

    @GetMapping("/declarations/{month}")
    @PreAuthorize("hasAuthority('declaration.manage')")
    public Map<String, Object> declarationSummary(@PathVariable YearMonth month) {
        return Map.of("month", month.toString(), "company", company.get(), "declarations", declarations.summary(month),
                "calendar", calendar.items(month, month));
    }

    @PostMapping("/declarations/{month}/{kind}/paid")
    @PreAuthorize("hasAuthority('declaration.manage')")
    public DeclarationCalendar.Item paid(@PathVariable YearMonth month, @PathVariable String kind, @Valid @RequestBody DeclarationPaidInput b) {
        return calendar.pay(month, kind.toUpperCase(), b.paidOn(), b.amount(), b.reference(), b.mode());
    }

    @DeleteMapping("/declarations/{month}/{kind}/paid")
    @PreAuthorize("hasAuthority('declaration.manage')")
    public DeclarationCalendar.Item unpaid(@PathVariable YearMonth month, @PathVariable String kind) {
        return calendar.unpay(month, kind.toUpperCase());
    }

    /** The CNSS forms MOD. DC and MOD. DCC, filled (DR Congo, employers outside the guichet unique). */
    @GetMapping("/declarations/{month}/CNSS/{form:mod-dc|mod-dcc}.pdf")
    @PreAuthorize("hasAuthority('declaration.manage')")
    public org.springframework.http.ResponseEntity<byte[]> cnssForm(@PathVariable YearMonth month, @PathVariable String form) {
        byte[] pdf = form.equals("mod-dc") ? cnssForms.modDc(month) : cnssForms.modDcc(month);
        audit.record("declaration.exported", "declaration", month + " CNSS", Map.of("form", form));
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "inline; filename=\"" + form + "-" + month + ".pdf\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(pdf);
    }

    @GetMapping("/declarations/{month}/{kind}")
    @PreAuthorize("hasAuthority('declaration.manage')")
    public Declarations.Declaration declaration(@PathVariable YearMonth month, @PathVariable String kind) {
        return declarations.declaration(month, kind.toUpperCase());
    }

    @GetMapping("/declarations/{month}/{kind}/file.csv")
    @PreAuthorize("hasAuthority('declaration.manage')")
    public void declarationFile(@PathVariable YearMonth month, @PathVariable String kind, HttpServletResponse response)
            throws IOException {
        Declarations.Declaration d = declarations.declaration(month, kind.toUpperCase());
        Company.Details c = company.get();
        PrintWriter out = Csv.start(response, d.kind().toLowerCase() + "-" + d.month() + ".csv");
        String employerId = switch (d.kind()) {
            case "CNSS" -> c.cnssNumber();
            case "IPR", "IERE", "TUS" -> c.taxNumber();
            case "INPP" -> c.inppNumber();
            default -> c.onemNumber();
        };
        Csv.row(out, "Employer", c.name(), "Employer number", employerId, "Month", d.month(), "Rate USD/CDF", d.usdCdf(),
                "Rate date", d.rateDate());
        boolean employeeShare = d.kind().equals("CNSS") || d.kind().equals("IPR");
        boolean employerShare = !d.kind().equals("IPR");
        String idLabel = d.kind().equals("CNSS") ? "CNSS number" : d.kind().equals("IPR") ? "NIF" : "";
        Csv.row(out, "No", "Registration no", "Name", idLabel, "Currency", "Base", "Base CDF",
                employeeShare ? (d.kind().equals("IPR") ? "IPR withheld CDF" : "Employee share CDF") : "",
                employerShare ? "Employer share CDF" : "", "Total CDF");
        int n = 0;
        for (Declarations.Row r : d.rows()) {
            Csv.row(out, ++n, r.registrationNo(), r.name(), r.idNumber(), r.currency(), r.base(), r.baseCdf(),
                    employeeShare ? r.employeeCdf() : "", employerShare ? r.employerCdf() : "", r.totalCdf());
        }
        Csv.row(out, "", "", "Total", "", "", "", d.baseCdf(), employeeShare ? d.employeeCdf() : "",
                employerShare ? d.employerCdf() : "", d.totalCdf());
        out.flush();
        audit.record("declaration.exported", "declaration", d.month() + " " + d.kind(), Map.of("rows", d.rows().size()));
    }

    @PostMapping("/declarations/{month}/{kind}/filed")
    @PreAuthorize("hasAuthority('declaration.manage')")
    public Declarations.Declaration filed(@PathVariable YearMonth month, @PathVariable String kind,
                                          @Valid @RequestBody FiledInput b) {
        return declarations.file(month, kind.toUpperCase(), b.filedOn(), b.reference(), b.note());
    }

    @DeleteMapping("/declarations/{month}/{kind}/filed")
    @PreAuthorize("hasAuthority('declaration.manage')")
    public Declarations.Declaration unfiled(@PathVariable YearMonth month, @PathVariable String kind) {
        return declarations.unfile(month, kind.toUpperCase());
    }

    // ---- year-end ----------------------------------------------------------------------------------------------

    @GetMapping("/year-summaries")
    @PreAuthorize("hasAuthority('payslip.read.all')")
    public List<YearSummaries.Row> yearSummaries(@RequestParam(required = false) Integer year) {
        return years.year(year != null ? year : thisYear());
    }

    @GetMapping("/year-summaries.csv")
    @PreAuthorize("hasAuthority('payslip.read.all')")
    public void yearSummariesFile(@RequestParam int year, HttpServletResponse response) throws IOException {
        PrintWriter out = Csv.start(response, "year-summary-" + year + ".csv");
        Csv.row(out, "Registration no", "Name", "Currency", "Months", "Gross", "CNSS employee", "IPR", "Other deductions",
                "Net", "IPR in CDF");
        List<YearSummaries.Row> rows = years.year(year);
        for (YearSummaries.Row r : rows) {
            Csv.row(out, r.registrationNo(), r.name(), r.currency(), r.months(), r.gross(), r.cnss(), r.ipr(),
                    r.otherDeductions(), r.net(), r.iprCdf());
        }
        out.flush();
        audit.record("year_summary.exported", "report", null, Map.of("year", year, "rows", rows.size()));
    }

    @GetMapping("/year-summaries/{userId}")
    @PreAuthorize("hasAuthority('payslip.read.all')")
    public YearSummaries.Employee yearSummary(@PathVariable long userId, @RequestParam(required = false) Integer year) {
        return years.employee(userId, year != null ? year : thisYear());
    }

    @GetMapping("/my/year-summary")
    @PreAuthorize("hasAuthority('payslip.read.own')")
    public YearSummaries.Employee myYearSummary(@RequestParam(required = false) Integer year) {
        return years.employee(CurrentUser.id(), year != null ? year : thisYear());
    }
}
