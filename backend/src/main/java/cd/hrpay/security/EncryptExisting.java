package cd.hrpay.security;

import cd.hrpay.tenancy.Tenants;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Once a data key is set, encrypts at start-up the identifiers written before it: employee files and the copies
 * kept in payslips. Values already encrypted are left alone, so this runs on every start at little cost.
 */
@Component
class EncryptExisting {

    private static final Logger log = LoggerFactory.getLogger(EncryptExisting.class);
    private static final List<String> PROFILE = List.of("cnss_number", "tax_number", "bank_account", "mobile_money_number");
    private static final List<String> SNAPSHOT = List.of("cnssNumber", "taxNumber", "bankAccount", "mobileMoneyNumber");

    private final DataCrypto crypto;
    private final JdbcClient jdbc;
    private final Tenants tenants;
    private final JsonMapper json = JsonMapper.builder().build();

    EncryptExisting(DataCrypto crypto, JdbcClient jdbc, Tenants tenants) {
        this.crypto = crypto;
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        if (!crypto.enabled()) {
            return;
        }
        int[] counts = tenants.callAsPlatform(() -> new int[] {profiles(), payslips()});
        if (counts[0] + counts[1] > 0) {
            log.info("Encrypted the identifiers of {} employee file(s) and {} payslip(s).", counts[0], counts[1]);
        }
    }

    int profiles() {
        String plain = String.join(" or ", PROFILE.stream().map(c -> "(" + c + " <> '' and " + c + " not like 'enc:v1:%')").toList());
        List<Map<String, Object>> rows = jdbc.sql("select user_id, " + String.join(", ", PROFILE) + " from employee_profile where " + plain)
                .query().listOfRows();
        for (Map<String, Object> r : rows) {
            jdbc.sql("update employee_profile set " + String.join(", ", PROFILE.stream().map(c -> c + " = ?").toList()) + " where user_id = ?")
                    .params(crypto.encrypt((String) r.get("cnss_number")), crypto.encrypt((String) r.get("tax_number")),
                            crypto.encrypt((String) r.get("bank_account")), crypto.encrypt((String) r.get("mobile_money_number")),
                            r.get("user_id"))
                    .update();
        }
        return rows.size();
    }

    @SuppressWarnings("unchecked")
    int payslips() {
        String plain = String.join(" or ", SNAPSHOT.stream()
                .map(k -> "(coalesce(snapshot ->> '" + k + "', '') <> '' and snapshot ->> '" + k + "' not like 'enc:v1:%')").toList());
        List<Map<String, Object>> rows = jdbc.sql("select id, snapshot::text as snapshot from payslip where " + plain).query().listOfRows();
        for (Map<String, Object> r : rows) {
            Map<String, Object> snapshot = json.readValue((String) r.get("snapshot"), Map.class);
            for (String k : SNAPSHOT) {
                if (snapshot.get(k) instanceof String v) {
                    snapshot.put(k, crypto.encrypt(v));
                }
            }
            jdbc.sql("update payslip set snapshot = ?::jsonb where id = ?").params(json.writeValueAsString(snapshot), r.get("id")).update();
        }
        return rows.size();
    }
}
