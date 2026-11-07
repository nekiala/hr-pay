package cd.hrpay.platform;

import cd.hrpay.common.BusinessRuleException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The licence of an on-premise installation: a file signed by the provider (Ed25519) that names the customer, the
 * modules, the number of employees and companies, and the end date. It is checked here, offline, against the
 * provider's public key built into the app. After the end date a grace period keeps everything working; after it,
 * changes in the licensed modules stop until a new licence is installed. Reading and exports keep working.
 */
@Service
public class Licences {

    /** What a licence grants. Signed by the provider; never edited by the customer. */
    public record Licence(String id, String licensee, LocalDate issued, LocalDate expires, Integer maxEmployees,
                          Integer maxCompanies, List<String> modules, Integer graceDays) {
        int grace() {
            return graceDays == null ? 30 : graceDays;
        }
    }

    public enum State { VALID, EXPIRING, GRACE, EVALUATION, LOCKED }

    /** The installation's licence as it stands today. */
    public record Status(State state, Licence licence, LocalDate evaluationEnds, LocalDate locksOn, long daysLeft,
                         String message) {
        public boolean locked() {
            return state == State.LOCKED;
        }
    }

    static final int EVALUATION_DAYS = 30;
    static final int EVALUATION_EMPLOYEES = 50;
    static final Set<String> MODULES = Set.of("ATTENDANCE", "PAYROLL", "EVALUATIONS");
    private static final ZoneId ZONE = ZoneId.of("Africa/Kinshasa");

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final PublicKey publicKey;

    public Licences(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.publicKey = loadPublicKey();
    }

    /** The provider's public key, from licence/public-key.txt on the class path; null if the build has none. */
    private static PublicKey loadPublicKey() {
        try (InputStream in = Licences.class.getResourceAsStream("/licence/public-key.txt")) {
            if (in == null) {
                return null;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.US_ASCII).lines()
                    .filter(l -> !l.isBlank() && !l.startsWith("#")).findFirst().orElse("").trim();
            if (text.isEmpty()) {
                return null;
            }
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(text)));
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("The licence public key built into the app cannot be read", e);
        }
    }

    /**
     * Reads and checks a licence file: the last line that is not a comment holds the licence and its signature, as
     * base64url(json) "." base64url(signature).
     */
    public Licence verify(String file) {
        if (publicKey == null) {
            throw new BusinessRuleException("This version of HR Pay has no licence key built in. Ask your provider for an official build.");
        }
        String line = file == null ? "" : file.lines().map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#")).reduce((a, b) -> b).orElse("");
        int dot = line.indexOf('.');
        if (dot <= 0) {
            throw new BusinessRuleException("This is not an HR Pay licence file.");
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(line.substring(0, dot));
            byte[] signature = Base64.getUrlDecoder().decode(line.substring(dot + 1));
            Signature check = Signature.getInstance("Ed25519");
            check.initVerify(publicKey);
            check.update(payload);
            if (!check.verify(signature)) {
                throw new BusinessRuleException("The licence signature is not valid. The file may have been changed.");
            }
            Licence licence = json.readValue(payload, Licence.class);
            if (licence.expires() == null || licence.licensee() == null || licence.modules() == null
                    || !MODULES.containsAll(licence.modules())) {
                throw new BusinessRuleException("The licence is incomplete.");
            }
            return licence;
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new BusinessRuleException("This is not an HR Pay licence file.");
        } catch (tools.jackson.core.JacksonException e) {
            throw new BusinessRuleException("The licence is incomplete.");
        }
    }

    /** Checks and installs a licence file, replacing the previous one. */
    public Licence install(String file, String by) {
        Licence licence = verify(file);
        jdbc.sql("update installation set licence = ?, licence_installed_at = now(), licence_installed_by = ?")
                .params(file.trim(), by).update();
        return licence;
    }

    public Status status() {
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        record Row(OffsetDateTime startedAt, String licence) {}
        Row row = jdbc.sql("select started_at, licence from installation").query(Row.class).single();
        Licence licence = null;
        if (row.licence() != null) {
            try {
                licence = verify(row.licence());
            } catch (BusinessRuleException e) {
                licence = null;
            }
        }
        if (licence == null) {
            LocalDate ends = row.startedAt().atZoneSameInstant(ZONE).toLocalDate().plusDays(EVALUATION_DAYS);
            long left = ChronoUnit.DAYS.between(today, ends);
            return left >= 0
                    ? new Status(State.EVALUATION, null, ends, ends, left,
                            "Evaluation copy: " + left + " day(s) left. Install the licence file from your provider.")
                    : new Status(State.LOCKED, null, ends, ends, 0,
                            "The evaluation period ended. Install the licence file from your provider to make changes again.");
        }
        LocalDate locks = licence.expires().plusDays(licence.grace());
        long toEnd = ChronoUnit.DAYS.between(today, licence.expires());
        if (toEnd >= 0) {
            return toEnd <= 30
                    ? new Status(State.EXPIRING, licence, null, locks, toEnd,
                            "The licence ends on " + licence.expires() + ". Ask your provider for the renewal.")
                    : new Status(State.VALID, licence, null, locks, toEnd, null);
        }
        long toLock = ChronoUnit.DAYS.between(today, locks);
        return toLock >= 0
                ? new Status(State.GRACE, licence, null, locks, toLock, "The licence ended on " + licence.expires()
                        + ". Everything keeps working until " + locks + "; install the renewal before then.")
                : new Status(State.LOCKED, licence, null, locks, 0, "The licence ended on " + licence.expires()
                        + ". Changes in Attendance, Payroll and Evaluations are stopped until the renewal is installed;"
                        + " reading and exports still work.");
    }
}
