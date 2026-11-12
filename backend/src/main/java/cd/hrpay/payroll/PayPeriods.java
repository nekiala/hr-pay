package cd.hrpay.payroll;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The attendance period of a payroll month. A grade category is paid on the calendar month (the default) or on three
 * decades. Decades are tied to the calendar: 1 to 10, 11 to 20 and 21 to the last day, so a short month only shortens
 * its last decade. The category chooses the day its first decade starts: with 21, the November payroll reads the
 * attendance of 21 October to 20 November. The salary itself stays the calendar month's (DRC Code du travail art. 99:
 * a regular interval of at most one month).
 */
@Service
public class PayPeriods {

    public static final String MONTH = "MONTH";
    public static final String DECADES = "DECADES";

    public record Decade(int number, LocalDate from, LocalDate to) {}

    /** from..to: the days whose absences, unpaid leave and overtime go on the month's payslip. */
    public record Period(YearMonth month, String kind, int firstDecadeDay, LocalDate from, LocalDate to, List<Decade> decades) {
        /** The period is the calendar month itself: the payslip works as it always did. */
        public boolean calendar() {
            return from.equals(month.atDay(1)) && to.equals(month.atEndOfMonth());
        }
    }

    /** from..to is one whole calendar month. */
    public static boolean isCalendarMonth(LocalDate from, LocalDate to) {
        return from.getDayOfMonth() == 1 && to.equals(from.withDayOfMonth(from.lengthOfMonth()));
    }

    public record Setting(String kind, int firstDecadeDay) {
        static final Setting CALENDAR = new Setting(MONTH, 1);
    }

    private final JdbcClient jdbc;

    public PayPeriods(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The last day of a month's period: the day before the first decade starts, in the month itself. */
    static LocalDate end(YearMonth month, Setting s) {
        if (!DECADES.equals(s.kind()) || s.firstDecadeDay() == 1) {
            return month.atEndOfMonth();
        }
        return month.atDay(s.firstDecadeDay() - 1);
    }

    /** The period a category's setting gives a month, when the month before used the same setting. */
    public static Period of(YearMonth month, Setting s) {
        return between(month, s, end(month.minusMonths(1), s).plusDays(1), end(month, s));
    }

    static Period between(YearMonth month, Setting s, LocalDate from, LocalDate to) {
        return new Period(month, s.kind(), s.firstDecadeDay(), from, to, decades(from, to));
    }

    /** The decades from..to, cut at the 1st, 11th and 21st of each month. */
    public static List<Decade> decades(LocalDate from, LocalDate to) {
        List<Decade> out = new ArrayList<>();
        LocalDate start = from;
        while (!start.isAfter(to)) {
            LocalDate next = start.getDayOfMonth() <= 10 ? start.withDayOfMonth(10)
                    : start.getDayOfMonth() <= 20 ? start.withDayOfMonth(20)
                    : start.withDayOfMonth(start.lengthOfMonth());
            LocalDate stop = next.isAfter(to) ? to : next;
            out.add(new Decade(out.size() + 1, start, stop));
            start = stop.plusDays(1);
        }
        return out;
    }

    public Setting setting(Long categoryId) {
        if (categoryId == null) {
            return Setting.CALENDAR;
        }
        return jdbc.sql("select pay_period, first_decade_day from grade_category where id = ?").param(categoryId)
                .query((rs, i) -> new Setting(rs.getString(1), rs.getInt(2))).optional().orElse(Setting.CALENDAR);
    }

    /**
     * One person's period for a month: it starts the day after the last day settled on their previous month's payslip
     * (so changing the setting, or moving to another category, loses or repeats no day), else as the setting says.
     */
    public Period forPerson(long userId, Long categoryId, YearMonth month) {
        Setting s = setting(categoryId);
        LocalDate to = end(month, s);
        LocalDate from = jdbc.sql("""
                        select coalesce(p.var_to, (r.month + interval '1 month' - interval '1 day')::date)
                        from payslip p join payroll_run r on r.id = p.run_id
                        where p.user_id = ? and r.month = ? and p.kind = 'REGULAR' order by 1 desc limit 1""")
                .params(userId, month.minusMonths(1).atDay(1)).query(LocalDate.class).list().stream().findFirst()
                .map(d -> d.plusDays(1)).orElse(null);
        if (from == null || from.isAfter(to) || from.isBefore(month.minusMonths(1).atDay(1))) {
            from = end(month.minusMonths(1), s).plusDays(1);
        }
        return between(month, s, from, to);
    }

    /** The next periods of a setting, for the settings page preview. */
    public static List<Period> preview(YearMonth from, Setting s, int months) {
        List<Period> out = new ArrayList<>();
        for (int i = 0; i < months; i++) {
            out.add(of(from.plusMonths(i), s));
        }
        return out;
    }
}
