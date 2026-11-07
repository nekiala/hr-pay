package cd.hrpay.notify;

import cd.hrpay.common.BusinessRuleException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Wakes a phone through its browser's push service (Web Push with VAPID, RFC 8292). The push carries no data, so
 * nothing needs encrypting: the phone's service worker then reads the latest notification with the person's own
 * session. Turned on by setting the VAPID key pair (APP_PUSH_PUBLIC_KEY and APP_PUSH_PRIVATE_KEY, base64url); without
 * it the bell in the app still works.
 */
@Component
public class WebPush {

    private static final Logger log = LoggerFactory.getLogger(WebPush.class);

    public enum Result { SENT, GONE, FAILED }

    /** The push services of the browsers people use; any other address is refused, so HR Pay never calls elsewhere. */
    static final List<String> SERVICES = List.of("fcm.googleapis.com", "push.services.mozilla.com", "push.apple.com",
            "notify.windows.com", "push.microsoft.com");

    private final String publicKey;
    private final PrivateKey privateKey;
    private final String subject;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();

    private record Token(String value, Instant expires) {}

    public WebPush(@Value("${app.push.public-key:}") String publicKey, @Value("${app.push.private-key:}") String privateKey,
                   @Value("${app.push.subject:mailto:support@hrpay.local}") String subject) {
        this.publicKey = publicKey == null || publicKey.isBlank() ? null : publicKey.trim();
        this.privateKey = this.publicKey == null || privateKey == null || privateKey.isBlank() ? null : key(privateKey.trim());
        this.subject = subject == null || subject.isBlank() ? "mailto:support@hrpay.local" : subject.trim();
        if (this.publicKey != null && this.privateKey == null) {
            log.warn("APP_PUSH_PUBLIC_KEY is set without APP_PUSH_PRIVATE_KEY: phone notifications are off");
        }
    }

    public boolean enabled() {
        return publicKey != null && privateKey != null;
    }

    /** The public key the browser subscribes with; null when push is off. */
    public String publicKey() {
        return enabled() ? publicKey : null;
    }

    public void checkEndpoint(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("This browser's push address cannot be used.");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equals(uri.getScheme()) || SERVICES.stream().noneMatch(s -> host.equals(s) || host.endsWith("." + s))) {
            throw new BusinessRuleException("This browser's push address cannot be used.");
        }
    }

    public Result send(String endpoint) {
        if (!enabled()) {
            return Result.FAILED;
        }
        try {
            checkEndpoint(endpoint);
            URI uri = URI.create(endpoint);
            String audience = uri.getScheme() + "://" + uri.getAuthority();
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("TTL", "86400").header("Urgency", "normal")
                    .header("Authorization", "vapid t=" + token(audience) + ", k=" + publicKey)
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            int code = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code >= 200 && code < 300) {
                return Result.SENT;
            }
            if (code == 404 || code == 410) {
                return Result.GONE;
            }
            log.info("Push service {} answered {}", audience, code);
            return Result.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        } catch (Exception e) {
            log.info("Push failed: {}", e.getMessage());
            return Result.FAILED;
        }
    }

    /** A signed VAPID token for one push service, kept until shortly before it expires. */
    String token(String audience) throws Exception {
        Token t = tokens.get(audience);
        if (t != null && t.expires().isAfter(Instant.now().plusSeconds(600))) {
            return t.value();
        }
        Instant expires = Instant.now().plusSeconds(12 * 3600);
        String header = b64("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = b64("{\"aud\":\"%s\",\"exp\":%d,\"sub\":\"%s\"}".formatted(audience, expires.getEpochSecond(), subject)
                .getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey);
        signer.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        String value = header + "." + claims + "." + b64(raw(signer.sign()));
        tokens.put(audience, new Token(value, expires));
        return value;
    }

    /** An ECDSA signature as the 64 bytes JWT wants (R then S), from Java's DER encoding. */
    public static byte[] raw(byte[] der) {
        int offset = 3;
        int rLength = der[offset++];
        byte[] r = new byte[rLength];
        System.arraycopy(der, offset, r, 0, rLength);
        offset += rLength + 1;
        int sLength = der[offset++];
        byte[] s = new byte[sLength];
        System.arraycopy(der, offset, s, 0, sLength);
        byte[] out = new byte[64];
        copy(r, out, 0);
        copy(s, out, 32);
        return out;
    }

    private static void copy(byte[] n, byte[] out, int at) {
        int start = Math.max(0, n.length - 32);
        int length = n.length - start;
        System.arraycopy(n, start, out, at + 32 - length, length);
    }

    private static PrivateKey key(String base64url) {
        try {
            AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
            p.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec spec = p.getParameterSpec(ECParameterSpec.class);
            byte[] d = Base64.getUrlDecoder().decode(base64url);
            return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, d), spec));
        } catch (Exception e) {
            log.warn("APP_PUSH_PRIVATE_KEY cannot be read ({}): phone notifications are off", e.getMessage());
            return null;
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
