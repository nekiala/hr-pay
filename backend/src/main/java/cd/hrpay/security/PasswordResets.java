package cd.hrpay.security;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.AppProperties;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.lifecycle.Documents;
import cd.hrpay.notify.NotificationService;
import cd.hrpay.tenancy.Tenants;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.PasswordPolicy;
import cd.hrpay.user.UserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Password recovery by a one-time link sent by email. Only the hash of the link's token is kept; a link lasts 30
 * minutes and works once. A new password signs out every browser, and the second step is still asked at sign-in.
 */
@Service
public class PasswordResets {

    static final Duration VALID = Duration.ofMinutes(30);
    /** Links one person may be sent in an hour, so the form cannot be used to flood a mailbox. */
    static final int PER_HOUR = 3;

    private record Row(long id, long userId, long companyId, OffsetDateTime expiresAt, OffsetDateTime usedAt) {}

    private final JdbcClient jdbc;
    private final Tenants tenants;
    private final TransactionTemplate tx;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final NotificationService messages;
    private final AuditService audit;
    private final UserSessions sessions;
    private final LoginAttempts attempts;
    private final AppProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PasswordResets(JdbcClient jdbc, Tenants tenants, TransactionTemplate tx, UserRepository users,
                          PasswordEncoder encoder, NotificationService messages, AuditService audit,
                          UserSessions sessions, LoginAttempts attempts, AppProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.tx = tx;
        this.users = users;
        this.encoder = encoder;
        this.messages = messages;
        this.audit = audit;
        this.sessions = sessions;
        this.attempts = attempts;
        this.properties = properties;
        this.clock = clock;
    }

    /** The sign-in page's "Forgot your password?": sends a link if the email belongs to an active person, and says nothing either way. */
    public void request(String email, String ip) {
        String key = email == null ? "" : email.trim().toLowerCase();
        Long company = tenants.companyOfEmail(key);
        if (company == null) {
            tenants.callAsPlatform(() -> {
                audit.recordAs(key, "auth.password_reset_unknown", "user", null, Map.of());
                return null;
            });
            return;
        }
        tenants.callAs(company, () -> tx.execute(s -> {
            AppUser user = users.findByEmailIgnoreCase(key).orElse(null);
            if (user == null || !user.isActive()) {
                audit.recordAs(key, "auth.password_reset_refused", "user", user == null ? null : user.getId(),
                        Map.of("reason", "inactive"));
                return null;
            }
            int recent = jdbc.sql("select count(*) from password_reset where user_id = ? and created_at > ?")
                    .params(user.getId(), now().minusHours(1)).query(Integer.class).single();
            if (recent >= PER_HOUR) {
                audit.recordAs(key, "auth.password_reset_refused", "user", user.getId(), Map.of("reason", "too_many"));
                return null;
            }
            send(user, key, ip, false);
            return null;
        }));
    }

    /**
     * Makes a link for the person and emails it; called inside their company. byProvider changes the email's wording:
     * the person did not ask for it themselves.
     */
    public NotificationService.Delivery send(AppUser user, String by, String ip, boolean byProvider) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("""
                insert into password_reset (user_id, token_hash, expires_at, requested_by, requested_ip)
                values (?, ?, ?, ?, ?)""")
                .params(user.getId(), Documents.sha256(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                        now().plus(VALID), cut(by, 320), cut(ip, 64))
                .update();
        String link = properties.publicUrl().replaceAll("/+$", "") + "/reset-password?token=" + token;
        audit.recordAs(by, "auth.password_reset_sent", "user", user.getId(), Map.of("byProvider", byProvider));
        return messages.email(user, () -> Texts.t("Choose a new HR Pay password"), () -> (byProvider
                ? Texts.t("Hello %s,\n\nYour HR Pay provider's support sent you this link to choose a new password, at your company's request.", user.getFirstName())
                : Texts.t("Hello %s,\n\nSomeone asked to choose a new password for your HR Pay account. If it was you, open this link:", user.getFirstName()))
                + "\n\n" + link + "\n\n"
                + Texts.t("The link works once, for 30 minutes. If you did not ask for it, ignore this email: your password does not change."));
    }

    /** Sets the new password from a link. Every browser signed in as the person is signed out. */
    public void confirm(String token, String password) {
        String hash = Documents.sha256((token == null ? "" : token.trim()).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        Row row = tenants.callAsPlatform(() -> jdbc.sql("""
                select id, user_id, company_id, expires_at, used_at from password_reset where token_hash = ?""")
                .param(hash).query(Row.class).optional().orElse(null));
        if (row == null || row.usedAt() != null || row.expiresAt().isBefore(now())) {
            throw new BusinessRuleException("This link no longer works. Ask for a new one from the sign-in page.");
        }
        PasswordPolicy.check(password);
        tenants.callAs(row.companyId(), () -> tx.execute(s -> {
            // Marked used first, so two clicks at the same moment cannot both set a password.
            int claimed = jdbc.sql("update password_reset set used_at = now() where id = ? and used_at is null")
                    .param(row.id()).update();
            AppUser user = users.findById(row.userId()).orElse(null);
            if (claimed == 0 || user == null || !user.isActive()) {
                throw new BusinessRuleException("This link no longer works. Ask for a new one from the sign-in page.");
            }
            user.setPasswordHash(encoder.encode(password));
            user.setMustChangePassword(false);
            users.save(user);
            jdbc.sql("update password_reset set used_at = now() where user_id = ? and used_at is null")
                    .param(user.getId()).update();
            audit.recordAs(user.getEmail(), "user.password_reset_by_link", "user", user.getId(), null);
            sessions.endAll(user.getId(), null, user.getEmail());
            attempts.succeeded(user.getEmail());
            return null;
        }));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
