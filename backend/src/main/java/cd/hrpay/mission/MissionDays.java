package cd.hrpay.mission;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The days a person is away on an approved travel mission, with where they are, for attendance and pay. */
@Component
public class MissionDays {

    private final JdbcClient jdbc;

    public MissionDays(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Each day from {@code from} to {@code to} covered by an approved (or closed) travel mission: "Mission: city". */
    public Map<LocalDate, String> of(long userId, LocalDate from, LocalDate to) {
        Map<LocalDate, String> days = new HashMap<>();
        jdbc.sql("""
                select work_date, end_date, city from mission
                where user_id = ? and kind = 'TRAVEL' and status in ('APPROVED', 'CLOSED')
                  and work_date <= ? and end_date >= ?""")
                .params(userId, to, from)
                .query((rs, n) -> {
                    LocalDate s = rs.getObject(1, LocalDate.class);
                    LocalDate e = rs.getObject(2, LocalDate.class);
                    String where = cd.hrpay.i18n.Texts.t("Mission: %s", rs.getString(3));
                    for (LocalDate d = s.isBefore(from) ? from : s; !d.isAfter(e) && !d.isAfter(to); d = d.plusDays(1)) {
                        days.put(d, where);
                    }
                    return null;
                }).list();
        return days;
    }

    /** People on a travel mission on a day (approved or closed). */
    public List<Long> away(LocalDate day) {
        return jdbc.sql("""
                select distinct user_id from mission where kind = 'TRAVEL' and status in ('APPROVED', 'CLOSED')
                and work_date <= ? and end_date >= ?""").params(day, day).query(Long.class).list();
    }
}
