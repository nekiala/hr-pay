package cd.hrpay.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The Republic of the Congo's tax on salaries (ITS), on one month's taxable income in XAF, with the family quotient:
 * annualise, take off the professional deduction, divide by the household's parts, apply the progressive bands to
 * one part, multiply back by the parts and bring back to a month.
 *
 * <p>Parts: 1 for the employee, 1 more for a spouse in charge, 0.5 per child in charge, up to a maximum. The bands,
 * the deduction and the parts are sample values from memory, to verify with a Congolese payroll accountant.
 */
public final class ItsCalculator {

    public record Result(BigDecimal annualNetTaxable, BigDecimal parts, BigDecimal tax) {}

    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal HALF = new BigDecimal("0.5");

    private ItsCalculator() {}

    /** The household's parts from who is in charge. */
    public static BigDecimal parts(boolean spouseInCharge, int childrenInCharge, BigDecimal maxParts) {
        BigDecimal parts = BigDecimal.ONE.add(spouseInCharge ? BigDecimal.ONE : BigDecimal.ZERO)
                .add(HALF.multiply(BigDecimal.valueOf(Math.max(0, childrenInCharge))));
        return maxParts == null ? parts : parts.min(maxParts);
    }

    public static Result monthly(BigDecimal taxableMonthly, BigDecimal parts, List<IprCalculator.Bracket> brackets,
                                 BigDecimal professionalDeductionPercent) {
        if (taxableMonthly.signum() <= 0) {
            return new Result(BigDecimal.ZERO, parts, BigDecimal.ZERO.setScale(2));
        }
        BigDecimal annual = taxableMonthly.multiply(TWELVE);
        BigDecimal net = annual.subtract(annual.multiply(professionalDeductionPercent).divide(HUNDRED, 6, RoundingMode.HALF_UP));
        BigDecimal perPart = net.divide(parts, 6, RoundingMode.HALF_UP);
        BigDecimal taxPerPart = BigDecimal.ZERO;
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
