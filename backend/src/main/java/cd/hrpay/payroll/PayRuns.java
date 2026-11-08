package cd.hrpay.payroll;

import cd.hrpay.i18n.Texts;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Every pay run in one list, whatever its type: the month-end payroll, the quinzaine, leave pay (allocation de congé),
 * the 13th month, corrections of closed months and final settlements. Each type keeps its own tables, rules, documents
 * and number series; this only reads them, so nothing closed is ever rewritten (the livre de paie stays as written).
 */
@Service
public class PayRuns {

    public enum Type { MONTHLY, QUINZAINE, LEAVE_PAY, THIRTEENTH, CORRECTION, SETTLEMENT }

    /** Where a run stands, the same words for every type. */
    public enum Stage { TO_APPROVE, TO_CLOSE, TO_PAY, DONE, CANCELLED }

    public record Amount(String currency, BigDecimal amount) {}

    public record Row(Type type, String key, LocalDate month, LocalDate payDate, String title, String status, Stage stage,
                      int people, List<Amount> totals, String calculatedBy, OffsetDateTime calculatedAt,
                      String approvedBy) {}

    public record TypeInfo(Type type, boolean available) {}

    public record Notice(String level, String text, Type type, String key) {}

    public record Month(LocalDate month, List<Row> runs, List<Notice> notices) {}

    private final JdbcClient jdbc;
    private final Quinzaines quinzaines;
    private final Thirteenths thirteenths;
    private final cd.hrpay.leave.LeaveService leave;
    private final Clock clock;

    public PayRuns(JdbcClient jdbc, Quinzaines quinzaines, Thirteenths thirteenths, cd.hrpay.leave.LeaveService leave, Clock clock) {
        this.jdbc = jdbc;
        this.quinzaines = quinzaines;
        this.thirteenths = thirteenths;
        this.leave = leave;
        this.clock = clock;
    }

    /** The types this company can run: the quinzaine, leave pay and the 13th month only when its settings turn them on. */
    public List<TypeInfo> types() {
        return List.of(new TypeInfo(Type.MONTHLY, true),
                new TypeInfo(Type.QUINZAINE, quinzaines.settings().enabled()),
                new TypeInfo(Type.LEAVE_PAY, leave.annualPaid()),
                new TypeInfo(Type.THIRTEENTH, thirteenths.settings().enabled()),
                new TypeInfo(Type.CORRECTION, true),
                new TypeInfo(Type.SETTLEMENT, true));
    }

    /** The runs whose month is between from and to (both included), newest first. */
    public List<Row> list(YearMonth from, YearMonth to) {
        LocalDate a = from.atDay(1);
        LocalDate b = to.atDay(1);
        List<Row> out = new ArrayList<>();
        out.addAll(monthly(a, b));
        out.addAll(quinzaine(a, b));
        out.addAll(leavePay(a, b));
        out.addAll(thirteenth(a, b));
        out.addAll(corrections(a, b));
        out.addAll(settlements(a, b));
        out.sort(Comparator.comparing(Row::month).reversed().thenComparing(r -> r.type().ordinal()));
        return out;
    }

    /** One month: its runs and what holds them up. */
    public Month month(YearMonth month) {
        List<Row> runs = list(month, month);
        List<Notice> notices = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);
        Row payroll = runs.stream().filter(r -> r.type() == Type.MONTHLY).findFirst().orElse(null);
        for (Row r : runs) {
            if (r.type() == Type.QUINZAINE && (r.stage() == Stage.TO_APPROVE || r.stage() == Stage.TO_CLOSE)) {
                notices.add(new Notice("WARN", Texts.t("Close or cancel the quinzaine of this month before closing the payroll."), r.type(), r.key()));
            }
            if (r.type() == Type.THIRTEENTH && r.stage() == Stage.TO_APPROVE) {
                notices.add(new Notice("WARN", Texts.t("Approve or cancel the 13th month paid with this payroll before closing it."), r.type(), r.key()));
            }
            if (r.type() == Type.CORRECTION && r.stage() == Stage.TO_APPROVE) {
                notices.add(new Notice("INFO", Texts.t("Complementary payslips wait for Finance's approval."), r.type(), r.key()));
            }
            if (r.type() == Type.SETTLEMENT && r.stage() == Stage.TO_APPROVE) {
                notices.add(new Notice("INFO", Texts.t("A final settlement is still a draft: confirm it before the payroll of its month."), r.type(), r.key()));
            }
        }
        // A 13th month approved after the payroll was calculated is not on its payslips yet.
        jdbc.sql("""
                select t.id from thirteenth_run t join payroll_run p on p.month = t.month
                where t.month = ? and t.status = 'APPROVED' and p.status <> 'CLOSED' and t.approved_at > p.calculated_at""")
                .param(month.atDay(1)).query(Long.class).list()
                .forEach(id -> notices.add(new Notice("WARN", Texts.t("The 13th month was approved after this payroll was calculated: calculate the payroll again."),
                        Type.MONTHLY, month.toString())));
        // Leave pay is due by the last working day before the leave.
        jdbc.sql("""
                select count(*) from leave_allowance where status in ('CALCULATED', 'APPROVED') and pay_by < ?
                  and pay_by >= ? and pay_by <= ?""")
                .params(today, month.atDay(1), month.atEndOfMonth()).query(Integer.class).optional()
                .filter(n -> n > 0)
                .ifPresent(n -> notices.add(new Notice("WARN", Texts.t("%s leave allowance(s) not paid by their pay-by date.", n),
                        Type.LEAVE_PAY, month.toString())));
        int due = jdbc.sql("""
                select count(*) from leave_request r join leave_type t on t.id = r.leave_type_id
                where r.status = 'APPROVED' and t.code = 'ANNUAL' and r.start_date between ? and ?
                  and not exists (select 1 from leave_allowance a where a.leave_request_id = r.id and a.status <> 'CANCELLED')""")
                .params(month.atDay(1), month.atEndOfMonth()).query(Integer.class).single();
        if (due > 0 && leave.annualPaid()) {
            notices.add(new Notice("INFO", Texts.t("%s approved annual leave(s) starting this month wait for their leave pay.", due),
                    Type.LEAVE_PAY, month.toString()));
        }
        if (payroll == null) {
            notices.add(new Notice("INFO", Texts.t("The month-end payroll is not calculated yet."), Type.MONTHLY, month.toString()));
        }
        return new Month(month.atDay(1), runs, notices);
    }

    private static Stage stage(String status) {
        return switch (status) {
            case "CALCULATED", "WAITING", "DRAFT" -> Stage.TO_APPROVE;
            case "APPROVED" -> Stage.TO_CLOSE;
            case "TO_PAY" -> Stage.TO_PAY;
            case "CANCELLED" -> Stage.CANCELLED;
            default -> Stage.DONE;
        };
    }

    private Map<Long, List<Amount>> totals(String sql, LocalDate a, LocalDate b) {
        Map<Long, List<Amount>> out = new LinkedHashMap<>();
        jdbc.sql(sql).params(a, b).query((rs, i) -> out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                .add(new Amount(rs.getString(2), rs.getBigDecimal(3)))).list();
        return out;
    }

    private List<Row> monthly(LocalDate a, LocalDate b) {
        Map<Long, List<Amount>> totals = totals("""
                select p.run_id, trim(p.currency), sum(p.net) from payslip p join payroll_run r on r.id = p.run_id
                where r.month between ? and ? and p.kind = 'REGULAR' group by p.run_id, p.currency order by 2""", a, b);
        return jdbc.sql("""
                select id, month, status, employee_count, calculated_by, calculated_at, approved_by from payroll_run
                where month between ? and ?""").params(a, b)
                .query((rs, i) -> {
                    LocalDate m = rs.getObject("month", LocalDate.class);
                    String status = rs.getString("status");
                    return new Row(Type.MONTHLY, YearMonth.from(m).toString(), m, m.withDayOfMonth(m.lengthOfMonth()),
                            Texts.t("Month-end payroll"), status, stage(status), rs.getInt("employee_count"),
                            totals.getOrDefault(rs.getLong("id"), List.of()), rs.getString("calculated_by"),
                            rs.getObject("calculated_at", OffsetDateTime.class), rs.getString("approved_by"));
                }).list();
    }

    private List<Row> quinzaine(LocalDate a, LocalDate b) {
        Map<Long, List<Amount>> totals = totals("""
                select l.run_id, trim(l.currency), sum(l.amount) from quinzaine_line l join quinzaine_run r on r.id = l.run_id
                where r.month between ? and ? group by l.run_id, l.currency order by 2""", a, b);
        return jdbc.sql("""
                select r.id, r.month, r.pay_date, r.status, r.calculated_by, r.calculated_at, r.approved_by,
                       (select count(*) from quinzaine_line l where l.run_id = r.id) as people
                from quinzaine_run r where r.month between ? and ?""").params(a, b)
                .query((rs, i) -> {
                    String status = rs.getString("status");
                    return new Row(Type.QUINZAINE, String.valueOf(rs.getLong("id")), rs.getObject("month", LocalDate.class),
                            rs.getObject("pay_date", LocalDate.class), Texts.t("Quinzaine"), status,
                            status.equals("CLOSED") ? Stage.TO_PAY : stage(status), rs.getInt("people"),
                            totals.getOrDefault(rs.getLong("id"), List.of()), rs.getString("calculated_by"),
                            rs.getObject("calculated_at", OffsetDateTime.class), rs.getString("approved_by"));
                }).list();
    }

    /** Leave pay is worked out per leave; the list shows one line per month of the pay-by dates. */
    private List<Row> leavePay(LocalDate a, LocalDate b) {
        Map<LocalDate, List<Amount>> totals = new LinkedHashMap<>();
        jdbc.sql("""
                select date_trunc('month', pay_by)::date as m, trim(currency), sum(advance) from leave_allowance
                where status <> 'CANCELLED' and date_trunc('month', pay_by)::date between ? and ? group by 1, 2 order by 2""")
                .params(a, b).query((rs, i) -> totals.computeIfAbsent(rs.getObject(1, LocalDate.class), k -> new ArrayList<>())
                        .add(new Amount(rs.getString(2), rs.getBigDecimal(3)))).list();
        return jdbc.sql("""
                select date_trunc('month', pay_by)::date as m, min(pay_by) filter (where status in ('CALCULATED', 'APPROVED')) as next_pay,
                       count(*) filter (where status <> 'CANCELLED') as people,
                       count(*) filter (where status = 'CALCULATED') as calculated,
                       count(*) filter (where status = 'APPROVED') as approved,
                       count(*) filter (where status = 'PAID') as paid,
                       max(calculated_at) as calculated_at,
                       (array_agg(calculated_by order by calculated_at desc))[1] as calculated_by,
                       (array_agg(approved_by order by approved_at desc nulls last))[1] as approved_by
                from leave_allowance where date_trunc('month', pay_by)::date between ? and ? group by 1""").params(a, b)
                .query((rs, i) -> {
                    LocalDate m = rs.getObject("m", LocalDate.class);
                    String status = rs.getInt("calculated") > 0 ? "CALCULATED" : rs.getInt("approved") > 0 ? "TO_PAY"
                            : rs.getInt("paid") > 0 ? "PAID" : "CANCELLED";
                    return new Row(Type.LEAVE_PAY, YearMonth.from(m).toString(), m, rs.getObject("next_pay", LocalDate.class),
                            Texts.t("Leave pay"), status, stage(status), rs.getInt("people"), totals.getOrDefault(m, List.of()),
                            rs.getString("calculated_by"), rs.getObject("calculated_at", OffsetDateTime.class),
                            rs.getString("approved_by"));
                }).list();
    }

    private List<Row> thirteenth(LocalDate a, LocalDate b) {
        Map<Long, List<Amount>> totals = totals("""
                select l.run_id, trim(l.currency), sum(l.amount) from thirteenth_line l join thirteenth_run r on r.id = l.run_id
                where r.month between ? and ? group by l.run_id, l.currency order by 2""", a, b);
        return jdbc.sql("""
                select r.id, r.year, r.month, r.status, r.calculated_by, r.calculated_at, r.approved_by,
                       (select count(*) from thirteenth_line l where l.run_id = r.id) as people
                from thirteenth_run r where r.month between ? and ?""").params(a, b)
                .query((rs, i) -> {
                    LocalDate m = rs.getObject("month", LocalDate.class);
                    String status = rs.getString("status");
                    // Approved: it waits for the month-end payroll that pays it.
                    return new Row(Type.THIRTEENTH, String.valueOf(rs.getLong("id")), m, m.withDayOfMonth(m.lengthOfMonth()),
                            Texts.t("13th month %s", String.valueOf(rs.getInt("year"))), status,
                            status.equals("APPROVED") ? Stage.TO_PAY : stage(status), rs.getInt("people"),
                            totals.getOrDefault(rs.getLong("id"), List.of()), rs.getString("calculated_by"),
                            rs.getObject("calculated_at", OffsetDateTime.class), rs.getString("approved_by"));
                }).list();
    }

    /** Corrections of closed months, one line per month they were made in. */
    private List<Row> corrections(LocalDate a, LocalDate b) {
        Map<LocalDate, List<Amount>> totals = new LinkedHashMap<>();
        jdbc.sql("""
                select date_trunc('month', created_at)::date as m, trim(currency), sum(gross_change) from payroll_correction
                where date_trunc('month', created_at)::date between ? and ? group by 1, 2 order by 2""")
                .params(a, b).query((rs, i) -> totals.computeIfAbsent(rs.getObject(1, LocalDate.class), k -> new ArrayList<>())
                        .add(new Amount(rs.getString(2), rs.getBigDecimal(3)))).list();
        return jdbc.sql("""
                select date_trunc('month', c.created_at)::date as m, count(distinct c.user_id) as people,
                       count(*) filter (where c.complementary_id is not null and s.approved_at is null) as waiting,
                       max(c.created_at) as created_at, (array_agg(c.created_by order by c.created_at desc))[1] as created_by
                from payroll_correction c left join payslip s on s.id = c.complementary_id
                where date_trunc('month', c.created_at)::date between ? and ? group by 1""").params(a, b)
                .query((rs, i) -> {
                    LocalDate m = rs.getObject("m", LocalDate.class);
                    String status = rs.getInt("waiting") > 0 ? "WAITING" : "APPROVED_DONE";
                    return new Row(Type.CORRECTION, YearMonth.from(m).toString(), m, null, Texts.t("Corrections and back pay"),
                            status, stage(status), rs.getInt("people"), totals.getOrDefault(m, List.of()),
                            rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class), null);
                }).list();
    }

    private List<Row> settlements(LocalDate a, LocalDate b) {
        return jdbc.sql("""
                select d.user_id, d.last_day, d.status, trim(d.currency) as currency, d.created_by, d.created_at, d.confirmed_by,
                       d.notice_amount + d.leave_amount + d.severance_amount + d.thirteenth_amount - d.notice_owed as total,
                       u.first_name || ' ' || u.last_name as name
                from departure d join app_user u on u.id = d.user_id
                where date_trunc('month', d.last_day)::date between ? and ?""").params(a, b)
                .query((rs, i) -> {
                    LocalDate last = rs.getObject("last_day", LocalDate.class);
                    String status = rs.getString("status");
                    return new Row(Type.SETTLEMENT, String.valueOf(rs.getLong("user_id")), last.withDayOfMonth(1), last,
                            Texts.t("Final settlement: %s", rs.getString("name")), status, stage(status), 1,
                            List.of(new Amount(rs.getString("currency"), rs.getBigDecimal("total"))), rs.getString("created_by"),
                            rs.getObject("created_at", OffsetDateTime.class), rs.getString("confirmed_by"));
                }).list();
    }
}
