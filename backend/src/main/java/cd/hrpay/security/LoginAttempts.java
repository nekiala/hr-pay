package cd.hrpay.security;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Blocks an email address for 15 minutes after 5 failed password attempts within 15 minutes. The failures are kept
 * in the database, so the lockout holds on every API server and survives a restart.
 */
@Component
public class LoginAttempts {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final JdbcClient jdbc;
    private final Clock clock;

    @Autowired
    public LoginAttempts(JdbcClient jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    LoginAttempts(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public boolean isBlocked(String email) {
        int recent = jdbc.sql("select count(*) from login_failure where email = ? and failed_at > ?")
                .param(key(email)).param(since()).query(Integer.class).single();
        return recent >= MAX_FAILURES;
    }

    public void failed(String email) {
        jdbc.sql("delete from login_failure where failed_at <= ?").param(since()).update();
        jdbc.sql("insert into login_failure (email, failed_at) values (?, ?)").param(key(email)).param(now()).update();
    }

    public void succeeded(String email) {
        jdbc.sql("delete from login_failure where email = ?").param(key(email)).update();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private OffsetDateTime since() {
        return OffsetDateTime.ofInstant(clock.instant().minus(WINDOW), ZoneOffset.UTC);
    }

    private static String key(String email) {
        String k = email == null ? "" : email.trim().toLowerCase();
        return k.length() > 200 ? k.substring(0, 200) : k;
    }
}
