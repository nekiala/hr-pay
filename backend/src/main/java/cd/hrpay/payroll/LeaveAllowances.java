package cd.hrpay.payroll;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.leave.LeaveService;
import cd.hrpay.salary.SalaryService;
import cd.hrpay.schedule.WorkCalendar;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The leave allowance (allocation de congé, the "pécule") of approved annual leave, when the company pays annual
 * leave. HR works it out on request (a leave pay run), Finance approves it, and it is paid before the person leaves.
 *
 * <p>DRC, art. 142 and 145 of the Labour Code: the allowance is the pay the person has when the leave starts, plus the
 * average of the commissions, bonuses and overtime of the 12 months before; it is paid at the latest on the last
 * working day before the leave. The payslip of the leave month already pays the leave days as salary, so the
 * allowance paid in advance is taken back on it (LEAVE_ADVANCE), and the average of bonuses and overtime is added
 * to it as an earning (LEAVE_ALLOWANCE), where CNSS and income tax apply.
 */
@Service
public class LeaveAllowances {

    /** Approved annual leave that has no leave allowance yet. */
    public record Due(long leaveRequestId, long userId, String employeeName, String registrationNo, LocalDate startDate,
                      LocalDate endDate, BigDecimal workingDays, LocalDate payBy) {}

    public record Month(LocalDate month, BigDecimal days, BigDecimal variableAmount, BigDecimal advanceAmount) {}

    public record Allowance(long id, long leaveRequestId, long userId, String employeeName, String registrationNo,
                            LocalDate startDate, LocalDate endDate, String currency, BigDecimal days,
                            BigDecimal monthlySalary, BigDecimal daysPerMonth, BigDecimal dailyRate,
                            BigDecimal salaryPart, BigDecimal variable12m, int monthsCounted, BigDecimal variablePart,
                            BigDecimal gross, BigDecimal netShare, BigDecimal advance, LocalDate payBy, String status,
                            List<String> explanation, String calculatedBy, OffsetDateTime calculatedAt,
                            String approvedBy, OffsetDateTime approvedAt, String paidBy, LocalDate paidOn,
                            String paymentReference, String cancelNote, List<Month> months) {}

    public record RunResult(List<Allowance> calculated, List<String> skipped) {}

    private record Row(long id, long leaveRequestId, long userId, String employeeName, String registrationNo,
                       LocalDate startDate, LocalDate endDate, String currency, BigDecimal days,
                       BigDecimal monthlySalary, BigDecimal daysPerMonth, BigDecimal dailyRate, BigDecimal salaryPart,
                       BigDecimal variable12m, int monthsCounted, BigDecimal variablePart, BigDecimal gross,
                       BigDecimal netShare, BigDecimal advance, LocalDate payBy, String status, String explanation,
                       String calculatedBy, OffsetDateTime calculatedAt, String approvedBy,
                       OffsetDateTime approvedAt, String paidBy, LocalDate paidOn, String paymentReference,
                       String cancelNote) {}

    private static final String SELECT = """
            select a.id, a.leave_request_id, a.user_id, u.first_name || ' ' || u.last_name as employee_name, u.registration_no,
                   r.start_date, r.end_date, trim(a.currency) as currency, a.days, a.monthly_salary, a.days_per_month,
                   a.daily_rate, a.salary_part, a.variable_12m as variable12m, a.months_counted, a.variable_part, a.gross, a.net_share,
                   a.advance, a.pay_by, a.status, a.explanation::text as explanation, a.calculated_by, a.calculated_at,
                   a.approved_by, a.approved_at, a.paid_by, a.paid_on, a.payment_reference, a.cancel_note
            from leave_allowance a join app_user u on u.id = a.user_id join leave_request r on r.id = a.leave_request_id""";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final JdbcClient jdbc;
    private final LeaveService leave;
    private final UserService users;
    private final SalaryService salaries;
    private final StatutoryRates statutory;
    private final WorkCalendar calendar;
    private final AuditService audit;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public LeaveAllowances(JdbcClient jdbc, LeaveService leave, UserService users, SalaryService salaries,
                           StatutoryRates statutory, WorkCalendar calendar, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.leave = leave;
        this.users = users;
        this.salaries = salaries;
        this.statutory = statutory;
        this.calendar = calendar;
        this.audit = audit;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneId.of("Africa/Kinshasa")));
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    // ---- what is due ------------------------------------------------------------------------------------------

    /** Approved annual leave, not yet ended more than 60 days ago, without a leave allowance. Empty when leave is unpaid. */
    public List<Due> due() {
        if (!leave.annualPaid()) {
            return List.of();
        }
        record R(long leaveRequestId, long userId, String employeeName, String registrationNo, LocalDate startDate,
                 LocalDate endDate, BigDecimal workingDays) {}
        return jdbc.sql("""
                select r.id as leave_request_id, r.user_id, u.first_name || ' ' || u.last_name as employee_name, u.registration_no,
                       r.start_date, r.end_date, r.working_days
                from leave_request r join leave_type t on t.id = r.leave_type_id join app_user u on u.id = r.user_id
                where t.code = 'ANNUAL' and r.status = 'APPROVED' and r.end_date >= ?
                  and not exists (select 1 from leave_allowance a where a.leave_request_id = r.id and a.status <> 'CANCELLED')
                order by r.start_date, u.last_name""").param(today().minusDays(60)).query(R.class).list().stream()
                .map(r -> new Due(r.leaveRequestId(), r.userId(), r.employeeName(), r.registrationNo(), r.startDate(),
                        r.endDate(), r.workingDays(), payBy(users.find(r.userId()), r.startDate())))
                .toList();
    }

    /** The last working day before the leave starts (DRC art. 145: the allowance is paid by then). */
    LocalDate payBy(AppUser user, LocalDate start) {
        for (LocalDate d = start.minusDays(1); d.isAfter(start.minusDays(15)); d = d.minusDays(1)) {
            if (calendar.plan(user, d).expectedAtWork()) {
                return d;
            }
        }
        return start.minusDays(1);
    }

    // ---- the leave pay run ------------------------------------------------------------------------------------

    /** Works out the allowance of the given approved annual leave, or of all that is due when none are given. */
    @Transactional
    public RunResult calculate(List<Long> leaveRequestIds) {
        if (!leave.annualPaid()) {
            throw new BusinessRuleException("Annual leave is not paid in this company (Leave settings).");
        }
        List<Due> due = due();
        List<Due> chosen = leaveRequestIds == null || leaveRequestIds.isEmpty() ? due
                : due.stream().filter(d -> leaveRequestIds.contains(d.leaveRequestId())).toList();
        List<Allowance> done = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Due d : chosen) {
            if (Objects.equals(d.userId(), CurrentUser.id())) {
                skipped.add(Texts.t("%s: you cannot work out your own leave pay.", d.employeeName()));
                continue;
            }
            try {
                done.add(calculateOne(d));
            } catch (BusinessRuleException e) {
                skipped.add(d.employeeName() + ": " + Texts.translate(e.getMessage()));
            }
        }
        audit.record("leave_allowance.calculated", "leave_allowance", null, Map.of("count", done.size(),
                "skipped", skipped.size()));
        return new RunResult(done, skipped);
    }

    private Allowance calculateOne(Due d) {
        AppUser user = users.find(d.userId());
        LocalDate start = d.startDate();
        var salary = salaries.agreed(d.userId(), start)
                .orElseThrow(() -> new BusinessRuleException("No basic salary recorded."));
        String cur = salary.currency();
        List<String> why = new ArrayList<>();
        BigDecimal perMonth = statutory.rate("SETTLEMENT_DAYS_PER_MONTH", start);
        BigDecimal daily = salary.grossMonthly().divide(perMonth, 4, RoundingMode.HALF_UP);
        BigDecimal days = d.workingDays();
        BigDecimal salaryPart = money(daily.multiply(days));
        why.add(Texts.t("Daily rate: monthly salary %s %s ÷ %s days = %s.", salary.grossMonthly().toPlainString(), cur,
                perMonth.stripTrailingZeros().toPlainString(), money(daily).toPlainString()));
        why.add(Texts.t("Salary for the leave: %s days × %s = %s.", days.stripTrailingZeros().toPlainString(),
                money(daily).toPlainString(), salaryPart.toPlainString()));

        // Commissions, bonuses and overtime paid on the closed payslips of the 12 months before the leave.
        YearMonth first = YearMonth.from(start).minusMonths(12);
        YearMonth last = YearMonth.from(start).minusMonths(1);
        record V(LocalDate month, String currency, BigDecimal usdCdf, BigDecimal amount) {}
        List<V> lines = jdbc.sql("""
                select r.month, trim(p.currency) as currency, r.usd_cdf, coalesce(sum(l.amount) filter (where e.id is not null), 0) as amount
                from payslip p join payroll_run r on r.id = p.run_id
                left join payslip_line l on l.payslip_id = p.id
                left join pay_element e on e.code = l.element_code and e.kind = 'EARNING' and (e.code = 'OVERTIME' or e.method = 'MANUAL')
                where p.user_id = ? and r.status = 'CLOSED' and p.kind = 'REGULAR' and r.month between ? and ?
                group by r.month, p.currency, r.usd_cdf""")
                .params(d.userId(), first.atDay(1), last.atDay(1)).query(V.class).list();
        BigDecimal variable = BigDecimal.ZERO;
        for (V v : lines) {
            variable = variable.add(ExchangeRates.convert(v.amount(), v.currency(), cur, v.usdCdf()));
        }
        variable = money(variable);
        int months = (int) lines.stream().map(V::month).distinct().count();
        BigDecimal variablePart = months == 0 ? BigDecimal.ZERO.setScale(2)
                : money(variable.divide(BigDecimal.valueOf(months), 6, RoundingMode.HALF_UP)
                        .divide(perMonth, 6, RoundingMode.HALF_UP).multiply(days));
        why.add(months == 0 ? Texts.t("No closed payslip in the 12 months before the leave: no average of bonuses and overtime.")
                : Texts.t("Bonuses, commissions and overtime of the last %s months: %s, a monthly average of %s; for %s days: %s.",
                        months, variable.toPlainString(),
                        money(variable.divide(BigDecimal.valueOf(months), 6, RoundingMode.HALF_UP)).toPlainString(),
                        days.stripTrailingZeros().toPlainString(), variablePart.toPlainString()));
        BigDecimal gross = salaryPart.add(variablePart);

        // Paid net: the share of the gross left after CNSS and income tax on the last closed payslip.
        BigDecimal netShare = jdbc.sql("""
                select case when p.gross > 0 then least(1, greatest(0, p.net / p.gross)) end from payslip p join payroll_run r on r.id = p.run_id
                where p.user_id = ? and r.status = 'CLOSED' and p.kind = 'REGULAR' order by r.month desc limit 1""")
                .param(d.userId()).query(BigDecimal.class).optional().orElse(null);
        if (netShare == null) {
            netShare = BigDecimal.ONE.subtract(statutory.rate("CNSS_EMPLOYEE", start).divide(HUNDRED, 6, RoundingMode.HALF_UP));
            why.add(Texts.t("No closed payslip yet: paid after the employee's CNSS share only (%s%% of the gross).",
                    netShare.multiply(HUNDRED).setScale(1, RoundingMode.HALF_UP).toPlainString()));
        } else {
            why.add(Texts.t("Paid net, at %s%% of the gross as on the last payslip; the payslip of the leave month settles the exact amount.",
                    netShare.multiply(HUNDRED).setScale(1, RoundingMode.HALF_UP).toPlainString()));
        }
        netShare = netShare.setScale(4, RoundingMode.HALF_UP);
        BigDecimal advance = money(gross.multiply(netShare));
        LocalDate payBy = d.payBy();
        why.add(Texts.t("Pay it by %s, the last working day before the leave.", Texts.day(payBy)));

        long id = jdbc.sql("""
                insert into leave_allowance (leave_request_id, user_id, currency, days, monthly_salary, days_per_month, daily_rate,
                    salary_part, variable_12m, months_counted, variable_part, gross, net_share, advance, pay_by, explanation, calculated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?) returning id""")
                .params(d.leaveRequestId(), d.userId(), cur, days, salary.grossMonthly(), perMonth, daily, salaryPart, variable,
                        months, variablePart, gross, netShare, advance, payBy, json.writeValueAsString(why), CurrentUser.email())
                .query(Long.class).single();

        // Split by the months the leave covers, in proportion to its working days in each.
        List<LocalDate[]> parts = new ArrayList<>();
        for (YearMonth m = YearMonth.from(start); !m.isAfter(YearMonth.from(d.endDate())); m = m.plusMonths(1)) {
            LocalDate from = start.isAfter(m.atDay(1)) ? start : m.atDay(1);
            LocalDate to = d.endDate().isBefore(m.atEndOfMonth()) ? d.endDate() : m.atEndOfMonth();
            parts.add(new LocalDate[] {from, to});
        }
        List<BigDecimal> inMonth = parts.stream().map(p -> parts.size() == 1 ? days : leave.workingDays(user, p[0], p[1])).toList();
        BigDecimal total = inMonth.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal variableLeft = variablePart;
        BigDecimal advanceLeft = advance;
        for (int i = 0; i < parts.size(); i++) {
            BigDecimal share = total.signum() == 0 ? BigDecimal.ONE.divide(BigDecimal.valueOf(parts.size()), 6, RoundingMode.HALF_UP)
                    : inMonth.get(i).divide(total, 6, RoundingMode.HALF_UP);
            boolean lastPart = i == parts.size() - 1;
            BigDecimal v = lastPart ? variableLeft : money(variablePart.multiply(share));
            BigDecimal a = lastPart ? advanceLeft : money(advance.multiply(share));
            variableLeft = variableLeft.subtract(v);
            advanceLeft = advanceLeft.subtract(a);
            jdbc.sql("insert into leave_allowance_month (allowance_id, month, days, variable_amount, advance_amount) values (?, ?, ?, ?, ?)")
                    .params(id, parts.get(i)[0].withDayOfMonth(1), inMonth.get(i), v, a).update();
        }
        return find(id);
    }

    // ---- list, approve, pay, cancel ---------------------------------------------------------------------------

    private List<Month> months(long id) {
        return jdbc.sql("select month, days, variable_amount, advance_amount from leave_allowance_month where allowance_id = ? order by month")
                .param(id).query(Month.class).list();
    }

    private Allowance view(Row r) {
        List<String> why = List.of(json.readValue(r.explanation(), String[].class));
        return new Allowance(r.id(), r.leaveRequestId(), r.userId(), r.employeeName(), r.registrationNo(), r.startDate(),
                r.endDate(), r.currency(), r.days(), r.monthlySalary(), r.daysPerMonth(), r.dailyRate(), r.salaryPart(),
                r.variable12m(), r.monthsCounted(), r.variablePart(), r.gross(), r.netShare(), r.advance(), r.payBy(),
                r.status(), why, r.calculatedBy(), r.calculatedAt(), r.approvedBy(), r.approvedAt(), r.paidBy(),
                r.paidOn(), r.paymentReference(), r.cancelNote(), months(r.id()));
    }

    public Allowance find(long id) {
        return jdbc.sql(SELECT + " where a.id = ?").param(id).query(Row.class).optional().map(this::view)
                .orElseThrow(() -> new NotFoundException("Leave allowance not found."));
    }

    public List<Allowance> list(String status) {
        return (status == null
                ? jdbc.sql(SELECT + " where a.status <> 'CANCELLED' or a.cancelled_at > now() - interval '30 days' order by r.start_date desc, a.id desc")
                : jdbc.sql(SELECT + " where a.status = ? order by r.start_date, a.id").param(status))
                .query(Row.class).list().stream().map(this::view).toList();
    }

    public List<Allowance> forUser(long userId) {
        return jdbc.sql(SELECT + " where a.user_id = ? and a.status <> 'CANCELLED' order by r.start_date desc").param(userId)
                .query(Row.class).list().stream().map(this::view).toList();
    }

    /** Finance approves the allowance (not the person who worked it out): it then goes into the payslips. */
    @Transactional
    public Allowance approve(long id) {
        Allowance a = find(id);
        if (!a.status().equals("CALCULATED")) {
            throw new BusinessRuleException("Only a calculated leave allowance can be approved.");
        }
        if (Objects.equals(a.userId(), CurrentUser.id())) {
            throw new AccessDeniedException("You cannot approve your own leave pay.");
        }
        if (a.calculatedBy().equalsIgnoreCase(CurrentUser.email())) {
            throw new BusinessRuleException("Someone other than the person who worked it out must approve it.");
        }
        for (Month m : a.months()) {
            PayrollRuns.requireOpen(jdbc, YearMonth.from(m.month()));
        }
        jdbc.sql("update leave_allowance set status = 'APPROVED', approved_by = ?, approved_at = now() where id = ?")
                .params(CurrentUser.email(), id).update();
        sync(a);
        audit.record("leave_allowance.approved", "leave_allowance", id, Map.of("userId", a.userId(),
                "advance", a.advance().toPlainString(), "currency", a.currency()));
        return find(id);
    }

    /** Records that the approved allowance was paid to the person. */
    @Transactional
    public Allowance markPaid(long id, LocalDate paidOn, String reference) {
        Allowance a = find(id);
        if (!a.status().equals("APPROVED")) {
            throw new BusinessRuleException("Only an approved leave allowance can be recorded as paid.");
        }
        if (Objects.equals(a.userId(), CurrentUser.id())) {
            throw new AccessDeniedException("You cannot record your own leave pay.");
        }
        jdbc.sql("update leave_allowance set status = 'PAID', paid_by = ?, paid_on = ?, payment_reference = ? where id = ?")
                .params(CurrentUser.email(), paidOn, reference == null || reference.isBlank() ? null : reference.trim(), id).update();
        audit.record("leave_allowance.paid", "leave_allowance", id, Map.of("userId", a.userId(), "paidOn", paidOn.toString()));
        return find(id);
    }

    /** Takes back an allowance not yet paid, so it can be worked out again. */
    @Transactional
    public Allowance cancel(long id, String note) {
        Allowance a = find(id);
        if (a.status().equals("PAID")) {
            throw new BusinessRuleException("This leave allowance is paid: the payslip of the leave month takes it back.");
        }
        if (a.status().equals("CANCELLED")) {
            throw new BusinessRuleException("This leave allowance is already cancelled.");
        }
        if (a.status().equals("APPROVED")) {
            for (Month m : a.months()) {
                PayrollRuns.requireOpen(jdbc, YearMonth.from(m.month()));
            }
        }
        jdbc.sql("update leave_allowance set status = 'CANCELLED', cancelled_by = ?, cancelled_at = now(), cancel_note = ? where id = ?")
                .params(CurrentUser.email(), note == null || note.isBlank() ? null : note.trim(), id).update();
        sync(a);
        audit.record("leave_allowance.cancelled", "leave_allowance", id, note == null ? Map.of() : Map.of("note", note));
        return find(id);
    }

    /**
     * Cancelled leave: an allowance not yet paid is cancelled. A paid one stays taken back on the payslip (the person
     * now works and is paid those days), without the average of bonuses and overtime.
     */
    @EventListener
    public void on(AuditService.Recorded r) {
        if (!"leave.cancelled".equals(r.action()) || r.entityId() == null) {
            return;
        }
        long requestId = Long.parseLong(r.entityId().toString());
        for (Long id : jdbc.sql("select id from leave_allowance where leave_request_id = ? and status <> 'CANCELLED'")
                .param(requestId).query(Long.class).list()) {
            Allowance a = find(id);
            boolean open = a.months().stream().allMatch(m -> isOpen(YearMonth.from(m.month())));
            if (!open) {
                continue;
            }
            if (a.status().equals("PAID")) {
                jdbc.sql("update leave_allowance_month set variable_amount = 0 where allowance_id = ?").param(id).update();
                jdbc.sql("update leave_allowance set variable_part = 0, cancel_note = ? where id = ?")
                        .params(Texts.t("Leave cancelled after payment: the advance is taken back on the payslip."), id).update();
            } else {
                jdbc.sql("update leave_allowance set status = 'CANCELLED', cancelled_by = ?, cancelled_at = now(), cancel_note = ? where id = ?")
                        .params(CurrentUser.email(), Texts.t("Leave cancelled"), id).update();
            }
            sync(a);
        }
    }

    private boolean isOpen(YearMonth month) {
        return jdbc.sql("select status from payroll_run where month = ?").param(month.atDay(1)).query(String.class).optional()
                .map("CALCULATED"::equals).orElse(true);
    }

    /** Writes the person's leave allowance pay entries of each month the allowance covers, from all its live allowances. */
    private void sync(Allowance a) {
        for (Month m : a.months()) {
            if (!isOpen(YearMonth.from(m.month()))) {
                continue;
            }
            record Sum(BigDecimal variable, BigDecimal advance) {}
            Sum s = jdbc.sql("""
                    select coalesce(sum(m.variable_amount), 0) as variable, coalesce(sum(m.advance_amount), 0) as advance
                    from leave_allowance_month m join leave_allowance a on a.id = m.allowance_id
                    where a.user_id = ? and m.month = ? and a.status in ('APPROVED', 'PAID') and trim(a.currency) = ?""")
                    .params(a.userId(), m.month(), a.currency()).query(Sum.class).single();
            write(a.userId(), "LEAVE_ALLOWANCE", m.month(), s.variable(), a.currency());
            write(a.userId(), "LEAVE_ADVANCE", m.month(), s.advance(), a.currency());
        }
    }

    private void write(long userId, String code, LocalDate month, BigDecimal amount, String currency) {
        long element = jdbc.sql("select id from pay_element where code = ?").param(code).query(Long.class).single();
        jdbc.sql("delete from pay_entry where user_id = ? and element_id = ? and month = ?").params(userId, element, month).update();
        if (amount.signum() > 0) {
            jdbc.sql("""
                    insert into pay_entry (user_id, element_id, month, amount, currency, note, created_by)
                    values (?, ?, ?, ?, ?, ?, ?)""")
                    .params(userId, element, month, amount, currency, Texts.t("leave pay"), CurrentUser.email()).update();
        }
    }
}
