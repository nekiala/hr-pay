package cd.hrpay.salary;

import cd.hrpay.user.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@cd.hrpay.platform.RequiresModule("PAYROLL")
@RequestMapping("/api/users/{userId}/salaries")
public class SalaryController {

    public record SalaryInput(@NotNull LocalDate effectiveFrom,
                              @NotNull @DecimalMin(value = "0.01") @Digits(integer = 12, fraction = 2) BigDecimal grossMonthly,
                              @NotNull @Pattern(regexp = "USD|CDF|XAF", message = "Use USD, CDF or XAF") String currency,
                              @Size(max = 300) String note) {}

    /** An agreed net for a normal month, from a date; HR Pay works out the complement above the grade's basic. */
    public record NetInput(@NotNull LocalDate effectiveFrom,
                           @NotNull @DecimalMin(value = "0.01") @Digits(integer = 12, fraction = 2) BigDecimal net,
                           @Size(max = 300) String note) {}

    private final SalaryService salaries;
    private final UserService users;
    private final cd.hrpay.payroll.EmployeeFile files;
    private final cd.hrpay.payroll.NegotiatedPay negotiated;

    public SalaryController(SalaryService salaries, UserService users, cd.hrpay.payroll.EmployeeFile files,
                            cd.hrpay.payroll.NegotiatedPay negotiated) {
        this.salaries = salaries;
        this.users = users;
        this.files = files;
        this.negotiated = negotiated;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('salary.read')")
    public List<SalaryService.Salary> history(@PathVariable Long userId) {
        return salaries.history(users.find(userId).getId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('salary.write')")
    public SalaryService.Salary add(@PathVariable Long userId, @Valid @RequestBody SalaryInput body) {
        long id = users.find(userId).getId();
        String note = body.note() == null || body.note().isBlank() ? null : body.note().trim();
        var contract = files.contractOn(id, body.effectiveFrom()).orElse(null);
        if (contract == null) {     // no grade yet: the amount is the basic as given
            return salaries.add(id, body.effectiveFrom(), body.grossMonthly(), body.currency(), note);
        }
        requireNoSalaryOn(id, body.effectiveFrom());
        // Under a contract the basic is the grade's: an agreed salary below it is refused, above it the rest is the complement.
        var plan = negotiated.fromNegotiated(id, body.effectiveFrom(), body.grossMonthly(), null);
        if (!plan.currency().equals(body.currency())) {
            throw new cd.hrpay.common.BusinessRuleException(cd.hrpay.i18n.Texts.t(
                    "Grade %s is paid in %s: give the agreed salary in %s.", plan.gradeCode(), plan.currency(), plan.currency()));
        }
        negotiated.apply(plan, note);
        return salaries.history(id).stream().filter(s -> s.effectiveFrom().equals(body.effectiveFrom())).findFirst().orElseThrow();
    }

    @PostMapping("/net/preview")
    @PreAuthorize("hasAuthority('salary.write')")
    public cd.hrpay.payroll.NegotiatedPay.Plan previewNet(@PathVariable Long userId, @Valid @RequestBody NetInput body) {
        return negotiated.fromNet(users.find(userId).getId(), body.effectiveFrom(), body.net(), null);
    }

    @PostMapping("/net")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('salary.write')")
    public cd.hrpay.payroll.NegotiatedPay.Plan addNet(@PathVariable Long userId, @Valid @RequestBody NetInput body) {
        long id = users.find(userId).getId();
        requireNoSalaryOn(id, body.effectiveFrom());
        return negotiated.apply(negotiated.fromNet(id, body.effectiveFrom(), body.net(), null), body.note());
    }

    private void requireNoSalaryOn(long userId, LocalDate date) {
        if (salaries.history(userId).stream().anyMatch(s -> s.effectiveFrom().equals(date))) {
            throw new cd.hrpay.common.ConflictException("A salary already starts on " + date + " for this employee.");
        }
    }
}
