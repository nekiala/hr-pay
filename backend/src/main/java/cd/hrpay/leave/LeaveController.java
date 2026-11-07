package cd.hrpay.leave;

import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequestMapping("/api/leave")
public class LeaveController {

    public record RequestInput(@NotNull Long leaveTypeId, @NotNull LocalDate startDate, @NotNull LocalDate endDate,
                               @Size(max = 500) String reason) {}

    public record DecisionInput(boolean approve, @Size(max = 300) String note) {}

    public record NoteInput(@Size(max = 300) String note) {}

    public record CategoryInput(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,32}", message = "Use up to 32 letters, digits, - or _") String code,
            @NotBlank @Size(max = 150) String name,
            @NotNull @DecimalMin("0") @Digits(integer = 3, fraction = 2) BigDecimal annualLeaveDays,
            boolean active) {}

    public record TypeInput(@NotBlank @Size(max = 150) String name, boolean paid, boolean usesBalance,
                            boolean requiresDocument, @Min(1) @Max(366) Integer maxDays, boolean active) {}

    public record AdjustInput(@NotNull Long leaveTypeId,
                              @NotNull @Digits(integer = 3, fraction = 2) BigDecimal days,
                              @NotBlank @Size(max = 300) String note) {}

    public record Mine(List<LeaveService.Balance> balances, List<LeaveService.LeaveRequest> requests) {}

    private final LeaveService leave;
    private final UserService users;

    public LeaveController(LeaveService leave, UserService users) {
        this.leave = leave;
        this.users = users;
    }

    @GetMapping("/types")
    public List<LeaveService.LeaveType> types() {
        return leave.types();
    }

    @PutMapping("/types/{id}")
    @PreAuthorize("hasAuthority('leave.manage')")
    public LeaveService.LeaveType updateType(@PathVariable long id, @Valid @RequestBody TypeInput b) {
        return leave.updateType(id, b.name().trim(), b.paid(), b.usesBalance(), b.requiresDocument(), b.maxDays(), b.active());
    }

    @GetMapping("/categories")
    public List<LeaveService.GradeCategory> categories() {
        return leave.categories();
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('masterdata.manage')")
    public LeaveService.GradeCategory addCategory(@Valid @RequestBody CategoryInput b) {
        return leave.saveCategory(null, b.code().trim(), b.name().trim(), b.annualLeaveDays(), b.active());
    }

    @PutMapping("/categories/{id}")
    @PreAuthorize("hasAuthority('masterdata.manage')")
    public LeaveService.GradeCategory updateCategory(@PathVariable long id, @Valid @RequestBody CategoryInput b) {
        return leave.saveCategory(id, b.code().trim(), b.name().trim(), b.annualLeaveDays(), b.active());
    }

    @PostMapping("/grant-annual")
    @PreAuthorize("hasAuthority('leave.manage')")
    public Map<String, Integer> grantAnnual(@RequestParam @Min(2000) @Max(2100) int year) {
        return leave.grantAnnual(year);
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAuthority('leave.request')")
    public Mine mine() {
        return new Mine(leave.balances(CurrentUser.id()), leave.forUser(CurrentUser.id()));
    }

    @GetMapping("/working-days")
    @PreAuthorize("hasAuthority('leave.request')")
    public Map<String, BigDecimal> workingDays(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Map.of("days", to.isBefore(from) || to.isAfter(from.plusYears(1)) ? BigDecimal.ZERO
                : leave.workingDays(users.find(CurrentUser.id()), from, to));
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('leave.request')")
    public LeaveService.LeaveRequest request(@Valid @RequestBody RequestInput b) {
        return leave.request(users.find(CurrentUser.id()), b.leaveTypeId(), b.startDate(), b.endDate(), b.reason());
    }

    @GetMapping("/requests/to-decide")
    @PreAuthorize("hasAnyAuthority('leave.manage', 'leave.approve.team')")
    public List<LeaveService.LeaveRequest> toDecide() {
        return leave.toDecide();
    }

    @PostMapping("/requests/{id}/decision")
    @PreAuthorize("hasAnyAuthority('leave.manage', 'leave.approve.team')")
    public LeaveService.LeaveRequest decide(@PathVariable long id, @Valid @RequestBody DecisionInput b) {
        return leave.decide(id, b.approve(), b.note());
    }

    @PostMapping("/requests/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('leave.request', 'leave.manage')")
    public LeaveService.LeaveRequest cancel(@PathVariable long id, @Valid @RequestBody(required = false) NoteInput b) {
        return leave.cancel(id, b == null ? null : b.note());
    }

    @GetMapping("/users/{userId}")
    @PreAuthorize("hasAuthority('leave.manage')")
    public Map<String, Object> forUser(@PathVariable long userId) {
        users.find(userId);
        return Map.of("balances", leave.balances(userId), "requests", leave.forUser(userId),
                "ledger", leave.ledger(userId));
    }

    @PostMapping("/users/{userId}/adjustments")
    @PreAuthorize("hasAuthority('leave.manage')")
    public Map<String, Object> adjust(@PathVariable long userId, @Valid @RequestBody AdjustInput b) {
        leave.adjust(userId, b.leaveTypeId(), b.days(), b.note());
        return forUser(userId);
    }
}
