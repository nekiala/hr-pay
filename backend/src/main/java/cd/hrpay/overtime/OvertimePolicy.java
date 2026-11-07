package cd.hrpay.overtime;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "overtime_policy")
public class OvertimePolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String countryCode;
    private LocalDate effectiveFrom;
    private boolean allowed = true;
    private boolean requiresApproval = true;
    private int roundingMinutes = 15;
    private Integer maxWeeklyOvertimeMinutes;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "overtime_rate_tier", joinColumns = @JoinColumn(name = "policy_id"))
    private List<OvertimeRateTier> tiers = new ArrayList<>();

    protected OvertimePolicy() {}

    public OvertimePolicy(String countryCode, LocalDate effectiveFrom) {
        this.countryCode = countryCode;
        this.effectiveFrom = effectiveFrom;
    }

    public Long getId() { return id; }
    public String getCountryCode() { return countryCode; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public boolean isAllowed() { return allowed; }
    public void setAllowed(boolean allowed) { this.allowed = allowed; }
    public boolean isRequiresApproval() { return requiresApproval; }
    public void setRequiresApproval(boolean v) { this.requiresApproval = v; }
    public int getRoundingMinutes() { return roundingMinutes; }
    public void setRoundingMinutes(int roundingMinutes) { this.roundingMinutes = roundingMinutes; }
    public Integer getMaxWeeklyOvertimeMinutes() { return maxWeeklyOvertimeMinutes; }
    public void setMaxWeeklyOvertimeMinutes(Integer v) { this.maxWeeklyOvertimeMinutes = v; }
    public List<OvertimeRateTier> getTiers() { return tiers; }
    public void setTiers(List<OvertimeRateTier> newTiers) {
        tiers.clear();
        tiers.addAll(newTiers);
    }
}
