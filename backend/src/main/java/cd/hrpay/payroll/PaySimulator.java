package cd.hrpay.payroll;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Simulates one person's pay with the real payroll engine, without saving anything: a month-end payslip, the quinzaine,
 * an allocation de congé, the 13th month or a final settlement, from the recorded data or with some of it changed
 * ("what if"). A simulation is not a payslip (Labour Code art. 103): it has no number, is not in the livre de paie and
 * the employee never sees it. Each one runs in its own transaction that is always rolled back.
 */
@Service
public class PaySimulator {

    public static final List<String> TYPES = List.of("MONTH", "QUINZAINE", "LEAVE_PAY", "THIRTEENTH", "SETTLEMENT");

    /** Recorded data changed for the simulation; a null field keeps what is recorded. */
    public record WhatIf(BigDecimal basic, BigDecimal complement, BigDecimal overtimeHours, BigDecimal unpaidDays,
                         BigDecimal bonus, Integer children, Integer dependants, BigDecimal usdRate) {
        static final WhatIf NONE = new WhatIf(null, null, null, null, null, null, null, null);

        boolean any() {
            return basic != null || complement != null || overtimeHours != null && overtimeHours.signum() > 0
                    || unpaidDays != null && unpaidDays.signum() > 0 || bonus != null && bonus.signum() > 0
                    || children != null || dependants != null || usdRate != null;
        }
    }

    /**
     * What to simulate: {@code month} (YYYY-MM) for a month-end payslip or the quinzaine; {@code year} for the 13th month;
     * a recorded leave ({@code leaveRequestId}) or dates for an allocation de congé; a last day and a reason for a final
     * settlement.
     */
    public record Request(long userId, String type, String month, Integer year, Long leaveRequestId, LocalDate leaveFrom,
                          LocalDate leaveTo, LocalDate lastDay, String reason, WhatIf whatIf) {}

    /** The person's payslip paid for the same month, or the last one before it, to compare with. */
    public record Paid(String month, String number, String currency, BigDecimal gross, BigDecimal employeeDeductions,
                       BigDecimal net, BigDecimal employerCharges) {}

    public record Result(String type, long userId, String name, String registrationNo, String period, String currency,
                         List<PayrollRuns.Line> lines, BigDecimal gross, BigDecimal employeeDeductions, BigDecimal net,
                         BigDecimal employerCharges, BigDecimal totalCost, List<String> explanation,
                         List<String> warnings, Paid paid, BigDecimal usdRate, LocalDate rateDate,
                         int scheduledMinutes, int paidMinutes, int daysWorked, boolean changed) {}

    private final JdbcClient jdbc;
    private final PayrollRuns runs;
    private final PayElements pay;
    private final ExchangeRates exchange;
    private final Quinzaines quinzaines;
    private final LeaveAllowances allowances;
    private final cd.hrpay.leave.LeaveService leave;
    private final Departures departures;
    private final cd.hrpay.evaluation.ThirteenthMonth thirteenthMonth;
    private final UserRepository users;
    private final AuditService audit;
    private final PayslipPdf pdf;
    private final TransactionTemplate dryRun;
    private final Clock clock;

    public PaySimulator(JdbcClient jdbc, PayrollRuns runs, PayElements pay, ExchangeRates exchange, Quinzaines quinzaines,
                        LeaveAllowances allowances, cd.hrpay.leave.LeaveService leave, Departures departures,
                        cd.hrpay.evaluation.ThirteenthMonth thirteenthMonth, UserRepository users, AuditService audit,
                        PayslipPdf pdf, PlatformTransactionManager transactions, Clock clock) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.pay = pay;
        this.exchange = exchange;
        this.quinzaines = quinzaines;
        this.allowances = allowances;
        this.leave = leave;
        this.departures = departures;
        this.thirteenthMonth = thirteenthMonth;
        this.users = users;
        this.audit = audit;
        this.pdf = pdf;
        this.dryRun = new TransactionTemplate(transactions);
        this.dryRun.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** Runs work in a transaction of its own that is always rolled back: whatever the engine reads, nothing is kept. */
    <T> T dryRun(Supplier<T> work) {
        return dryRun.execute(status -> {
            status.setRollbackOnly();
            return work.get();
        });
    }

    /** Refuses a simulation of one's own pay, as for the other payroll steps. */
    static void notSelf(long userId) {
        if (Objects.equals(userId, CurrentUser.id())) {
            throw new AccessDeniedException("You cannot simulate or project your own pay.");
        }
    }

    public Result simulate(Request in) {
        Result r = dryRun(() -> run(in).result());
        audit.recordIndependently("payroll.simulated", "user", in.userId(), Map.of("type", r.type(), "period", r.period(),
                "whatIf", r.changed()));
        return r;
    }

    /** The simulation as a PDF payslip marked SIMULATION, with no number. */
    public byte[] pdf(Request in) {
        byte[] bytes = dryRun(() -> {
            Outcome o = run(in);
            if (o.slip() == null) {
                throw new BusinessRuleException("A quinzaine has a receipt, not a payslip: there is no PDF to simulate.");
            }
            return pdf.render(o.slip());
        });
        audit.recordIndependently("payroll.simulated", "user", in.userId(), Map.of("type", in.type() == null ? "MONTH" : in.type(), "pdf", true));
        return bytes;
    }

    // ---- the simulation ----------------------------------------------------------------------------------------

    private record Outcome(Result result, PayrollRuns.Payslip slip) {}

    private Outcome run(Request in) {
        notSelf(in.userId());
        AppUser user = users.findById(in.userId()).orElseThrow(() -> new NotFoundException("User not found."));
        String type = in.type() == null ? "MONTH" : in.type();
        WhatIf w = in.whatIf() == null ? WhatIf.NONE : in.whatIf();
        return switch (type) {
            case "MONTH" -> month(user, month(in), w, null, null, List.of(), List.of(), "MONTH");
            case "QUINZAINE" -> quinzaine(user, month(in));
            case "LEAVE_PAY" -> leavePay(user, in);
            case "THIRTEENTH" -> thirteenth(user, in, w);
            case "SETTLEMENT" -> settlement(user, in, w);
            default -> throw new BusinessRuleException("Unknown pay type: " + type);
        };
    }

    private YearMonth month(Request in) {
        if (in.month() == null || in.month().isBlank()) {
            return YearMonth.now(clock);
        }
        try {
            return YearMonth.parse(in.month());
        } catch (RuntimeException e) {
            throw new BusinessRuleException("Give the month as YYYY-MM.");
        }
    }

    /** The dollar's price for a month: the one given, else that of the month's last day (today for a month not over). */
    ExchangeRates.Rate rate(YearMonth month, BigDecimal given) {
        LocalDate today = LocalDate.now(clock);
        LocalDate last = month.atEndOfMonth();
        ExchangeRates.Rate known = exchange.local(runs.localCurrency(), last.isAfter(today) ? today : last);
        if (given == null || given.signum() <= 0) {
            return known;
        }
        return new ExchangeRates.Rate(0, known.currency(), today, given, "SIMULATION", null, CurrentUser.email(), null);
    }

    private PayElements.Entry entry(long userId, YearMonth month, String code, BigDecimal amount, String currency, String note) {
        PayElements.Element e = pay.elements().stream().filter(x -> x.code().equals(code)).findFirst()
                .orElseThrow(() -> new BusinessRuleException("Pay element " + code + " is missing."));
        return new PayElements.Entry(0, userId, null, e.id(), e.code(), e.name(), e.kind(), month.atDay(1), amount, currency,
                note, null, null);
    }

    /** A month-end payslip worked out with the engine of the payroll run, with the changes given. */
    private Outcome month(AppUser user, YearMonth month, WhatIf w, LocalDate lastDay, Thirteenths.Due thirteenth,
                          List<PayElements.Entry> extra, List<String> explanation, String type) {
        long userId = user.getId();
        ExchangeRates.Rate usd = rate(month, w.usdRate());
        PayslipCalculator.Rates rates = runs.rates(month.atEndOfMonth(), usd.cdfPerUnit());
        PayrollRuns.MonthContext ctx = runs.context(month, rates, usd.cdfPerUnit());
        List<PayElements.Entry> entries = new ArrayList<>(extra);
        String currency = runs.salaries().on(userId, month.atEndOfMonth().isAfter(LocalDate.now(clock))
                ? LocalDate.now(clock) : month.atEndOfMonth()).map(s -> s.currency()).orElse(null);
        if (w.bonus() != null && w.bonus().signum() > 0) {
            entries.add(entry(userId, month, "OTHER_BONUS", w.bonus(), currency == null ? "USD" : currency,
                    Texts.t("simulated bonus")));
        }
        int overtime = w.overtimeHours() == null ? 0 : w.overtimeHours().multiply(BigDecimal.valueOf(60)).intValue();
        PayrollRuns.Scenario sc = new PayrollRuns.Scenario(w.basic(), w.complement(), lastDay, overtime, w.unpaidDays(),
                w.children(), w.dependants(), thirteenth != null, thirteenth, null, List.of());
        PayrollRuns.Computed c = runs.compute(ctx, userId, true, entries, sc);
        if (c.skipped() != null) {
            throw new BusinessRuleException(c.skipped());
        }
        PayslipCalculator.Result r = c.result();
        List<String> warnings = new ArrayList<>(c.warnings());
        String status = runs.run(month).map(PayrollRuns.Run::status).orElse(null);
        if ("CLOSED".equals(status)) {
            warnings.add(Texts.t("The payroll of %s is closed: this simulation uses today's data, the payslip paid is shown beside it.",
                    Texts.month(month)));
        }
        if (month.atEndOfMonth().isAfter(LocalDate.now(clock))) {
            warnings.add(Texts.t("Days still to come are counted as worked, as in a projected payroll."));
        }
        List<String> why = new ArrayList<>(explanation);
        describe(w, why);
        Result result = new Result(type, userId, user.fullName(), user.getRegistrationNo(), month.toString(), r.currency(),
                lines(r), r.gross(), r.employeeDeductions(), r.net(), r.employerCharges(), r.gross().add(r.employerCharges()),
                why, warnings, paid(userId, month), usd.cdfPerUnit(), usd.rateDate(), c.scheduledMinutes(), c.paidMinutes(),
                c.daysWorked(), w.any());
        Map<String, Object> snapshot = new LinkedHashMap<>(c.snapshot());
        PayrollRuns.Payslip slip = new PayrollRuns.Payslip(0, 0, month.atDay(1), "SIMULATION", userId, null, r.currency(),
                r.gross(), r.cnssBase(), r.taxableBase(), r.taxableBaseCdf(), r.employeeDeductions(), r.net(),
                r.employerCharges(), c.scheduledMinutes(), c.paidMinutes(), c.daysWorked(), usd.cdfPerUnit(), usd.rateDate(),
                false, snapshot, warnings, result.lines(), "SIMULATION", null, null, null);
        return new Outcome(result, slip);
    }

    private static void describe(WhatIf w, List<String> why) {
        if (w.basic() != null) {
            why.add(Texts.t("What if: basic salary %s.", w.basic().toPlainString()));
        }
        if (w.complement() != null) {
            why.add(Texts.t("What if: salary complement %s.", w.complement().toPlainString()));
        }
        if (w.overtimeHours() != null && w.overtimeHours().signum() > 0) {
            why.add(Texts.t("What if: %s more hours of weekday overtime, priced with the overtime policy.",
                    w.overtimeHours().stripTrailingZeros().toPlainString()));
        }
        if (w.unpaidDays() != null && w.unpaidDays().signum() > 0) {
            why.add(Texts.t("What if: %s days not paid (unjustified absence or unpaid leave).",
                    w.unpaidDays().stripTrailingZeros().toPlainString()));
        }
        if (w.bonus() != null && w.bonus().signum() > 0) {
            why.add(Texts.t("What if: a one-off bonus of %s.", w.bonus().toPlainString()));
        }
        if (w.children() != null) {
            why.add(Texts.t("What if: %s children counted.", w.children()));
        }
        if (w.dependants() != null) {
            why.add(Texts.t("What if: %s dependants in charge.", w.dependants()));
        }
        if (w.usdRate() != null) {
            why.add(Texts.t("What if: 1 USD = %s.", w.usdRate().toPlainString()));
        }
    }

    private static List<PayrollRuns.Line> lines(PayslipCalculator.Result r) {
        return r.lines().stream().filter(l -> l.amount().signum() != 0)
                .sorted(Comparator.comparingInt(PayslipCalculator.Line::order))
                .map(l -> new PayrollRuns.Line(l.code(), l.label(), l.kind(), l.base(), l.rate(), l.amount(), l.order(), l.loanId()))
                .toList();
    }

    /** The regular payslip of a closed payroll for the month, else the last one before it. */
    Paid paid(long userId, YearMonth month) {
        return jdbc.sql("""
                select to_char(r.month, 'YYYY-MM') as month, p.number, trim(p.currency) as currency, p.gross,
                       p.employee_deductions, p.net, p.employer_charges
                from payslip p join payroll_run r on r.id = p.run_id
                where p.user_id = ? and p.kind = 'REGULAR' and r.status = 'CLOSED' and r.month <= ?
                order by r.month desc limit 1""").params(userId, month.atDay(1)).query(Paid.class).optional().orElse(null);
    }

    private Outcome quinzaine(AppUser user, YearMonth month) {
        Quinzaines.Assessed a = quinzaines.simulate(user.getId(), month);
        List<String> why = new ArrayList<>(a.explanation());
        List<String> warnings = new ArrayList<>();
        if (!quinzaines.settings().enabled()) {
            warnings.add(Texts.t("The quinzaine is turned off for this company: this is what it would pay."));
        }
        if (a.skipped() != null) {
            warnings.add(a.skipped());
        }
        BigDecimal amount = a.amount() == null ? BigDecimal.ZERO.setScale(2) : a.amount();
        String currency = a.currency();
        List<PayrollRuns.Line> lines = amount.signum() == 0 ? List.of()
                : List.of(new PayrollRuns.Line("QUINZAINE", Texts.t("Quinzaine (advance on the month's pay)"), "EARNING",
                a.base(), a.percent(), amount, 1, null));
        if (a.payDate() != null) {
            why.add(Texts.t("Paid on %s.", Texts.day(a.payDate())));
        }
        Result result = new Result("QUINZAINE", user.getId(), user.fullName(), user.getRegistrationNo(), month.toString(),
                currency, lines, amount, BigDecimal.ZERO, amount, BigDecimal.ZERO, amount, why, warnings, null, null, null,
                0, 0, 0, false);
        return new Outcome(result, null);
    }

    private Outcome leavePay(AppUser user, Request in) {
        if (!leave.annualPaid()) {
            throw new BusinessRuleException("Annual leave is not paid in this company (Leave settings).");
        }
        LeaveAllowances.Worked w;
        if (in.leaveRequestId() != null) {
            w = allowances.simulate(in.leaveRequestId());
            if (w.due().userId() != user.getId()) {
                throw new BusinessRuleException("This leave is not this employee's.");
            }
        } else {
            if (in.leaveFrom() == null || in.leaveTo() == null) {
                throw new BusinessRuleException("Give the first and last day of the leave.");
            }
            w = allowances.simulate(user.getId(), in.leaveFrom(), in.leaveTo());
        }
        List<String> why = new ArrayList<>(w.explanation());
        for (LeaveAllowances.Part p : w.parts()) {
            why.add(Texts.t("%s: %s days, %s on that month's payslip, %s paid in advance.", Texts.month(YearMonth.from(p.month())),
                    p.days().stripTrailingZeros().toPlainString(), p.grossAmount().toPlainString(), p.advanceAmount().toPlainString()));
        }
        PayslipCalculator.Result r = w.result();
        List<PayrollRuns.Line> lines = w.lines().stream()
                .map(l -> new PayrollRuns.Line(l.code(), l.label(), l.kind(), l.base(), l.rate(), l.amount(), l.order(), l.loanId()))
                .toList();
        String period = w.due().startDate() + "/" + w.due().endDate();
        Result result = new Result("LEAVE_PAY", user.getId(), user.fullName(), user.getRegistrationNo(), period, w.currency(),
                lines, w.gross(), r.employeeDeductions(), w.net(), r.employerCharges(), w.gross().add(r.employerCharges()), why,
                List.of(), null, w.usd().cdfPerUnit(), w.usd().rateDate(), 0, 0, 0, false);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", user.fullName());
        snapshot.put("registrationNo", user.getRegistrationNo());
        PayrollRuns.Payslip slip = new PayrollRuns.Payslip(0, 0, YearMonth.from(w.due().startDate()).atDay(1), "SIMULATION",
                user.getId(), null, w.currency(), w.gross(), r.cnssBase(), r.taxableBase(), r.taxableBaseCdf(),
                r.employeeDeductions(), w.net(), r.employerCharges(), 0, 0, 0, w.usd().cdfPerUnit(), w.usd().rateDate(), false,
                snapshot, List.of(), lines, "LEAVE", null, null,
                Texts.t("%s days, %s to %s", w.days().stripTrailingZeros().toPlainString(), Texts.day(w.due().startDate()),
                        Texts.day(w.due().endDate())));
        return new Outcome(result, slip);
    }

    private Outcome thirteenth(AppUser user, Request in, WhatIf w) {
        int year = in.year() != null ? in.year() : LocalDate.now(clock).getYear();
        var settings = thirteenthMonth.settings();
        var row = thirteenthMonth.forEmployee(user.getId(), user.fullName(), year, settings).orElseThrow(() ->
                new BusinessRuleException(Texts.t("No contract or basic salary in %s.", year)));
        YearMonth payMonth = YearMonth.of(year, Math.max(1, Math.min(12, settings.payMonth())));
        List<String> why = new ArrayList<>();
        why.add(Texts.t("Base: %s %s (%s).", row.base().toPlainString(), row.currency(),
                settings.base().equals("LAST_BASIC") ? Texts.t("last basic salary of the year") : Texts.t("average basic salary of the months worked")));
        why.add("EVALUATIONS".equals(row.scoreSource())
                ? Texts.t("Evaluation score of the year: %s%% (%s evaluations).", row.scorePercent().stripTrailingZeros().toPlainString(), row.evaluations())
                : Texts.t("No approved evaluation: the fallback rate %s%% applies.", row.scorePercent().stripTrailingZeros().toPlainString()));
        if (settings.prorate() && row.monthsWorked() < 12) {
            why.add(Texts.t("Prorated: %s months of 12 under contract.", row.monthsWorked()));
        }
        why.add(Texts.t("13th month: %s %s, paid with the payroll of %s.", row.amount().toPlainString(), row.currency(),
                Texts.month(payMonth)));
        Outcome o = month(user, payMonth, w, null, new Thirteenths.Due(row.amount(), row.currency(),
                Texts.t("%s, simulation", String.valueOf(year))), List.of(), why, "THIRTEENTH");
        if (!settings.enabled()) {
            List<String> warnings = new ArrayList<>(o.result().warnings());
            warnings.addFirst(Texts.t("The company does not pay a 13th month (13th month settings): this is what it would pay."));
            return new Outcome(withWarnings(o.result(), warnings), o.slip());
        }
        return o;
    }

    private static Result withWarnings(Result r, List<String> warnings) {
        return new Result(r.type(), r.userId(), r.name(), r.registrationNo(), r.period(), r.currency(), r.lines(), r.gross(),
                r.employeeDeductions(), r.net(), r.employerCharges(), r.totalCost(), r.explanation(), warnings, r.paid(),
                r.usdRate(), r.rateDate(), r.scheduledMinutes(), r.paidMinutes(), r.daysWorked(), r.changed());
    }

    private Outcome settlement(AppUser user, Request in, WhatIf w) {
        if (in.lastDay() == null || in.reason() == null) {
            throw new BusinessRuleException("Give the last day and the reason for leaving.");
        }
        departures.find(user.getId()).filter(d -> "CONFIRMED".equals(d.status())).ifPresent(d -> {
            throw new BusinessRuleException(Texts.t("The departure of this employee is already confirmed (last day %s): see it on the Departure page.",
                    Texts.day(d.lastDay())));
        });
        Departures.Departure d = departures.propose(user.getId(), new Departures.Input(in.lastDay(), in.reason(), null, null,
                null, null, null, null, null, null));
        YearMonth month = YearMonth.from(in.lastDay());
        List<PayElements.Entry> extra = settlementEntries(user.getId(), month, d);
        return month(user, month, w, in.lastDay(), null, extra, d.explanation(), "SETTLEMENT");
    }

    /** The final settlement's amounts as the pay entries a confirmed departure writes on the last payslip. */
    List<PayElements.Entry> settlementEntries(long userId, YearMonth month, Departures.Departure d) {
        List<PayElements.Entry> extra = new ArrayList<>();
        String cur = d.currency();
        String days = d.noticeDays().stripTrailingZeros().toPlainString();
        if (d.noticeAmount().signum() > 0) {
            extra.add(entry(userId, month, "NOTICE_PAY", d.noticeAmount(), cur, Texts.t("%s days", days)));
        }
        if (d.noticeOwed().signum() > 0) {
            extra.add(entry(userId, month, "NOTICE_OWED", d.noticeOwed(), cur, Texts.t("%s days", days)));
        }
        if (d.leaveAmount().signum() > 0) {
            extra.add(entry(userId, month, "LEAVE_PAYOUT", d.leaveAmount(), cur,
                    Texts.t("%s days", d.leaveDays().stripTrailingZeros().toPlainString())));
        }
        String note = Texts.t("final settlement, last day %s", Texts.day(d.lastDay()));
        if (d.severanceAmount().signum() > 0) {
            extra.add(entry(userId, month, "SEVERANCE", d.severanceAmount(), cur, note));
        }
        if (d.endOfCareerAmount().signum() > 0) {
            extra.add(entry(userId, month, "END_OF_CAREER", d.endOfCareerAmount(), cur, note));
        }
        if (d.thirteenthAmount().signum() > 0) {
            extra.add(entry(userId, month, "THIRTEENTH_MONTH", d.thirteenthAmount(), cur, note));
        }
        return extra;
    }

    static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
