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

    /** How the warning about a basic pay below the legal minimum starts; PayrollChecks blocks approval on it. */
    static final String BELOW_MINIMUM = "The basic salary";


    /**
     * The month's rates. {@code usdCdf} is the price of one US dollar in the local currency ({@code local}: CDF in
     * the DRC, XAF in the Republic of the Congo); {@code country} picks the rules (CD or CG); {@code more} holds the
     * rates only one country has, by their code (for CG: CNSS_CEILING, CNSS_FAMILY, CNSS_ACCIDENT,
     * CNSS_FAMILY_CEILING, TUS, ITS_PROFESSIONAL_DEDUCTION, ITS_MAX_PARTS, SMIG_MONTHLY); {@code rules} the
     * contributions and taxes of a country whose rules are data (then the CNSS and IPR fields are not used).
     */
    public record Rates(BigDecimal usdCdf, BigDecimal cnssEmployee, BigDecimal cnssEmployer, BigDecimal inpp,
                        BigDecimal onem, BigDecimal iere, BigDecimal debtMaxShare, List<IprCalculator.Bracket> iprBrackets,
                        BigDecimal iprDependantReduction, int iprMaxDependants, BigDecimal iprCap,
                        BigDecimal smigDailyCdf, String country, String local, Map<String, BigDecimal> more,
                        CountryRules rules) {

        /** The rates of a country whose rules are in code (CD or CG). */
        public Rates(BigDecimal usdCdf, BigDecimal cnssEmployee, BigDecimal cnssEmployer, BigDecimal inpp,
                     BigDecimal onem, BigDecimal iere, BigDecimal debtMaxShare, List<IprCalculator.Bracket> iprBrackets,
                     BigDecimal iprDependantReduction, int iprMaxDependants, BigDecimal iprCap,
                     BigDecimal smigDailyCdf, String country, String local, Map<String, BigDecimal> more) {
            this(usdCdf, cnssEmployee, cnssEmployer, inpp, onem, iere, debtMaxShare, iprBrackets, iprDependantReduction,
                    iprMaxDependants, iprCap, smigDailyCdf, country, local, more, null);
        }

        /** A country whose rules are data ({@link CountryRules}): Cameroon, Gabon. */
        public static Rates of(BigDecimal usdLocal, String country, String local, BigDecimal debtMaxShare,
                               List<IprCalculator.Bracket> brackets, CountryRules rules) {
            return new Rates(usdLocal, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    debtMaxShare, brackets, BigDecimal.ZERO, 0, BigDecimal.valueOf(100), null, country, local, Map.of(), rules);
        }

        /** The DRC's rates, in CDF. */
        public Rates(BigDecimal usdCdf, BigDecimal cnssEmployee, BigDecimal cnssEmployer, BigDecimal inpp,
                     BigDecimal onem, BigDecimal iere, BigDecimal debtMaxShare, List<IprCalculator.Bracket> iprBrackets,
                     BigDecimal iprDependantReduction, int iprMaxDependants, BigDecimal iprCap, BigDecimal smigDailyCdf) {
            this(usdCdf, cnssEmployee, cnssEmployer, inpp, onem, iere, debtMaxShare, iprBrackets, iprDependantReduction,
                    iprMaxDependants, iprCap, smigDailyCdf, "CD", "CDF", Map.of());
        }

        boolean congo() {
            return "CG".equals(country);
        }

        BigDecimal more(String code) {
            BigDecimal v = more.get(code);
            if (v == null) {
                throw new IllegalStateException("The rate " + code + " is missing");
            }
            return v;
        }
    }

    public record LoanDue(long loanId, String kind, BigDecimal installment, BigDecimal balance, String currency) {}

    public record Input(String currency, BigDecimal basicMonthly, int scheduledMinutes, int paidMinutes,
                        int scheduledDays, int daysWorked, BigDecimal overtimeAmount, int overtimeMinutes,
                        BigDecimal thirteenthMonth, String thirteenthMonthNote,
                        int childrenInCharge, int dependantsInCharge, boolean expatriate,
                        List<PayElements.Element> elements, List<PayElements.Assignment> standing,
                        List<PayElements.Entry> entries, List<LoanDue> loans, Rates rates, int allocatedLeaveMinutes,
                        BigDecimal smigCoefficient, String maritalStatus, BigDecimal seniorityPercent) {

        /** Without a seniority bonus. */
        public Input(String currency, BigDecimal basicMonthly, int scheduledMinutes, int paidMinutes,
                     int scheduledDays, int daysWorked, BigDecimal overtimeAmount, int overtimeMinutes,
                     BigDecimal thirteenthMonth, String thirteenthMonthNote,
                     int childrenInCharge, int dependantsInCharge, boolean expatriate,
                     List<PayElements.Element> elements, List<PayElements.Assignment> standing,
                     List<PayElements.Entry> entries, List<LoanDue> loans, Rates rates, int allocatedLeaveMinutes,
                     BigDecimal smigCoefficient, String maritalStatus) {
            this(currency, basicMonthly, scheduledMinutes, paidMinutes, scheduledDays, daysWorked, overtimeAmount,
                    overtimeMinutes, thirteenthMonth, thirteenthMonthNote, childrenInCharge, dependantsInCharge, expatriate,
                    elements, standing, entries, loans, rates, allocatedLeaveMinutes, smigCoefficient, maritalStatus, null);
        }

        /**
         * Without the grade's minimum-wage coefficient (100 = the manœuvre ordinaire) or the marital status: the
         * lowest minimum wage is checked and the Congo's family parts come from the spouse in charge.
         */
        public Input(String currency, BigDecimal basicMonthly, int scheduledMinutes, int paidMinutes,
                     int scheduledDays, int daysWorked, BigDecimal overtimeAmount, int overtimeMinutes,
                     BigDecimal thirteenthMonth, String thirteenthMonthNote,
                     int childrenInCharge, int dependantsInCharge, boolean expatriate,
                     List<PayElements.Element> elements, List<PayElements.Assignment> standing,
                     List<PayElements.Entry> entries, List<LoanDue> loans, Rates rates, int allocatedLeaveMinutes) {
            this(currency, basicMonthly, scheduledMinutes, paidMinutes, scheduledDays, daysWorked, overtimeAmount,
                    overtimeMinutes, thirteenthMonth, thirteenthMonthNote, childrenInCharge, dependantsInCharge, expatriate,
                    elements, standing, entries, loans, rates, allocatedLeaveMinutes, null, null);
        }

        public Input(String currency, BigDecimal basicMonthly, int scheduledMinutes, int paidMinutes,
                     int scheduledDays, int daysWorked, BigDecimal overtimeAmount, int overtimeMinutes,
                     BigDecimal thirteenthMonth, String thirteenthMonthNote,
                     int childrenInCharge, int dependantsInCharge, boolean expatriate,
                     List<PayElements.Element> elements, List<PayElements.Assignment> standing,
                     List<PayElements.Entry> entries, List<LoanDue> loans, Rates rates) {
            this(currency, basicMonthly, scheduledMinutes, paidMinutes, scheduledDays, daysWorked, overtimeAmount,
                    overtimeMinutes, thirteenthMonth, thirteenthMonthNote, childrenInCharge, dependantsInCharge, expatriate,
                    elements, standing, entries, loans, rates, 0, null, null);
        }
    }

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

        BigDecimal shareAll = in.scheduledMinutes() == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(in.paidMinutes()).divide(BigDecimal.valueOf(in.scheduledMinutes()), 10, RoundingMode.HALF_UP);

        // Annual leave days paid by an allocation de congé: the allocation pays the remuneration (Labour Code art. 7:
        // the elements in the CNSS base). The allowances outside it (housing) keep running for those days (art. 138, 142).
        BigDecimal keptShare = in.scheduledMinutes() == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(Math.min(in.scheduledMinutes(), in.paidMinutes() + in.allocatedLeaveMinutes()))
                        .divide(BigDecimal.valueOf(in.scheduledMinutes()), 10, RoundingMode.HALF_UP);

        // 1. Basic salary, prorated by paid hours.
        BigDecimal basic = money(in.basicMonthly().multiply(shareAll));
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
            boolean keptOnLeave = !e.subjectToCnss() && in.allocatedLeaveMinutes() > 0;
            BigDecimal share = keptOnLeave ? keptShare : shareAll;
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
                        base = !e.prorated() ? in.basicMonthly() : keptOnLeave ? money(in.basicMonthly().multiply(share)) : basic;
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

        // 3b. The seniority bonus of a collective agreement: a share of the basic salary earned this month.
        PayElements.Element seniority = byCode.get("SENIORITY_BONUS");
        if (in.seniorityPercent() != null && in.seniorityPercent().signum() > 0 && seniority != null && seniority.active()) {
            BigDecimal base = seniority.prorated() ? basic : in.basicMonthly();
            BigDecimal value = money(base.multiply(in.seniorityPercent()).divide(HUNDRED, 10, RoundingMode.HALF_UP));
            if (value.signum() > 0) {
                lines.add(new Line("SENIORITY_BONUS", seniority.name(), "EARNING", base, in.seniorityPercent(), value,
                        seniority.displayOrder(), null));
            }
        }

        // 4. One-off entries for the month.
        for (PayElements.Entry x : in.entries()) {
            PayElements.Element e = byCode.get(x.elementCode());
            String label = e.name() + (x.note() == null || x.note().isBlank() ? "" : " (" + x.note() + ")");
            BigDecimal amount = convert(x.amount(), x.currency(), cur, r.usdCdf());
            // A holiday change that takes back pay from an earlier month lowers the earnings (and their CNSS and IPR bases).
            lines.add(new Line(e.code(), label, e.kind(), null, null,
                    e.code().equals("HOLIDAY_ADJ_LESS") ? amount.negate() : amount, e.displayOrder(), null));
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

        String local = r.local();
        BigDecimal taxableBase;
        BigDecimal taxableCdf;
        BigDecimal pensionBase = cnssBase;
        BigDecimal iprAmount = BigDecimal.ZERO;
        if (r.rules() != null) {
            // A country whose rules are data: employee contributions, the income tax, then what is worked out on it.
            Map<String, BigDecimal> bases = new java.util.HashMap<>(Map.of("CNSS", cnssBase, "GROSS", gross,
                    "TAXABLE", taxable, "BASIC", basic));
            BigDecimal beforeTax = BigDecimal.ZERO;
            for (CountryRules.Contribution c : r.rules().contributions()) {
                if (c.employee() && !c.base().equals("TAX") && !c.base().equals("NET_TAXABLE")) {
                    BigDecimal amount = contribution(lines, byCode, c, bases.get(c.base()), r, cur);
                    if (c.beforeTax()) {
                        beforeTax = beforeTax.add(amount);
                    }
                }
            }
            taxableBase = taxable.subtract(beforeTax).max(BigDecimal.ZERO);
            taxableCdf = convert(taxableBase, cur, local, r.usdCdf());
            BigDecimal parts = BigDecimal.ONE;
            if (r.rules().familyQuotient()) {
                boolean spouse = in.dependantsInCharge() > in.childrenInCharge();
                parts = ItsCalculator.parts(in.maritalStatus(), spouse, in.childrenInCharge(), r.rules().maxParts());
            }
            BigDecimal taxLocal = r.rules().monthlyTax(taxableCdf, convert(taxable, cur, local, r.usdCdf()), parts, r.iprBrackets());
            iprAmount = convert(taxLocal, local, cur, r.usdCdf());
            lines.add(new Line("IPR", name(byCode, "IPR"), "DEDUCTION", taxableBase, null, iprAmount, order(byCode, "IPR"), null));
            bases.put("TAX", iprAmount);
            bases.put("NET_TAXABLE", taxableBase);
            for (CountryRules.Contribution c : r.rules().contributions()) {
                if (c.employee() && (c.base().equals("TAX") || c.base().equals("NET_TAXABLE"))) {
                    contribution(lines, byCode, c, bases.get(c.base()), r, cur);
                }
            }
        } else {
            // In the Republic of the Congo the pension contributions stop at a monthly ceiling.
            pensionBase = r.congo() ? cnssBase.min(convert(r.more("CNSS_CEILING"), local, cur, r.usdCdf())) : cnssBase;
            BigDecimal cnssEmployee = money(pensionBase.multiply(r.cnssEmployee()).divide(HUNDRED, 10, RoundingMode.HALF_UP));
            lines.add(new Line("CNSS_EMPLOYEE", byCode.get("CNSS_EMPLOYEE").name(), "DEDUCTION", pensionBase, r.cnssEmployee(),
                    cnssEmployee, byCode.get("CNSS_EMPLOYEE").displayOrder(), null));

            taxableBase = taxable.subtract(cnssEmployee).max(BigDecimal.ZERO);
            taxableCdf = convert(taxableBase, cur, local, r.usdCdf());
            BigDecimal taxLocal;
            if (r.congo()) {
                boolean spouse = in.dependantsInCharge() > in.childrenInCharge();
                BigDecimal parts = ItsCalculator.parts(in.maritalStatus(), spouse, in.childrenInCharge(), r.more("ITS_MAX_PARTS"));
                taxLocal = ItsCalculator.monthly(taxableCdf, parts, r.iprBrackets(), r.more("ITS_PROFESSIONAL_DEDUCTION"),
                        r.more().getOrDefault("ITS_FIRST_BAND_FLAT", BigDecimal.ZERO)).tax();
                // CGI art. 116 G 2 (Loi 42-2025): a gross below the SMIG owes a minimum yearly tax.
                BigDecimal minimumYear = r.more().getOrDefault("ITS_MINIMUM_YEARLY", BigDecimal.ZERO);
                BigDecimal smig = r.more().get("SMIG_MONTHLY");
                BigDecimal grossLocal = convert(gross, cur, local, r.usdCdf());
                if (minimumYear.signum() > 0 && smig != null && grossLocal.signum() > 0 && grossLocal.compareTo(smig) < 0) {
                    taxLocal = taxLocal.max(minimumYear.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP));
                }
            } else {
                taxLocal = IprCalculator.monthly(taxableCdf, in.dependantsInCharge(), r.iprBrackets(),
                        r.iprDependantReduction(), r.iprMaxDependants(), r.iprCap()).tax();
                // DGI: the IPR due after family charges is never below a monthly minimum.
                BigDecimal minimum = r.more().getOrDefault("IPR_MIN_MONTHLY", BigDecimal.ZERO);
                if (taxLocal.signum() > 0 && taxLocal.compareTo(minimum) < 0) {
                    taxLocal = minimum.setScale(2, RoundingMode.HALF_UP);
                }
            }
            iprAmount = convert(taxLocal, local, cur, r.usdCdf());
            lines.add(new Line("IPR", byCode.get("IPR").name(), "DEDUCTION", taxableBase, null, iprAmount,
                    byCode.get("IPR").displayOrder(), null));
            if (r.congo()) {
                congoLevies(lines, byCode, r, in, cur, taxableBase);
            }
        }

        // 6. Net before loans, then loans within the allowed share.
        BigDecimal deductions = lines.stream().filter(l -> l.kind().equals("DEDUCTION")).map(Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal netBeforeLoans = gross.subtract(deductions);
        // The quinzaine is an advance on this month's pay: taken back before loans. What the net cannot cover is
        // carried to the next month as an advance when the month closes.
        if (netBeforeLoans.signum() < 0) {
            for (int i = 0; i < lines.size() && netBeforeLoans.signum() < 0; i++) {
                Line l = lines.get(i);
                if (!TAKEN_BACK_FIRST.contains(l.code())) {
                    continue;
                }
                BigDecimal keep = l.amount().add(netBeforeLoans).max(BigDecimal.ZERO);
                BigDecimal left = l.amount().subtract(keep);
                lines.set(i, new Line(l.code(), l.label(), l.kind(), l.base(), l.rate(), keep, l.order(), l.loanId()));
                deductions = deductions.subtract(left);
                netBeforeLoans = netBeforeLoans.add(left);
                warnings.add((l.code().equals("QUINZAINE") ? "Quinzaine" : "Salary advance") + ": only " + keep.toPlainString()
                        + " of " + l.amount().toPlainString() + " " + cur
                        + " could be taken back; the rest is carried to next month as an advance when the month closes.");
            }
        }
        // DRC Code du travail art. 114: pay may be assigned or seized for debts up to 1/5 of the part not above five times
        // the monthly SMIG of the worker's category and 1/3 of the rest, after tax and social deductions. Elsewhere, the
        // company's DEBT_MAX_SHARE of the net, which also applies in the DRC when it is stricter.
        BigDecimal debtCeiling = null;
        BigDecimal room = money(netBeforeLoans.max(BigDecimal.ZERO).multiply(r.debtMaxShare()).divide(HUNDRED, 10, RoundingMode.HALF_UP));
        if (!r.congo() && r.smigDailyCdf() != null) {
            BigDecimal coefficient = in.smigCoefficient() == null ? HUNDRED : in.smigCoefficient();
            BigDecimal ceiling = convert(minimumMonthly(r.smigDailyCdf(), coefficient), r.local(), cur, r.usdCdf())
                    .multiply(BigDecimal.valueOf(5));
            BigDecimal legal = assignable(netBeforeLoans.max(BigDecimal.ZERO), ceiling);
            if (legal.compareTo(room) < 0) {
                room = legal;
                debtCeiling = ceiling;
            }
        }
        for (LoanDue loan : in.loans().stream().sorted(Comparator.comparingLong(LoanDue::loanId)).toList()) {
            BigDecimal due = convert(loan.installment().min(loan.balance()), loan.currency(), cur, r.usdCdf());
            BigDecimal take = due.min(room);
            if (take.compareTo(due) < 0) {
                warnings.add((loan.kind().equals("LOAN") ? "Loan" : "Advance") + " #" + loan.loanId()
                        + ": only " + take.toPlainString() + " of " + due.toPlainString() + " " + cur
                        + (debtCeiling != null
                                ? " taken, to stay within the share of pay that may be withheld for debts (Code du travail art. 114: 1/5 up to "
                                        + plain(debtCeiling) + " " + cur + ", 1/3 above)."
                                : " taken, to stay within " + r.debtMaxShare().stripTrailingZeros().toPlainString() + "% of net pay."));
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
        if (r.rules() != null) {
            Map<String, BigDecimal> bases = Map.of("CNSS", cnssBase, "GROSS", gross, "TAXABLE", taxable, "BASIC", basic,
                    "TAX", iprAmount, "NET_TAXABLE", taxableBase);
            for (CountryRules.Contribution c : r.rules().contributions()) {
                if (!c.employee()) {
                    employer = employer.add(contribution(lines, byCode, c, bases.get(c.base()), r, cur));
                }
            }
        } else if (r.congo()) {
            // Pensions up to their ceiling; family benefits and work accidents up to theirs; the tax on salaries (TUS) on the gross.
            BigDecimal familyBase = cnssBase.min(convert(r.more("CNSS_FAMILY_CEILING"), local, cur, r.usdCdf()));
            employer = employer.add(charge(lines, byCode, "CNSS_EMPLOYER", pensionBase, r.cnssEmployer()));
            employer = employer.add(charge(lines, byCode, "CNSS_EMPLOYER_FAMILY", familyBase, r.more("CNSS_FAMILY")));
            employer = employer.add(charge(lines, byCode, "CNSS_EMPLOYER_ACCIDENT", familyBase, r.more("CNSS_ACCIDENT")));
            employer = employer.add(charge(lines, byCode, "TUS", gross, r.more("TUS")));
        } else {
            employer = employer.add(charge(lines, byCode, "CNSS_EMPLOYER", cnssBase, r.cnssEmployer()));
            employer = employer.add(charge(lines, byCode, "INPP", gross, r.inpp()));
            employer = employer.add(charge(lines, byCode, "ONEM", gross, r.onem()));
            if (in.expatriate()) {
                employer = employer.add(charge(lines, byCode, "IERE", gross, r.iere()));
            }
        }

        // 8. Checks.
        if (r.rules() != null || r.congo()) {
            BigDecimal smig = r.rules() != null ? r.rules().smigMonthly() : r.more().get("SMIG_MONTHLY");
            if (smig != null && convert(in.basicMonthly(), cur, local, r.usdCdf()).compareTo(smig) < 0) {
                warnings.add(BELOW_MINIMUM + " is below the monthly minimum wage (SMIG).");
            }
        } else if (r.smigDailyCdf() != null) {
            // Décret 25/22 art. 4 and 7: the grade's minimum is the SMIG times its coefficient, 26 days a month.
            BigDecimal coefficient = in.smigCoefficient() == null ? HUNDRED : in.smigCoefficient();
            BigDecimal minimum = minimumMonthly(r.smigDailyCdf(), coefficient);
            BigDecimal basicLocal = convert(in.basicMonthly(), cur, local, r.usdCdf());
            if (basicLocal.compareTo(minimum) < 0) {
                warnings.add(String.format(java.util.Locale.UK,
                        BELOW_MINIMUM + " (%s %s) is below the legal minimum of the grade (SMIG %s x coefficient %s x 26 days = %s %s).",
                        plain(basicLocal), local, plain(r.smigDailyCdf()), plain(coefficient), plain(minimum), local));
            }
            // Décret 25/22 art. 5: family allowances are at least 1/27 of the daily SMIG per child and day.
            BigDecimal perChildMinimum = familyAllowanceMinimum(r.smigDailyCdf());
            for (Line l : lines) {
                if (!l.code().equals("FAMILY_ALLOWANCE") || l.base() == null || l.base().signum() <= 0) {
                    continue;
                }
                BigDecimal perChild = convert(l.amount(), cur, local, r.usdCdf()).divide(l.base(), 2, RoundingMode.HALF_UP);
                if (perChild.compareTo(perChildMinimum) < 0) {
                    warnings.add(String.format(java.util.Locale.UK,
                            "Family allowances (%s %s a child) are below the legal minimum (1/27 of the SMIG a day x 26 days = %s %s a child).",
                            plain(perChild), local, plain(perChildMinimum), local));
                }
            }
        }
        if (in.scheduledMinutes() == 0) {
            warnings.add("No scheduled hours this month: check the employee's work schedule.");
        }

        lines.sort(Comparator.comparingInt(Line::order));
        return new Result(cur, List.copyOf(lines), gross, cnssBase, taxableBase, taxableCdf, deductions, net,
                employer, List.copyOf(warnings));
    }

    /** Deductions the payslip takes in full before any loan: advances on this month's own pay. */
    static final java.util.Set<String> TAKEN_BACK_FIRST = java.util.Set.of("QUINZAINE", "EARNED_ADVANCE");

    /** Art. 114: 1/5 of the net up to the ceiling (five times the category's monthly SMIG), 1/3 of the part above. */
    public static BigDecimal assignable(BigDecimal net, BigDecimal ceiling) {
        BigDecimal low = net.min(ceiling);
        BigDecimal high = net.subtract(ceiling).max(BigDecimal.ZERO);
        return low.divide(BigDecimal.valueOf(5), 2, RoundingMode.DOWN).add(high.divide(BigDecimal.valueOf(3), 2, RoundingMode.DOWN));
    }

    /** Décret 25/22 art. 7: monthly values are 26 times the daily ones. */
    static final BigDecimal SMIG_DAYS_PER_MONTH = BigDecimal.valueOf(26);

    /** A grade's monthly minimum: the daily SMIG times its coefficient (100 = manœuvre ordinaire), 26 days. */
    public static BigDecimal minimumMonthly(BigDecimal smigDaily, BigDecimal coefficient) {
        return smigDaily.multiply(coefficient).multiply(SMIG_DAYS_PER_MONTH).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /** The legal minimum family allowance for one child and one month: 1/27 of the daily SMIG, 26 days. */
    public static BigDecimal familyAllowanceMinimum(BigDecimal smigDaily) {
        return smigDaily.multiply(SMIG_DAYS_PER_MONTH).divide(BigDecimal.valueOf(27), 2, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /**
     * One contribution or tax of a country whose rules are data, added to the payslip: the rate on the base within its
     * ceiling and less its exempt part, or the scale's flat amount. Limits and scales are in the local currency.
     */
    private static BigDecimal contribution(List<Line> lines, Map<String, PayElements.Element> byCode,
                                           CountryRules.Contribution c, BigDecimal base, Rates r, String cur) {
        String kind = c.employee() ? "DEDUCTION" : "EMPLOYER_CHARGE";
        BigDecimal amount;
        BigDecimal shownBase = base.max(BigDecimal.ZERO);
        BigDecimal rate = null;
        if (c.scale() != null) {
            BigDecimal local = convert(shownBase, cur, r.local(), r.usdCdf());
            amount = money(convert(r.rules().scaleAmount(c.scale(), local), r.local(), cur, r.usdCdf()));
            if (amount.signum() == 0) {
                return amount;
            }
        } else {
            if (c.ceiling() != null && c.ceiling().signum() > 0) {
                shownBase = shownBase.min(convert(c.ceiling(), r.local(), cur, r.usdCdf()));
            }
            if (c.exempt() != null && c.exempt().signum() > 0) {
                shownBase = shownBase.subtract(convert(c.exempt(), r.local(), cur, r.usdCdf())).max(BigDecimal.ZERO);
                if (shownBase.signum() == 0) {
                    return BigDecimal.ZERO;
                }
            }
            rate = c.rate();
            amount = money(shownBase.multiply(rate).divide(HUNDRED, 10, RoundingMode.HALF_UP));
        }
        lines.add(new Line(c.code(), name(byCode, c.code()), kind, shownBase, rate, amount,
                byCode.containsKey(c.code()) ? order(byCode, c.code()) : c.displayOrder(), null));
        return amount;
    }

    /**
     * The Republic of the Congo's other employee levies, each only when its rate is set: the CAMU solidarity
     * contribution on the taxable pay (after the CNSS) above a monthly threshold, and the TOL (taxe d'occupation des
     * locaux), a flat amount once a month on the month-end payslip.
     */
    private static void congoLevies(List<Line> lines, Map<String, PayElements.Element> byCode, Rates r, Input in,
                                    String cur, BigDecimal taxableBase) {
        String local = r.local();
        BigDecimal camu = r.more().getOrDefault("CAMU_SOLIDARITY", BigDecimal.ZERO);
        if (camu.signum() > 0) {
            BigDecimal threshold = convert(r.more().getOrDefault("CAMU_SOLIDARITY_THRESHOLD", BigDecimal.ZERO), local, cur, r.usdCdf());
            BigDecimal base = taxableBase.subtract(threshold).max(BigDecimal.ZERO);
            BigDecimal amount = money(base.multiply(camu).divide(HUNDRED, 10, RoundingMode.HALF_UP));
            if (amount.signum() > 0) {
                lines.add(new Line("CAMU_SOLIDARITY", name(byCode, "CAMU_SOLIDARITY"), "DEDUCTION", money(base), camu, amount,
                        order(byCode, "CAMU_SOLIDARITY"), null));
            }
        }
        BigDecimal tol = r.more().getOrDefault("TOL_MONTHLY", BigDecimal.ZERO);
        if (tol.signum() > 0 && in.basicMonthly() != null && in.basicMonthly().signum() > 0) {
            lines.add(new Line("TOL", name(byCode, "TOL"), "DEDUCTION", null, null, money(convert(tol, local, cur, r.usdCdf())),
                    order(byCode, "TOL"), null));
        }
    }

    private static String name(Map<String, PayElements.Element> byCode, String code) {
        PayElements.Element e = byCode.get(code);
        return e == null ? code : e.name();
    }

    private static int order(Map<String, PayElements.Element> byCode, String code) {
        PayElements.Element e = byCode.get(code);
        return e == null ? 200 : e.displayOrder();
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
