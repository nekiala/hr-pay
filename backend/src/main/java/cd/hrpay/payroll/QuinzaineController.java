package cd.hrpay.payroll;

import cd.hrpay.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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
 * The quinzaine (mid-month advance on pay): worked out by HR, approved by Finance, closed by HR, paid through the payment
 * files, taken back on the month's payslip.
 */
@RestController
@cd.hrpay.platform.RequiresModule("PAYROLL")
@RequestMapping("/api/quinzaine")
public class QuinzaineController {

    public record MonthInput(@NotNull YearMonth month) {}

    public record NoteInput(@Size(max = 300) String note) {}

    public record RefusalInput(boolean refused, @Size(max = 300) String reason) {}

    public record ExclusionInput(@NotNull YearMonth month, @NotNull Long userId, @NotBlank @Size(max = 300) String reason) {}

    public record RateInput(BigDecimal percent) {}

    public record Overview(Quinzaines.Settings settings, List<Quinzaines.Category> categories, List<Quinzaines.Run> runs) {}

    private final Quinzaines quinzaines;
    private final QuinzaineReceipt receipt;
    private final cd.hrpay.job.BackgroundJobs jobs;

    public QuinzaineController(Quinzaines quinzaines, QuinzaineReceipt receipt, cd.hrpay.job.BackgroundJobs jobs) {
        this.jobs = jobs;
        this.quinzaines = quinzaines;
        this.receipt = receipt;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve') or hasAuthority('payroll.pay')")
    public Overview overview() {
        return new Overview(quinzaines.settings(), quinzaines.categories(), quinzaines.runs());
    }

    @GetMapping("/pay-date")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve')")
    public java.time.LocalDate payDate(@RequestParam YearMonth month) {
        return quinzaines.payDate(month);
    }

    @PutMapping("/categories/{id}")
    @PreAuthorize("hasAuthority('masterdata.manage')")
    public List<Quinzaines.Category> setRate(@PathVariable long id, @RequestBody RateInput b) {
        return quinzaines.setCategoryPercent(id, b.percent());
    }

    @GetMapping("/employees")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve')")
    public List<Quinzaines.Employee> employees() {
        return quinzaines.employees();
    }

    @GetMapping("/exclusions")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve')")
    public List<Quinzaines.Exclusion> exclusions(@RequestParam YearMonth month) {
        return quinzaines.exclusions(month);
    }

    @PostMapping("/exclusions")
    @PreAuthorize("hasAuthority('payroll.run')")
    public List<Quinzaines.Exclusion> exclude(@Valid @RequestBody ExclusionInput b) {
        return quinzaines.exclude(b.month(), b.userId(), b.reason());
    }

    @DeleteMapping("/exclusions")
    @PreAuthorize("hasAuthority('payroll.run')")
    public List<Quinzaines.Exclusion> include(@RequestParam YearMonth month, @RequestParam long userId) {
        return quinzaines.include(month, userId);
    }

    @GetMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    public Quinzaines.Mine mine() {
        return quinzaines.mine(CurrentUser.id());
    }

    /** The employee asks not to receive the quinzaine (from the next one not yet approved), or withdraws the request. */
    @PutMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    public Quinzaines.Mine setMine(@Valid @RequestBody RefusalInput b) {
        quinzaines.setRefused(CurrentUser.id(), b.refused(), b.reason());
        return quinzaines.mine(CurrentUser.id());
    }

    @PostMapping("/calculate")
    @PreAuthorize("hasAuthority('payroll.run')")
    public org.springframework.http.ResponseEntity<Object> calculate(@Valid @RequestBody MonthInput b) {
        return jobs.answer(jobs.submit("quinzaine.calculate", "quinzaine:" + b.month(), java.util.Map.of("month", b.month().toString()), null));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('payroll.approve')")
    public Quinzaines.Run approve(@PathVariable long id) {
        return quinzaines.approve(id);
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('payroll.close')")
    public Quinzaines.Run close(@PathVariable long id) {
        return quinzaines.close(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('payroll.approve')")
    public Quinzaines.Run cancel(@PathVariable long id, @Valid @RequestBody(required = false) NoteInput b) {
        return quinzaines.cancel(id, b == null ? null : b.note());
    }

    /** A receipt: payroll staff see every one, an employee their own once the quinzaine is closed. */
    @GetMapping("/receipts/{lineId}.pdf")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> receipt(@PathVariable long lineId) {
        Quinzaines.Line line = quinzaines.line(lineId);
        Quinzaines.Run run = quinzaines.runOfLine(lineId);
        boolean staff = CurrentUser.has("payroll.run") || CurrentUser.has("payroll.approve")
                || CurrentUser.has("payroll.pay");
        if (!staff && !(Objects.equals(line.userId(), CurrentUser.id()) && line.number() != null)) {
            throw new AccessDeniedException("Not your receipt.");
        }
        String name = ("quinzaine-" + (line.number() == null ? "draft-" + lineId : line.number())).replaceAll("[^A-Za-z0-9_-]", "") + ".pdf";
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", ContentDisposition.inline().filename(name).build().toString())
                .body(receipt.render(run, line));
    }
}
