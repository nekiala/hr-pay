package cd.hrpay.payroll;

import cd.hrpay.common.Sheet;
import cd.hrpay.i18n.Texts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

/** Departures and final settlements, corrections of closed months, back pay, accounting, the parallel run, sign-off. */
@RestController
@cd.hrpay.platform.RequiresModule("PAYROLL")
@RequestMapping("/api")
public class PayrollCompletenessController {

    public record DepartureInput(@NotNull LocalDate lastDay,
                                 @NotNull @Pattern(regexp = "RESIGNATION|DISMISSAL|GROSS_MISCONDUCT|REDUNDANCY|END_OF_CONTRACT|MUTUAL_AGREEMENT|RETIREMENT|DEATH") String reason,
                                 LocalDate noticeDate,
                                 @Pattern(regexp = "EMPLOYER|EMPLOYEE") String noticeGivenBy,
                                 @Pattern(regexp = "WORKED|PAID|OWED|NONE") String notice,
                                 @DecimalMin("0") @Digits(integer = 4, fraction = 2) BigDecimal noticeDays,
                                 @DecimalMin("0") @Digits(integer = 4, fraction = 2) BigDecimal leaveDays,
                                 @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal severanceAmount,
                                 @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal thirteenthAmount,
                                 @Size(max = 500) String note) {

        Departures.Input toInput() {
            return new Departures.Input(lastDay, reason, noticeDate, noticeGivenBy, notice, noticeDays, leaveDays, severanceAmount, thirteenthAmount, note);
        }
    }

    public record ExtraInput(@NotNull Long elementId, @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
                             @Size(max = 200) String note) {}

    public record PreviewInput(@Valid List<ExtraInput> extra) {}

    public record CorrectionInput(@NotNull @Pattern(regexp = "COMPLEMENTARY|NEXT_PAYROLL|REVERSAL") String mode,
                                  @NotBlank @Size(max = 300) String reason, @Valid List<ExtraInput> extra) {}

    public record BackPayInput(List<Long> payslipIds) {}

    public record AccountsInput(@Size(max = 12) @Pattern(regexp = "[0-9A-Za-z.-]*") String expenseAccount,
                                @Size(max = 12) @Pattern(regexp = "[0-9A-Za-z.-]*") String liabilityAccount) {}

    public record SignOffInput(@NotBlank @Size(max = 150) String certifiedBy, @NotNull LocalDate certifiedOn,
                               @Size(max = 500) String note) {}

    public record CompareInput(@NotBlank @Size(max = 5_000_000) String csv, @DecimalMin("0") BigDecimal tolerance) {}

    private final Departures departures;
    private final Corrections corrections;
    private final Accounting accounting;
    private final Certification certification;
    private final ParallelRun parallel;

    public PayrollCompletenessController(Departures departures, Corrections corrections, Accounting accounting,
                                         Certification certification, ParallelRun parallel) {
        this.departures = departures;
        this.corrections = corrections;
        this.accounting = accounting;
        this.certification = certification;
        this.parallel = parallel;
    }

    private static List<Corrections.Extra> extras(List<ExtraInput> in) {
        return in == null ? List.of() : in.stream().map(x -> new Corrections.Extra(x.elementId(), x.amount(), x.note())).toList();
    }

    // ---- departures -------------------------------------------------------------------------------------------

    @GetMapping("/departures")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve')")
    public List<Departures.Summary> departures() {
        return departures.list();
    }

    @GetMapping("/users/{userId}/departure")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve')")
    public ResponseEntity<Departures.Departure> departure(@PathVariable long userId) {
        return departures.find(userId).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/users/{userId}/departure/propose")
    @PreAuthorize("hasAuthority('payroll.run')")
    public Departures.Departure propose(@PathVariable long userId, @Valid @RequestBody DepartureInput in) {
        return departures.propose(userId, in.toInput());
    }

    @PutMapping("/users/{userId}/departure")
    @PreAuthorize("hasAuthority('payroll.run')")
    public Departures.Departure saveDeparture(@PathVariable long userId, @Valid @RequestBody DepartureInput in) {
        return departures.save(userId, in.toInput());
    }

    @PostMapping("/users/{userId}/departure/confirm")
    @PreAuthorize("hasAuthority('payroll.run')")
    public Departures.Departure confirm(@PathVariable long userId) {
        return departures.confirm(userId);
    }

    @DeleteMapping("/users/{userId}/departure")
    @PreAuthorize("hasAuthority('payroll.run')")
    public ResponseEntity<Void> cancelDeparture(@PathVariable long userId) {
        departures.cancel(userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/{userId}/departure/certificate.pdf")
    @PreAuthorize("hasAuthority('payroll.run')")
    public ResponseEntity<byte[]> certificate(@PathVariable long userId) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "inline; filename=\"certificate-of-employment-" + userId + ".pdf\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(departures.certificate(userId));
    }

    // ---- corrections and back pay ---------------------------------------------------------------------------

    @GetMapping("/payroll/corrections")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve')")
    public List<Corrections.Correction> corrections() {
        return corrections.list();
    }

    @PostMapping("/payslips/{id}/correction/preview")
    @PreAuthorize("hasAuthority('payroll.run')")
    public Corrections.Preview preview(@PathVariable long id, @Valid @RequestBody(required = false) PreviewInput in) {
        return corrections.preview(id, extras(in == null ? null : in.extra()));
    }

    @PostMapping("/payslips/{id}/corrections")
    @PreAuthorize("hasAuthority('payroll.run')")
    public Corrections.Correction correct(@PathVariable long id, @Valid @RequestBody CorrectionInput in) {
        return corrections.create(id, in.mode(), in.reason(), extras(in.extra()));
    }

    @PostMapping("/payroll/corrections/{id}/approve")
    @PreAuthorize("hasAuthority('payroll.approve')")
    public Corrections.Correction approveCorrection(@PathVariable long id) {
        return corrections.approve(id);
    }

    @DeleteMapping("/payroll/corrections/{id}")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve')")
    public ResponseEntity<Void> discardCorrection(@PathVariable long id) {
        corrections.discard(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/payroll/back-pay")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve')")
    public List<Corrections.BackPayDue> backPay() {
        return corrections.backPayDue();
    }

    @PostMapping("/payroll/back-pay")
    @PreAuthorize("hasAuthority('payroll.run')")
    public List<Corrections.Correction> payBackPay(@RequestBody(required = false) BackPayInput in) {
        return corrections.payBackPay(in == null ? null : in.payslipIds());
    }

    // ---- accounting -----------------------------------------------------------------------------------------

    @GetMapping("/accounting/accounts")
    @PreAuthorize("hasAuthority('accounting.export') or hasAuthority('masterdata.manage')")
    public List<Accounting.ElementAccounts> accounts() {
        return accounting.accounts();
    }

    @PutMapping("/accounting/accounts/{elementId}")
    @PreAuthorize("hasAuthority('accounting.export')")
    public Accounting.ElementAccounts saveAccounts(@PathVariable long elementId, @Valid @RequestBody AccountsInput in) {
        return accounting.saveAccounts(elementId, in.expenseAccount(), in.liabilityAccount());
    }

    @GetMapping("/accounting/{month}")
    @PreAuthorize("hasAuthority('accounting.export')")
    public Accounting.Journal journal(@PathVariable YearMonth month, @RequestParam(defaultValue = "false") boolean byCostCentre) {
        return accounting.journal(month, byCostCentre);
    }

    @GetMapping({"/accounting/{month}.csv", "/accounting/{month}.xlsx"})
    @PreAuthorize("hasAuthority('accounting.export')")
    public void journalFile(@PathVariable YearMonth month, @RequestParam(defaultValue = "false") boolean byCostCentre,
                            HttpServletRequest request, HttpServletResponse response) throws IOException {
        Accounting.Journal j = accounting.journal(month, byCostCentre);
        Sheet sheet = Sheet.open(request, response, "payroll-entries-" + month);
        sheet.row(Texts.t("Journal"), Texts.t("Date"), Texts.t("Reference"), Texts.t("Account"), Texts.t("Label"),
                Texts.t("Cost centre"), Texts.t("Debit"), Texts.t("Credit"), Texts.t("Currency"));
        for (Accounting.Entry e : j.entries()) {
            sheet.row(j.journal(), j.date().toString(), j.reference(), e.account(), Texts.t("Payroll %s", month) + " · " + e.label(),
                    e.costCentre(), e.debit(), e.credit(), j.currency());
        }
        sheet.finish();
        accounting.exported(month);
    }

    // ---- parallel run ---------------------------------------------------------------------------------------

    @PostMapping("/payroll/runs/{month}/compare")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve')")
    public ParallelRun.Comparison compare(@PathVariable YearMonth month, @Valid @RequestBody CompareInput in) {
        return parallel.compare(month, in.csv(), in.tolerance());
    }

    @GetMapping("/payroll/compare-template.csv")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve')")
    public ResponseEntity<String> compareTemplate() {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header("Content-Disposition", "attachment; filename=\"old-payroll-template.csv\"")
                .body("registration;gross;cnss;ipr;net\nEMP-0001;1200.00;60.00;85.50;1054.50\n");
    }

    // ---- sign-off of rates and formulas ------------------------------------------------------------------------

    @GetMapping("/payroll/certification")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve') or hasAuthority('masterdata.manage')")
    public Certification.Status certificationStatus() {
        return certification.status();
    }

    @PostMapping("/payroll/certification")
    @PreAuthorize("hasAuthority('masterdata.manage')")
    public Certification.Status certify(@Valid @RequestBody SignOffInput in) {
        return certification.record(in.certifiedBy(), in.certifiedOn(), in.note());
    }

    @GetMapping({"/payroll/certification.xlsx", "/payroll/certification.csv"})
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve') or hasAuthority('masterdata.manage')")
    public void certificationWorkbook(HttpServletRequest request, HttpServletResponse response) throws IOException {
        certification.workbook(Sheet.open(request, response, "rates-and-formulas-to-check"));
    }
}
