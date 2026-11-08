package cd.hrpay.payroll;

import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Salary advance on demand: employees ask with the "mine" endpoints, HR decides and sets the rules. */
@RestController
@cd.hrpay.platform.RequiresModule("PAYROLL")
@RequestMapping("/api/advances")
public class SalaryAdvanceController {

    public record AmountInput(@NotNull BigDecimal amount) {}

    public record RequestInput(@NotNull UUID challengeId, @NotBlank @Size(max = 12) String code, @NotNull BigDecimal amount,
                               @NotBlank @Size(max = 200) String signedName) {}

    public record NoteInput(@Size(max = 500) String note) {}

    public record PercentInput(BigDecimal percent) {}

    public record Overview(SalaryAdvances.Settings settings, List<SalaryAdvances.Category> categories,
                           List<SalaryAdvances.Advance> waiting, List<SalaryAdvances.Advance> month) {}

    private final SalaryAdvances advances;
    private final UserService users;

    public SalaryAdvanceController(SalaryAdvances advances, UserService users) {
        this.advances = advances;
        this.users = users;
    }

    // ---- the employee ---------------------------------------------------------------------------------------------

    /** Whether the company offers advances, for the menu. */
    @GetMapping("/offered")
    @PreAuthorize("isAuthenticated()")
    public boolean offered() {
        return advances.settings().enabled();
    }

    @GetMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    public SalaryAdvances.Mine mine() {
        return advances.mine(CurrentUser.id());
    }

    /** Sends the one-time code and returns the receipt text the employee confirms. */
    @PostMapping("/mine/code")
    @PreAuthorize("isAuthenticated()")
    public SalaryAdvances.CodeSent code(@Valid @RequestBody AmountInput b) {
        return advances.sendCode(users.find(CurrentUser.id()), b.amount());
    }

    @PostMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    public SalaryAdvances.Advance request(@Valid @RequestBody RequestInput b, HttpServletRequest http) {
        return advances.request(users.find(CurrentUser.id()), b.challengeId(), b.code().trim(), b.amount(), b.signedName(),
                http.getRemoteAddr());
    }

    @PostMapping("/mine/{id}/cancel")
    @PreAuthorize("isAuthenticated()")
    public SalaryAdvances.Advance cancel(@PathVariable long id) {
        return advances.cancelMine(id, users.find(CurrentUser.id()));
    }

    // ---- HR ----------------------------------------------------------------------------------------------------------

    @GetMapping
    @PreAuthorize("hasAuthority('advance.manage') or hasAuthority('payroll.pay')")
    public Overview overview(@RequestParam(required = false) YearMonth month, @RequestParam(required = false) String status) {
        return new Overview(advances.settings(), advances.categories(), advances.waiting(),
                advances.list(month == null ? YearMonth.now() : month, status));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('advance.manage')")
    public SalaryAdvances.Advance approve(@PathVariable long id) {
        return advances.approve(id);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('advance.manage')")
    public SalaryAdvances.Advance reject(@PathVariable long id, @Valid @RequestBody NoteInput b) {
        return advances.reject(id, b.note());
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('advance.manage')")
    public SalaryAdvances.Settings settings(@Valid @RequestBody SalaryAdvances.Settings b) {
        return advances.saveSettings(b);
    }

    @PutMapping("/categories/{id}")
    @PreAuthorize("hasAuthority('advance.manage')")
    public List<SalaryAdvances.Category> category(@PathVariable long id, @RequestBody PercentInput b) {
        return advances.setCategoryPercent(id, b.percent());
    }
}
