package cd.hrpay.overtime;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OvertimeDecisions {

    public record Decision(long id, long userId, LocalDate workDate, int computedMinutes, int approvedMinutes,
                           String kind, String status, String note, String decidedBy, OffsetDateTime decidedAt) {}

    private static final String COLUMNS = """
            id, user_id, work_date, computed_minutes, approved_minutes, kind, status, note, decided_by, decided_at""";

    private final JdbcClient jdbc;

    public OvertimeDecisions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Decision> forPeriod(long userId, LocalDate from, LocalDate to) {
        return jdbc.sql("select " + COLUMNS + " from overtime_decision where user_id = ? and work_date between ? and ? order by work_date")
                .params(userId, from, to).query(Decision.class).list();
    }

    public Optional<Decision> find(long userId, LocalDate date) {
        return jdbc.sql("select " + COLUMNS + " from overtime_decision where user_id = ? and work_date = ?")
                .params(userId, date).query(Decision.class).optional();
    }

    public void insert(long userId, LocalDate date, int computed, int approved, String kind, String status,
                       String note, String by) {
        jdbc.sql("""
                insert into overtime_decision (user_id, work_date, computed_minutes, approved_minutes, kind, status, note, decided_by)
                values (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(userId, date, computed, approved, kind, status, note, by).update();
    }
}
