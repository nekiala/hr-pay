package cd.hrpay.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The Republic of the Congo's tax on salaries (ITS), on one month's taxable income in XAF, with the family quotient:
 * annualise, take off the professional deduction, divide by the household's parts, apply the progressive bands to
 * one part, multiply back by the parts and bring back to a month.
 *
 * <p>Parts (CGI art. 116 B, as rewritten by Loi n° 42-2025 du 31 décembre 2025, finance law for 2026): a single,
 * divorced or widowed person without children in charge has 1 part; a married person 2; a married or widowed person
 * with children 2 and a half part per child; a single or divorced parent 1.5 and a half part per child (2 parts with
 * one child); at most {@code maxParts} (6.5). The net taxable income is rounded down to the thousand francs before it
 * is divided (art. 116 A). Since 2026 the first band is a flat amount per part ({@code firstBandFlat}, 1 200 FCFA a
 * year) rather than a percentage (art. 116 G).
 */
public final class ItsCalculator {

    public record Result(BigDecimal annualNetTaxable, BigDecimal parts, BigDecimal tax) {}

    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    private ItsCalculator() {}

    /** The household's parts from the marital status and the children in charge (CGI art. 116 B). */
    public static BigDecimal parts(String maritalStatus, boolean spouseInCharge, int childrenInCharge, BigDecimal maxParts) {
        int children = Math.max(0, childrenInCharge);
        BigDecimal perChildren = HALF.multiply(BigDecimal.valueOf(children));
        BigDecimal parts;
        if ("MARRIED".equals(maritalStatus) || maritalStatus == null && spouseInCharge
                || "WIDOWED".equals(maritalStatus) && children > 0) {
            parts = BigDecimal.TWO.add(perChildren);
        } else if (children > 0) {
            parts = new BigDecimal("1.5").add(perChildren);
        } else {
            parts = BigDecimal.ONE;
        }
        return maxParts == null ? parts : parts.min(maxParts);
    }

    public static Result monthly(BigDecimal taxableMonthly, BigDecimal parts, List<IprCalculator.Bracket> brackets,
                                 BigDecimal professionalDeductionPercent) {
        return monthly(taxableMonthly, parts, brackets, professionalDeductionPercent, BigDecimal.ZERO);
    }

    public static Result monthly(BigDecimal taxableMonthly, BigDecimal parts, List<IprCalculator.Bracket> brackets,
                                 BigDecimal professionalDeductionPercent, BigDecimal firstBandFlat) {
        if (taxableMonthly.signum() <= 0) {
            return new Result(BigDecimal.ZERO, parts, BigDecimal.ZERO.setScale(2));
        }
        BigDecimal annual = taxableMonthly.multiply(TWELVE);
        BigDecimal net = annual.subtract(annual.multiply(professionalDeductionPercent).divide(HUNDRED, 6, RoundingMode.HALF_UP));
        net = net.divide(THOUSAND, 0, RoundingMode.FLOOR).multiply(THOUSAND);
        BigDecimal perPart = net.divide(parts, 6, RoundingMode.HALF_UP);
        BigDecimal taxPerPart = perPart.signum() > 0 && firstBandFlat != null ? firstBandFlat : BigDecimal.ZERO;
        for (IprCalculator.Bracket b : brackets) {
            if (perPart.compareTo(b.lowerBound()) <= 0) {
                continue;
            }
            BigDecimal top = b.upperBound() == null ? perPart : perPart.min(b.upperBound());
            taxPerPart = taxPerPart.add(top.subtract(b.lowerBound()).multiply(b.ratePercent()).divide(HUNDRED));
        }
        BigDecimal monthly = taxPerPart.multiply(parts).divide(TWELVE, 2, RoundingMode.HALF_UP);
        return new Result(net.setScale(2, RoundingMode.HALF_UP), parts, monthly);
    }
}
