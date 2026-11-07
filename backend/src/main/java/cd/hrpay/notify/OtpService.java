package cd.hrpay.notify;

import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.user.AppUser;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Six-digit one-time codes: 5-minute expiry, 5 attempts, stored only as a hash. */
@Service
public class OtpService {

    public record Challenge(UUID id, List<NotificationService.Delivery> deliveries, OffsetDateTime expiresAt) {}

    static final Duration TTL = Duration.ofMinutes(5);
    static final int MAX_ATTEMPTS = 5;
    static final int MAX_PER_15_MIN = 5;

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final NotificationService notifications;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public OtpService(JdbcClient jdbc, PasswordEncoder encoder, NotificationService notifications) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.notifications = notifications;
        this.clock = Clock.systemUTC();
    }

    @Transactional
    public Challenge issue(AppUser user, String purpose, String contextJson) {
        long recent = jdbc.sql("""
                select count(*) from otp_challenge
                where user_id = :user and created_at > now() - interval '15 minutes'""")
                .param("user", user.getId()).query(Long.class).single();
        if (recent >= MAX_PER_15_MIN) {
            throw new BusinessRuleException("Too many codes requested. Wait a few minutes and try again.");
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        UUID id = UUID.randomUUID();
        OffsetDateTime expires = OffsetDateTime.now(clock).plus(TTL);
        jdbc.sql("""
                insert into otp_challenge (id, user_id, purpose, code_hash, expires_at, context)
                values (:id, :user, :purpose, :hash, :expires, cast(:context as jsonb))""")
                .param("id", id).param("user", user.getId()).param("purpose", purpose)
                .param("hash", encoder.encode(code)).param("expires", expires).param("context", contextJson)
                .update();
        List<NotificationService.Delivery> deliveries = notifications.sendCode(user, code, purpose);
        return new Challenge(id, deliveries, expires);
    }

    public record Verified(String contextJson, OffsetDateTime createdAt) {}

    /**
     * Checks a code. A wrong code counts an attempt in its own transaction, so the attempt sticks even
     * though the caller's request fails.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = BusinessRuleException.class)
    public Verified verify(UUID id, AppUser user, String purpose, String code) {
        var row = jdbc.sql("""
                select code_hash, expires_at, attempts, consumed_at, context::text as context, created_at
                from otp_challenge where id = :id and user_id = :user and purpose = :purpose for update""")
                .param("id", id).param("user", user.getId()).param("purpose", purpose)
                .query((rs, n) -> new Object[] {rs.getString("code_hash"), rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getInt("attempts"), rs.getObject("consumed_at", OffsetDateTime.class), rs.getString("context"),
                        rs.getObject("created_at", OffsetDateTime.class)})
                .optional()
                .orElseThrow(() -> new BusinessRuleException("This code request was not found. Start again."));
        if (row[3] != null) {
            throw new BusinessRuleException("This code was already used. Start again.");
        }
        if (((OffsetDateTime) row[1]).isBefore(OffsetDateTime.now(clock))) {
            throw new BusinessRuleException("The code has expired. Start again to get a new one.");
        }
        if ((int) row[2] >= MAX_ATTEMPTS) {
            throw new BusinessRuleException("Too many wrong codes. Start again to get a new one.");
        }
        if (code == null || !encoder.matches(code.trim(), (String) row[0])) {
            jdbc.sql("update otp_challenge set attempts = attempts + 1 where id = :id").param("id", id).update();
            throw new BusinessRuleException("The code is not correct.");
        }
        jdbc.sql("update otp_challenge set consumed_at = now() where id = :id").param("id", id).update();
        return new Verified((String) row[4], ((OffsetDateTime) row[5]).withOffsetSameInstant(ZoneOffset.UTC));
    }
}
