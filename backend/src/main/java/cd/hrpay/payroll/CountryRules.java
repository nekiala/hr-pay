package cd.hrpay.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The payroll rules of a country kept as data (Cameroon, Gabon): its contributions and taxes, each a rate on a base
 * with an optional ceiling and exempt part, or a flat amount by band; and how the income tax is worked out. Amounts
 * are monthly, in the local currency, as in force on the payroll's day.
 *
 * <p>Income tax: the taxable pay less the employee contributions taken before tax, made yearly; less the professional
 * deduction (a percentage, capped when a cap is set) and a yearly allowance; divided by the household's parts when
 * the country uses a family quotient; the bands applied to one part and multiplied back; brought back to a month.
 * Nothing is due when the taxable pay of the month is below the exempt threshold.
 */
public record CountryRules(List<Contribution> contributions, Map<String, List<Band>> scales,
                           BigDecimal professionalPercent, BigDecimal professionalCapYearly, BigDecimal yearlyAllowance,
                           BigDecimal exemptBelowMonthly, BigDecimal maxParts, BigDecimal smigMonthly) {

    public static final List<String> BASES = List.of("CNSS", "GROSS", "TAXABLE", "NET_TAXABLE", "TAX", "BASIC");

    /** One contribution or tax as in force: its rate (percent) or its scale, on a base, with its limits. */
    public record Contribution(String code, String side, String base, BigDecimal rate, BigDecimal ceiling,
                               BigDecimal exempt, String scale, boolean beforeTax, int displayOrder) {

        boolean employee() {
            return "EMPLOYEE".equals(side);
        }
    }

    /** A band of a flat-amount scale: a base above {@code lower} and up to {@code upper} (null: no end) pays {@code amount}. */
    public record Band(BigDecimal lower, BigDecimal upper, BigDecimal amount) {}

    /** A contribution as the company lists it, its values named by statutory rate codes. */
    public record Row(String code, String side, String base, String rateCode, String ceilingCode, String exemptCode,
                      String scaleCode, boolean beforeTax, int displayOrder) {}

    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * The rules in force, from the company's rows: {@code rate} gives a statutory rate's value or null when none is in
     * force, {@code scale} the bands of a scale. A contribution whose rate is not set is left out.
     */
    public static CountryRules of(List<Row> rows, Function<String, BigDecimal> rate, Function<String, List<Band>> scale) {
        List<Contribution> list = new ArrayList<>();
        Map<String, List<Band>> scales = new java.util.HashMap<>();
        for (Row r : rows) {
            BigDecimal value = r.rateCode() == null ? null : rate.apply(r.rateCode());
            if (r.scaleCode() == null && value == null) {
                continue;
            }
            if (r.scaleCode() != null) {
                scales.put(r.scaleCode(), scale.apply(r.scaleCode()));
            }
            list.add(new Contribution(r.code(), r.side(), r.base(), value,
                    r.ceilingCode() == null ? null : rate.apply(r.ceilingCode()),
                    r.exemptCode() == null ? null : rate.apply(r.exemptCode()),
                    r.scaleCode(), r.beforeTax(), r.displayOrder()));
        }
        return new CountryRules(List.copyOf(list), Map.copyOf(scales), orZero(rate.apply("TAX_PROFESSIONAL_DEDUCTION")),
                rate.apply("TAX_PROFESSIONAL_CAP"), orZero(rate.apply("TAX_YEARLY_ALLOWANCE")),
                orZero(rate.apply("TAX_EXEMPT_BELOW")), rate.apply("TAX_MAX_PARTS"), rate.apply("SMIG_MONTHLY"));
    }

    /** Whether the income tax divides the yearly pay by the household's parts. */
    public boolean familyQuotient() {
        return maxParts != null && maxParts.signum() > 0;
    }

    /** The scale's amount for a monthly base in the local currency; zero when no band holds it. */
    public BigDecimal scaleAmount(String code, BigDecimal baseLocal) {
        for (Band b : scales.getOrDefault(code, List.of())) {
            if (baseLocal.compareTo(b.lower()) > 0 && (b.upper() == null || baseLocal.compareTo(b.upper()) <= 0)) {
                return b.amount();
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * The month's income tax, in the local currency.
     *
     * @param taxableLocal the taxable pay less the employee contributions taken before tax
     * @param earningsLocal the taxable pay before them, compared with the exempt threshold
     * @param parts the household's parts (1 without a family quotient)
     */
    public BigDecimal monthlyTax(BigDecimal taxableLocal, BigDecimal earningsLocal, BigDecimal parts,
                                 List<IprCalculator.Bracket> brackets) {
        if (taxableLocal.signum() <= 0 || exemptBelowMonthly.signum() > 0 && earningsLocal.compareTo(exemptBelowMonthly) < 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal yearly = taxableLocal.multiply(TWELVE);
        BigDecimal professional = yearly.multiply(professionalPercent).divide(HUNDRED, 6, RoundingMode.HALF_UP);
        if (professionalCapYearly != null && professionalCapYearly.signum() > 0) {
            professional = professional.min(professionalCapYearly);
        }
        BigDecimal net = yearly.subtract(professional).subtract(yearlyAllowance);
        if (net.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal p = parts == null || parts.signum() <= 0 ? BigDecimal.ONE : parts;
        BigDecimal perPart = net.divide(p, 6, RoundingMode.HALF_UP);
        BigDecimal tax = BigDecimal.ZERO;
        for (IprCalculator.Bracket b : brackets) {
            if (perPart.compareTo(b.lowerBound()) <= 0) {
                continue;
            }
            BigDecimal top = b.upperBound() == null ? perPart : perPart.min(b.upperBound());
            tax = tax.add(top.subtract(b.lowerBound()).multiply(b.ratePercent()).divide(HUNDRED, 6, RoundingMode.HALF_UP));
        }
        return tax.multiply(p).divide(TWELVE, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
