package cd.hrpay.ops;

import cd.hrpay.tenancy.Tenants;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Checks the audit log's hash chain: each row's hash covers its content and the hash of the row before, so a row
 * changed or removed behind the app's back (straight in the database) breaks the chain from there on. Checked
 * every night and on the System page; a break raises an alert.
 */
@Service
public class AuditChain {

    public record Check(boolean intact, long rows, Long brokenAt, OffsetDateTime brokenRowAt, String lastHash,
                        OffsetDateTime checkedAt) {}

    private final JdbcClient jdbc;
    private final Tenants tenants;
    private final SystemAlerts alerts;
    private final JobLocks locks;

    public AuditChain(JdbcClient jdbc, Tenants tenants, SystemAlerts alerts, JobLocks locks) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.alerts = alerts;
        this.locks = locks;
    }

    public Check check() {
        return tenants.callAsPlatform(() -> {
            long rows = jdbc.sql("select count(*) from audit_log").query(Long.class).single();
            record Broken(long id, OffsetDateTime occurredAt) {}
            Broken broken = jdbc.sql("""
                    select id, occurred_at from (
                        select a.id, a.chain_seq, a.occurred_at, a.hash, a.prev_hash, lag(a.hash) over (order by a.chain_seq) as before,
                               encode(sha256(convert_to(coalesce(a.prev_hash, '') || '|' || audit_log_content(a), 'UTF8')), 'hex') as computed
                        from audit_log a) x
                    where hash is distinct from computed or prev_hash is distinct from before
                    order by chain_seq limit 1""").query(Broken.class).optional().orElse(null);
            String last = jdbc.sql("select hash from audit_log order by chain_seq desc nulls last limit 1").query(String.class).optional().orElse(null);
            return new Check(broken == null, rows, broken == null ? null : broken.id(),
                    broken == null ? null : broken.occurredAt(), last, OffsetDateTime.now());
        });
    }

    @Scheduled(cron = "0 30 2 * * *", zone = "Africa/Kinshasa")
    public void nightly() {
        locks.run("audit-chain", Duration.ofHours(1), () -> {
            Check c = check();
            if (!c.intact()) {
                alerts.raise("AUDIT_CHAIN_BROKEN", "row-" + c.brokenAt(),
                        "The audit log was changed outside the app (row " + c.brokenAt() + ")",
                        "A row of the audit log no longer matches its hash: it was edited or a row before it was deleted "
                                + "directly in the database. Keep a copy of the database as it is now and find out who had access.");
            }
        });
    }
}
