package cd.hrpay.career;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Trials and fixed-term contracts coming to their end, and HR's decisions. */
@RestController
public class ContractFollowupController {

    private final ContractFollowup followup;

    public ContractFollowupController(ContractFollowup followup) {
        this.followup = followup;
    }

    public record Note(@Size(max = 300) String note) {}

    public record EndTrial(@NotNull LocalDate lastDay, @Pattern(regexp = "EMPLOYER|EMPLOYEE") String givenBy,
                           @Size(max = 300) String note) {}

    public record Renew(@NotNull LocalDate endDate, @Size(max = 300) String note) {}

    public record Permanent(LocalDate from, @Size(max = 300) String note) {}

    @GetMapping("/api/contract-followup")
    @PreAuthorize("hasAuthority('lifecycle.manage')")
    public ContractFollowup.Overview overview(@RequestParam(defaultValue = "60") int days) {
        return followup.overview(days);
    }

    @GetMapping("/api/users/{userId}/contract-followup")
    @PreAuthorize("hasAuthority('lifecycle.manage')")
    public List<ContractFollowup.Item> of(@PathVariable long userId) {
        return followup.of(userId);
    }

    @PostMapping("/api/users/{userId}/contract-followup/{contractId}/confirm")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('lifecycle.manage')")
    public ContractFollowup.Result confirm(@PathVariable long userId, @PathVariable long contractId, @Valid @RequestBody Note b) {
        return followup.confirm(userId, contractId, b.note());
    }

    @PostMapping("/api/users/{userId}/contract-followup/{contractId}/end-trial")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('lifecycle.manage') and hasAuthority('payroll.run')")
    public ContractFollowup.Result endTrial(@PathVariable long userId, @PathVariable long contractId, @Valid @RequestBody EndTrial b) {
        return followup.endTrial(userId, contractId, b.lastDay(), b.givenBy(), b.note());
    }

    @PostMapping("/api/users/{userId}/contract-followup/{contractId}/renew")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('lifecycle.manage') and hasAuthority('salary.write')")
    public ContractFollowup.Result renew(@PathVariable long userId, @PathVariable long contractId, @Valid @RequestBody Renew b) {
        return followup.renew(userId, contractId, b.endDate(), b.note());
    }

    @PostMapping("/api/users/{userId}/contract-followup/{contractId}/permanent")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('lifecycle.manage') and hasAuthority('salary.write')")
    public ContractFollowup.Result permanent(@PathVariable long userId, @PathVariable long contractId, @Valid @RequestBody Permanent b) {
        return followup.makePermanent(userId, contractId, b.from(), b.note());
    }

    @PostMapping("/api/users/{userId}/contract-followup/{contractId}/let-end")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('lifecycle.manage') and hasAuthority('payroll.run')")
    public ContractFollowup.Result letEnd(@PathVariable long userId, @PathVariable long contractId, @Valid @RequestBody Note b) {
        return followup.letEnd(userId, contractId, b.note());
    }

    @DeleteMapping("/api/users/{userId}/contract-followup/{contractId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('lifecycle.manage')")
    public void undo(@PathVariable long userId, @PathVariable long contractId,
                     @RequestParam @Pattern(regexp = "TRIAL|FIXED_TERM") String kind) {
        followup.undo(userId, kind, contractId);
    }
}
