package cd.hrpay.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import cd.hrpay.it.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The lockout is kept in the database, so it holds on every server. */
class LoginAttemptsIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;


    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-07T08:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void blocksAfterFiveFailuresAndUnblocksAfterTheWindow() {
        MutableClock clock = new MutableClock();
        LoginAttempts attempts = new LoginAttempts(jdbc, clock);
        for (int i = 0; i < 4; i++) {
            attempts.failed("A@x.cd");
        }
        assertThat(attempts.isBlocked("a@x.cd")).isFalse();
        attempts.failed("a@x.cd");
        assertThat(attempts.isBlocked("a@x.cd")).isTrue();
        // Another server sees the same lockout.
        assertThat(new LoginAttempts(jdbc, clock).isBlocked("a@x.cd")).isTrue();

        clock.now = clock.now.plus(Duration.ofMinutes(16));
        assertThat(attempts.isBlocked("a@x.cd")).isFalse();
    }

    @Test
    void successClearsFailures() {
        LoginAttempts attempts = new LoginAttempts(jdbc, new MutableClock());
        for (int i = 0; i < 4; i++) {
            attempts.failed("b@x.cd");
        }
        attempts.succeeded("b@x.cd");
        attempts.failed("b@x.cd");
        assertThat(attempts.isBlocked("b@x.cd")).isFalse();
    }
}
