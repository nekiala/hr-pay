package cd.hrpay.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class QrTokensTest {

    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-09T07:55:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void tokenIsValidForAtMostTwoWindows() {
        TestClock clock = new TestClock();
        QrTokens qr = new QrTokens(clock);
        String token = qr.issue("KIN-HQ", "secret");
        assertThat(qr.verify("KIN-HQ", "secret", token)).isTrue();
        clock.now = clock.now.plusSeconds(30);
        assertThat(qr.verify("KIN-HQ", "secret", token)).isTrue();
        clock.now = clock.now.plusSeconds(30);
        assertThat(qr.verify("KIN-HQ", "secret", token)).isFalse();
    }

    @Test
    void tokenIsBoundToTheSiteAndKey() {
        QrTokens qr = new QrTokens(new TestClock());
        String token = qr.issue("KIN-HQ", "secret");
        assertThat(qr.verify("LUB-01", "secret", token)).isFalse();
        assertThat(qr.verify("KIN-HQ", "other", token)).isFalse();
        assertThat(qr.verify("KIN-HQ", "secret", "garbage")).isFalse();
        assertThat(qr.verify("KIN-HQ", "secret", null)).isFalse();
    }
}
