package cd.hrpay.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * IPR on one month's taxable income, in CDF: annualise, apply the progressive bands, bring back to a month,
 * reduce by a percentage per dependant (up to a maximum count), then cap at a share of the taxable income.
 */
public final class IprCalculator {

    public record Bracket(BigDecimal lowerBound, BigDecimal upperBound, BigDecimal ratePercent) {}

    public record Result(BigDecimal beforeReduction, BigDecimal reduction, BigDecimal capped, BigDecimal tax) {}

    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private IprCalculator() {}

    public static Result monthly(BigDecimal taxableMonthly, int dependants, List<Bracket> brackets,
                                 BigDecimal reductionPercent, int maxDependants, BigDecimal capPercent) {
        if (taxableMonthly.signum() <= 0) {
            return new Result(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO.setScale(2));
        }
        BigDecimal annual = taxableMonthly.multiply(TWELVE);
        BigDecimal annualTax = BigDecimal.ZERO;
        for (Bracket b : brackets) {
            if (annual.compareTo(b.lowerBound()) <= 0) {
                continue;
            }
            BigDecimal top = b.upperBound() == null ? annual : annual.min(b.upperBound());
            annualTax = annualTax.add(top.subtract(b.lowerBound()).multiply(b.ratePercent()).divide(HUNDRED));
        }
        BigDecimal tax = annualTax.divide(TWELVE, 6, RoundingMode.HALF_UP);
        int counted = Math.max(0, Math.min(dependants, maxDependants));
        BigDecimal reduction = tax.multiply(reductionPercent).multiply(BigDecimal.valueOf(counted)).divide(HUNDRED, 6, RoundingMode.HALF_UP)
                .min(tax);
        BigDecimal afterReduction = tax.subtract(reduction);
        BigDecimal cap = taxableMonthly.multiply(capPercent).divide(HUNDRED, 6, RoundingMode.HALF_UP);
        BigDecimal capped = afterReduction.compareTo(cap) > 0 ? afterReduction.subtract(cap) : BigDecimal.ZERO;
        BigDecimal result = afterReduction.min(cap).setScale(2, RoundingMode.HALF_UP);
        return new Result(tax.setScale(2, RoundingMode.HALF_UP), reduction.setScale(2, RoundingMode.HALF_UP),
                capped.setScale(2, RoundingMode.HALF_UP), result);
    }
}
