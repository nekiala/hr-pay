package cd.hrpay.attendance;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Shared office computers registered by an administrator. The browser keeps a random token in an
 * HttpOnly cookie; only its SHA-256 hash is stored.
 */
@Service
public class TrustedDevices {

    public static final String COOKIE = "hrpay_device";

    public record Device(long id, long siteId, String name, boolean active, String createdBy, OffsetDateTime createdAt,
                         OffsetDateTime lastUsedAt) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    public TrustedDevices(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Registers a device and returns the raw token for the cookie. */
    public String register(long siteId, String name) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        long id = jdbc.sql("""
                insert into trusted_device (site_id, name, token_hash, created_by)
                values (:site, :name, :hash, :by) returning id""")
                .param("site", siteId).param("name", name).param("hash", hash(token))
                .param("by", SecurityContextHolder.getContext().getAuthentication().getName())
                .query(Long.class).single();
        audit.record("device.registered", "trusted_device", id, Map.of("siteId", siteId, "name", name));
        return token;
    }

    public Optional<Device> find(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("select * from trusted_device where token_hash = :hash and active")
                .param("hash", hash(token)).query(this::map).optional();
    }

    public void touch(long id) {
        jdbc.sql("update trusted_device set last_used_at = now() where id = :id").param("id", id).update();
    }

    public List<Device> list(long siteId) {
        return jdbc.sql("select * from trusted_device where site_id = :site order by created_at desc")
                .param("site", siteId).query(this::map).list();
    }

    public void deactivate(long siteId, long id) {
        int n = jdbc.sql("update trusted_device set active = false where id = :id and site_id = :site")
                .param("id", id).param("site", siteId).update();
        if (n == 0) {
            throw new NotFoundException("Device not found.");
        }
        audit.record("device.deactivated", "trusted_device", id, Map.of("siteId", siteId));
    }

    private Device map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Device(rs.getLong("id"), rs.getLong("site_id"), rs.getString("name"), rs.getBoolean("active"),
                rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("last_used_at", OffsetDateTime.class));
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
