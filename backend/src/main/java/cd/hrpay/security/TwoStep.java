package cd.hrpay.security;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.notify.OtpService;
import cd.hrpay.user.AppUser;
import java.io.Serializable;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import cd.hrpay.notify.MailServers;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * The second step at sign-in: a six-digit code from an authenticator app, or one sent by email (and WhatsApp when
 * set up). It is required for the members of the roles marked for it (administrators, HR and finance by default),
 * and anyone else may turn it on. Ten one-time recovery codes stand in for a lost phone; an administrator can also
 * reset a person's second step.
 */
@Service
public class TwoStep {

    public enum Method { TOTP, CODE }

    public record Status(boolean enabled, Method method, boolean required, int recoveryCodesLeft,
                         OffsetDateTime confirmedAt, boolean codesAvailable) {}

    /** A sign-in waiting for its second step, kept in the HTTP session. */
    public record Pending(long userId, long companyId, String email, Method method, UUID challenge, boolean setup,
                          int attempts, OffsetDateTime startedAt, String via) implements Serializable {
        Pending withAttempt() {
            return new Pending(userId, companyId, email, method, challenge, setup, attempts + 1, startedAt, via);
        }

        Pending with(Method m, UUID c) {
            return new Pending(userId, companyId, email, m, c, setup, attempts, startedAt, via);
        }
    }

    public record Setup(Method method, String secret, String uri) {}

    static final String SESSION_KEY = "hrpay.two-step";
    static final int MAX_ATTEMPTS = 5;
    static final int RECOVERY_CODES = 10;

    private final JdbcClient jdbc;
    private final DataCrypto crypto;
    private final PasswordEncoder encoder;
    private final OtpService otp;
    private final AuditService audit;
    private final MailServers mail;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private volatile boolean enforced;

    public TwoStep(JdbcClient jdbc, DataCrypto crypto, PasswordEncoder encoder, OtpService otp, AuditService audit,
                   MailServers mail, Clock clock,
                   @Value("${app.security.two-step-enforced:true}") boolean enforced) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.encoder = encoder;
        this.otp = otp;
        this.audit = audit;
        this.mail = mail;
        this.clock = clock;
        this.enforced = enforced;
    }

    /** Whether the roles' requirement applies; tests switch it to check both ways. */
    public void setEnforced(boolean enforced) {
        this.enforced = enforced;
    }

    public boolean enforced() {
        return enforced;
    }

    /** A code by email only reaches people when their company has an email server; otherwise only the app is offered. */
    public boolean codesAvailable(long companyId) {
        return mail.available(companyId);
    }

    public boolean required(AppUser user) {
        if (!enforced) {
            return false;
        }
        return jdbc.sql("""
                select count(*) from user_role ur join role r on r.id = ur.role_id
                where ur.user_id = ? and r.two_step_required""").param(user.getId()).query(Integer.class).single() > 0;
    }

    private record Row(String method, String totpSecret, String pendingSecret, Long lastStep, String[] recoveryCodes,
                       OffsetDateTime confirmedAt) {}

    private Optional<Row> row(long userId) {
        return jdbc.sql("""
                select method, totp_secret, pending_secret, last_step, recovery_codes, confirmed_at
                from user_two_step where user_id = ?""").param(userId)
                .query((rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), (Long) rs.getObject(4),
                        (String[]) rs.getArray(5).getArray(), rs.getObject(6, OffsetDateTime.class)))
                .optional();
    }

    public Status status(AppUser user) {
        Optional<Row> r = row(user.getId()).filter(x -> x.confirmedAt() != null);
        return new Status(r.isPresent(), r.map(x -> Method.valueOf(x.method())).orElse(null), required(user),
                r.map(x -> x.recoveryCodes().length).orElse(0), r.map(Row::confirmedAt).orElse(null), codesAvailable(user.getCompanyId()));
    }

    /** Whether the sign-in needs a second step, and the step to ask for. */
    public Optional<Pending> start(AppUser user, long companyId, String via) {
        Optional<Row> r = row(user.getId()).filter(x -> x.confirmedAt() != null);
        if (r.isEmpty() && !required(user)) {
            return Optional.empty();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (r.isEmpty()) {
            return Optional.of(new Pending(user.getId(), companyId, user.getEmail(), null, null, true, 0, now, via));
        }
        Method m = Method.valueOf(r.get().method());
        UUID challenge = m == Method.CODE ? otp.issue(user, "SIGN_IN", null).id() : null;
        return Optional.of(new Pending(user.getId(), companyId, user.getEmail(), m, challenge, false, 0, now, via));
    }

    /** Re-sends the code of a sign-in by code. */
    public Pending resend(Pending p, AppUser user) {
        if (p.method() != Method.CODE) {
            throw new BusinessRuleException("This sign-in uses an authenticator app.");
        }
        return p.with(Method.CODE, otp.issue(user, "SIGN_IN", null).id());
    }

    /** Starts setting up a method: a new authenticator secret, or a code sent now. Nothing changes until confirmed. */
    public Setup beginSetup(AppUser user, Method method) {
        if (method == Method.CODE) {
            if (!codesAvailable(user.getCompanyId())) {
                throw new BusinessRuleException("Codes by email are not available on this installation: use an authenticator app.");
            }
            UUID id = otp.issue(user, "TWO_STEP_SETUP", null).id();
            upsertPending(user, null);
            return new Setup(Method.CODE, id.toString(), null);
        }
        String secret = Totp.newSecret();
        upsertPending(user, crypto.encrypt(secret));
        return new Setup(Method.TOTP, secret, Totp.uri("HR Pay", user.getEmail(), secret));
    }

    private void upsertPending(AppUser user, String pendingSecret) {
        jdbc.sql("""
                insert into user_two_step (user_id, method, pending_secret) values (?, 'TOTP', ?)
                on conflict (user_id) do update set pending_secret = excluded.pending_secret, updated_at = now()""")
                .params(user.getId(), pendingSecret).update();
    }

    /** Confirms the method with a first code and returns the recovery codes, shown once. */
    public List<String> confirm(AppUser user, Method method, String code, String challenge) {
        Row r = row(user.getId()).orElseThrow(() -> new BusinessRuleException("Start the set-up again."));
        String secret = null;
        long step = 0;
        if (method == Method.TOTP) {
            if (r.pendingSecret() == null) {
                throw new BusinessRuleException("Start the set-up again.");
            }
            secret = crypto.decrypt(r.pendingSecret());
            step = Totp.match(secret, code, clock.instant().getEpochSecond());
            if (step < 0) {
                throw new BusinessRuleException("The code is not correct. Check the time on your phone and try the newest code.");
            }
        } else {
            otp.verify(parse(challenge), user, "TWO_STEP_SETUP", code);
        }
        List<String> codes = new ArrayList<>();
        String[] hashes = newCodes(codes);
        jdbc.sql("""
                update user_two_step set method = ?, totp_secret = ?, pending_secret = null, last_step = ?,
                       recovery_codes = ?, confirmed_at = now(), updated_at = now() where user_id = ?""")
                .params(method.name(), secret == null ? null : crypto.encrypt(secret), method == Method.TOTP ? step : null,
                        hashes, user.getId()).update();
        audit.recordAs(user.getEmail(), "auth.two_step_enabled", "user", user.getId(), Map.of("method", method.name()));
        return codes;
    }

    /** Replaces the recovery codes with ten new ones, shown once; the old ones stop working. */
    public List<String> newRecoveryCodes(AppUser user) {
        row(user.getId()).filter(x -> x.confirmedAt() != null)
                .orElseThrow(() -> new BusinessRuleException("Turn on the second step first."));
        List<String> codes = new ArrayList<>();
        jdbc.sql("update user_two_step set recovery_codes = ?, updated_at = now() where user_id = ?")
                .params(newCodes(codes), user.getId()).update();
        audit.recordAs(user.getEmail(), "auth.recovery_codes_renewed", "user", user.getId(), Map.of());
        return codes;
    }

    private String[] newCodes(List<String> codes) {
        String[] hashes = new String[RECOVERY_CODES];
        for (int i = 0; i < RECOVERY_CODES; i++) {
            String c = recoveryCode();
            codes.add(c);
            hashes[i] = encoder.encode(c);
        }
        return hashes;
    }

    /** Checks the second step of a sign-in: the app's code, the code sent, or a recovery code. */
    public boolean verify(Pending p, AppUser user, String code) {
        Row r = row(user.getId()).filter(x -> x.confirmedAt() != null).orElse(null);
        if (r == null || code == null || code.isBlank()) {
            return false;
        }
        String c = code.trim();
        if (c.contains("-")) {
            return useRecoveryCode(user, r, c);
        }
        if (p.method() == Method.TOTP) {
            long step = Totp.match(crypto.decrypt(r.totpSecret()), c, clock.instant().getEpochSecond());
            // A code works once: the step must move on from the last one used.
            if (step < 0 || (r.lastStep() != null && step <= r.lastStep())) {
                return false;
            }
            jdbc.sql("update user_two_step set last_step = ? where user_id = ?").params(step, user.getId()).update();
            return true;
        }
        try {
            otp.verify(p.challenge(), user, "SIGN_IN", c);
            return true;
        } catch (BusinessRuleException e) {
            return false;
        }
    }

    private boolean useRecoveryCode(AppUser user, Row r, String code) {
        String normalized = code.toUpperCase().replaceAll("\\s", "");
        List<String> left = new ArrayList<>(List.of(r.recoveryCodes()));
        for (String hash : left) {
            if (encoder.matches(normalized, hash)) {
                left.remove(hash);
                jdbc.sql("update user_two_step set recovery_codes = ? where user_id = ?")
                        .params(left.toArray(String[]::new), user.getId()).update();
                audit.recordAs(user.getEmail(), "auth.recovery_code_used", "user", user.getId(), Map.of("left", left.size()));
                return true;
            }
        }
        return false;
    }

    /** Turns the second step off: by its owner when their roles do not require it, or by an administrator. */
    public void remove(AppUser user, String by) {
        int n = jdbc.sql("delete from user_two_step where user_id = ?").param(user.getId()).update();
        if (n > 0) {
            audit.record("auth.two_step_removed", "user", user.getId(), Map.of("by", by));
        }
    }

    private String recoveryCode() {
        String letters = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                b.append('-');
            }
            b.append(letters.charAt(random.nextInt(letters.length())));
        }
        return b.toString();
    }

    private static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (RuntimeException e) {
            throw new BusinessRuleException("Start the set-up again.");
        }
    }
}
