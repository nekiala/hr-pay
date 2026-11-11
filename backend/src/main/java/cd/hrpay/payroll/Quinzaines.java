package cd.hrpay.payroll;

import cd.hrpay.attendance.AttendanceDays;
import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.holiday.HolidayService;
import cd.hrpay.i18n.Texts;
import cd.hrpay.org.OrgStructure;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The quinzaine: an optional advance on the month's pay (acompte de quinzaine), paid around the 15th when the company
 * turns it on, and taken back in full on the month's payslip. Like the month-end payroll, HR works it out, Finance
 * approves it (the amounts then go onto the month's payslips), HR closes it (receipts are numbered and the payment files
 * can be made on the Payments page), and it is paid once every payment file is paid.
 *
 * <p>The amount is the grade category's rate (10% to 50%) of a normal month's net pay or of the basic salary (company
 * setting), times the share of the time from the 1st to the pay day that is paid, never more than the net already earned
 * on the pay day, rounded down to a whole unit. So the month's payslip can always take it back, even from someone who
 * stops working after the pay day; it is taken before loan installments, and what a payslip still cannot take back is
 * carried to the next month as an advance when the month closes.
 *
 * <p>Left out: employees who asked not to receive it, those HR leaves out for the month, a category without a rate, unpaid
 * leave on the pay day, a departure in the month, and loans and advances owed above QUINZAINE_MAX_DEBT_MONTHS of net pay.
 */
@Service
public class Quinzaines {

    public static final String ENABLED = "QUINZAINE_ENABLED";
    public static final String BASE = "QUINZAINE_BASE";
    public static final String DAY = "QUINZAINE_DAY";
    public static final String MAX_DEBT_MONTHS = "QUINZAINE_MAX_DEBT_MONTHS";
    public static final String ELEMENT = "QUINZAINE";
    public static final BigDecimal MIN_PERCENT = BigDecimal.TEN;
    public static final BigDecimal MAX_PERCENT = BigDecimal.valueOf(50);

    public record Settings(boolean enabled, String base, int day, BigDecimal maxDebtMonths) {}

    public record Category(long id, String code, String name, BigDecimal quinzainePercent) {}

    public record Line(long id, long userId, String employeeName, String registrationNo, String number, String currency,
                       String category, BigDecimal percent, BigDecimal baseAmount, BigDecimal normalNet,
                       BigDecimal workedShare, BigDecimal earnedNet, BigDecimal amount, List<String> explanation,
                       boolean paid) {}

    public record Total(String currency, BigDecimal amount, int employees) {}

    public record Run(long id, LocalDate month, LocalDate payDate, String base, String status, List<String> skipped,
                      String calculatedBy, OffsetDateTime calculatedAt, String approvedBy, OffsetDateTime approvedAt,
                      String closedBy, OffsetDateTime closedAt, String cancelNote, List<Total> totals, List<Line> lines) {}

    public record Employee(long userId, String employeeName, String registrationNo, String category,
                           BigDecimal percent, boolean refused, String reason, String recordedBy,
                           OffsetDateTime recordedAt) {}

    public record Exclusion(long id, LocalDate month, long userId, String employeeName, String reason, String recordedBy,
                            OffsetDateTime recordedAt) {}

    public record MyLine(long id, LocalDate month, LocalDate payDate, String status, String number, String currency,
                         BigDecimal amount) {}

    public record Mine(boolean enabled, int day, boolean refused, List<MyLine> payments) {}

    private record RunRow(long id, LocalDate month, LocalDate payDate, String base, String status, String skipped,
                          String calculatedBy, OffsetDateTime calculatedAt, String approvedBy, OffsetDateTime approvedAt,
                          String closedBy, OffsetDateTime closedAt, String cancelNote) {}

    private record LineRow(long id, long userId, String employeeName, String registrationNo, String number,
                           String currency, String category, BigDecimal percent, BigDecimal baseAmount,
                           BigDecimal normalNet, BigDecimal workedShare, BigDecimal earnedNet, BigDecimal amount,
                           String explanation, boolean paid) {}

    private static final String RUN_SELECT = """
            select id, month, pay_date, base, status, skipped::text as skipped, calculated_by, calculated_at, approved_by,
                   approved_at, closed_by, closed_at, cancel_note
            from quinzaine_run""";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final JdbcClient jdbc;
    private final NegotiatedPay negotiated;
    private final AuditService audit;
    private final UserService users;
    private final AttendanceDays attendance;
    private final HolidayService holidays;
    private final ExchangeRates exchange;
    private final PayrollRuns runs;
    private final OrgStructure org;
    private final JsonMapper json = JsonMapper.builder().build();

    public Quinzaines(JdbcClient jdbc, NegotiatedPay negotiated, AuditService audit, UserService users,
                      AttendanceDays attendance, HolidayService holidays, ExchangeRates exchange, PayrollRuns runs,
                      OrgStructure org) {
        this.jdbc = jdbc;
        this.negotiated = negotiated;
        this.audit = audit;
        this.users = users;
        this.attendance = attendance;
        this.holidays = holidays;
        this.exchange = exchange;
        this.runs = runs;
        this.org = org;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal v) {
        return money(v).toPlainString();
    }

    // ---- settings -----------------------------------------------------------------------------------------------

    private String setting(String key, String fallback) {
        return jdbc.sql("select value from payroll_setting where key = ?").param(key).query(String.class).optional()
                .orElse(fallback);
    }

    public Settings settings() {
        return new Settings(Boolean.parseBoolean(setting(ENABLED, "false")), setting(BASE, "NET"),
                Integer.parseInt(setting(DAY, "15")), new BigDecimal(setting(MAX_DEBT_MONTHS, "3")));
    }

    public List<Category> categories() {
        return jdbc.sql("select id, code, name, quinzaine_percent from grade_category where active order by code")
                .query(Category.class).list();
    }

    /** The category's rate, from 10% to 50%, or none: the category does not get the quinzaine. */
    @Transactional
    public List<Category> setCategoryPercent(long categoryId, BigDecimal percent) {
        if (percent != null && (percent.compareTo(MIN_PERCENT) < 0 || percent.compareTo(MAX_PERCENT) > 0)) {
            throw new BusinessRuleException("The quinzaine rate must be between 10% and 50%.");
        }
        if (jdbc.sql("update grade_category set quinzaine_percent = ? where id = ?").params(percent, categoryId).update() == 0) {
            throw new NotFoundException("Grade category not found.");
        }
        audit.record("grade_category.quinzaine_percent", "grade_category", categoryId,
                percent == null ? Map.of() : Map.of("percent", percent.toPlainString()));
        return categories();
    }

    /** One person's quinzaine line worked out, or why they get none. */
    public record Assessed(String skipped, String currency, String category, BigDecimal percent, BigDecimal base,
                           BigDecimal normalNet, BigDecimal workedShare, BigDecimal earnedNet, BigDecimal amount,
                           LocalDate payDate, List<String> explanation) {
        static Assessed skip(String why) {
            return new Assessed(why, null, null, null, null, null, null, null, null, null, List.of());
        }
    }

    private Assessed assess(Candidate c, Settings s, YearMonth month, LocalDate payDate, BigDecimal[] usd) {
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        String who = c.employeeName();
        if (refused(c.userId())) {
            return Assessed.skip(Texts.t("%s: asked not to receive the quinzaine.", who));
        }
        String excluded = jdbc.sql("select reason from quinzaine_exclusion where month = ? and user_id = ?")
                .params(first, c.userId()).query(String.class).optional().orElse(null);
        if (excluded != null) {
            return Assessed.skip(Texts.t("%s: left out by HR for this month (%s).", who, excluded));
        }
        if (c.percent() == null) {
            return Assessed.skip(Texts.t("%s: the category %s has no quinzaine rate.", who, c.category()));
        }
        if (jdbc.sql("select count(*) from departure where user_id = ? and status = 'CONFIRMED' and last_day <= ?")
                .params(c.userId(), last).query(Integer.class).single() > 0) {
            return Assessed.skip(Texts.t("%s: leaves this month; the final settlement pays the month.", who));
        }
        if (jdbc.sql("""
                select count(*) from leave_request r join leave_type t on t.id = r.leave_type_id
                where r.user_id = ? and r.status = 'APPROVED' and not t.paid and r.start_date <= ? and r.end_date >= ?""")
                .params(c.userId(), payDate, payDate).query(Integer.class).single() > 0) {
            return Assessed.skip(Texts.t("%s: on unpaid leave on the pay day.", who));
        }
        PayslipCalculator.Result normal;
        try {
            normal = negotiated.normalMonth(c.userId(), payDate);
        } catch (BusinessRuleException e) {
            return Assessed.skip(who + ": " + Texts.translate(e.getMessage()));
        }
        String cur = normal.currency();
        BigDecimal net = money(normal.net());
        if (net.signum() <= 0) {
            return Assessed.skip(Texts.t("%s: no net pay in a normal month.", who));
        }
        List<String> why = new ArrayList<>();

        // Loans and advances owed.
        List<Owed> owed = jdbc.sql("""
                select trim(l.currency) as currency, sum(l.principal - coalesce((select sum(r.amount) from loan_repayment r
                       where r.loan_id = l.id), 0)) as balance
                from loan l where l.user_id = ? and l.status = 'ACTIVE' group by l.currency""")
                .param(c.userId()).query(Owed.class).list();
        BigDecimal debt = BigDecimal.ZERO;
        for (Owed o : owed) {
            if (!o.currency().equals(cur) && usd[0] == null) {
                usd[0] = exchange.local(runs.localCurrency(), payDate.isAfter(LocalDate.now()) ? LocalDate.now() : payDate).cdfPerUnit();
            }
            debt = debt.add(ExchangeRates.convert(o.balance(), o.currency(), cur, usd[0]));
        }
        debt = money(debt.max(BigDecimal.ZERO));
        BigDecimal debtCap = money(net.multiply(s.maxDebtMonths()));
        if (debt.compareTo(debtCap) > 0) {
            return Assessed.skip(Texts.t("%s: owes %s %s in loans and advances, more than %s months of net pay.", who,
                    debt.toPlainString(), cur, s.maxDebtMonths().stripTrailingZeros().toPlainString()));
        }

        // Time paid from the 1st to the pay day (days still to come count as worked).
        AppUser user = users.find(c.userId());
        AttendanceDays.MonthSummary summary = attendance.month(user, month, false);
        int scheduledHalf = 0;
        int paidHalf = 0;
        for (var d : summary.days()) {
            if (d.date().isAfter(payDate)) {
                continue;
            }
            scheduledHalf += d.scheduledMinutes();
            if (!d.date().isBefore(c.firstDay())) {
                paidHalf += d.date().isAfter(summary.asOf()) ? d.scheduledMinutes() : d.payableMinutes();
            }
        }
        int scheduledMonth = summary.scheduledMinutesMonth();
        if (scheduledHalf <= 0 || scheduledMonth <= 0) {
            return Assessed.skip(Texts.t("%s: no scheduled time before the pay day.", who));
        }
        BigDecimal share = BigDecimal.valueOf(paidHalf).divide(BigDecimal.valueOf(scheduledHalf), 4, RoundingMode.HALF_UP)
                .min(BigDecimal.ONE);
        BigDecimal earned = money(net.multiply(BigDecimal.valueOf(paidHalf))
                .divide(BigDecimal.valueOf(scheduledMonth), 6, RoundingMode.HALF_UP));

        BigDecimal base = s.base().equals("BASIC")
                ? money(normal.lines().stream().filter(l -> l.code().equals("BASIC")).map(PayslipCalculator.Line::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                : net;
        BigDecimal byRate = base.multiply(c.percent()).divide(HUNDRED, 6, RoundingMode.HALF_UP).multiply(share);
        BigDecimal amount = byRate.min(earned).setScale(0, RoundingMode.DOWN);
        why.add(Texts.t(s.base().equals("BASIC") ? "Basic salary of a normal month: %s %s." : "Net pay of a normal month: %s %s.",
                base.toPlainString(), cur));
        why.add(Texts.t("Rate of the category %s: %s%%.", c.category(), c.percent().stripTrailingZeros().toPlainString()));
        if (share.compareTo(BigDecimal.ONE) < 0) {
            why.add(Texts.t("Paid time from the 1st to the pay day: %s%% (unpaid absence, unpaid leave or hire after the 1st).",
                    share.multiply(HUNDRED).setScale(0, RoundingMode.HALF_UP).toPlainString()));
        }
        if (byRate.compareTo(earned) > 0) {
            why.add(Texts.t("Limited to the net already earned on the pay day: %s %s.", earned.toPlainString(), cur));
        }
        if (debt.signum() > 0) {
            why.add(Texts.t("Loans and advances owed: %s %s (limit %s).", debt.toPlainString(), cur, debtCap.toPlainString()));
        }
        why.add(Texts.t("Rounded down to a whole unit: %s %s, taken back on the payslip of the month.", plain(amount), cur));
        if (amount.signum() <= 0) {
            return Assessed.skip(Texts.t("%s: nothing to pay this mid-month.", who));
        }
        return new Assessed(null, cur, c.category(), c.percent(), base, net, share, earned, money(amount), payDate, why);
    }

    /**
     * One person's quinzaine of a month as it would be worked out today (a simulation): nothing is stored. The company's
     * switch is not checked, so HR can see what it would pay before turning it on.
     */
    public Assessed simulate(long userId, YearMonth month) {
        Settings s = settings();
        LocalDate first = month.atDay(1);
        LocalDate payDate = payDate(month);
        Candidate c = jdbc.sql("""
                select u.id as user_id, person_name(u.first_name, u.middle_name, u.last_name) as employee_name,
                       greatest((select min(c2.start_date) from employee_contract c2 where c2.user_id = u.id
                                 and (c2.end_date is null or c2.end_date >= ?)), ?) as first_day,
                       k.code as category, k.quinzaine_percent as percent
                from app_user u
                join lateral (select c.grade_id from employee_contract c where c.user_id = u.id and c.start_date <= ?
                              and (c.end_date is null or c.end_date >= ?) order by c.start_date desc limit 1) c on true
                join grade g on g.id = c.grade_id join grade_category k on k.id = g.category_id
                where u.id = ?""").params(first, first, payDate, payDate, userId).query(Candidate.class).optional().orElse(null);
        if (c == null) {
            return Assessed.skip(Texts.t("No contract on the pay day (%s).", Texts.day(payDate)));
        }
        return assess(c, s, month, payDate, new BigDecimal[1]);
    }

    /** The day the quinzaine of a month is paid: the setting's day, or the working day before (not a weekend or holiday). */
    public LocalDate payDate(YearMonth month) {
        LocalDate day = month.atDay(Math.min(settings().day(), month.lengthOfMonth()));
        String country = jdbc.sql("select country_code from company where id = current_company_id()").query(String.class)
                .optional().orElse("CD");
        Set<LocalDate> off = holidays.observedDates(country, month.atDay(1), day);
        LocalDate d = day;
        while (d.isAfter(month.atDay(1)) && (d.getDayOfWeek() == DayOfWeek.SATURDAY
                || d.getDayOfWeek() == DayOfWeek.SUNDAY || off.contains(d))) {
            d = d.minusDays(1);
        }
        return d;
    }

    // ---- who takes it -------------------------------------------------------------------------------------------

    public boolean refused(long userId) {
        return jdbc.sql("select count(*) from quinzaine_refusal where user_id = ?").param(userId).query(Integer.class)
                .single() > 0;
    }

    /** Only the employees who asked not to receive it (a company may have thousands who do), with their current category. */
    public List<Employee> employees() {
        return jdbc.sql("""
                select u.id as user_id, person_name(u.first_name, u.middle_name, u.last_name) as employee_name, u.registration_no,
                       k.code as category, k.quinzaine_percent as percent,
                       true as refused, r.reason, r.recorded_by, r.recorded_at
                from quinzaine_refusal r
                join app_user u on u.id = r.user_id
                left join lateral (select c.grade_id from employee_contract c where c.user_id = u.id and c.start_date <= current_date
                              and (c.end_date is null or c.end_date >= current_date) order by c.start_date desc limit 1) c on true
                left join grade g on g.id = c.grade_id left join grade_category k on k.id = g.category_id
                where u.status = 'ACTIVE'
                order by u.last_name, u.first_name, u.id""").query(Employee.class).list();
    }

    /**
     * Records the employee's request not to receive the quinzaine, or withdraws it. It applies from the next quinzaine
     * not yet approved: one worked out and not approved loses the person's line; one approved or paid stays.
     */
    @Transactional
    public void setRefused(long userId, boolean refuse, String reason) {
        boolean was = refused(userId);
        if (refuse && !was) {
            jdbc.sql("insert into quinzaine_refusal (user_id, reason, recorded_by) values (?, ?, ?)")
                    .params(userId, reason == null || reason.isBlank() ? null : reason.trim(), CurrentUser.email()).update();
            jdbc.sql("""
                    delete from quinzaine_line l using quinzaine_run r
                    where r.id = l.run_id and r.status = 'CALCULATED' and l.user_id = ?""").param(userId).update();
            audit.record("quinzaine.refused", "user", userId, reason == null ? Map.of() : Map.of("reason", reason));
        } else if (!refuse && was) {
            jdbc.sql("delete from quinzaine_refusal where user_id = ?").param(userId).update();
            audit.record("quinzaine.accepted", "user", userId, Map.of());
        }
    }

    public List<Exclusion> exclusions(YearMonth month) {
        return jdbc.sql("""
                select x.id, x.month, x.user_id, person_name(u.first_name, u.middle_name, u.last_name) as employee_name, x.reason, x.recorded_by,
                       x.recorded_at
                from quinzaine_exclusion x join app_user u on u.id = x.user_id where x.month = ? order by u.last_name""")
                .param(month.atDay(1)).query(Exclusion.class).list();
    }

    private void requireNotApproved(YearMonth month) {
        jdbc.sql("select status from quinzaine_run where month = ? and status <> 'CANCELLED'").param(month.atDay(1))
                .query(String.class).optional().filter(s -> !s.equals("CALCULATED")).ifPresent(s -> {
                    throw new BusinessRuleException("The quinzaine of this month is already approved.");
                });
    }

    /** HR leaves one person out of one month's quinzaine, with a reason, before Finance approves it. */
    @Transactional
    public List<Exclusion> exclude(YearMonth month, long userId, String reason) {
        users.find(userId);
        requireNotApproved(month);
        jdbc.sql("""
                insert into quinzaine_exclusion (month, user_id, reason, recorded_by) values (?, ?, ?, ?)
                on conflict (company_id, month, user_id) do update set reason = excluded.reason, recorded_by = excluded.recorded_by,
                    recorded_at = now()""").params(month.atDay(1), userId, reason.trim(), CurrentUser.email()).update();
        jdbc.sql("""
                delete from quinzaine_line l using quinzaine_run r
                where r.id = l.run_id and r.status = 'CALCULATED' and r.month = ? and l.user_id = ?""")
                .params(month.atDay(1), userId).update();
        audit.record("quinzaine.excluded", "user", userId, Map.of("month", month.toString(), "reason", reason.trim()));
        return exclusions(month);
    }

    @Transactional
    public List<Exclusion> include(YearMonth month, long userId) {
        requireNotApproved(month);
        jdbc.sql("delete from quinzaine_exclusion where month = ? and user_id = ?").params(month.atDay(1), userId).update();
        audit.record("quinzaine.included", "user", userId, Map.of("month", month.toString()));
        return exclusions(month);
    }

    public Mine mine(long userId) {
        Settings s = settings();
        List<MyLine> payments = jdbc.sql("""
                select l.id, r.month, r.pay_date, r.status, l.number, trim(l.currency) as currency, l.amount
                from quinzaine_line l join quinzaine_run r on r.id = l.run_id
                where l.user_id = ? and r.status in ('APPROVED', 'CLOSED', 'PAID')
                order by r.month desc limit 24""").param(userId).query(MyLine.class).list();
        return new Mine(s.enabled(), s.day(), refused(userId), payments);
    }

    // ---- the runs -----------------------------------------------------------------------------------------------

    public List<Run> runs() {
        return jdbc.sql(RUN_SELECT + " where status <> 'CANCELLED' or cancelled_at > now() - interval '30 days' order by month desc, id desc limit 24")
                .query(RunRow.class).list().stream().map(this::view).toList();
    }

    public Run find(long id) {
        return jdbc.sql(RUN_SELECT + " where id = ?").param(id).query(RunRow.class).optional().map(this::view)
                .orElseThrow(() -> new NotFoundException("Quinzaine not found."));
    }

    private List<String> strings(String text) {
        return text == null ? List.of() : List.of(json.readValue(text, String[].class));
    }

    private Run view(RunRow r) {
        List<Line> lines = jdbc.sql("""
                select l.id, l.user_id, person_name(u.first_name, u.middle_name, u.last_name) as employee_name, u.registration_no, l.number,
                       trim(l.currency) as currency, l.category, l.percent, l.base_amount, l.normal_net, l.worked_share,
                       l.earned_net, l.amount, l.explanation::text as explanation,
                       exists (select 1 from payment_line p join payment_batch b on b.id = p.batch_id
                               where p.quinzaine_line_id = l.id and b.status = 'PAID') as paid
                from quinzaine_line l join app_user u on u.id = l.user_id
                where l.run_id = ? order by u.last_name, u.first_name, u.id""").param(r.id()).query(LineRow.class).list()
                .stream().map(l -> new Line(l.id(), l.userId(), l.employeeName(), l.registrationNo(), l.number(),
                        l.currency(), l.category(), l.percent(), l.baseAmount(), l.normalNet(), l.workedShare(),
                        l.earnedNet(), l.amount(), strings(l.explanation()), l.paid()))
                .toList();
        Map<String, Total> totals = new java.util.TreeMap<>();
        for (Line l : lines) {
            totals.merge(l.currency(), new Total(l.currency(), l.amount(), 1),
                    (a, b) -> new Total(a.currency(), a.amount().add(b.amount()), a.employees() + 1));
        }
        return new Run(r.id(), r.month(), r.payDate(), r.base(), r.status(), strings(r.skipped()), r.calculatedBy(),
                r.calculatedAt(), r.approvedBy(), r.approvedAt(), r.closedBy(), r.closedAt(), r.cancelNote(),
                List.copyOf(totals.values()), lines);
    }

    private record Candidate(long userId, String employeeName, LocalDate firstDay, String category, BigDecimal percent) {}

    private record Owed(String currency, BigDecimal balance) {}

    /** Works out (or again, while not approved) the quinzaine of a month for everyone who can take it. */
    @Transactional
    public Run calculate(YearMonth month) {
        Settings s = settings();
        if (!s.enabled()) {
            throw new BusinessRuleException("The quinzaine is turned off for this company.");
        }
        PayrollRuns.requireOpen(jdbc, month);
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        LocalDate payDate = payDate(month);
        Long existing = jdbc.sql("select id from quinzaine_run where month = ? and status <> 'CANCELLED'").param(first)
                .query(Long.class).optional().orElse(null);
        long runId;
        if (existing != null) {
            if (!find(existing).status().equals("CALCULATED")) {
                throw new BusinessRuleException("The quinzaine of this month is already approved.");
            }
            jdbc.sql("delete from quinzaine_line where run_id = ?").param(existing).update();
            jdbc.sql("update quinzaine_run set pay_date = ?, base = ?, calculated_by = ?, calculated_at = now() where id = ?")
                    .params(payDate, s.base(), CurrentUser.email(), existing).update();
            runId = existing;
        } else {
            runId = jdbc.sql("insert into quinzaine_run (month, pay_date, base, calculated_by) values (?, ?, ?, ?) returning id")
                    .params(first, payDate, s.base(), CurrentUser.email()).query(Long.class).single();
        }

        List<String> skipped = new ArrayList<>();
        // Under contract on the pay day (its grade gives the category), and active.
        List<Candidate> people = jdbc.sql("""
                select u.id as user_id, person_name(u.first_name, u.middle_name, u.last_name) as employee_name,
                       greatest((select min(c2.start_date) from employee_contract c2 where c2.user_id = u.id
                                 and (c2.end_date is null or c2.end_date >= ?)), ?) as first_day,
                       k.code as category, k.quinzaine_percent as percent
                from app_user u
                join lateral (select c.grade_id from employee_contract c where c.user_id = u.id and c.start_date <= ?
                              and (c.end_date is null or c.end_date >= ?) order by c.start_date desc limit 1) c on true
                join grade g on g.id = c.grade_id join grade_category k on k.id = g.category_id
                where u.status = 'ACTIVE'
                order by u.last_name, u.first_name, u.id""")
                .params(first, first, payDate, payDate).query(Candidate.class).list();
        Map<Long, OrgStructure.Placement> places = org.placementsOn(payDate);
        BigDecimal[] usd = new BigDecimal[1];
        int done = 0;
        for (Candidate c : people) {
            Assessed a = assess(c, s, month, payDate, usd);
            if (a.skipped() != null) {
                skipped.add(a.skipped());
                continue;
            }
            OrgStructure.Placement place = places.get(c.userId());
            jdbc.sql("""
                    insert into quinzaine_line (run_id, user_id, currency, category, percent, base_amount, normal_net, worked_share,
                                                earned_net, amount, unit_id, explanation)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)""")
                    .params(runId, c.userId(), a.currency(), c.category(), c.percent(), a.base(), a.normalNet(), a.workedShare(),
                            a.earnedNet(), a.amount(), place == null ? null : place.unitId(), json.writeValueAsString(a.explanation())).update();
            done++;
        }
        jdbc.sql("update quinzaine_run set skipped = ?::jsonb where id = ?").params(json.writeValueAsString(skipped), runId).update();
        audit.record("quinzaine.calculated", "quinzaine_run", runId, Map.of("month", month.toString(),
                "employees", done, "skipped", skipped.size()));
        return find(runId);
    }

    /** Finance approves the quinzaine (not the person who worked it out): each amount goes onto the month's payslip. */
    @Transactional
    public Run approve(long id) {
        Run r = find(id);
        if (!r.status().equals("CALCULATED")) {
            throw new BusinessRuleException("Only a calculated quinzaine can be approved.");
        }
        if (r.calculatedBy().equalsIgnoreCase(CurrentUser.email())) {
            throw new BusinessRuleException("Someone other than the person who worked it out must approve it.");
        }
        if (r.lines().isEmpty()) {
            throw new BusinessRuleException("Nobody to pay in this quinzaine.");
        }
        PayrollRuns.requireOpen(jdbc, YearMonth.from(r.month()));
        jdbc.sql("update quinzaine_run set status = 'APPROVED', approved_by = ?, approved_at = now() where id = ?")
                .params(CurrentUser.email(), id).update();
        sync(r, true);
        audit.record("quinzaine.approved", "quinzaine_run", id, Map.of("month", r.month().toString(),
                "employees", r.lines().size()));
        return find(id);
    }

    /** HR closes the approved quinzaine: each receipt gets its number and the payment files can be made. */
    @Transactional
    public Run close(long id) {
        Run r = find(id);
        if (!r.status().equals("APPROVED")) {
            throw new BusinessRuleException("Only an approved quinzaine can be closed.");
        }
        String month = YearMonth.from(r.month()).toString();
        int n = 0;
        for (Line l : r.lines()) {
            jdbc.sql("update quinzaine_line set number = ? where id = ?").params("QZ-%s-%04d".formatted(month, ++n), l.id()).update();
        }
        jdbc.sql("update quinzaine_run set status = 'CLOSED', closed_by = ?, closed_at = now() where id = ?")
                .params(CurrentUser.email(), id).update();
        audit.record("quinzaine.closed", "quinzaine_run", id, Map.of("month", month, "receipts", n));
        return find(id);
    }

    /** Takes back a quinzaine not yet closed, and its amounts from the payslips. */
    @Transactional
    public Run cancel(long id, String note) {
        Run r = find(id);
        if (!r.status().equals("CALCULATED") && !r.status().equals("APPROVED")) {
            throw new BusinessRuleException("Only a quinzaine not yet closed can be cancelled.");
        }
        if (r.status().equals("APPROVED")) {
            PayrollRuns.requireOpen(jdbc, YearMonth.from(r.month()));
            sync(r, false);
        }
        jdbc.sql("update quinzaine_run set status = 'CANCELLED', cancelled_by = ?, cancelled_at = now(), cancel_note = ? where id = ?")
                .params(CurrentUser.email(), note == null || note.isBlank() ? null : note.trim(), id).update();
        audit.record("quinzaine.cancelled", "quinzaine_run", id, note == null ? Map.of() : Map.of("note", note));
        return find(id);
    }

    /** Writes (or removes) the QUINZAINE deduction of each person on the month's payslip. */
    private void sync(Run r, boolean write) {
        long element = jdbc.sql("select id from pay_element where code = ?").param(ELEMENT).query(Long.class).optional()
                .orElseThrow(() -> new BusinessRuleException("The pay element QUINZAINE is missing."));
        String note = Texts.t("paid on %s", Texts.day(r.payDate()));
        for (Line l : r.lines()) {
            jdbc.sql("delete from pay_entry where user_id = ? and element_id = ? and month = ?")
                    .params(l.userId(), element, r.month()).update();
            if (write) {
                jdbc.sql("""
                        insert into pay_entry (user_id, element_id, month, amount, currency, note, created_by)
                        values (?, ?, ?, ?, ?, ?, ?)""")
                        .params(l.userId(), element, r.month(), l.amount(), l.currency(), note, CurrentUser.email()).update();
            }
        }
    }

    /** The month-end payroll cannot close while that month's quinzaine is worked out or approved but not closed. */
    static void requireSettled(JdbcClient jdbc, YearMonth month) {
        jdbc.sql("select status from quinzaine_run where month = ? and status in ('CALCULATED', 'APPROVED')")
                .param(month.atDay(1)).query(String.class).optional().ifPresent(s -> {
                    throw new BusinessRuleException("Close or cancel the quinzaine of this month before closing the payroll.");
                });
    }

    /** The line of a receipt, for its PDF; the employee sees their own once the quinzaine is closed. */
    public Line line(long lineId) {
        Long runId = jdbc.sql("select run_id from quinzaine_line where id = ?").param(lineId).query(Long.class).optional()
                .orElseThrow(() -> new NotFoundException("Quinzaine not found."));
        return find(runId).lines().stream().filter(l -> l.id() == lineId).findFirst().orElseThrow();
    }

    public Run runOfLine(long lineId) {
        return find(jdbc.sql("select run_id from quinzaine_line where id = ?").param(lineId).query(Long.class).single());
    }
}
