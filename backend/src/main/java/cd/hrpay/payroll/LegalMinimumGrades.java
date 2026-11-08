package cd.hrpay.payroll;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.salary.SalaryService;
import cd.hrpay.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Brings grade base salaries up to the legal minimum of their grade (DRC Décret 25/22: daily SMIG × the grade's
 * coefficient ÷ 100 × 26 days) and keeps each person's net pay as it is: the basic becomes the new grade base and the
 * negotiated complement shrinks by what the basic gained. A person whose net is too low for the raised base (the
 * complement would be negative) moves to the nearest grade below whose base still fits, with the complement for the
 * rest. Everything is dated from the first day of a month whose payroll is not approved yet; it is shown first, then
 * applied.
 */
@Service
public class LegalMinimumGrades {

    public record GradeChange(long gradeId, String code, String currency, BigDecimal coefficient, BigDecimal minimumLocal,
                              BigDecimal oldBase, BigDecimal newBase) {}

    public record PersonChange(long userId, String name, String registrationNo, String oldGrade, BigDecimal oldBasic,
                               BigDecimal oldComplement, BigDecimal net, String newGrade, BigDecimal newBasic,
                               BigDecimal newComplement, BigDecimal newNet, String currency, String problem) {}

    public record Result(LocalDate from, BigDecimal smigDaily, BigDecimal usdLocal, LocalDate rateDate,
                         List<GradeChange> grades, List<PersonChange> people, boolean applied) {}

    private final JdbcClient jdbc;
    private final MasterData masterData;
    private final EmployeeFile files;
    private final SalaryService salaries;
    private final NegotiatedPay negotiated;
    private final StatutoryRates statutory;
    private final ExchangeRates exchange;
    private final PayrollRuns runs;
    private final AuditService audit;
    private final cd.hrpay.user.UserRepository users;
    private final java.time.Clock clock;
    private final TransactionTemplate tx;

    public LegalMinimumGrades(JdbcClient jdbc, MasterData masterData, EmployeeFile files, SalaryService salaries,
                              NegotiatedPay negotiated, StatutoryRates statutory, ExchangeRates exchange, PayrollRuns runs,
                              AuditService audit, cd.hrpay.user.UserRepository users,
                              PlatformTransactionManager txManager, java.time.Clock clock) {
        this.jdbc = jdbc;
        this.masterData = masterData;
        this.files = files;
        this.salaries = salaries;
        this.negotiated = negotiated;
        this.statutory = statutory;
        this.exchange = exchange;
        this.runs = runs;
        this.audit = audit;
        this.users = users;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /** What raising the grades from that date would do, without changing anything. */
    public Result preview(LocalDate from) {
        return tx.execute(status -> {
            Result r = run(from, false);
            status.setRollbackOnly();
            return r;
        });
    }

    public Result apply(LocalDate from) {
        return tx.execute(status -> run(from, true));
    }

    private Result run(LocalDate from, boolean applied) {
        if (from.getDayOfMonth() != 1) {
            throw new BusinessRuleException("Choose the first day of a month.");
        }
        int locked = jdbc.sql("select count(*) from payroll_run where month >= ? and status in ('APPROVED', 'CLOSED')")
                .param(from).query(Integer.class).single();
        if (locked > 0) {
            throw new BusinessRuleException(Texts.t("The payroll of %s or later is already approved: choose a later month.",
                    YearMonth.from(from)));
        }
        String local = runs.localCurrency();
        if (!"CDF".equals(local)) {
            throw new BusinessRuleException("Grade minimums from the SMIG apply to the DR Congo only.");
        }
        LocalDate last = YearMonth.from(from).atEndOfMonth();
        LocalDate today = LocalDate.now(clock);
        ExchangeRates.Rate usd = exchange.local(local, last.isAfter(today) ? today : last);
        BigDecimal smig = statutory.rate("SMIG_DAILY", last);

        // 1. The grades below their minimum, and the net each of their people takes home now.
        List<MasterData.Grade> grades = masterData.grades();
        List<GradeChange> gradeChanges = new ArrayList<>();
        for (MasterData.Grade g : grades) {
            if (!g.active() || g.smigCoefficient() == null) {
                continue;
            }
            BigDecimal minimum = PayslipCalculator.minimumMonthly(smig, g.smigCoefficient());
            BigDecimal rate = "USD".equals(g.currency()) ? usd.cdfPerUnit() : BigDecimal.ONE;
            BigDecimal newBase = minimum.divide(rate, 0, RoundingMode.CEILING);
            if (g.baseSalary().compareTo(newBase) < 0) {
                gradeChanges.add(new GradeChange(g.id(), g.code(), g.currency(), g.smigCoefficient(), minimum,
                        g.baseSalary(), newBase));
            }
        }
        record Person(long userId, String name, String registrationNo, EmployeeFile.Contract contract, BigDecimal basic,
                      BigDecimal complement, BigDecimal net, String currency, boolean salaryOnThatDay) {}
        List<Person> people = new ArrayList<>();
        for (GradeChange gc : gradeChanges) {
            List<Long> onGrade = jdbc.sql("""
                    select c.user_id from employee_contract c join app_user u on u.id = c.user_id
                    where c.grade_id = ? and c.start_date <= ? and (c.end_date is null or c.end_date >= ?) and u.status = 'ACTIVE'
                    order by u.last_name, u.first_name""").params(gc.gradeId(), from, from).query(Long.class).list();
            for (long userId : onGrade) {
                EmployeeFile.Contract contract = files.contractOn(userId, from).orElseThrow();
                if (contract.gradeId() != gc.gradeId()) {
                    continue;
                }
                var salary = salaries.on(userId, from);
                if (salary.isEmpty() || salary.get().grossMonthly().compareTo(gc.newBase()) >= 0) {
                    continue;
                }
                PayslipCalculator.Result month = negotiated.normalMonth(userId, from);
                BigDecimal complement = month.lines().stream().filter(l -> l.code().equals(NegotiatedPay.COMPLEMENT))
                        .map(PayslipCalculator.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                var who = jdbc.sql("select first_name || ' ' || last_name as name, registration_no from app_user where id = ?")
                        .param(userId).query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).single();
                people.add(new Person(userId, who[0], who[1], contract, salary.get().grossMonthly(), complement,
                        month.net(), salary.get().currency(), salary.get().effectiveFrom().equals(from)));
            }
        }

        // 2. The new bases.
        for (GradeChange gc : gradeChanges) {
            jdbc.sql("update grade set base_salary = ? where id = ?").params(gc.newBase(), gc.gradeId()).update();
            audit.record("grade.legal_minimum", "grade", gc.gradeId(), Map.of("code", gc.code(),
                    "oldBase", gc.oldBase().toPlainString(), "newBase", gc.newBase().toPlainString(),
                    "from", from.toString(), "smigDaily", smig.toPlainString()));
        }

        // 3. Each person: the same net on the raised base, or on the nearest grade below that fits.
        List<MasterData.Grade> raised = masterData.grades();
        List<PersonChange> changes = new ArrayList<>();
        for (Person p : people) {
            MasterData.Grade current = raised.stream().filter(g -> g.id() == p.contract().gradeId()).findFirst().orElseThrow();
            List<MasterData.Grade> candidates = new ArrayList<>();
            candidates.add(current);
            raised.stream().filter(g -> g.active() && g.id() != current.id() && g.currency().equals(current.currency())
                            && g.baseSalary().compareTo(current.baseSalary()) < 0)
                    .sorted(Comparator.comparing(MasterData.Grade::baseSalary).reversed())
                    .forEach(candidates::add);
            NegotiatedPay.Plan plan = null;
            MasterData.Grade chosen = null;
            for (MasterData.Grade g : candidates) {
                try {
                    plan = negotiated.fromNet(p.userId(), from, p.net(), g.id());
                    chosen = g;
                    break;
                } catch (BusinessRuleException e) {
                    // The net at this grade's base is already above the person's: try the next grade down.
                }
            }
            String problem = null;
            if (plan == null) {
                problem = Texts.t("No grade has a base low enough for this net.");
            } else if (p.salaryOnThatDay()) {
                problem = Texts.t("A salary already starts on %s: change it on the employee file.", from);
            } else if (chosen.id() != current.id() && p.contract().startDate().equals(from)) {
                problem = Texts.t("A contract already starts on %s: change its grade on the employee file.", from);
            } else if (chosen.id() != current.id() && Objects.equals(p.userId(), CurrentUser.id())) {
                problem = Texts.t("You cannot change your own contract.");
            }
            if (applied && problem == null) {
                String note = Texts.t("Legal minimum of grade %s (SMIG %s FC x %s x 26 days)", current.code(),
                        smig.stripTrailingZeros().toPlainString(), current.smigCoefficient().stripTrailingZeros().toPlainString());
                if (chosen.id() != current.id()) {
                    keepHireDate(p.userId());
                    EmployeeFile.Contract c = p.contract();
                    files.startContract(p.userId(), c.contractType(), from, c.endDate(), chosen.id(), c.occupationId(),
                            c.expatriate(), null, Texts.t("Grade %s to %s, same net pay", current.code(), chosen.code())
                                    + " · " + note);
                }
                negotiated.apply(plan, note);
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("from", from.toString());
                details.put("oldGrade", current.code());
                details.put("newGrade", chosen.code());
                details.put("net", p.net().toPlainString());
                details.put("complement", plan.complement().toPlainString());
                audit.record("salary.legal_minimum", "user", p.userId(), details);
            }
            changes.add(new PersonChange(p.userId(), p.name(), p.registrationNo(), current.code(), p.basic(), p.complement(),
                    p.net(), plan == null ? null : chosen.code(), plan == null ? null : plan.gradeBasic(),
                    plan == null ? null : plan.complement(), plan == null ? null : plan.net(), p.currency(), problem));
        }
        return new Result(from, smig, usd.cdfPerUnit(), usd.rateDate(), gradeChanges, changes, applied);
    }

    /** A new contract row must not restart the seniority: the hire date is the first contract's start when not set. */
    private void keepHireDate(long userId) {
        users.findById(userId).filter(u -> u.getHireDate() == null).ifPresent(u -> u.setHireDate(
                files.contracts(userId).stream().map(EmployeeFile.Contract::startDate).min(LocalDate::compareTo).orElse(null)));
    }
}
