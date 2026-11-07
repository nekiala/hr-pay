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

    private final SalaryService salaries;
    private final UserService users;

    public SalaryController(SalaryService salaries, UserService users) {
        this.salaries = salaries;
        this.users = users;
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
        return salaries.add(users.find(userId).getId(), body.effectiveFrom(), body.grossMonthly(), body.currency(),
                body.note() == null || body.note().isBlank() ? null : body.note().trim());
    }
}
