package cd.hrpay.payroll;

import cd.hrpay.attendance.AttendanceDays;
import cd.hrpay.audit.AuditService;
import cd.hrpay.holiday.HolidayService;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * When HR moves a holiday into or out of a month whose payroll is closed, the basic pay of that month is worked out
 * again with the new calendar, and the difference is carried into the first payroll that is not approved yet, as
 * "Holiday change, earlier month" (more) or "(less)". The closed payslips are never changed.
 */
@Service
public class HolidayAdjustments {

    public record Adjustment(long id, long userId, String userName, long payslipId, String closedMonth, BigDecimal amount,
                             String currency, LocalDate targetMonth, String note, String createdBy) {}

    private final JdbcClient jdbc;
    private final AttendanceDays attendance;
    private final UserRepository users;
    private final AuditService audit;
    private final Clock clock;

    public HolidayAdjustments(JdbcClient jdbc, AttendanceDays attendance, UserRepository users, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.attendance = attendance;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    /** The first month from the current one whose payroll is not yet approved or closed. */
    YearMonth targetMonth() {
        YearMonth m = YearMonth.now(clock.withZone(ZoneId.of("Africa/Kinshasa")));
        for (int i = 0; i < 24; i++, m = m.plusMonths(1)) {
            String status = jdbc.sql("select status from payroll_run where month = ?").param(m.atDay(1))
                    .query(String.class).optional().orElse(null);
            if (status == null || status.equals("CALCULATED")) {
                return m;
            }
        }
        return m;
    }

    @EventListener
    @Transactional
    public void onHolidayMoved(HolidayService.Moved moved) {
        Set<YearMonth> months = new LinkedHashSet<>(List.of(YearMonth.from(moved.from()), YearMonth.from(moved.to())));
        String note = moved.holiday() + " moved from " + moved.from() + " to " + moved.to();
        for (YearMonth month : months) {
            settle(month, note);
        }
    }

    private record Slip(long id, long userId, String currency, int scheduledMinutes, int paidMinutes, BigDecimal basic) {}

    /** Settles one closed month: for each payslip, what the new calendar owes compared to what was paid. */
    @Transactional
    public int settle(YearMonth month, String note) {
        Long runId = jdbc.sql("select id from payroll_run where month = ? and status = 'CLOSED'").param(month.atDay(1))
                .query(Long.class).optional().orElse(null);
        if (runId == null) {
            return 0;   // An open payroll takes the new calendar into account when it is calculated again.
        }
        YearMonth target = targetMonth();
        List<Slip> slips = jdbc.sql("""
                select id, user_id, trim(currency) as currency, scheduled_minutes, paid_minutes,
                       (snapshot ->> 'basicMonthly')::numeric as basic
                from payslip where run_id = ?""").param(runId).query(Slip.class).list();
        int changed = 0;
        Set<Long> touched = new LinkedHashSet<>();
        for (Slip s : slips) {
            var user = users.findById(s.userId()).orElse(null);
            if (user == null || s.basic() == null || s.scheduledMinutes() == 0) {
                continue;
            }
            var now = attendance.month(user, month, false);
            int scheduled = now.scheduledMinutesMonth();
            int paid = now.payableMinutesToDate() + (scheduled - now.scheduledMinutesToDate());
            BigDecimal before = share(s.basic(), s.paidMinutes(), s.scheduledMinutes());
            BigDecimal after = scheduled == 0 ? BigDecimal.ZERO : share(s.basic(), paid, scheduled);
            BigDecimal already = jdbc.sql("select coalesce(sum(amount), 0) from holiday_adjustment where payslip_id = ?")
                    .param(s.id()).query(BigDecimal.class).single();
            BigDecimal delta = after.subtract(before).subtract(already).setScale(2, RoundingMode.HALF_UP);
            if (delta.abs().compareTo(new BigDecimal("0.01")) < 0) {
                continue;
            }
            jdbc.sql("""
                    insert into holiday_adjustment (user_id, payslip_id, amount, currency, target_month, note, created_by)
                    values (?, ?, ?, ?, ?, ?, ?)""")
                    .params(s.userId(), s.id(), delta, s.currency(), target.atDay(1),
                            (month + ": " + note).length() > 300 ? (month + ": " + note).substring(0, 300) : month + ": " + note,
                            CurrentUser.email() == null ? "system" : CurrentUser.email())
                    .update();
            touched.add(s.userId());
            changed++;
        }
        for (long userId : touched) {
            rebuildEntries(userId, target);
        }
        if (changed > 0) {
            audit.record("payroll.holiday_adjusted", "payroll_run", runId, Map.of("month", month.toString(),
                    "payslips", changed, "carriedTo", target.toString(), "note", note));
        }
        return changed;
    }

    private static BigDecimal share(BigDecimal basic, int paid, int scheduled) {
        return basic.multiply(BigDecimal.valueOf(paid)).divide(BigDecimal.valueOf(scheduled), 10, RoundingMode.HALF_UP);
    }

    /** One pay entry per person for the target month: the sum of their adjustments, as "more" or "less". */
    private void rebuildEntries(long userId, YearMonth target) {
        long more = elementId("HOLIDAY_ADJ");
        long less = elementId("HOLIDAY_ADJ_LESS");
        jdbc.sql("delete from pay_entry where user_id = ? and month = ? and element_id in (?, ?)")
                .params(userId, target.atDay(1), more, less).update();
        var sums = jdbc.sql("""
                select currency, sum(amount) as amount from holiday_adjustment
                where user_id = ? and target_month = ? group by currency""").params(userId, target.atDay(1))
                .query((rs, i) -> Map.entry(rs.getString("currency"), rs.getBigDecimal("amount"))).list();
        for (var sum : sums) {
            if (sum.getValue().signum() == 0) {
                continue;
            }
            jdbc.sql("""
                    insert into pay_entry (user_id, element_id, month, amount, currency, note, created_by)
                    values (?, ?, ?, ?, ?, ?, ?)""")
                    .params(userId, sum.getValue().signum() > 0 ? more : less, target.atDay(1), sum.getValue().abs(),
                            sum.getKey(), "holiday moved in a closed month", CurrentUser.email() == null ? "system" : CurrentUser.email())
                    .update();
            break;   // One currency per person; a second one would clash with the first entry.
        }
    }

    private long elementId(String code) {
        return jdbc.sql("select id from pay_element where code = ?").param(code).query(Long.class).single();
    }

    public List<Adjustment> recent() {
        return jdbc.sql("""
                select a.id, a.user_id, u.first_name || ' ' || u.last_name as user_name, a.payslip_id,
                       to_char(r.month, 'YYYY-MM') as closed_month, a.amount, trim(a.currency) as currency, a.target_month,
                       a.note, a.created_by
                from holiday_adjustment a join app_user u on u.id = a.user_id join payslip p on p.id = a.payslip_id
                join payroll_run r on r.id = p.run_id order by a.id desc limit 200""").query(Adjustment.class).list();
    }
}
