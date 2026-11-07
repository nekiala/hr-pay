package cd.hrpay.overtime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Prices approved overtime with the policy's tiers. Weekday tiers count overtime hours within the week (Monday
 * to Sunday), so the 7th weekday overtime hour of a week is paid at the second tier. Rest-day and holiday tiers
 * use the same counting within their own kind.
 */
public final class OvertimeValuation {

    public record Approved(LocalDate date, OvertimeRateTier.Kind kind, int minutes) {}

    public record Priced(LocalDate date, OvertimeRateTier.Kind kind, int minutes, BigDecimal weightedMinutes) {}

    private OvertimeValuation() {}

    /** Weighted minutes (minutes x multiplier) for each approved day, in date order. */
    public static List<Priced> price(List<Approved> approved, List<OvertimeRateTier> tiers) {
        List<Approved> sorted = new ArrayList<>(approved);
        sorted.sort(Comparator.comparing(Approved::date));
        Map<String, Integer> usedInWeek = new HashMap<>();
        List<Priced> result = new ArrayList<>();
        for (Approved a : sorted) {
            LocalDate monday = a.date().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            String key = monday + "/" + a.kind();
            int used = usedInWeek.getOrDefault(key, 0);
            BigDecimal weighted = BigDecimal.ZERO;
            for (int m = 0; m < a.minutes(); m++) {
                weighted = weighted.add(multiplier(tiers, a.kind(), (used + m) / 60));
            }
            usedInWeek.put(key, used + a.minutes());
            result.add(new Priced(a.date(), a.kind(), a.minutes(), weighted.setScale(4, RoundingMode.HALF_UP)));
        }
        return result;
    }

    static BigDecimal multiplier(List<OvertimeRateTier> tiers, OvertimeRateTier.Kind kind, int hourIndex) {
        return tiers.stream()
                .filter(t -> t.getKind() == kind && hourIndex >= t.getFromHour()
                        && (t.getToHour() == null || hourIndex < t.getToHour()))
                .map(OvertimeRateTier::getMultiplier)
                .findFirst()
                .orElse(BigDecimal.ONE);
    }
}
