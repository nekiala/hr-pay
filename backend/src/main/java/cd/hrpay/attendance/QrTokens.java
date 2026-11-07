package cd.hrpay.attendance;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * The kiosk's rotating QR code: an HMAC of the site and the current 30-second window, keyed with the
 * site's kiosk secret. A token is accepted during its window and the next one (up to 60 seconds).
 */
@Component
public class QrTokens {

    public static final Duration WINDOW = Duration.ofSeconds(30);

    private final Clock clock;

    public QrTokens() {
        this(Clock.systemUTC());
    }

    QrTokens(Clock clock) {
        this.clock = clock;
    }

    public long currentWindow() {
        return clock.millis() / WINDOW.toMillis();
    }

    public long secondsLeft() {
        return (WINDOW.toMillis() - clock.millis() % WINDOW.toMillis()) / 1000 + 1;
    }

    public String issue(String siteCode, String kioskKey) {
        return issue(siteCode, kioskKey, currentWindow());
    }

    String issue(String siteCode, String kioskKey, long window) {
        return window + "." + sign(siteCode + "|" + window, kioskKey);
    }

    public boolean verify(String siteCode, String kioskKey, String token) {
        if (token == null || !token.contains(".")) {
            return false;
        }
        long window;
        try {
            window = Long.parseLong(token.substring(0, token.indexOf('.')));
        } catch (NumberFormatException e) {
            return false;
        }
        long now = currentWindow();
        if (window != now && window != now - 1) {
            return false;
        }
        byte[] expected = issue(siteCode, kioskKey, window).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.UTF_8));
    }

    private static String sign(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 22);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
