package cd.hrpay.integration;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.tenancy.Tenants;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Access keys of the public API. A key acts for its company with the permissions chosen when it was made (never more
 * than its maker had). The secret is shown once; only its SHA-256 is kept. A key reads {@code hrp_<prefix>_<secret>}.
 */
@Service
public class ApiKeys {

    /** What a key can be given: the permissions the public API checks. */
    public static final Map<String, String> SCOPES = scopes();

    private static Map<String, String> scopes() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("user.read", "Read employees and the structure");
        m.put("attendance.read.all", "Read attendance");
        m.put("attendance.correct", "Send check-ins and check-outs");
        m.put("leave.manage", "Read leave requests");
        m.put("payslip.read.all", "Read payrolls and payslips");
        m.put("payroll.run", "Add the month's pay entries");
        m.put("accounting.export", "Read the accounting entries");
        return m;
    }

    public record Key(long id, String name, String prefix, List<String> permissions, String createdBy, OffsetDateTime createdAt,
                      LocalDate expiresOn, OffsetDateTime lastUsedAt, String lastUsedIp, OffsetDateTime revokedAt) {}

    public record Created(Key key, String secret) {}

    /** Who a request with a valid key acts as. */
    public record Caller(long keyId, long companyId, String name, List<String> permissions) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final JdbcClient jdbc;
    private final Tenants tenants;
    private final AuditService audit;
    private final Map<Long, OffsetDateTime> touched = new ConcurrentHashMap<>();

    public ApiKeys(JdbcClient jdbc, Tenants tenants, AuditService audit) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.audit = audit;
    }

    private static final String SELECT = """
            select id, name, prefix, permissions, created_by, created_at, expires_on, last_used_at, last_used_ip, revoked_at
            from api_key""";

    private Key map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Key(rs.getLong("id"), rs.getString("name"), rs.getString("prefix"),
                List.of((String[]) rs.getArray("permissions").getArray()), rs.getString("created_by"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("expires_on", LocalDate.class),
                rs.getObject("last_used_at", OffsetDateTime.class), rs.getString("last_used_ip"),
                rs.getObject("revoked_at", OffsetDateTime.class));
    }

    public List<Key> list() {
        return jdbc.sql(SELECT + " order by revoked_at nulls first, id desc").query((rs, i) -> map(rs)).list();
    }

    @Transactional
    public Created create(String name, List<String> permissions, LocalDate expiresOn) {
        if (permissions == null || permissions.isEmpty()) {
            throw new BusinessRuleException("Choose at least one permission for the key.");
        }
        for (String p : permissions) {
            if (!SCOPES.containsKey(p)) {
                throw new BusinessRuleException("Unknown permission %s.".formatted(p));
            }
            if (!CurrentUser.has(p)) {
                throw new BusinessRuleException("You cannot give a key a permission you do not have: %s.".formatted(p));
            }
        }
        if (expiresOn != null && !expiresOn.isAfter(LocalDate.now())) {
            throw new BusinessRuleException("The expiry date must be in the future.");
        }
        String prefix = random(10);
        String secret = random(32);
        String token = "hrp_" + prefix + "_" + secret;
        long id = jdbc.sql("""
                insert into api_key (name, prefix, secret_hash, permissions, created_by, expires_on)
                values (?, ?, ?, ?, ?, ?) returning id""")
                .params(name.trim(), prefix, sha256(token), permissions.stream().distinct().toArray(String[]::new),
                        CurrentUser.email(), expiresOn)
                .query(Long.class).single();
        audit.record("api_key.created", "api_key", id, Map.of("name", name.trim(), "permissions", permissions));
        return new Created(find(id), token);
    }

    public Key find(long id) {
        return jdbc.sql(SELECT + " where id = ?").param(id).query((rs, i) -> map(rs)).optional()
                .orElseThrow(() -> new NotFoundException("Key not found."));
    }

    @Transactional
    public Key revoke(long id) {
        Key k = find(id);
        if (k.revokedAt() == null) {
            jdbc.sql("update api_key set revoked_at = now(), revoked_by = ? where id = ?").params(CurrentUser.email(), id).update();
            audit.record("api_key.revoked", "api_key", id, Map.of("name", k.name()));
        }
        return find(id);
    }

    /** The caller of a token, if the key exists, is not revoked or expired, and its company is active. */
    public Optional<Caller> authenticate(String token, String ip) {
        if (token == null || !token.matches("hrp_[A-Za-z0-9]{10}_[A-Za-z0-9]{32}")) {
            return Optional.empty();
        }
        String prefix = token.substring(4, 14);
        record Row(long id, long companyId, String name, String secretHash, String[] permissions, LocalDate expiresOn,
                   OffsetDateTime revokedAt, String companyStatus) {}
        Optional<Row> row = tenants.callAsPlatform(() -> jdbc.sql("""
                select k.id, k.company_id, k.name, k.secret_hash, k.permissions, k.expires_on, k.revoked_at, c.status as company_status
                from api_key k join company c on c.id = k.company_id where k.prefix = ?""")
                .param(prefix).query((rs, i) -> new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        (String[]) rs.getArray(5).getArray(), rs.getObject(6, LocalDate.class),
                        rs.getObject(7, OffsetDateTime.class), rs.getString(8))).optional());
        if (row.isEmpty()) {
            return Optional.empty();
        }
        Row r = row.get();
        boolean same = MessageDigest.isEqual(sha256(token).getBytes(StandardCharsets.US_ASCII),
                r.secretHash().getBytes(StandardCharsets.US_ASCII));
        if (!same || r.revokedAt() != null || (r.expiresOn() != null && !r.expiresOn().isAfter(LocalDate.now()))
                || "CLOSED".equals(r.companyStatus())) {
            return Optional.empty();
        }
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime last = touched.get(r.id());
        if (last == null || last.isBefore(now.minusMinutes(1))) {
            touched.put(r.id(), now);
            tenants.runAs(r.companyId(), () -> jdbc.sql("update api_key set last_used_at = now(), last_used_ip = ? where id = ?")
                    .params(ip, r.id()).update());
        }
        return Optional.of(new Caller(r.id(), r.companyId(), r.name(), List.of(r.permissions())));
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String random(int n) {
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            b.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return b.toString();
    }
}
