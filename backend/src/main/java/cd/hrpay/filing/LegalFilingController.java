package cd.hrpay.filing;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.payroll.report.Report;
import cd.hrpay.payroll.report.ReportPdf;
import cd.hrpay.payroll.report.ReportXlsx;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
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

/**
 * The legal registers and annual filings: the page, each form as JSON, PDF or Excel (every download written to the
 * audit log), and recording what was filed.
 */
@RestController
@RequestMapping("/api/legal-filings")
@PreAuthorize("hasAuthority('declaration.manage')")
public class LegalFilingController {

    private final LegalFilings filings;
    private final AnnualPayFilings annualPay;
    private final ReportPdf pdf;
    private final AuditService audit;

    public LegalFilingController(LegalFilings filings, AnnualPayFilings annualPay, ReportPdf pdf, AuditService audit) {
        this.filings = filings;
        this.annualPay = annualPay;
        this.pdf = pdf;
        this.audit = audit;
    }

    @GetMapping
    public LegalFilings.Page page() {
        return filings.page();
    }

    public record SettingsBody(LocalDate filingsFrom, Boolean personnelRegister) {}

    @PutMapping("/settings")
    public LegalFilings.Settings saveSettings(@RequestBody SettingsBody body) {
        return filings.saveSettings(body.filingsFrom(), body.personnelRegister());
    }

    public record FiledBody(LocalDate filedOn, String reference, String note) {}

    @PostMapping("/movements/{kind}/{userId}/{eventDate}/filed")
    public LegalFilings.Page fileMovement(@PathVariable String kind, @PathVariable long userId,
                                         @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate eventDate,
                                         @RequestBody FiledBody body) {
        filings.fileMovement(kind, userId, eventDate, body.filedOn(), body.reference(), body.note());
        return filings.page();
    }

    @DeleteMapping("/movements/{kind}/{userId}/{eventDate}/filed")
    public LegalFilings.Page unfileMovement(@PathVariable String kind, @PathVariable long userId,
                                           @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate eventDate) {
        filings.unfile(kind, userId, eventDate, null);
        return filings.page();
    }

    @PostMapping("/annual/{kind}/{year}/filed")
    public LegalFilings.Page fileAnnual(@PathVariable String kind, @PathVariable int year, @RequestBody FiledBody body) {
        filings.fileAnnual(kind, year, body.filedOn(), body.reference(), body.note());
        return filings.page();
    }

    @DeleteMapping("/annual/{kind}/{year}/filed")
    public LegalFilings.Page unfileAnnual(@PathVariable String kind, @PathVariable int year) {
        filings.unfile(kind, null, null, year);
        return filings.page();
    }

    @GetMapping("/movements/{kind}/{userId}/{eventDate}")
    public ResponseEntity<?> movementForm(@PathVariable String kind, @PathVariable long userId,
                                          @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate eventDate,
                                          @RequestParam(defaultValue = "json") String format, HttpServletResponse response) throws IOException {
        Report r = filings.movementForm(kind, userId, eventDate);
        return send(r, format, (kind.equals("HIRE") ? "declaration-embauche-" : "declaration-depart-") + userId + "-" + eventDate,
                Map.of("kind", kind, "userId", userId, "eventDate", eventDate.toString()), response);
    }

    @GetMapping("/movements")
    public ResponseEntity<?> movementList(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                          @RequestParam(defaultValue = "json") String format, HttpServletResponse response) throws IOException {
        return send(filings.movementList(from, to), format, "mouvements-" + from + "-" + to,
                Map.of("kind", "MOVEMENTS", "from", from.toString(), "to", to.toString()), response);
    }

    @GetMapping("/annual/{kind}/{year}")
    public ResponseEntity<?> annual(@PathVariable String kind, @PathVariable int year,
                                    @RequestParam(defaultValue = "json") String format, HttpServletResponse response) throws IOException {
        checkYear(year);
        Report r = switch (kind) {
            case "WORKFORCE" -> filings.workforce(year);
            case "BILAN_SOCIAL" -> filings.bilanSocial(year);
            case "TAX_ANNUAL" -> annualPay.taxSummary(year);
            default -> throw new cd.hrpay.common.NotFoundException("Unknown filing " + kind + ".");
        };
        String name = switch (kind) {
            case "WORKFORCE" -> "declaration-main-oeuvre-";
            case "BILAN_SOCIAL" -> "bilan-social-";
            default -> "releve-annuel-impot-";
        };
        return send(r, format, name + year, Map.of("kind", kind, "year", year), response);
    }

    /** The individual tax sheets that go with the annual summary: everyone, or one person. */
    @GetMapping("/annual/TAX_ANNUAL/{year}/sheets")
    public ResponseEntity<?> sheets(@PathVariable int year, @RequestParam(required = false) Long userId,
                                    @RequestParam(defaultValue = "json") String format, HttpServletResponse response) throws IOException {
        checkYear(year);
        Map<String, Object> what = new LinkedHashMap<>(Map.of("kind", "TAX_SHEETS", "year", year));
        if (userId != null) {
            what.put("userId", userId);
        }
        return send(annualPay.individualSheets(year, userId), format, "fiches-individuelles-" + year + (userId == null ? "" : "-" + userId),
                what, response);
    }

    static void checkYear(int year) {
        if (year < 2000 || year > LocalDate.now().getYear() + 1) {
            throw new BusinessRuleException("Choose a year.");
        }
    }

    @GetMapping("/register")
    public ResponseEntity<?> register(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                      @RequestParam(defaultValue = "json") String format, HttpServletResponse response) throws IOException {
        return send(filings.register(from, to), format, "registre-employeur-" + from + "-" + to,
                Map.of("kind", "REGISTER", "from", from.toString(), "to", to.toString()), response);
    }

    @GetMapping("/register/visits")
    public List<LegalFilings.Visit> visits() {
        return filings.visits();
    }

    public record VisitBody(LocalDate visitedOn, String inspector, String observations) {}

    @PostMapping("/register/visits")
    public LegalFilings.Visit addVisit(@RequestBody VisitBody body) {
        return filings.addVisit(body.visitedOn(), body.inspector(), body.observations());
    }

    static ResponseEntity<?> send(ReportPdf pdf, AuditService audit, Report r, String format, String name, Map<String, Object> what,
                                  HttpServletResponse response) throws IOException {
        return sendWith(pdf, audit, r, format, name, what, response);
    }

    private ResponseEntity<?> send(Report r, String format, String name, Map<String, Object> what,
                                   HttpServletResponse response) throws IOException {
        return sendWith(pdf, audit, r, format, name, what, response);
    }

    private static ResponseEntity<?> sendWith(ReportPdf pdf, AuditService audit, Report r, String format, String name, Map<String, Object> what,
                                   HttpServletResponse response) throws IOException {
        if (!List.of("json", "pdf", "xlsx").contains(format)) {
            throw new BusinessRuleException("Choose the format: json, pdf or xlsx.");
        }
        if (format.equals("json")) {
            return ResponseEntity.ok(r);
        }
        Map<String, Object> details = new LinkedHashMap<>(what);
        details.put("format", format);
        audit.record("filing.exported", "legal_filing", null, details);
        if (format.equals("xlsx")) {
            ReportXlsx.write(response, name, List.of(r));
            return null;
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "attachment; filename=\"" + name + ".pdf\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(pdf.render(r));
    }
}
