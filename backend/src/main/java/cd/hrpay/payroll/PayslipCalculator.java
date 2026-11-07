package cd.hrpay.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One employee's payslip for one month, from facts gathered by {@link PayrollRuns}. No database access, so
 * every rule can be tested on its own. Amounts are in the employee's pay currency (the basic salary's); IPR is
 * worked out in CDF and converted back.
 *
 * <p>Order: basic (prorated by paid hours), overtime, allowances and bonuses, CNSS on elements subject to it,
 * IPR on taxable elements less employee CNSS, other deductions, loans within the allowed share of net pay,
 * then employer charges.
 */
public final class PayslipCalculator {

    public record Rates(BigDecimal usdCdf, BigDecimal cnssEmployee, BigDecimal cnssEmployer, BigDecimal inpp,
                        BigDecimal onem, BigDecimal iere, BigDecimal debtMaxShare, List<IprCalculator.Bracket> iprBrackets,
                        BigDecimal iprDependantReduction, int iprMaxDependants, BigDecimal iprCap,
                        BigDecimal smigDailyCdf) {}

    public record LoanDue(long loanId, String kind, BigDecimal installment, BigDecimal balance, String currency) {}

    public record Input(String currency, BigDecimal basicMonthly, int scheduledMinutes, int paidMinutes,
                        int scheduledDays, int daysWorked, BigDecimal overtimeAmount, int overtimeMinutes,
                        BigDecimal thirteenthMonth, String thirteenthMonthNote,
                        int childrenInCharge, int dependantsInCharge, boolean expatriate,
                        List<PayElements.Element> elements, List<PayElements.Assignment> standing,
                        List<PayElements.Entry> entries, List<LoanDue> loans, Rates rates) {}

    public record Line(String code, String label, String kind, BigDecimal base, BigDecimal rate, BigDecimal amount,
                       int order, Long loanId) {}

    public record Result(String currency, List<Line> lines, BigDecimal gross, BigDecimal cnssBase,
                         BigDecimal taxableBase, BigDecimal taxableBaseCdf, BigDecimal employeeDeductions,
                         BigDecimal net, BigDecimal employerCharges, List<String> warnings) {}

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PayslipCalculator() {}

    public static Result calculate(Input in) {
        Rates r = in.rates();
        String cur = in.currency();
        Map<String, PayElements.Element> byCode = in.elements().stream()
                .collect(Collectors.toMap(PayElements.Element::code, Function.identity()));
        List<Line> lines = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        // Taxable and CNSS bases, collected per earning line.
        BigDecimal cnssBase = BigDecimal.ZERO;
        BigDecimal taxable = BigDecimal.ZERO;

        BigDecimal share = in.scheduledMinutes() == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(in.paidMinutes()).divide(BigDecimal.valueOf(in.scheduledMinutes()), 10, RoundingMode.HALF_UP);

        // 1. Basic salary, prorated by paid hours.
        BigDecimal basic = money(in.basicMonthly().multiply(share));
        PayElements.Element basicEl = byCode.get("BASIC");
        BigDecimal hourly = in.scheduledMinutes() == 0 ? BigDecimal.ZERO
                : in.basicMonthly().multiply(BigDecimal.valueOf(60)).divide(BigDecimal.valueOf(in.scheduledMinutes()), 6, RoundingMode.HALF_UP);
        lines.add(new Line("BASIC", basicEl.name(), "EARNING", hours(in.paidMinutes()), hourly, basic, basicEl.displayOrder(), null));

        // 2. Approved overtime, already priced by tier.
        if (in.overtimeAmount().signum() > 0) {
            PayElements.Element ot = byCode.get("OVERTIME");
            lines.add(new Line("OVERTIME", ot.name(), "EARNING", hours(in.overtimeMinutes()), null,
                    money(in.overtimeAmount()), ot.displayOrder(), null));
        }

        // 2b. The 13th month, worked out from the year's evaluations by the caller.
        if (in.thirteenthMonth() != null && in.thirteenthMonth().signum() > 0) {
            PayElements.Element t = byCode.get("THIRTEENTH_MONTH");
            lines.add(new Line("THIRTEENTH_MONTH", t.name() + (in.thirteenthMonthNote() == null ? "" : " (" + in.thirteenthMonthNote() + ")"),
                    "EARNING", null, null, money(in.thirteenthMonth()), t.displayOrder(), null));
        }

        // 3. Standing allowances and deductions from the catalogue.
        for (PayElements.Element e : in.elements()) {
            if (!e.active() || e.method().equals("COMPUTED") || e.method().equals("MANUAL")) {
                continue;
            }
            PayElements.Assignment a = in.standing().stream().filter(x -> x.elementId() == e.id()).findFirst().orElse(null);
            if (a != null && a.excluded() || a == null && !e.defaultForAll()) {
                continue;
            }
            BigDecimal amount = a != null && a.amount() != null ? a.amount() : e.defaultAmount();
            String amountCur = a != null && a.amount() != null ? a.currency() : e.defaultCurrency();
            BigDecimal percent = a != null && a.percent() != null ? a.percent() : e.percent();
            BigDecimal value;
            BigDecimal base = null;
            BigDecimal rate = null;
            switch (e.method()) {
                case "FIXED" -> {
                    value = convert(amount, amountCur, cur, r.usdCdf());
                    if (e.prorated()) {
                        value = value.multiply(share);
                    }
                }
                case "PERCENT_OF_BASIC" -> {
                    if (a != null && a.amount() != null) {   // an employee-specific fixed amount replaces the percentage
                        value = convert(amount, amountCur, cur, r.usdCdf());
                        if (e.prorated()) {
                            value = value.multiply(share);
                        }
                    } else {
                        base = e.prorated() ? basic : in.basicMonthly();
                        rate = percent;
                        value = base.multiply(percent).divide(HUNDRED, 10, RoundingMode.HALF_UP);
                    }
                }
                case "PER_DAY_WORKED" -> {
                    base = BigDecimal.valueOf(in.daysWorked());
                    rate = convert(amount, amountCur, cur, r.usdCdf());
                    value = base.multiply(rate);
                }
                case "PER_CHILD" -> {
                    if (in.childrenInCharge() == 0) {
                        continue;
                    }
                    base = BigDecimal.valueOf(in.childrenInCharge());
                    rate = convert(amount, amountCur, cur, r.usdCdf());
                    value = base.multiply(rate);
                    if (e.prorated()) {
                        value = value.multiply(share);
                    }
                }
                default -> throw new IllegalStateException("Unknown method " + e.method());
            }
            value = money(value);
            if (value.signum() == 0) {
                continue;
            }
            lines.add(new Line(e.code(), e.name(), e.kind(), base, rate, value, e.displayOrder(), null));
        }

        // 4. One-off entries for the month.
        for (PayElements.Entry x : in.entries()) {
            PayElements.Element e = byCode.get(x.elementCode());
            String label = e.name() + (x.note() == null || x.note().isBlank() ? "" : " (" + x.note() + ")");
            lines.add(new Line(e.code(), label, e.kind(), null, null, convert(x.amount(), x.currency(), cur, r.usdCdf()),
                    e.displayOrder(), null));
        }

        // 5. Gross, CNSS base and taxable income.
        BigDecimal gross = BigDecimal.ZERO;
        for (Line l : lines) {
            if (!l.kind().equals("EARNING")) {
                continue;
            }
            PayElements.Element e = byCode.get(l.code());
            gross = gross.add(l.amount());
            if (e.subjectToCnss()) {
                cnssBase = cnssBase.add(l.amount());
            }
            if (e.taxableIpr()) {
                BigDecimal exempt = e.iprExemptCapPercent() == null ? BigDecimal.ZERO
                        : l.amount().min(in.basicMonthly().multiply(e.iprExemptCapPercent()).divide(HUNDRED, 2, RoundingMode.HALF_UP));
                taxable = taxable.add(l.amount()).subtract(exempt);
            }
        }

        BigDecimal cnssEmployee = money(cnssBase.multiply(r.cnssEmployee()).divide(HUNDRED, 10, RoundingMode.HALF_UP));
        lines.add(new Line("CNSS_EMPLOYEE", byCode.get("CNSS_EMPLOYEE").name(), "DEDUCTION", cnssBase, r.cnssEmployee(),
                cnssEmployee, byCode.get("CNSS_EMPLOYEE").displayOrder(), null));

        BigDecimal taxableBase = taxable.subtract(cnssEmployee).max(BigDecimal.ZERO);
        BigDecimal taxableCdf = convert(taxableBase, cur, "CDF", r.usdCdf());
        IprCalculator.Result ipr = IprCalculator.monthly(taxableCdf, in.dependantsInCharge(), r.iprBrackets(),
                r.iprDependantReduction(), r.iprMaxDependants(), r.iprCap());
        BigDecimal iprAmount = convert(ipr.tax(), "CDF", cur, r.usdCdf());
        lines.add(new Line("IPR", byCode.get("IPR").name(), "DEDUCTION", taxableBase, null, iprAmount,
                byCode.get("IPR").displayOrder(), null));

        // 6. Net before loans, then loans within the allowed share.
        BigDecimal deductions = lines.stream().filter(l -> l.kind().equals("DEDUCTION")).map(Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal netBeforeLoans = gross.subtract(deductions);
        BigDecimal room = money(netBeforeLoans.max(BigDecimal.ZERO).multiply(r.debtMaxShare()).divide(HUNDRED, 10, RoundingMode.HALF_UP));
        for (LoanDue loan : in.loans().stream().sorted(Comparator.comparingLong(LoanDue::loanId)).toList()) {
            BigDecimal due = convert(loan.installment().min(loan.balance()), loan.currency(), cur, r.usdCdf());
            BigDecimal take = due.min(room);
            if (take.compareTo(due) < 0) {
                warnings.add((loan.kind().equals("LOAN") ? "Loan" : "Advance") + " #" + loan.loanId()
                        + ": only " + take.toPlainString() + " of " + due.toPlainString() + " " + cur
                        + " taken, to stay within " + r.debtMaxShare().stripTrailingZeros().toPlainString() + "% of net pay.");
            }
            if (take.signum() > 0) {
                PayElements.Element e = byCode.get(loan.kind());
                lines.add(new Line(loan.kind(), e.name() + " #" + loan.loanId(), "DEDUCTION", null, null, take,
                        e.displayOrder(), loan.loanId()));
                room = room.subtract(take);
                deductions = deductions.add(take);
            }
        }
        BigDecimal net = gross.subtract(deductions);
        if (net.signum() < 0) {
            warnings.add("Net pay is negative.");
        }

        // 7. Employer charges.
        BigDecimal employer = BigDecimal.ZERO;
        employer = employer.add(charge(lines, byCode, "CNSS_EMPLOYER", cnssBase, r.cnssEmployer()));
        employer = employer.add(charge(lines, byCode, "INPP", gross, r.inpp()));
        employer = employer.add(charge(lines, byCode, "ONEM", gross, r.onem()));
        if (in.expatriate()) {
            employer = employer.add(charge(lines, byCode, "IERE", gross, r.iere()));
        }

        // 8. Checks.
        if (r.smigDailyCdf() != null && in.scheduledDays() > 0) {
            BigDecimal smigMonth = r.smigDailyCdf().multiply(BigDecimal.valueOf(in.scheduledDays()));
            if (convert(in.basicMonthly(), cur, "CDF", r.usdCdf()).compareTo(smigMonth) < 0) {
                warnings.add("The basic salary is below the minimum wage (SMIG) for " + in.scheduledDays() + " days.");
            }
        }
        if (in.scheduledMinutes() == 0) {
            warnings.add("No scheduled hours this month: check the employee's work schedule.");
        }

        lines.sort(Comparator.comparingInt(Line::order));
        return new Result(cur, List.copyOf(lines), gross, cnssBase, taxableBase, taxableCdf, deductions, net,
                employer, List.copyOf(warnings));
    }

    private static BigDecimal charge(List<Line> lines, Map<String, PayElements.Element> byCode, String code,
                                     BigDecimal base, BigDecimal percent) {
        BigDecimal amount = money(base.multiply(percent).divide(HUNDRED, 10, RoundingMode.HALF_UP));
        PayElements.Element e = byCode.get(code);
        lines.add(new Line(code, e.name(), "EMPLOYER_CHARGE", base, percent, amount, e.displayOrder(), null));
        return amount;
    }

    static BigDecimal convert(BigDecimal amount, String from, String to, BigDecimal usdCdf) {
        return ExchangeRates.convert(amount, from, to, usdCdf);
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal hours(int minutes) {
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }
}
