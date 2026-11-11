package cd.hrpay.health;

import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.lifecycle.Documents;
import cd.hrpay.notify.Notifications;
import java.time.LocalDate;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** What the health and safety services share: the company's settings, uploaded files, reminders sent once. */
@Component
public class HealthSupport {

    public static final String PERMISSION = "health.safety";

    /** A file sent with a record: a PDF, JPEG or PNG. */
    public record Upload(String name, String type, byte[] data) {

        static final Upload NONE = new Upload(null, null, null);

        boolean present() {
            return data != null;
        }
    }

    /** A file read back. */
    public record File(String name, String contentType, byte[] data) {}

    private final JdbcClient jdbc;
    private final Notifications notifications;

    public HealthSupport(JdbcClient jdbc, Notifications notifications) {
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    int intSetting(String key, int fallback) {
        try {
            return jdbc.sql("select value from payroll_setting where key = ?").param(key).query(String.class).optional()
                    .map(String::trim).filter(v -> !v.isEmpty()).map(Integer::parseInt).orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    String setting(String key, String fallback) {
        return jdbc.sql("select value from payroll_setting where key = ?").param(key).query(String.class).optional()
                .map(String::trim).filter(v -> !v.isEmpty()).orElse(fallback);
    }

    void saveSetting(String key, String value) {
        jdbc.sql("""
                insert into payroll_setting (key, value) values (?, ?)
                on conflict (company_id, key) do update set value = excluded.value""").params(key, value).update();
    }

    /** Checks a file and names it; no file gives NONE. */
    static Upload upload(String fileName, byte[] data) {
        if (data == null || data.length == 0) {
            return Upload.NONE;
        }
        if (data.length > Documents.MAX_BYTES) {
            throw new BusinessRuleException("A document is at most 10 MB.");
        }
        String type = Documents.sniff(data);
        if (type == null) {
            throw new BusinessRuleException("Send a PDF, JPEG or PNG file.");
        }
        return new Upload(Documents.cleanName(fileName, type), type, data);
    }

    /** Workers under contract on a day (employees, not consultants). */
    int headcount(LocalDate day) {
        return jdbc.sql("""
                select count(distinct c.user_id) from employee_contract c join app_user u on u.id = c.user_id
                where not u.not_employee and c.contract_type <> 'CONSULTANT' and c.start_date <= ?
                  and (c.end_date is null or c.end_date >= ?)""").params(day, day).query(Integer.class).single();
    }

    /** Tells each holder of the permission once for the kind and day given; the number told. */
    int remind(String kind, LocalDate day, Supplier<String> title, Supplier<String> body, String link) {
        int n = 0;
        for (long holder : notifications.holders(PERMISSION)) {
            n += tell(holder, kind, day, title, body, link);
        }
        return n;
    }

    /** Tells one person once for the kind and day given. */
    int tell(long userId, String kind, LocalDate day, Supplier<String> title, Supplier<String> body, String link) {
        boolean first = jdbc.sql("insert into reminder_sent (user_id, kind, on_day) values (?, ?, ?) on conflict do nothing")
                .params(userId, kind, day).update() > 0;
        if (first) {
            notifications.send(userId, kind, title, body, link);
            return 1;
        }
        return 0;
    }

    static String clean(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
