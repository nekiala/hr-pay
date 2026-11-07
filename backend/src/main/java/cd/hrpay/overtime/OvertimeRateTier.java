package cd.hrpay.overtime;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;

@Embeddable
public class OvertimeRateTier {

    public enum Kind { WEEKDAY, REST_DAY, HOLIDAY }

    @Enumerated(EnumType.STRING)
    private Kind kind;
    private int fromHour;
    private Integer toHour;
    private BigDecimal multiplier;

    protected OvertimeRateTier() {}

    public OvertimeRateTier(Kind kind, int fromHour, Integer toHour, BigDecimal multiplier) {
        this.kind = kind;
        this.fromHour = fromHour;
        this.toHour = toHour;
        this.multiplier = multiplier;
    }

    public Kind getKind() { return kind; }
    public int getFromHour() { return fromHour; }
    public Integer getToHour() { return toHour; }
    public BigDecimal getMultiplier() { return multiplier; }
}
