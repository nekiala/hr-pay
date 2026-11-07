package cd.hrpay.overtime;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Overtime rules per country, versioned by effective date. Saving with a new effective date keeps the
 * old version for past periods.
 */
@RestController
@cd.hrpay.platform.RequiresModule("ATTENDANCE")
@RequestMapping("/api/overtime-policies")
public class OvertimePolicyController {

    public record Tier(@NotNull OvertimeRateTier.Kind kind, @Min(0) int fromHour, Integer toHour,
                       @NotNull @DecimalMin("1.00") BigDecimal multiplier) {}

    public record PolicyInput(@NotNull LocalDate effectiveFrom, boolean allowed, boolean requiresApproval,
                              @Min(1) @Max(60) int roundingMinutes, Integer maxWeeklyOvertimeMinutes,
                              @NotEmpty List<@Valid Tier> tiers) {}

    public record PolicyView(Long id, String countryCode, LocalDate effectiveFrom, boolean allowed,
                             boolean requiresApproval, int roundingMinutes, Integer maxWeeklyOvertimeMinutes,
                             List<Tier> tiers) {
        static PolicyView of(OvertimePolicy p) {
            return new PolicyView(p.getId(), p.getCountryCode(), p.getEffectiveFrom(), p.isAllowed(),
                    p.isRequiresApproval(), p.getRoundingMinutes(), p.getMaxWeeklyOvertimeMinutes(),
                    p.getTiers().stream()
                            .sorted(Comparator.comparing(OvertimeRateTier::getKind).thenComparing(OvertimeRateTier::getFromHour))
                            .map(t -> new Tier(t.getKind(), t.getFromHour(), t.getToHour(), t.getMultiplier())).toList());
        }
    }

    private final OvertimePolicyRepository policies;
    private final AuditService audit;

    public OvertimePolicyController(OvertimePolicyRepository policies, AuditService audit) {
        this.policies = policies;
        this.audit = audit;
    }

    @GetMapping
    public List<PolicyView> list(@RequestParam(defaultValue = "CD") String country) {
        return policies.findByCountryCodeOrderByEffectiveFromDesc(country).stream().map(PolicyView::of).toList();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('overtime_policy.manage')")
    @Transactional
    public PolicyView save(@RequestParam(defaultValue = "CD") String country, @Valid @RequestBody PolicyInput body) {
        for (OvertimeRateTier.Kind kind : OvertimeRateTier.Kind.values()) {
            List<Tier> ofKind = body.tiers().stream().filter(t -> t.kind() == kind)
                    .sorted(Comparator.comparingInt(Tier::fromHour)).toList();
            if (ofKind.isEmpty() || ofKind.getFirst().fromHour() != 0) {
                throw new BusinessRuleException("Each overtime kind needs a rate starting at hour 0: " + kind);
            }
            for (int i = 0; i < ofKind.size(); i++) {
                Integer to = ofKind.get(i).toHour();
                boolean last = i == ofKind.size() - 1;
                if (last ? to != null : (to == null || to != ofKind.get(i + 1).fromHour())) {
                    throw new BusinessRuleException("Rates for " + kind + " must follow each other without gaps, the last one open-ended.");
                }
            }
        }
        OvertimePolicy policy = policies.findByCountryCodeAndEffectiveFrom(country, body.effectiveFrom())
                .orElseGet(() -> new OvertimePolicy(country, body.effectiveFrom()));
        policy.setAllowed(body.allowed());
        policy.setRequiresApproval(body.requiresApproval());
        policy.setRoundingMinutes(body.roundingMinutes());
        policy.setMaxWeeklyOvertimeMinutes(body.maxWeeklyOvertimeMinutes());
        policy.setTiers(body.tiers().stream()
                .map(t -> new OvertimeRateTier(t.kind(), t.fromHour(), t.toHour(), t.multiplier())).toList());
        policies.save(policy);
        audit.record("overtime_policy.saved", "overtime_policy", policy.getId(),
                Map.of("country", country, "effectiveFrom", body.effectiveFrom().toString(), "allowed", body.allowed()));
        return PolicyView.of(policy);
    }
}
