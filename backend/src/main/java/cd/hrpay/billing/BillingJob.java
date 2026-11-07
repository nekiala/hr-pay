package cd.hrpay.billing;

import cd.hrpay.ops.JobLocks;
import cd.hrpay.platform.Entitlements;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Every morning on a SaaS installation: the invoices that are due, then the suspensions for unpaid ones. */
@Service
public class BillingJob {

    private static final Logger log = LoggerFactory.getLogger(BillingJob.class);
    private static final ZoneId ZONE = ZoneId.of("Africa/Kinshasa");

    private final Billing billing;
    private final JobLocks locks;
    private final Entitlements entitlements;
    private final Clock clock;

    public BillingJob(Billing billing, JobLocks locks, Entitlements entitlements, Clock clock) {
        this.billing = billing;
        this.locks = locks;
        this.entitlements = entitlements;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.billing.cron:0 0 7 * * *}", zone = "Africa/Kinshasa")
    public void daily() {
        if (entitlements.mode() != Entitlements.Mode.SAAS) {
            return; // on premise the licence covers the use
        }
        locks.run("billing", Duration.ofMinutes(30), () -> {
            Billing.RunResult r = run(LocalDate.now(clock.withZone(ZONE)));
            if (!r.errors().isEmpty()) {
                throw new IllegalStateException(r.errors().size() + " invoice(s) could not be issued: "
                        + String.join("; ", r.errors()));
            }
        });
    }

    public Billing.RunResult run(LocalDate today) {
        Billing.RunResult r = billing.daily(today, companyId -> billing.issue(companyId, today, "system"));
        if (r.issued() > 0 || r.suspended() > 0) {
            log.info("Billing: {} invoice(s) issued, {} company(ies) made read-only", r.issued(), r.suspended());
        }
        return r;
    }
}
