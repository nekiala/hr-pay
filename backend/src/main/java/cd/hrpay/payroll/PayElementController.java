package cd.hrpay.payroll;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PayElementController {

    public record ElementInput(@NotBlank @Pattern(regexp = MasterDataController.CODE) String code,
                               @NotBlank @Size(max = 150) String name,
                               @NotNull @Pattern(regexp = "EARNING|DEDUCTION") String kind,
                               @NotNull @Pattern(regexp = "FIXED|PERCENT_OF_BASIC|PER_DAY_WORKED|PER_CHILD|MANUAL") String method,
                               @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal defaultAmount,
                               @Pattern(regexp = "USD|CDF") String defaultCurrency,
                               @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 3) BigDecimal percent,
                               boolean subjectToCnss, boolean taxableIpr,
                               @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 3) BigDecimal iprExemptCapPercent,
                               boolean prorated, boolean defaultForAll, @Min(0) @Max(999) int displayOrder,
                               boolean active) {

        PayElements.Element toElement() {
            return new PayElements.Element(0, code.trim(), name.trim(), kind, method, defaultAmount, defaultCurrency,
                    percent, subjectToCnss, taxableIpr, iprExemptCapPercent, prorated, defaultForAll, false,
                    displayOrder, active);
        }
    }

    public record AssignmentInput(@NotNull Long elementId,
                                  @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal amount,
                                  @Pattern(regexp = "USD|CDF") String currency,
                                  @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 3) BigDecimal percent,
                                  boolean excluded, @NotNull YearMonth startMonth, YearMonth endMonth,
                                  @Size(max = 300) String note) {}

    public record EndInput(YearMonth lastMonth) {}

    public record EntryInput(@NotNull Long userId, @NotNull Long elementId, @NotNull YearMonth month,
                             @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
                             @NotNull @Pattern(regexp = "USD|CDF") String currency, @Size(max = 300) String note) {}

    public record EntryUpdate(@NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
                              @NotNull @Pattern(regexp = "USD|CDF") String currency, @Size(max = 300) String note) {}

    private final PayElements pay;

    public PayElementController(PayElements pay) {
        this.pay = pay;
    }

    @GetMapping("/pay-elements")
    @PreAuthorize("hasAuthority('salary.read') or hasAuthority('masterdata.manage')")
    public List<PayElements.Element> elements() {
        return pay.elements();
    }

    @PostMapping("/pay-elements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('masterdata.manage')")
    public PayElements.Element add(@Valid @RequestBody ElementInput b) {
        return pay.save(null, b.toElement());
    }

    @PutMapping("/pay-elements/{id}")
    @PreAuthorize("hasAuthority('masterdata.manage')")
    public PayElements.Element update(@PathVariable long id, @Valid @RequestBody ElementInput b) {
        return pay.save(id, b.toElement());
    }

    @GetMapping("/users/{userId}/pay-elements")
    @PreAuthorize("hasAuthority('salary.read')")
    public List<PayElements.Assignment> assignments(@PathVariable long userId) {
        return pay.assignments(userId);
    }

    @PostMapping("/users/{userId}/pay-elements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('salary.write')")
    public PayElements.Assignment assign(@PathVariable long userId, @Valid @RequestBody AssignmentInput b) {
        return pay.assign(userId, b.elementId(), b.amount(), b.currency(), b.percent(), b.excluded(), b.startMonth(),
                b.endMonth(), b.note());
    }

    @PutMapping("/users/{userId}/pay-elements/{id}/end")
    @PreAuthorize("hasAuthority('salary.write')")
    public List<PayElements.Assignment> end(@PathVariable long userId, @PathVariable long id,
                                            @RequestBody EndInput b) {
        pay.endAssignment(userId, id, b.lastMonth());
        return pay.assignments(userId);
    }

    @GetMapping("/pay-entries")
    @PreAuthorize("hasAuthority('salary.read')")
    public List<PayElements.Entry> entries(@RequestParam YearMonth month) {
        return pay.entries(month);
    }

    @PostMapping("/pay-entries")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('salary.write')")
    public PayElements.Entry addEntry(@Valid @RequestBody EntryInput b) {
        return pay.addEntry(b.userId(), b.elementId(), b.month(), b.amount(), b.currency(), b.note());
    }

    @PutMapping("/pay-entries/{id}")
    @PreAuthorize("hasAuthority('salary.write')")
    public PayElements.Entry updateEntry(@PathVariable long id, @Valid @RequestBody EntryUpdate b) {
        return pay.updateEntry(id, b.amount(), b.currency(), b.note());
    }

    @DeleteMapping("/pay-entries/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('salary.write')")
    public void removeEntry(@PathVariable long id) {
        pay.removeEntry(id);
    }
}
