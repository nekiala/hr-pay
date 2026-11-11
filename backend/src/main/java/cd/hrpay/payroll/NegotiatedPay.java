package cd.hrpay.payroll;

import cd.hrpay.attendance.AttendanceDays;
import cd.hrpay.attendance.DayCalculator;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.salary.SalaryService;
import cd.hrpay.user.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A person's agreed pay. The basic salary is always the grade's; what was agreed above it is the negotiated salary
 * complement (pay element {@code COMPLEMENT}), and an agreed pay below the grade's basic is refused. HR can give the
 * agreed pay as the net the person takes home: the complement is then worked out with the payroll engine for a normal
 * month (every scheduled day worked, no overtime, the person's dependants, this month's rates and allowances).
 */
@Service
public class NegotiatedPay {

    public static final String COMPLEMENT = "COMPLEMENT";

    /** What the agreed pay comes to in a normal month. */
    public record Plan(long userId, LocalDate from, String gradeCode, String currency, BigDecimal gradeBasic,
                       BigDecimal complement, BigDecimal negotiated, BigDecimal gross, BigDecimal cnss, BigDecimal tax,
                       BigDecimal net, BigDecimal targetNet, BigDecimal usdLocal, LocalDate rateDate) {}

    private final PayrollRuns runs;
    private final EmployeeFile files;
    private final MasterData masterData;
    private final SalaryService salaries;
    private final PayElements pay;
    private final ExchangeRates exchange;
    private final AttendanceDays attendance;
    private final UserRepository users;
    private final Clock clock;

    public NegotiatedPay(PayrollRuns runs, EmployeeFile files, MasterData masterData, SalaryService salaries,
                         PayElements pay, ExchangeRates exchange, AttendanceDays attendance, UserRepository users,
                         Clock clock) {
        this.runs = runs;
        this.files = files;
        this.masterData = masterData;
        this.salaries = salaries;
        this.pay = pay;
        this.exchange = exchange;
        this.attendance = attendance;
        this.users = users;
        this.clock = clock;
    }

    private record Basis(MasterData.Grade grade, boolean expatriate) {}

    private Basis basis(long userId, LocalDate from, Long gradeId) {
        if (gradeId != null) {
            return new Basis(masterData.grade(gradeId), files.contractOn(userId, from).map(EmployeeFile.Contract::expatriate).orElse(false));
        }
        EmployeeFile.Contract c = files.contractOn(userId, from).orElseThrow(() -> new BusinessRuleException(
                Texts.t("There is no contract on %s: start one first, its grade gives the basic salary.", from)));
        return new Basis(masterData.grade(c.gradeId()), c.expatriate());
    }

    /** The pay for an agreed amount above the grade's basic (basic + complement, before allowances). */
    public Plan fromNegotiated(long userId, LocalDate from, BigDecimal negotiated, Long gradeId) {
        Basis b = basis(userId, from, gradeId);
        BigDecimal base = b.grade().baseSalary();
        if (negotiated.compareTo(base) < 0) {
            throw new BusinessRuleException(Texts.t("The agreed salary (%s) cannot be below the basic salary of grade %s (%s).",
                    negotiated.toPlainString(), b.grade().code(), base.toPlainString()));
        }
        BigDecimal complement = negotiated.subtract(base).setScale(2, RoundingMode.HALF_UP);
        try {
            return plan(userId, from, b, month(userId, from, b), complement, null);
        } catch (BusinessRuleException | cd.hrpay.common.NotFoundException e) {
            // No rate for that month yet (or no complement element): the split stands, without a normal month's figures.
            return new Plan(userId, from, b.grade().code(), b.grade().currency(), base, complement, negotiated, null, null,
                    null, null, null, null, null);
        }
    }

    /** The pay for an agreed net: the grade's basic, and the complement that brings a normal month's net to it. */
    public Plan fromNet(long userId, LocalDate from, BigDecimal targetNet, Long gradeId) {
        Basis b = basis(userId, from, gradeId);
        Month m = month(userId, from, b);
        BigDecimal target = targetNet.setScale(2, RoundingMode.HALF_UP);
        BigDecimal atBase = m.net(BigDecimal.ZERO);
        if (atBase.compareTo(target) > 0) {
            throw new BusinessRuleException(Texts.t("At the basic salary of grade %s (%s) the net is already %s, above %s. Choose a lower grade.",
                    b.grade().code(), b.grade().baseSalary().toPlainString(), atBase.toPlainString(), target.toPlainString()));
        }
        BigDecimal lo = BigDecimal.ZERO;
        BigDecimal hi = target.max(BigDecimal.ONE);
        for (int i = 0; i < 40 && m.net(hi).compareTo(target) < 0; i++) {
            hi = hi.multiply(BigDecimal.TWO);
        }
        for (int i = 0; i < 60 && hi.subtract(lo).compareTo(new BigDecimal("0.001")) > 0; i++) {
            BigDecimal mid = lo.add(hi).divide(BigDecimal.TWO, 6, RoundingMode.HALF_UP);
            if (m.net(mid).compareTo(target) < 0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        // The cent that comes closest; on a tie, the smaller complement.
        BigDecimal best = hi.setScale(2, RoundingMode.HALF_UP);
        BigDecimal bestGap = m.net(best).subtract(target).abs();
        for (BigDecimal c : List.of(best.subtract(new BigDecimal("0.02")), best.subtract(new BigDecimal("0.01")),
                best.add(new BigDecimal("0.01")))) {
            if (c.signum() < 0) {
                continue;
            }
            BigDecimal gap = m.net(c).subtract(target).abs();
            if (gap.compareTo(bestGap) < 0 || (gap.compareTo(bestGap) == 0 && c.compareTo(best) < 0)) {
                best = c;
                bestGap = gap;
            }
        }
        return plan(userId, from, b, m, best, target);
    }

    /** Records the pay from its date: the grade's basic in the salary history, the complement as a standing item. */
    @Transactional
    public Plan apply(Plan p, String note) {
        LocalDate from = p.from();
        String what = p.targetNet() != null
                ? Texts.t("Net agreed: %s %s", p.targetNet().toPlainString(), p.currency())
                : Texts.t("Agreed salary: %s %s", p.negotiated().toPlainString(), p.currency());
        String text = note == null || note.isBlank() ? what : what + " · " + note.trim();
        var same = salaries.history(p.userId()).stream().filter(s -> s.effectiveFrom().equals(from)).findFirst();
        if (same.isPresent() && (same.get().grossMonthly().compareTo(p.gradeBasic()) != 0
                || !same.get().currency().equals(p.currency()))) {
            throw new cd.hrpay.common.ConflictException("A salary already starts on " + from + " for this employee.");
        }
        if (same.isEmpty()) {   // a contract that starts that day has already recorded the grade's basic
            salaries.add(p.userId(), from, p.gradeBasic(), p.currency(), Texts.t("Grade %s basic", p.gradeCode()) + " · " + text);
        }
        YearMonth start = YearMonth.from(from);
        for (PayElements.Assignment a : pay.assignments(p.userId())) {
            if (a.elementCode().equals(COMPLEMENT) && (a.endMonth() == null || !a.endMonth().isBefore(start.atDay(1)))) {
                pay.endAssignment(p.userId(), a.id(), start.minusMonths(1));
            }
        }
        if (p.complement().signum() > 0) {
            pay.assign(p.userId(), pay.element(COMPLEMENT).id(), p.complement(), p.currency(), null, false, start, null, text);
        }
        return p;
    }

    /**
     * Starts a contract with an agreed pay: an agreed salary (at least the grade's basic) or an agreed net. Without
     * either, the basic is the grade's.
     */
    @Transactional
    public EmployeeFile.Contract startContract(long userId, String type, LocalDate start, LocalDate end, long gradeId,
                                               Long occupationId, boolean expatriate, BigDecimal agreedSalary,
                                               BigDecimal agreedNet, String note) {
        return startContract(userId, type, start, end, gradeId, occupationId, expatriate, agreedSalary, agreedNet, note, null);
    }

    /** Starts a contract with its first salary; the first contract also sets the hire date (since: an earlier one). */
    @Transactional
    public EmployeeFile.Contract startContract(long userId, String type, LocalDate start, LocalDate end, long gradeId,
                                               Long occupationId, boolean expatriate, BigDecimal agreedSalary,
                                               BigDecimal agreedNet, String note, LocalDate since) {
        if (agreedSalary != null && agreedNet != null) {
            throw new BusinessRuleException("Give the agreed salary or the agreed net, not both.");
        }
        MasterData.Grade grade = masterData.grade(gradeId);
        if (agreedSalary != null && agreedSalary.compareTo(grade.baseSalary()) < 0) {
            throw new BusinessRuleException(Texts.t("The agreed salary (%s) cannot be below the basic salary of grade %s (%s).",
                    agreedSalary.toPlainString(), grade.code(), grade.baseSalary().toPlainString()));
        }
        EmployeeFile.Contract c = files.startContract(userId, type, start, end, gradeId, occupationId, expatriate, null, note,
                since);
        if (agreedSalary != null) {
            apply(fromNegotiated(userId, start, agreedSalary, gradeId), null);
        } else if (agreedNet != null) {
            apply(fromNet(userId, start, agreedNet, gradeId), null);
        }
        return c;
    }

    /**
     * Moves the person to another grade from a date (a promotion or a reclassification): the contract goes on under
     * the new grade, the previous record ending the day before, and the basic becomes the new grade's. The salary
     * complement stays as it is, or, with keepNet, is worked out again so that a normal month's net does not change.
     */
    @Transactional
    public EmployeeFile.Contract changeGrade(long userId, long gradeId, LocalDate from, boolean keepNet, String note) {
        EmployeeFile.Contract current = files.contractOn(userId, from).orElseThrow(() -> new BusinessRuleException(
                Texts.t("There is no contract on %s: start one first, its grade gives the basic salary.", from)));
        if (current.gradeId() == gradeId) {
            throw new BusinessRuleException("The employee is already in this grade.");
        }
        return changeJob(userId, gradeId, current.occupationId(), from, keepNet, note);
    }

    /**
     * From a date, the contract goes on with another grade and/or occupation (an avenant): the current record ends the
     * day before. With {@code keepNet}, the salary complement absorbs the new basic so that the net stays the same.
     */
    @Transactional
    public EmployeeFile.Contract changeJob(long userId, long gradeId, Long occupationId, LocalDate from, boolean keepNet,
                                           String note) {
        EmployeeFile.Contract current = files.contractOn(userId, from).orElseThrow(() -> new BusinessRuleException(
                Texts.t("There is no contract on %s: start one first, its grade gives the basic salary.", from)));
        boolean newGrade = current.gradeId() != gradeId;
        if (!newGrade && java.util.Objects.equals(current.occupationId(), occupationId)) {
            throw new BusinessRuleException("The employee is already in this grade.");
        }
        if (current.startDate().equals(from)) {
            throw new BusinessRuleException("The contract starts that day: use Correct to fix its grade.");
        }
        files.requireOpen(from);
        BigDecimal net = keepNet && newGrade ? normalMonth(userId, from).net() : null;
        MasterData.Grade old = masterData.grade(current.gradeId());
        MasterData.Grade grade = masterData.grade(gradeId);
        String text = note == null || note.isBlank()
                ? newGrade ? Texts.t("Grade change: %s to %s", old.code(), grade.code()) : Texts.t("Change of occupation")
                : note.trim();
        EmployeeFile.Contract c = files.startContract(userId, current.contractType(), from, current.endDate(), gradeId,
                occupationId, current.expatriate(), null, text);
        if (net != null) {
            apply(fromNet(userId, from, net, gradeId), text);
        }
        return c;
    }

    // ---- a normal month ---------------------------------------------------------------------------------------

    private final class Month {
        final PayslipCalculator.Rates rates;
        final ExchangeRates.Rate usd;
        final List<PayElements.Element> elements;
        final List<PayElements.Assignment> standing;
        final PayElements.Element complement;
        final int scheduledMinutes;
        final int scheduledDays;
        final int daysWorked;
        final int children;
        final int dependants;
        final Basis basis;
        final BigDecimal seniorityPercent;

        Month(PayslipCalculator.Rates rates, ExchangeRates.Rate usd, List<PayElements.Element> elements,
              List<PayElements.Assignment> standing, PayElements.Element complement, int scheduledMinutes,
              int scheduledDays, int daysWorked, int children, int dependants, Basis basis, BigDecimal seniorityPercent) {
            this.rates = rates;
            this.usd = usd;
            this.elements = elements;
            this.standing = standing;
            this.complement = complement;
            this.scheduledMinutes = scheduledMinutes;
            this.scheduledDays = scheduledDays;
            this.daysWorked = daysWorked;
            this.children = children;
            this.dependants = dependants;
            this.basis = basis;
            this.seniorityPercent = seniorityPercent;
        }

        PayslipCalculator.Result result(BigDecimal amount) {
            List<PayElements.Assignment> items = new ArrayList<>(standing);
            if (amount.signum() > 0) {
                items.add(new PayElements.Assignment(0, 0, complement.id(), COMPLEMENT, complement.name(), amount,
                        basis.grade().currency(), null, false, null, null, null, null, null));
            }
            return PayslipCalculator.calculate(new PayslipCalculator.Input(basis.grade().currency(),
                    basis.grade().baseSalary(), scheduledMinutes, scheduledMinutes, scheduledDays, daysWorked,
                    BigDecimal.ZERO, 0, null, null, children, dependants, basis.expatriate(), elements, items, List.of(),
                    List.of(), rates, 0, null, null, seniorityPercent));
        }

        BigDecimal net(BigDecimal amount) {
            return result(amount).net();
        }
    }

    private Month month(long userId, LocalDate from, Basis b) {
        YearMonth ym = YearMonth.from(from);
        LocalDate today = LocalDate.now(clock);
        LocalDate last = ym.atEndOfMonth();
        ExchangeRates.Rate usd = exchange.local(runs.localCurrency(), last.isAfter(today) ? today : last);
        PayslipCalculator.Rates rates = runs.rates(last, usd.cdfPerUnit());
        PayElements.Element complement = pay.element(COMPLEMENT);
        List<PayElements.Assignment> standing = pay.standingFor(userId, ym).stream()
                .filter(a -> !a.elementCode().equals(COMPLEMENT)).toList();
        var summary = attendance.month(users.findById(userId).orElseThrow(), ym, false);
        int minutes = summary.scheduledMinutesMonth();
        int days = (int) summary.days().stream().filter(d -> d.scheduledMinutes() > 0).count();
        int worked = (int) summary.days().stream()
                .filter(d -> d.scheduledMinutes() > 0 && d.status() != DayCalculator.Status.HOLIDAY).count();
        if (minutes <= 0 || days == 0) {      // no schedule yet: a month of 22 days of 8 hours
            minutes = 22 * 8 * 60;
            days = 22;
            worked = 22;
        }
        EmployeeFile.Counted counted = files.counted(userId, ym);
        int children = counted.children();
        int inCharge = counted.dependants();
        return new Month(rates, usd, pay.elements(), standing, complement, minutes, days, worked, children, inCharge, b,
                seniorityPercent(userId, last));
    }

    /**
     * A normal month of the person's pay as it stands on a date (their basic salary and standing items, the salary
     * complement included): every scheduled day worked, no overtime or one-off entries.
     */
    public PayslipCalculator.Result normalMonth(long userId, LocalDate on) {
        return normalMonth(userId, on, null);
    }

    /** The same with another basic salary (a general increase being simulated); null keeps the recorded one. */
    public PayslipCalculator.Result normalMonth(long userId, LocalDate on, BigDecimal basic) {
        var salary = salaries.on(userId, on).orElseThrow(() -> new BusinessRuleException("No basic salary recorded."));
        YearMonth ym = YearMonth.from(on);
        LocalDate today = LocalDate.now(clock);
        LocalDate last = ym.atEndOfMonth();
        ExchangeRates.Rate usd = exchange.local(runs.localCurrency(), last.isAfter(today) ? today : last);
        PayslipCalculator.Rates rates = runs.rates(last, usd.cdfPerUnit());
        var summary = attendance.month(users.findById(userId).orElseThrow(), ym, false);
        int minutes = summary.scheduledMinutesMonth();
        int days = (int) summary.days().stream().filter(d -> d.scheduledMinutes() > 0).count();
        int worked = (int) summary.days().stream()
                .filter(d -> d.scheduledMinutes() > 0 && d.status() != DayCalculator.Status.HOLIDAY).count();
        if (minutes <= 0 || days == 0) {
            minutes = 22 * 8 * 60;
            days = 22;
            worked = 22;
        }
        EmployeeFile.Counted counted = files.counted(userId, ym);
        int children = counted.children();
        int inCharge = counted.dependants();
        boolean expatriate = files.contractOn(userId, on).map(EmployeeFile.Contract::expatriate).orElse(false);
        return PayslipCalculator.calculate(new PayslipCalculator.Input(salary.currency(), basic != null ? basic : salary.grossMonthly(), minutes,
                minutes, days, worked, BigDecimal.ZERO, 0, null, null, children, inCharge, expatriate, pay.elements(),
                pay.standingFor(userId, ym), List.of(), List.of(), rates, 0, null, null, seniorityPercent(userId, last)));
    }

    private Plan plan(long userId, LocalDate from, Basis b, Month m, BigDecimal complement, BigDecimal target) {
        PayslipCalculator.Result r = m.result(complement);
        BigDecimal cnss = line(r, "CNSS_EMPLOYEE");
        BigDecimal tax = line(r, "IPR");
        BigDecimal base = b.grade().baseSalary();
        return new Plan(userId, from, b.grade().code(), b.grade().currency(), base, complement, base.add(complement),
                r.gross(), cnss, tax, r.net(), target, m.usd.cdfPerUnit(), m.usd.rateDate());
    }

    /** The seniority bonus of the company's agreement, from the hire date (or the first contract). */
    private BigDecimal seniorityPercent(long userId, LocalDate on) {
        LocalDate hired = users.findById(userId).map(u -> u.getHireDate()).orElse(null);
        if (hired == null) {
            hired = files.contracts(userId).stream().map(EmployeeFile.Contract::startDate).min(LocalDate::compareTo).orElse(null);
        }
        return runs.seniority().percent(hired, on);
    }

    private static BigDecimal line(PayslipCalculator.Result r, String code) {
        return r.lines().stream().filter(l -> l.code().equals(code)).map(PayslipCalculator.Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
