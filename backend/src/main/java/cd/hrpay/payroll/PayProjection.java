package cd.hrpay.payroll;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * A year's pay projected month by month for one person, or added up for a unit or the whole company: the months of a
 * closed payroll as paid, the others worked out by the payroll engine with what is already known (salary changes
 * recorded with a future date, the anniversary raises still to come, approved and requested annual leave with its
 * allocation de congé, the 13th month, loan installments, the end of a fixed-term contract, children reaching the age
 * limit, rates entered for later dates). Nothing is saved.
 */
@Service
public class PayProjection {

    /** One month of the projection: PAID (closed payroll), PROJECTED, NO_CONTRACT, LEFT or NOT_PAID. */
    public record Month(String month, String status, String currency, BigDecimal basic, BigDecimal gross, BigDecimal cnss,
                        BigDecimal tax, BigDecimal employeeDeductions, BigDecimal net, BigDecimal employerCharges,
                        BigDecimal cost, BigDecimal leaveEarned, List<String> events) {}

    public record Total(String currency, BigDecimal gross, BigDecimal cnss, BigDecimal tax, BigDecimal employeeDeductions,
                        BigDecimal net, BigDecimal employerCharges, BigDecimal cost) {}

    /** The leave a person will still have on 31 December, and what it is worth (paid only when the contract ends). */
    public record LeaveAtYearEnd(BigDecimal balanceNow, BigDecimal earnedToCome, BigDecimal requested,
                                 BigDecimal balanceYearEnd, BigDecimal dailyRate, BigDecimal value, String currency) {}

    public record Person(long userId, String name, String registrationNo, int year, String variable, BigDecimal usdRate,
                         LocalDate rateDate, List<Month> months, List<Total> totals, LeaveAtYearEnd leave,
                         int raises, List<String> notes) {}

    /** A unit's or the company's months: the headcount paid and the amounts, by currency. */
    public record GroupMonth(String month, String currency, int people, BigDecimal gross, BigDecimal cnss, BigDecimal tax,
                             BigDecimal net, BigDecimal employerCharges, BigDecimal cost) {}

    public record PersonTotal(long userId, String name, String registrationNo, String currency, BigDecimal gross,
                              BigDecimal net, BigDecimal cost, int raises, BigDecimal leaveValue) {}

    public record Group(int year, Long unitId, String unitName, String variable, BigDecimal usdRate, int people,
                        List<GroupMonth> months, List<Total> totals, List<PersonTotal> employees, List<String> skipped) {}

    static final List<String> TAXES = List.of("IPR", "ITS");

    private final JdbcClient jdbc;
    private final PayrollRuns runs;
    private final PaySimulator simulator;
    private final AnniversaryRaises raises;
    private final LeaveAllowances allowances;
    private final cd.hrpay.leave.LeaveService leave;
    private final Departures departures;
    private final Thirteenths thirteenths;
    private final cd.hrpay.evaluation.ThirteenthMonth thirteenthMonth;
    private final Quinzaines quinzaines;
    private final EmployeeFile files;
    private final Loans loans;
    private final PayElements pay;
    private final StatutoryRates statutory;
    private final cd.hrpay.org.OrgStructure org;
    private final UserRepository users;
    private final AuditService audit;
    private final Clock clock;

    public PayProjection(JdbcClient jdbc, PayrollRuns runs, PaySimulator simulator, AnniversaryRaises raises,
                         LeaveAllowances allowances, cd.hrpay.leave.LeaveService leave, Departures departures,
                         Thirteenths thirteenths, cd.hrpay.evaluation.ThirteenthMonth thirteenthMonth, Quinzaines quinzaines,
                         EmployeeFile files, Loans loans, PayElements pay, StatutoryRates statutory,
                         cd.hrpay.org.OrgStructure org, UserRepository users, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.simulator = simulator;
        this.raises = raises;
        this.allowances = allowances;
        this.leave = leave;
        this.departures = departures;
        this.thirteenths = thirteenths;
        this.thirteenthMonth = thirteenthMonth;
        this.quinzaines = quinzaines;
        this.files = files;
        this.loans = loans;
        this.pay = pay;
        this.statutory = statutory;
        this.org = org;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    /** One person's year. {@code variable} AVERAGE adds the last 12 months' average of bonuses and overtime to the months to come. */
    public Person person(long userId, int year, String variable, BigDecimal usdRate) {
        PaySimulator.notSelf(userId);
        Person p = simulator.dryRun(() -> project(userId, year, variable, usdRate));
        audit.recordIndependently("payroll.projected", "user", userId, Map.of("year", year));
        return p;
    }

    /** Everyone under contract in the year, in a unit's branch or the whole company, added up by month. */
    public Group group(int year, Long unitId, String variable, BigDecimal usdRate) {
        Group g = simulator.dryRun(() -> projectGroup(year, unitId, variable, usdRate));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("year", year);
        details.put("people", g.people());
        if (unitId != null) {
            details.put("unitId", unitId);
        }
        audit.recordIndependently("payroll.projected", "company", null, details);
        return g;
    }

    // ---- one person --------------------------------------------------------------------------------------------

    Person project(long userId, int year, String variable, BigDecimal usdRate) {
        if (year < 2000 || year > 2100) {
            throw new BusinessRuleException("Give a year between 2000 and 2100.");
        }
        AppUser user = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found."));
        boolean average = !"NONE".equals(variable);
        LocalDate today = LocalDate.now(clock);
        YearMonth current = YearMonth.from(today);
        ExchangeRates.Rate usd = simulator.rate(current, usdRate);
        List<String> notes = new ArrayList<>();
        if (usdRate != null) {
            notes.add(Texts.t("Exchange rate given for the projection: 1 USD = %s.", usdRate.toPlainString()));
        } else {
            notes.add(Texts.t("Months to come use today's exchange rate (1 USD = %s, %s).", usd.cdfPerUnit().toPlainString(),
                    Texts.day(usd.rateDate())));
        }
        notes.add(Texts.t("Rates and the minimum wage: those in force each month, including changes already entered for later dates."));

        List<AnniversaryRaises.Planned> planned = raises.upcoming(userId, LocalDate.of(year, 12, 31));
        Map<String, BigDecimal> variableAverage = average ? averageVariable(userId) : Map.of();
        if (average) {
            notes.add(variableAverage.isEmpty()
                    ? Texts.t("No bonus or overtime on the closed payslips of the last 12 months: none projected.")
                    : Texts.t("Months to come include the average of bonuses and overtime of the last 12 closed payslips."));
        } else {
            notes.add(Texts.t("No bonus or overtime projected for the months to come."));
        }
        var t13 = thirteenthMonth.settings();
        boolean quinzaine = quinzaines.settings().enabled();
        Departures.Departure confirmed = departures.find(userId).filter(d -> "CONFIRMED".equals(d.status())).orElse(null);
        List<Leave> leaves = leave.annualPaid() ? plannedLeave(userId, year) : List.of();
        Map<Long, LeaveAllowances.Worked> worked = new HashMap<>();
        Map<Long, BigDecimal> loanLeft = new HashMap<>();

        List<Month> months = new ArrayList<>();
        Integer childrenBefore = null;
        BigDecimal earnedToCome = BigDecimal.ZERO;
        BigDecimal lastAgreed = null;
        String lastCurrency = null;
        boolean ended = false;
        for (int m = 1; m <= 12; m++) {
            YearMonth ym = YearMonth.of(year, m);
            List<String> events = new ArrayList<>();
            String status = runs.run(ym).map(PayrollRuns.Run::status).orElse(null);
            if ("CLOSED".equals(status)) {
                months.add(paidMonth(userId, ym));
                continue;
            }
            if (ended || confirmed != null && confirmed.lastDay().isBefore(ym.atDay(1))) {
                months.add(empty(ym, "LEFT", events));
                continue;
            }
            EmployeeFile.Contract contract = files.contracts(userId).stream()
                    .filter(c -> !c.startDate().isAfter(ym.atEndOfMonth()) && (c.endDate() == null || !c.endDate().isBefore(ym.atDay(1))))
                    .max(java.util.Comparator.comparing(EmployeeFile.Contract::startDate)).orElse(null);
            if (contract == null) {
                months.add(empty(ym, "NO_CONTRACT", events));
                continue;
            }
            try {
                var salary = runs.salaries().on(userId, ym.atEndOfMonth()).orElse(null);
                if (salary == null) {
                    events.add(Texts.t("No basic salary recorded."));
                    months.add(empty(ym, "NO_CONTRACT", events));
                    continue;
                }
                BigDecimal basic = salary.grossMonthly();
                for (AnniversaryRaises.Planned r : planned) {
                    if (!r.effectiveFrom().isAfter(ym.atEndOfMonth()) && r.currency().equals(salary.currency())) {
                        basic = basic.add(r.raiseAmount());
                        if (YearMonth.from(r.effectiveFrom()).equals(ym)) {
                            events.add(Texts.t("Anniversary raise (%s years, %s): +%s, basic %s %s.", r.years(),
                                    Texts.day(r.anniversaryDate()), r.raiseAmount().toPlainString(), basic.toPlainString(),
                                    salary.currency()));
                        }
                    }
                }
                if (salary.effectiveFrom().getYear() == year && YearMonth.from(salary.effectiveFrom()).equals(ym)
                        && salary.effectiveFrom().isAfter(today)) {
                    events.add(Texts.t("Salary change recorded from %s.", Texts.day(salary.effectiveFrom())));
                }

                // The end of the contract: a confirmed departure is on file; a fixed-term contract that ends with no
                // contract after it gets its final settlement.
                LocalDate lastDay = null;
                List<PayElements.Entry> extra = new ArrayList<>();
                if (confirmed != null && YearMonth.from(confirmed.lastDay()).equals(ym)) {
                    events.add(Texts.t("Leaves on %s: final settlement on this payslip.", Texts.day(confirmed.lastDay())));
                } else if (contract.endDate() != null && YearMonth.from(contract.endDate()).equals(ym)
                        && files.contracts(userId).stream().noneMatch(c -> c.startDate().isAfter(contract.endDate()))) {
                    lastDay = contract.endDate();
                    events.add(Texts.t("The fixed-term contract ends on %s and no renewal is recorded: final settlement on this payslip.",
                            Texts.day(lastDay)));
                    try {
                        Departures.Departure d = departures.propose(userId, new Departures.Input(lastDay, "END_OF_CONTRACT",
                                null, null, null, null, null, null, null, null));
                        extra.addAll(simulator.settlementEntries(userId, ym, d));
                    } catch (BusinessRuleException e) {
                        events.add(Texts.translate(e.getMessage()));
                    }
                }

                // The 13th month, in its pay month, when no run is approved for it yet.
                Thirteenths.Due thirteenth = null;
                boolean thirteenthSet = false;
                if (t13.enabled() && t13.payMonth() == m && lastDay == null && !thirteenths.dueIn(ym).containsKey(userId)) {
                    var row = thirteenthMonth.forEmployee(userId, user.fullName(), year, t13).orElse(null);
                    if (row != null) {
                        thirteenth = new Thirteenths.Due(row.amount(), row.currency(), Texts.t("%s, projection", String.valueOf(year)));
                        thirteenthSet = true;
                        events.add(Texts.t("13th month: %s %s (%s%%%s).", row.amount().toPlainString(), row.currency(),
                                row.scorePercent().stripTrailingZeros().toPlainString(),
                                "EVALUATIONS".equals(row.scoreSource()) ? Texts.t(", evaluations so far") : Texts.t(", fallback rate")));
                    }
                }

                // Annual leave with no allocation approved yet: its days leave the monthly payslip, the allocation comes in.
                List<LocalDate[]> ranges = new ArrayList<>();
                for (Leave l : leaves) {
                    if (l.endDate().isBefore(ym.atDay(1)) || l.startDate().isAfter(ym.atEndOfMonth())) {
                        continue;
                    }
                    LeaveAllowances.Worked w;
                    try {
                        w = worked.computeIfAbsent(l.id(), id -> allowances.simulate(id));
                    } catch (BusinessRuleException e) {
                        events.add(Texts.t("Leave %s to %s: %s", Texts.day(l.startDate()), Texts.day(l.endDate()),
                                Texts.translate(e.getMessage())));
                        continue;
                    }
                    ranges.add(new LocalDate[] {l.startDate(), l.endDate()});
                    for (LeaveAllowances.Part part : w.parts()) {
                        if (YearMonth.from(part.month()).equals(ym) && part.grossAmount().signum() > 0) {
                            extra.add(entry(userId, ym, "LEAVE_ALLOWANCE", part.grossAmount(), w.currency(),
                                    Texts.t("allocation de congé (projection)")));
                        }
                    }
                    if (!l.startDate().isBefore(ym.atDay(1))) {
                        events.add(Texts.t("Annual leave %s to %s (%s days%s): allocation de congé %s %s, paid by %s.",
                                Texts.day(l.startDate()), Texts.day(l.endDate()), l.workingDays().stripTrailingZeros().toPlainString(),
                                "APPROVED".equals(l.status()) ? "" : Texts.t(", requested"), w.gross().toPlainString(),
                                w.currency(), Texts.day(w.payBy())));
                    }
                }

                // The average of bonuses and overtime, for the months after this one.
                if (ym.isAfter(YearMonth.from(today))) {
                    BigDecimal avg = variableAverage.get(salary.currency());
                    if (avg != null && avg.signum() > 0) {
                        extra.add(entry(userId, ym, "OTHER_BONUS", avg, salary.currency(),
                                Texts.t("average of bonuses and overtime (projection)")));
                    }
                }

                // Loans: what the months before have taken back.
                List<PayslipCalculator.LoanDue> due = new ArrayList<>();
                for (PayslipCalculator.LoanDue l : loans.dueFor(userId, ym)) {
                    BigDecimal left = loanLeft.getOrDefault(l.loanId(), l.balance());
                    if (left.signum() > 0) {
                        due.add(new PayslipCalculator.LoanDue(l.loanId(), l.kind(), l.installment().min(left), left, l.currency()));
                    }
                }

                ExchangeRates.Rate rate = simulator.rate(ym, usdRate);
                PayslipCalculator.Rates rates = runs.rates(ym.atEndOfMonth(), rate.cdfPerUnit());
                PayrollRuns.MonthContext ctx = runs.context(ym, rates, rate.cdfPerUnit());
                PayrollRuns.Scenario sc = new PayrollRuns.Scenario(basic, null, lastDay, 0, null, null, null, thirteenthSet,
                        thirteenth, due, ranges);
                PayrollRuns.Computed c = runs.compute(ctx, userId, true, extra, sc);
                if (c.skipped() != null) {
                    events.add(c.skipped());
                    months.add(empty(ym, "NOT_PAID", events));
                    continue;
                }
                PayslipCalculator.Result r = c.result();
                for (PayslipCalculator.Line l : r.lines()) {
                    if (l.loanId() != null) {
                        PayslipCalculator.LoanDue d = due.stream().filter(x -> x.loanId() == l.loanId()).findFirst().orElse(null);
                        if (d != null) {
                            BigDecimal taken = ExchangeRates.convert(l.amount(), r.currency(), d.currency(), rate.cdfPerUnit());
                            BigDecimal left = d.balance().subtract(taken).max(BigDecimal.ZERO);
                            loanLeft.put(d.loanId(), left);
                            if (left.signum() == 0) {
                                events.add(Texts.t("Loan #%s fully repaid.", d.loanId()));
                            }
                        }
                    }
                }
                Object kids = c.snapshot().get("childrenInCharge");
                if (kids instanceof Number n) {
                    if (childrenBefore != null && n.intValue() != childrenBefore) {
                        events.add(Texts.t("Children counted for allowances and tax: %s (was %s).", n.intValue(), childrenBefore));
                    }
                    childrenBefore = n.intValue();
                }
                if (quinzaine && lastDay == null) {
                    events.add(Texts.t("Quinzaine advance paid mid-month and taken back on this payslip."));
                }
                BigDecimal earned = c.snapshot().get("leaveEarned") instanceof Number n ? new BigDecimal(n.toString()) : null;
                if (earned != null && runs.run(ym).map(x -> !"CLOSED".equals(x.status())).orElse(true)) {
                    earnedToCome = earnedToCome.add(earned);
                }
                months.add(new Month(ym.toString(), "PROJECTED", r.currency(), money(basic), r.gross(), line(r, "CNSS_EMPLOYEE"),
                        tax(r), r.employeeDeductions(), r.net(), r.employerCharges(), r.gross().add(r.employerCharges()), earned,
                        events));
                lastAgreed = basic.add(complement(userId, ym, salary.currency()));
                lastCurrency = salary.currency();
                if (lastDay != null) {
                    ended = true;
                }
            } catch (BusinessRuleException e) {
                events.add(Texts.translate(e.getMessage()));
                months.add(empty(ym, "NOT_PAID", events));
            }
        }

        LeaveAtYearEnd leaveEnd = null;
        if (leave.annualPaid()) {
            BigDecimal now = leave.annualBalance(userId);
            BigDecimal requested = leaves.stream().filter(l -> !"APPROVED".equals(l.status())).map(Leave::workingDays)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal end = now.add(earnedToCome).subtract(requested).max(BigDecimal.ZERO);
            BigDecimal daily = null;
            BigDecimal value = null;
            if (lastAgreed != null && !ended) {
                BigDecimal perMonth = statutory.rate("SETTLEMENT_DAYS_PER_MONTH", LocalDate.of(year, 12, 31));
                daily = lastAgreed.divide(perMonth, 4, RoundingMode.HALF_UP);
                value = money(daily.multiply(end));
                daily = money(daily);
            }
            leaveEnd = new LeaveAtYearEnd(now, earnedToCome, requested, end, daily, value, lastCurrency);
            notes.add(Texts.t("Leave not taken by 31 December is not paid (Labour Code art. 144): it is shown as a liability, paid only when the contract ends."));
        }
        return new Person(userId, user.fullName(), user.getRegistrationNo(), year, average ? "AVERAGE" : "NONE",
                usd.cdfPerUnit(), usd.rateDate(), months, totals(months), leaveEnd,
                (int) planned.stream().filter(r -> r.effectiveFrom().getYear() == year).count(), notes);
    }

    private record Leave(long id, LocalDate startDate, LocalDate endDate, BigDecimal workingDays, String status) {}

    /** Annual leave in the year, approved or still asked for, with no allocation approved or paid yet. */
    private List<Leave> plannedLeave(long userId, int year) {
        return jdbc.sql("""
                select r.id, r.start_date, r.end_date, r.working_days, r.status
                from leave_request r join leave_type t on t.id = r.leave_type_id
                where r.user_id = ? and t.code = 'ANNUAL' and r.status in ('APPROVED', 'PENDING', 'AWAITING_HR')
                  and r.end_date >= ? and r.start_date <= ?
                  and not exists (select 1 from leave_allowance a where a.leave_request_id = r.id and a.status in ('APPROVED', 'PAID'))
                order by r.start_date""").params(userId, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
                .query(Leave.class).list();
    }

    private PayElements.Entry entry(long userId, YearMonth month, String code, BigDecimal amount, String currency, String note) {
        PayElements.Element e = pay.elements().stream().filter(x -> x.code().equals(code)).findFirst()
                .orElseThrow(() -> new BusinessRuleException("Pay element " + code + " is missing."));
        return new PayElements.Entry(0, userId, null, e.id(), e.code(), e.name(), e.kind(), month.atDay(1), amount, currency,
                note, null, null);
    }

    private BigDecimal complement(long userId, YearMonth ym, String currency) {
        return pay.standingFor(userId, ym).stream()
                .filter(a -> a.elementCode().equals(NegotiatedPay.COMPLEMENT) && a.amount() != null && currency.equals(a.currency()))
                .map(PayElements.Assignment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Average per month, by currency, of overtime and one-off earnings on the closed payslips of the last 12 months. */
    private Map<String, BigDecimal> averageVariable(long userId) {
        YearMonth now = YearMonth.now(clock);
        record V(String currency, BigDecimal amount, int months) {}
        List<V> rows = jdbc.sql("""
                select trim(p.currency) as currency, coalesce(sum(l.amount) filter (where e.id is not null), 0) as amount,
                       count(distinct r.month) as months
                from payslip p join payroll_run r on r.id = p.run_id
                left join payslip_line l on l.payslip_id = p.id
                left join pay_element e on e.code = l.element_code and e.kind = 'EARNING' and (e.code = 'OVERTIME' or e.method = 'MANUAL')
                     and e.code not in ('LEAVE_ALLOWANCE', 'NOTICE_PAY', 'LEAVE_PAYOUT', 'SEVERANCE', 'END_OF_CAREER', 'THIRTEENTH_MONTH')
                where p.user_id = ? and r.status = 'CLOSED' and p.kind = 'REGULAR' and r.month between ? and ?
                group by p.currency""").params(userId, now.minusMonths(12).atDay(1), now.minusMonths(1).atDay(1))
                .query(V.class).list();
        Map<String, BigDecimal> out = new HashMap<>();
        for (V v : rows) {
            if (v.months() > 0 && v.amount().signum() > 0) {
                out.put(v.currency(), money(v.amount().divide(BigDecimal.valueOf(v.months()), 6, RoundingMode.HALF_UP)));
            }
        }
        return out;
    }

    /** A month of a closed payroll, as the regular payslip paid it. */
    private Month paidMonth(long userId, YearMonth ym) {
        record P(long id, String currency, BigDecimal gross, BigDecimal employeeDeductions, BigDecimal net,
                 BigDecimal employerCharges, BigDecimal basic) {}
        P p = jdbc.sql("""
                select p.id, trim(p.currency) as currency, p.gross, p.employee_deductions, p.net, p.employer_charges,
                       cast(p.snapshot ->> 'basicMonthly' as numeric) as basic
                from payslip p join payroll_run r on r.id = p.run_id
                where p.user_id = ? and r.month = ? and p.kind = 'REGULAR'""").params(userId, ym.atDay(1)).query(P.class)
                .optional().orElse(null);
        if (p == null) {
            return empty(ym, "NOT_PAID", List.of(Texts.t("Not on the closed payroll of this month.")));
        }
        Map<String, BigDecimal> by = new HashMap<>();
        jdbc.sql("select element_code, sum(amount) as amount from payslip_line where payslip_id = ? group by element_code")
                .param(p.id()).query((rs, i) -> by.put(rs.getString(1), rs.getBigDecimal(2))).list();
        BigDecimal tax = TAXES.stream().map(c -> by.getOrDefault(c, BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Month(ym.toString(), "PAID", p.currency(), p.basic(), p.gross(), by.getOrDefault("CNSS_EMPLOYEE", BigDecimal.ZERO),
                tax, p.employeeDeductions(), p.net(), p.employerCharges(), p.gross().add(p.employerCharges()), null, List.of());
    }

    private static Month empty(YearMonth ym, String status, List<String> events) {
        return new Month(ym.toString(), status, null, null, null, null, null, null, null, null, null, null, events);
    }

    private static BigDecimal line(PayslipCalculator.Result r, String code) {
        return r.lines().stream().filter(l -> l.code().equals(code)).map(PayslipCalculator.Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal tax(PayslipCalculator.Result r) {
        return r.lines().stream().filter(l -> TAXES.contains(l.code())).map(PayslipCalculator.Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static List<Total> totals(List<Month> months) {
        Map<String, BigDecimal[]> by = new TreeMap<>();
        for (Month m : months) {
            if (m.currency() == null || m.gross() == null) {
                continue;
            }
            BigDecimal[] t = by.computeIfAbsent(m.currency(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            t[0] = t[0].add(m.gross());
            t[1] = t[1].add(m.cnss());
            t[2] = t[2].add(m.tax());
            t[3] = t[3].add(m.employeeDeductions());
            t[4] = t[4].add(m.net());
            t[5] = t[5].add(m.employerCharges());
            t[6] = t[6].add(m.cost());
        }
        return by.entrySet().stream().map(e -> new Total(e.getKey(), money(e.getValue()[0]), money(e.getValue()[1]),
                money(e.getValue()[2]), money(e.getValue()[3]), money(e.getValue()[4]), money(e.getValue()[5]),
                money(e.getValue()[6]))).toList();
    }

    // ---- a unit or the company ---------------------------------------------------------------------------------

    Group projectGroup(int year, Long unitId, String variable, BigDecimal usdRate) {
        LocalDate first = LocalDate.of(year, 1, 1);
        LocalDate last = LocalDate.of(year, 12, 31);
        List<Long> people = jdbc.sql("""
                select distinct c.user_id from employee_contract c join app_user u on u.id = c.user_id
                where c.start_date <= ? and (c.end_date is null or c.end_date >= ?)
                order by c.user_id""").params(last, first).query(Long.class).list();
        String unitName = null;
        if (unitId != null) {
            unitName = org.unit(unitId).name();
            java.util.Set<Long> branch = org.branch(unitId);
            LocalDate on = LocalDate.now(clock).getYear() == year ? LocalDate.now(clock) : last;
            Map<Long, Long> placed = org.unitsOn(on);
            people = people.stream().filter(id -> placed.get(id) != null && branch.contains(placed.get(id))).toList();
        }
        Long self = cd.hrpay.security.CurrentUser.id();
        Map<String, BigDecimal[]> sums = new TreeMap<>();
        Map<String, Integer> counts = new HashMap<>();
        List<PersonTotal> employees = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<Month> all = new ArrayList<>();
        int done = 0;
        for (long id : people) {
            done++;
            cd.hrpay.job.Progress.report(done, people.size(), Texts.t("Employee %s of %s", done, people.size()));
            if (self != null && self == id) {
                skipped.add(Texts.t("Your own pay is left out."));
                continue;
            }
            Person p;
            try {
                p = project(id, year, variable, usdRate);
            } catch (RuntimeException e) {
                skipped.add(id + ": " + Texts.translate(e.getMessage() == null ? e.toString() : e.getMessage()));
                continue;
            }
            for (Month m : p.months()) {
                if (m.currency() == null || m.gross() == null) {
                    continue;
                }
                all.add(m);
                String key = m.month() + "|" + m.currency();
                BigDecimal[] t = sums.computeIfAbsent(key, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                t[0] = t[0].add(m.gross());
                t[1] = t[1].add(m.cnss());
                t[2] = t[2].add(m.tax());
                t[3] = t[3].add(m.net());
                t[4] = t[4].add(m.employerCharges());
                t[5] = t[5].add(m.cost());
                counts.merge(key, 1, Integer::sum);
            }
            int raisesInYear = p.raises();
            for (Total t : p.totals()) {
                employees.add(new PersonTotal(id, p.name(), p.registrationNo(), t.currency(), t.gross(), t.net(), t.cost(),
                        raisesInYear, p.leave() == null || p.leave().value() == null || !t.currency().equals(p.leave().currency())
                                ? null : p.leave().value()));
            }
        }
        List<GroupMonth> months = sums.entrySet().stream().map(e -> {
            String[] k = e.getKey().split("\\|");
            BigDecimal[] t = e.getValue();
            return new GroupMonth(k[0], k[1], counts.get(e.getKey()), money(t[0]), money(t[1]), money(t[2]), money(t[3]),
                    money(t[4]), money(t[5]));
        }).toList();
        employees.sort(java.util.Comparator.comparing(PersonTotal::name, java.util.Comparator.nullsLast(String::compareTo)));
        return new Group(year, unitId, unitName, "NONE".equals(variable) ? "NONE" : "AVERAGE",
                usdRate, (int) employees.stream().map(PersonTotal::userId).distinct().count(), months, totals(all), employees, skipped);
    }

    static BigDecimal money(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }
}
