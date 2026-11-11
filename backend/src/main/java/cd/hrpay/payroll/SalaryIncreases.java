package cd.hrpay.payroll;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.Notifications;
import cd.hrpay.salary.SalaryService;
import cd.hrpay.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A general salary increase, as a revision of the grade scale: the grade bases go up by a percentage from a month, and
 * every person on those grades gets the same amount on their basic salary (DRC Code du travail art. 86: equal pay for
 * equal work; the salary complement stays as it is). HR prepares and simulates it (nothing is saved), a second person
 * approves it; the approval records the new bases and salaries from the start month and pays the months already
 * approved as back pay ("Rappel de salaire") on the next payroll. Undone only while no later payroll is approved.
 */
@Service
public class SalaryIncreases {

    private static final Logger log = LoggerFactory.getLogger(SalaryIncreases.class);
    private static final ZoneId ZONE = ZoneId.of("Africa/Kinshasa");
    public static final String ARREARS = "SALARY_ARREARS";
    /** Back pay reaches back one year at most (Congo Code du travail art. 99: wage claims lapse after a year). */
    static final int MAX_MONTHS_BACK = 12;

    public record Input(String name, BigDecimal percent, BigDecimal minimumAmount, String minimumCurrency,
                        BigDecimal rounding, LocalDate startMonth, List<Long> categoryIds, List<Long> gradeIds) {}

    public record GradeLine(long gradeId, String code, String name, String categoryName, String currency,
                            BigDecimal oldBase, BigDecimal newBase, BigDecimal raise, BigDecimal legalMinimum, int people) {}

    public record PersonLine(long userId, String name, String registrationNo, String gradeCode, String currency,
                             BigDecimal oldBasic, BigDecimal newBasic, BigDecimal oldGross, BigDecimal newGross,
                             BigDecimal oldNet, BigDecimal newNet, BigDecimal oldCost, BigDecimal newCost,
                             BigDecimal arrears, int arrearsMonths, String problem) {}

    /** The monthly figures are the difference the increase makes in a normal month. */
    public record Total(String currency, int people, BigDecimal payrollCost, BigDecimal gross, BigDecimal net,
                        BigDecimal employerCharges, BigDecimal cost, BigDecimal percentOfCost, BigDecimal costToYearEnd,
                        int monthsToYearEnd, BigDecimal costTwelveMonths, BigDecimal arrears) {}

    public record Result(Long scenarioId, String name, BigDecimal percent, LocalDate startMonth, String referenceMonth,
                         String arrearsMonth, List<String> paidMonths, List<GradeLine> grades, List<PersonLine> people,
                         List<Total> totals, List<String> warnings, List<String> notes) {}

    public record Scenario(long id, String name, BigDecimal percent, BigDecimal minimumAmount, String minimumCurrency,
                           BigDecimal rounding, LocalDate startMonth, List<Long> categoryIds, List<Long> gradeIds,
                           String status, List<Total> summary, LocalDate arrearsMonth, String createdBy,
                           OffsetDateTime createdAt, String submittedBy, OffsetDateTime submittedAt, String decidedBy,
                           OffsetDateTime decidedAt, String decisionNote, boolean canUndo) {}

    private final JdbcClient jdbc;
    private final PayrollRuns runs;
    private final NegotiatedPay negotiated;
    private final MasterData masterData;
    private final EmployeeFile files;
    private final SalaryService salaries;
    private final ExchangeRates exchange;
    private final StatutoryRates statutory;
    private final AuditService audit;
    private final Notifications notifications;
    private final JsonMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;
    private cd.hrpay.ops.JobLocks locks;

    public SalaryIncreases(JdbcClient jdbc, PayrollRuns runs, NegotiatedPay negotiated, MasterData masterData,
                           EmployeeFile files, SalaryService salaries, ExchangeRates exchange, StatutoryRates statutory,
                           AuditService audit, Notifications notifications, JsonMapper json, Clock clock,
                           PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.negotiated = negotiated;
        this.masterData = masterData;
        this.files = files;
        this.salaries = salaries;
        this.exchange = exchange;
        this.statutory = statutory;
        this.audit = audit;
        this.notifications = notifications;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setLocks(cd.hrpay.ops.JobLocks locks) {
        this.locks = locks;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    // ---- saved scenarios ---------------------------------------------------------------------------------------

    private static final String SELECT = """
            select id, name, percent, minimum_amount, trim(minimum_currency) as minimum_currency, rounding, start_month,
                   category_ids, grade_ids, status, summary::text as summary, arrears_month, created_by, created_at,
                   submitted_by, submitted_at, decided_by, decided_at, decision_note
            from salary_increase""";

    private Scenario map(ResultSet rs) throws SQLException {
        String summary = rs.getString("summary");
        String status = rs.getString("status");
        LocalDate start = rs.getObject("start_month", LocalDate.class);
        LocalDate arrears = rs.getObject("arrears_month", LocalDate.class);
        return new Scenario(rs.getLong("id"), rs.getString("name"), rs.getBigDecimal("percent"),
                rs.getBigDecimal("minimum_amount"), rs.getString("minimum_currency"), rs.getBigDecimal("rounding"), start,
                ids(rs.getArray("category_ids")), ids(rs.getArray("grade_ids")), status,
                summary == null ? null : json.readValue(summary, new TypeReference<List<Total>>() {}), arrears,
                rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class), rs.getString("submitted_by"),
                rs.getObject("submitted_at", OffsetDateTime.class), rs.getString("decided_by"),
                rs.getObject("decided_at", OffsetDateTime.class), rs.getString("decision_note"),
                "APPROVED".equals(status) && laterApproved(arrears != null ? arrears : start) == 0);
    }

    private static List<Long> ids(Array a) throws SQLException {
        if (a == null) {
            return List.of();
        }
        Object[] values = (Object[]) a.getArray();
        return Arrays.stream(values).map(v -> ((Number) v).longValue()).toList();
    }

    private int laterApproved(LocalDate from) {
        return jdbc.sql("select count(*) from payroll_run where month >= ? and status in ('APPROVED', 'CLOSED')")
                .param(from).query(Integer.class).single();
    }

    public List<Scenario> list() {
        return jdbc.sql(SELECT + " order by created_at desc").query((rs, n) -> map(rs)).list();
    }

    public Scenario get(long id) {
        return jdbc.sql(SELECT + " where id = ?").param(id).query((rs, n) -> map(rs)).optional()
                .orElseThrow(() -> new NotFoundException("Salary increase " + id + " not found."));
    }

    public Scenario create(Input in) {
        Input v = valid(in);
        long id = jdbc.sql("""
                insert into salary_increase (name, percent, minimum_amount, minimum_currency, rounding, start_month,
                                             category_ids, grade_ids, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(v.name(), v.percent(), v.minimumAmount(), v.minimumCurrency(), v.rounding(), v.startMonth(),
                        v.categoryIds().toArray(Long[]::new), v.gradeIds().toArray(Long[]::new), CurrentUser.email())
                .query(Long.class).single();
        audit.record("salary_increase.created", "salary_increase", id, describe(v));
        return get(id);
    }

    public Scenario update(long id, Input in) {
        Scenario s = get(id);
        if (!s.status().equals("DRAFT") && !s.status().equals("REJECTED")) {
            throw new ConflictException(Texts.t("Only a draft can be changed."));
        }
        Input v = valid(in);
        jdbc.sql("""
                update salary_increase set name = ?, percent = ?, minimum_amount = ?, minimum_currency = ?, rounding = ?,
                       start_month = ?, category_ids = ?, grade_ids = ?, status = 'DRAFT', summary = null,
                       submitted_by = null, submitted_at = null, decided_by = null, decided_at = null, decision_note = null
                where id = ?""")
                .params(v.name(), v.percent(), v.minimumAmount(), v.minimumCurrency(), v.rounding(), v.startMonth(),
                        v.categoryIds().toArray(Long[]::new), v.gradeIds().toArray(Long[]::new), id).update();
        audit.record("salary_increase.changed", "salary_increase", id, describe(v));
        return get(id);
    }

    public void delete(long id) {
        Scenario s = get(id);
        if (s.status().equals("APPROVED") || s.status().equals("UNDONE")) {
            throw new ConflictException(Texts.t("An approved increase stays on record: undo it instead."));
        }
        jdbc.sql("delete from salary_increase where id = ?").param(id).update();
        audit.record("salary_increase.deleted", "salary_increase", id, Map.of("name", s.name()));
    }

    public Scenario submit(long id) {
        Scenario s = get(id);
        if (!s.status().equals("DRAFT")) {
            throw new ConflictException(Texts.t("Only a draft can be sent for approval."));
        }
        Result r = preview(id);
        if (r.people().stream().allMatch(p -> p.problem() != null)) {
            throw new BusinessRuleException(Texts.t("Nobody would get this increase."));
        }
        jdbc.sql("update salary_increase set status = 'SUBMITTED', submitted_by = ?, submitted_at = now() where id = ?")
                .params(CurrentUser.email(), id).update();
        audit.record("salary_increase.submitted", "salary_increase", id, Map.of("name", s.name()));
        notifications.sendToHolders("payroll.increase.approve", "salary_increase.to_approve",
                () -> Texts.t("Salary increase to approve"),
                () -> Texts.t("%s: %s%% from %s", s.name(), s.percent().stripTrailingZeros().toPlainString(),
                        Texts.month(YearMonth.from(s.startMonth()))), "/payroll/simulate?tab=increase");
        return get(id);
    }

    public Scenario reject(long id, String note) {
        Scenario s = get(id);
        if (!s.status().equals("SUBMITTED")) {
            throw new ConflictException(Texts.t("Only an increase waiting for approval can be rejected."));
        }
        notOwn(s);
        jdbc.sql("update salary_increase set status = 'REJECTED', decided_by = ?, decided_at = now(), decision_note = ? where id = ?")
                .params(CurrentUser.email(), note == null || note.isBlank() ? null : note.trim(), id).update();
        audit.record("salary_increase.rejected", "salary_increase", id, Map.of("name", s.name()));
        return get(id);
    }

    /** Whoever prepared or sent an increase cannot decide on it. */
    private static void notOwn(Scenario s) {
        String me = CurrentUser.email();
        if (me != null && (me.equalsIgnoreCase(s.createdBy()) || me.equalsIgnoreCase(s.submittedBy()))) {
            throw new AccessDeniedException(Texts.t("Another person must approve an increase you prepared."));
        }
    }

    private Input valid(Input in) {
        if (in.name() == null || in.name().isBlank()) {
            throw new BusinessRuleException(Texts.t("Give the increase a name."));
        }
        if (in.percent() == null || in.percent().signum() <= 0 || in.percent().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BusinessRuleException(Texts.t("The percentage must be above 0 and at most 100."));
        }
        if (in.startMonth() == null) {
            throw new BusinessRuleException(Texts.t("Choose the month the increase starts."));
        }
        LocalDate start = in.startMonth().withDayOfMonth(1);
        YearMonth earliest = YearMonth.from(today()).minusMonths(MAX_MONTHS_BACK);
        if (YearMonth.from(start).isBefore(earliest)) {
            throw new BusinessRuleException(Texts.t("Back pay goes back %s months at most: start in %s or later.",
                    MAX_MONTHS_BACK, Texts.month(earliest)));
        }
        boolean minimum = in.minimumAmount() != null && in.minimumAmount().signum() > 0;
        String currency = minimum ? (in.minimumCurrency() == null ? "USD" : in.minimumCurrency().trim().toUpperCase()) : null;
        if (currency != null && !Set.of("USD", "CDF", "XAF").contains(currency)) {
            throw new BusinessRuleException("Unknown currency " + currency + ".");
        }
        BigDecimal rounding = in.rounding() != null && in.rounding().signum() > 0 ? in.rounding() : null;
        return new Input(in.name().trim(), in.percent(), minimum ? in.minimumAmount() : null, currency, rounding, start,
                in.categoryIds() == null ? List.of() : in.categoryIds().stream().filter(Objects::nonNull).distinct().toList(),
                in.gradeIds() == null ? List.of() : in.gradeIds().stream().filter(Objects::nonNull).distinct().toList());
    }

    private static Map<String, Object> describe(Input v) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", v.name());
        d.put("percent", v.percent().toPlainString());
        d.put("startMonth", v.startMonth().toString());
        d.put("categories", v.categoryIds().toString());
        d.put("grades", v.gradeIds().toString());
        if (v.minimumAmount() != null) {
            d.put("minimum", v.minimumAmount().toPlainString() + " " + v.minimumCurrency());
        }
        return d;
    }

    private static Input input(Scenario s) {
        return new Input(s.name(), s.percent(), s.minimumAmount(), s.minimumCurrency(), s.rounding(), s.startMonth(),
                s.categoryIds(), s.gradeIds());
    }

    // ---- the simulation ----------------------------------------------------------------------------------------

    /** What an increase not saved would do (nothing is written). */
    public Result simulate(Input in) {
        Input v = valid(in);
        return readOnly(() -> work(null, v));
    }

    /** What a saved increase would do; its totals are kept on the scenario to compare it with others. */
    public Result preview(long id) {
        Scenario s = get(id);
        Result r = readOnly(() -> work(id, input(s)));
        if (s.status().equals("DRAFT") || s.status().equals("SUBMITTED")) {
            jdbc.sql("update salary_increase set summary = cast(? as jsonb) where id = ?")
                    .params(json.writeValueAsString(r.totals()), id).update();
        }
        return r;
    }

    private Result readOnly(java.util.function.Supplier<Result> work) {
        return tx.execute(status -> {
            status.setRollbackOnly();
            return work.get();
        });
    }

    private record Plan(List<MasterData.Grade> grades, Map<Long, GradeLine> lines, ExchangeRates.Rate usd) {}

    private Plan plan(Input v) {
        String local = runs.localCurrency();
        LocalDate today = today();
        ExchangeRates.Rate usd = null;
        try {
            usd = exchange.local(local, today);
        } catch (RuntimeException e) {
            // Only needed to convert the minimum amount and the legal minimums of USD grades.
        }
        LocalDate startLast = YearMonth.from(v.startMonth()).atEndOfMonth();
        BigDecimal smig = null;
        if ("CDF".equals(local)) {
            try {
                smig = statutory.rate("SMIG_DAILY", startLast);
            } catch (RuntimeException e) {
                smig = null;
            }
        }
        Set<Long> categories = Set.copyOf(v.categoryIds());
        Set<Long> chosen = Set.copyOf(v.gradeIds());
        boolean all = categories.isEmpty() && chosen.isEmpty();
        List<MasterData.Grade> grades = masterData.grades().stream().filter(MasterData.Grade::active)
                .filter(g -> all || categories.contains(g.categoryId()) || chosen.contains(g.id())).toList();
        if (grades.isEmpty()) {
            throw new BusinessRuleException(Texts.t("No active grade in this choice."));
        }
        Map<Long, GradeLine> lines = new LinkedHashMap<>();
        for (MasterData.Grade g : grades) {
            BigDecimal raise = g.baseSalary().multiply(v.percent()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            if (v.minimumAmount() != null) {
                if (!v.minimumCurrency().equals(g.currency()) && usd == null) {
                    throw new BusinessRuleException(Texts.t("No exchange rate today to convert the minimum amount: enter the rate first."));
                }
                BigDecimal min = v.minimumCurrency().equals(g.currency()) ? v.minimumAmount()
                        : ExchangeRates.convert(v.minimumAmount(), v.minimumCurrency(), g.currency(), usd.cdfPerUnit());
                raise = raise.max(min.setScale(2, RoundingMode.HALF_UP));
            }
            BigDecimal newBase = g.baseSalary().add(raise);
            if (v.rounding() != null) {
                newBase = newBase.divide(v.rounding(), 0, RoundingMode.CEILING).multiply(v.rounding()).setScale(2, RoundingMode.HALF_UP);
            }
            BigDecimal minimum = null;
            if (smig != null && g.smigCoefficient() != null) {
                BigDecimal m = PayslipCalculator.minimumMonthly(smig, g.smigCoefficient());
                minimum = "USD".equals(g.currency())
                        ? usd == null ? null : m.divide(usd.cdfPerUnit(), 2, RoundingMode.CEILING)
                        : m.setScale(2, RoundingMode.CEILING);
            }
            lines.put(g.id(), new GradeLine(g.id(), g.code(), g.name(), g.categoryName(), g.currency(), g.baseSalary(),
                    newBase, newBase.subtract(g.baseSalary()), minimum, 0));
        }
        return new Plan(grades, lines, usd);
    }

    /** The people on the chosen grades from the start month, with the grade their contract had then. */
    private Map<Long, Long> people(Set<Long> gradeIds, LocalDate start) {
        Map<Long, Long> out = new LinkedHashMap<>();
        List<Long> users = jdbc.sql("""
                select distinct c.user_id, u.last_name, u.first_name from employee_contract c join app_user u on u.id = c.user_id
                where (c.end_date is null or c.end_date >= ?) and u.status = 'ACTIVE' and c.grade_id = any(?)
                order by u.last_name, u.first_name""")
                .params(start, gradeIds.toArray(Long[]::new)).query((rs, n) -> rs.getLong(1)).list();
        for (long userId : users) {
            LocalDate from = files.contracts(userId).stream()
                    .filter(c -> c.endDate() == null || !c.endDate().isBefore(start))
                    .map(c -> c.startDate().isBefore(start) ? start : c.startDate()).min(LocalDate::compareTo).orElse(start);
            files.contractOn(userId, from).filter(c -> gradeIds.contains(c.gradeId()))
                    .ifPresent(c -> out.put(userId, c.gradeId()));
        }
        return out;
    }

    /** The months from the start already approved or closed: their payslips get back pay. */
    private List<YearMonth> paidMonths(LocalDate start) {
        return jdbc.sql("select month from payroll_run where month >= ? and status in ('APPROVED', 'CLOSED') order by month")
                .param(start).query((rs, n) -> YearMonth.from(rs.getObject(1, LocalDate.class))).list();
    }

    private Result work(Long scenarioId, Input v) {
        LocalDate today = today();
        Plan plan = plan(v);
        LocalDate start = v.startMonth();
        List<YearMonth> paid = paidMonths(start);
        YearMonth arrearsMonth = paid.isEmpty() ? null : paid.getLast().plusMonths(1);
        // Today's pay for an increase already started, the start month's for one to come.
        YearMonth reference = YearMonth.from(start).isBefore(YearMonth.from(today)) ? YearMonth.from(today) : YearMonth.from(start);
        if (arrearsMonth != null && reference.isBefore(arrearsMonth)) {
            reference = arrearsMonth;
        }
        LocalDate ref = reference.atDay(1);
        List<String> warnings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<Long, Long> people = people(plan.lines().keySet(), start);
        Map<Long, Integer> perGrade = new LinkedHashMap<>();
        Map<YearMonth, PayrollRuns.MonthContext> contexts = new LinkedHashMap<>();
        Map<YearMonth, Long> runIds = new LinkedHashMap<>();
        for (YearMonth m : paid) {
            runs.run(m).ifPresent(r -> runIds.put(m, r.id()));
        }
        List<PersonLine> lines = new ArrayList<>();
        for (Map.Entry<Long, Long> e : people.entrySet()) {
            long userId = e.getKey();
            GradeLine g = plan.lines().get(e.getValue());
            var who = jdbc.sql("select person_name(first_name, middle_name, last_name), registration_no from app_user where id = ?")
                    .param(userId).query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).single();
            var salary = salaries.on(userId, ref.isAfter(today) ? ref : today).or(() -> salaries.on(userId, ref)).orElse(null);
            if (salary == null) {
                lines.add(new PersonLine(userId, who[0], who[1], g.code(), g.currency(), null, null, null, null, null, null,
                        null, null, null, 0, Texts.t("No basic salary recorded.")));
                continue;
            }
            perGrade.merge(g.gradeId(), 1, Integer::sum);
            BigDecimal delta = delta(g, salary.currency(), plan.usd());
            if (delta == null) {
                lines.add(new PersonLine(userId, who[0], who[1], g.code(), salary.currency(), salary.grossMonthly(), null,
                        null, null, null, null, null, null, null, 0,
                        Texts.t("The salary is in %s and the grade in %s: no exchange rate today.", salary.currency(), g.currency())));
                continue;
            }
            BigDecimal oldBasic = salaries.on(userId, ref).map(SalaryService.Salary::grossMonthly).orElse(salary.grossMonthly());
            BigDecimal newBasic = oldBasic.add(delta);
            PayslipCalculator.Result before;
            PayslipCalculator.Result after;
            try {
                before = negotiated.normalMonth(userId, ref);
                after = negotiated.normalMonth(userId, ref, newBasic);
            } catch (RuntimeException ex) {
                lines.add(new PersonLine(userId, who[0], who[1], g.code(), salary.currency(), oldBasic, newBasic, null, null,
                        null, null, null, null, null, 0, ex.getMessage()));
                continue;
            }
            // Back pay: each approved month's payslip worked out again with the higher basic.
            BigDecimal arrears = BigDecimal.ZERO;
            int months = 0;
            for (YearMonth m : paid) {
                Long runId = runIds.get(m);
                if (runId == null || jdbc.sql("select count(*) from payslip where run_id = ? and user_id = ?")
                        .params(runId, userId).query(Integer.class).single() == 0) {
                    continue;
                }
                PayrollRuns.MonthContext ctx = contexts.computeIfAbsent(m, this::context);
                if (ctx == null) {
                    continue;
                }
                var then = salaries.on(userId, m.atEndOfMonth()).orElse(null);
                if (then == null) {
                    continue;
                }
                BigDecimal d = delta(g, then.currency(), plan.usd());
                if (d == null) {
                    continue;
                }
                PayrollRuns.Computed was = runs.compute(ctx, userId, false, List.of(), PayrollRuns.Scenario.NONE);
                PayrollRuns.Computed now = runs.compute(ctx, userId, false, List.of(), new PayrollRuns.Scenario(
                        then.grossMonthly().add(d), null, null, 0, null, null, null, false, null, null, List.of()));
                if (was.result() == null || now.result() == null) {
                    continue;
                }
                BigDecimal diff = ExchangeRates.convert(now.result().gross().subtract(was.result().gross()),
                        now.result().currency(), salary.currency(), ctx.usdLocal());
                if (diff.signum() > 0) {
                    arrears = arrears.add(diff);
                    months++;
                }
            }
            lines.add(new PersonLine(userId, who[0], who[1], g.code(), before.currency(), oldBasic, newBasic, before.gross(),
                    after.gross(), before.net(), after.net(), before.gross().add(before.employerCharges()),
                    after.gross().add(after.employerCharges()), arrears.setScale(2, RoundingMode.HALF_UP), months, null));
        }

        List<GradeLine> grades = plan.lines().values().stream().map(g -> new GradeLine(g.gradeId(), g.code(), g.name(),
                g.categoryName(), g.currency(), g.oldBase(), g.newBase(), g.raise(), g.legalMinimum(),
                perGrade.getOrDefault(g.gradeId(), 0))).toList();
        for (GradeLine g : grades) {
            if (g.legalMinimum() != null && g.newBase().compareTo(g.legalMinimum()) < 0) {
                warnings.add(Texts.t("Grade %s stays below its legal minimum (%s %s) even after the increase.",
                        g.code(), g.legalMinimum().toPlainString(), g.currency()));
            }
        }
        long problems = lines.stream().filter(p -> p.problem() != null).count();
        if (problems > 0) {
            warnings.add(Texts.t("%s people cannot get the increase as things stand: see the list.", problems));
        }
        if (arrearsMonth != null) {
            String status = runs.run(arrearsMonth).map(PayrollRuns.Run::status).orElse(null);
            notes.add(Texts.t("The payroll is already approved for %s: those months are paid as back pay (Rappel de salaire) on the payroll of %s.",
                    paid.stream().map(Texts::month).collect(Collectors.joining(", ")), Texts.month(arrearsMonth)));
            if (status != null) {
                warnings.add(Texts.t("The payroll of %s is already calculated: calculate it again after the approval.",
                        Texts.month(arrearsMonth)));
            }
        }
        notes.add(Texts.t("Everyone on a grade gets the same amount on their basic salary (Code du travail art. 86); the salary complement stays as it is."));
        notes.add(Texts.t("Monthly figures: the difference in a normal month of %s (every scheduled day worked, no overtime or one-off pay).",
                Texts.month(reference)));
        notes.add(Texts.t("The 13th month, leave pay and anniversary raises worked out on the grade bases also go up: they are not in these totals."));

        // Totals by currency.
        int monthsToYearEnd = 12 - YearMonth.from(start).getMonthValue() + 1;
        Map<String, BigDecimal[]> sums = new TreeMap<>();
        Map<String, Integer> counts = new TreeMap<>();
        for (PersonLine p : lines) {
            if (p.problem() != null || p.oldGross() == null) {
                continue;
            }
            BigDecimal[] s = sums.computeIfAbsent(p.currency(), c -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            s[0] = s[0].add(p.oldCost());
            s[1] = s[1].add(p.newGross().subtract(p.oldGross()));
            s[2] = s[2].add(p.newNet().subtract(p.oldNet()));
            s[3] = s[3].add(p.newCost().subtract(p.newGross()).subtract(p.oldCost().subtract(p.oldGross())));
            s[4] = s[4].add(p.newCost().subtract(p.oldCost()));
            s[5] = s[5].add(p.arrears());
            counts.merge(p.currency(), 1, Integer::sum);
        }
        List<Total> totals = sums.entrySet().stream().map(x -> {
            BigDecimal[] s = x.getValue();
            BigDecimal pct = s[0].signum() == 0 ? null : s[4].multiply(BigDecimal.valueOf(100)).divide(s[0], 2, RoundingMode.HALF_UP);
            return new Total(x.getKey(), counts.get(x.getKey()), s[0], s[1], s[2], s[3], s[4], pct,
                    s[4].multiply(BigDecimal.valueOf(monthsToYearEnd)), monthsToYearEnd, s[4].multiply(BigDecimal.valueOf(12)), s[5]);
        }).toList();
        return new Result(scenarioId, v.name(), v.percent(), start, reference.toString(),
                arrearsMonth == null ? null : arrearsMonth.toString(), paid.stream().map(YearMonth::toString).toList(),
                grades, lines, totals, warnings, notes);
    }

    private PayrollRuns.MonthContext context(YearMonth m) {
        try {
            LocalDate last = m.atEndOfMonth();
            LocalDate today = today();
            ExchangeRates.Rate usd = exchange.local(runs.localCurrency(), last.isAfter(today) ? today : last);
            return runs.context(m, runs.rates(last, usd.cdfPerUnit()), usd.cdfPerUnit());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The grade's raise in the currency of the person's salary. */
    private static BigDecimal delta(GradeLine g, String currency, ExchangeRates.Rate usd) {
        if (g.currency().equals(currency)) {
            return g.raise();
        }
        if (usd == null) {
            return null;
        }
        return ExchangeRates.convert(g.raise(), g.currency(), currency, usd.cdfPerUnit()).setScale(2, RoundingMode.HALF_UP);
    }

    // ---- approval, and undoing it ------------------------------------------------------------------------------

    /** Records the increase: grade bases (from the start month), each person's basic, and the back pay. */
    @org.springframework.transaction.annotation.Transactional
    public Scenario approve(long id) {
        Scenario s = get(id);
        if (!s.status().equals("SUBMITTED")) {
            throw new ConflictException(Texts.t("Only an increase waiting for approval can be approved."));
        }
        notOwn(s);
        Input v = valid(input(s));
        Result r = work(id, v);
        LocalDate start = v.startMonth();
        LocalDate today = today();
        YearMonth arrearsMonth = r.arrearsMonth() == null ? null : YearMonth.parse(r.arrearsMonth());
        if (arrearsMonth != null) {
            PayrollRuns.requireOpenMonth(jdbc, arrearsMonth);
        }
        String note = Texts.t("General increase \"%s\": %s%% of the grade bases from %s", s.name(),
                s.percent().stripTrailingZeros().toPlainString(), Texts.month(YearMonth.from(start)));
        boolean started = !start.isAfter(today);
        for (GradeLine g : r.grades()) {
            change(id, "GRADE", null, g.gradeId(), g.oldBase(), g.newBase(), g.currency(), started);
            if (started) {
                jdbc.sql("update grade set base_salary = base_salary + ? where id = ?").params(g.raise(), g.gradeId()).update();
            }
            audit.record("grade.general_increase", "grade", g.gradeId(), Map.of("code", g.code(),
                    "oldBase", g.oldBase().toPlainString(), "newBase", g.newBase().toPlainString(), "from", start.toString(),
                    "increase", String.valueOf(id)));
        }
        long arrearsElement = jdbc.sql("select id from pay_element where code = ?").param(ARREARS).query(Long.class).optional()
                .orElseThrow(() -> new BusinessRuleException("Pay element " + ARREARS + " is missing."));
        int applied = 0;
        for (PersonLine p : r.people()) {
            if (p.problem() != null) {
                continue;
            }
            GradeLine g = r.grades().stream().filter(x -> x.code().equals(p.gradeCode())).findFirst().orElseThrow();
            List<SalaryService.Salary> history = salaries.history(p.userId());
            var atStart = salaries.on(p.userId(), start);
            boolean exact = history.stream().anyMatch(h -> h.effectiveFrom().equals(start));
            if (atStart.isPresent() && !exact) {
                BigDecimal d = delta(g, atStart.get().currency(), null);
                BigDecimal amount = atStart.get().grossMonthly().add(d != null ? d
                        : p.newBasic().subtract(p.oldBasic()));
                SalaryService.Salary added = salaries.add(p.userId(), start, amount, atStart.get().currency(), note);
                change(id, "SALARY_NEW", p.userId(), added.id(), atStart.get().grossMonthly(), amount, added.currency(), true);
            }
            for (SalaryService.Salary h : history) {
                if (h.effectiveFrom().isBefore(start)) {
                    continue;
                }
                BigDecimal d = delta(g, h.currency(), null);
                BigDecimal add = d != null ? d : p.newBasic().subtract(p.oldBasic());
                BigDecimal amount = h.grossMonthly().add(add);
                jdbc.sql("update employee_salary set gross_monthly = ?, note = left(coalesce(note || ' · ', '') || ?, 300) where id = ?")
                        .params(amount, note, h.id()).update();
                change(id, "SALARY_RAISED", p.userId(), h.id(), h.grossMonthly(), amount, h.currency(), true);
            }
            if (arrearsMonth != null && p.arrears() != null && p.arrears().signum() > 0) {
                var existing = jdbc.sql("select id, amount from pay_entry where user_id = ? and element_id = ? and month = ?")
                        .params(p.userId(), arrearsElement, arrearsMonth.atDay(1))
                        .query((rs, n) -> new Object[] {rs.getLong(1), rs.getBigDecimal(2)}).optional();
                String text = Texts.t("Back pay of the general increase \"%s\" for %s months", s.name(), p.arrearsMonths());
                if (existing.isPresent()) {
                    BigDecimal old = (BigDecimal) existing.get()[1];
                    jdbc.sql("update pay_entry set amount = ? where id = ?").params(old.add(p.arrears()), existing.get()[0]).update();
                    change(id, "ARREARS", p.userId(), (Long) existing.get()[0], old, old.add(p.arrears()), p.currency(), true);
                } else {
                    long entry = jdbc.sql("""
                            insert into pay_entry (user_id, element_id, month, amount, currency, note, created_by)
                            values (?, ?, ?, ?, ?, ?, ?) returning id""")
                            .params(p.userId(), arrearsElement, arrearsMonth.atDay(1), p.arrears(), p.currency(), text,
                                    CurrentUser.email()).query(Long.class).single();
                    change(id, "ARREARS", p.userId(), entry, null, p.arrears(), p.currency(), true);
                }
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("increase", String.valueOf(id));
            details.put("from", start.toString());
            details.put("oldBasic", p.oldBasic().toPlainString());
            details.put("newBasic", p.newBasic().toPlainString());
            details.put("arrears", p.arrears() == null ? "0" : p.arrears().toPlainString());
            audit.record("salary.general_increase", "user", p.userId(), details);
            applied++;
        }
        jdbc.sql("""
                update salary_increase set status = 'APPROVED', decided_by = ?, decided_at = now(), arrears_month = ?,
                       summary = cast(? as jsonb) where id = ?""")
                .params(CurrentUser.email(), arrearsMonth == null ? null : arrearsMonth.atDay(1),
                        json.writeValueAsString(r.totals()), id).update();
        audit.record("salary_increase.approved", "salary_increase", id, Map.of("name", s.name(), "people", applied));
        return get(id);
    }

    /** Puts everything back as it was, while no payroll from the start (or the back pay month) is approved. */
    @org.springframework.transaction.annotation.Transactional
    public Scenario undo(long id) {
        Scenario s = get(id);
        if (!s.status().equals("APPROVED")) {
            throw new ConflictException(Texts.t("Only an approved increase can be undone."));
        }
        if (!s.canUndo()) {
            throw new ConflictException(Texts.t("A payroll using this increase is already approved: it can no longer be undone."));
        }
        record Change(long id, String kind, Long userId, long refId, BigDecimal oldAmount, BigDecimal newAmount, boolean applied) {}
        List<Change> changes = jdbc.sql("""
                select id, kind, user_id, ref_id, old_amount, new_amount, applied from salary_increase_change
                where increase_id = ? order by id desc""").param(id)
                .query((rs, n) -> new Change(rs.getLong(1), rs.getString(2), (Long) rs.getObject(3, Long.class),
                        rs.getLong(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBoolean(7))).list();
        for (Change c : changes) {
            switch (c.kind()) {
                case "ARREARS" -> {
                    if (c.oldAmount() == null) {
                        jdbc.sql("delete from pay_entry where id = ?").param(c.refId()).update();
                    } else {
                        jdbc.sql("update pay_entry set amount = amount - ? where id = ?")
                                .params(c.newAmount().subtract(c.oldAmount()), c.refId()).update();
                    }
                }
                case "SALARY_NEW" -> jdbc.sql("delete from employee_salary where id = ?").param(c.refId()).update();
                case "SALARY_RAISED" -> jdbc.sql("update employee_salary set gross_monthly = gross_monthly - ? where id = ?")
                        .params(c.newAmount().subtract(c.oldAmount()), c.refId()).update();
                case "GRADE" -> {
                    if (c.applied()) {
                        jdbc.sql("update grade set base_salary = base_salary - ? where id = ?")
                                .params(c.newAmount().subtract(c.oldAmount()), c.refId()).update();
                    }
                }
                default -> throw new IllegalStateException(c.kind());
            }
        }
        jdbc.sql("update salary_increase set status = 'UNDONE', decision_note = left(coalesce(decision_note || ' · ', '') || ?, 300) where id = ?")
                .params(Texts.t("Undone by %s", CurrentUser.email()), id).update();
        audit.record("salary_increase.undone", "salary_increase", id, Map.of("name", s.name(), "changes", changes.size()));
        return get(id);
    }

    private void change(long increaseId, String kind, Long userId, long refId, BigDecimal oldAmount, BigDecimal newAmount,
                        String currency, boolean applied) {
        jdbc.sql("""
                insert into salary_increase_change (increase_id, kind, user_id, ref_id, old_amount, new_amount, currency, applied)
                values (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(increaseId, kind, userId, refId, oldAmount, newAmount, currency, applied).update();
    }

    /** The grade bases of increases approved ahead of their start month change on that month. */
    public int applyDueGrades(LocalDate today) {
        record Due(long changeId, long gradeId, BigDecimal raise) {}
        List<Due> due = jdbc.sql("""
                select c.id, c.ref_id, c.new_amount - c.old_amount from salary_increase_change c
                join salary_increase i on i.id = c.increase_id
                where c.kind = 'GRADE' and not c.applied and i.status = 'APPROVED' and i.start_month <= ?""")
                .param(today).query((rs, n) -> new Due(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3))).list();
        for (Due d : due) {
            jdbc.sql("update grade set base_salary = base_salary + ? where id = ?").params(d.raise(), d.gradeId()).update();
            jdbc.sql("update salary_increase_change set applied = true where id = ?").param(d.changeId()).update();
        }
        return due.size();
    }

    @Scheduled(cron = "${app.raises.cron:0 30 6 * * *}", zone = "Africa/Kinshasa")
    public void daily() {
        locks.runForEachCompany("general-increase-grades", java.time.Duration.ofMinutes(10), () -> {
            int n = applyDueGrades(today());
            if (n > 0) {
                log.info("General increase: {} grade bases raised", n);
            }
        });
    }
}
