package cd.hrpay.attendance;

import cd.hrpay.audit.AuditService;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.site.GeoFence;
import cd.hrpay.site.Site;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Check-ins and check-outs made on the phone while it had no connection, sent when it is back online. The office
 * network cannot be checked afterwards and no code can be sent, so the site decides what happens to them: refused
 * (OFF), sent to the manager as a correction request (REVIEW, the default), or recorded at once when the phone's
 * location at that moment was inside the site's area (IN_AREA), and sent for review otherwise.
 */
@Service
public class OfflinePunches {

    /** How old an offline check-in may be when it arrives, and how far ahead a phone's clock may run. */
    static final Duration MAX_AGE = Duration.ofHours(72);
    static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    public record Punch(String clientId, ClockEvent.Type type, OffsetDateTime at, BigDecimal lat, BigDecimal lng, Integer accuracyM) {}

    public record Outcome(String clientId, String outcome, String detail) {}

    private final JdbcClient jdbc;
    private final UserService users;
    private final ClockIngest ingest;
    private final CorrectionRequests corrections;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OfflinePunches(JdbcClient jdbc, UserService users, ClockIngest ingest, CorrectionRequests corrections, AuditService audit,
                          org.springframework.transaction.PlatformTransactionManager txManager, Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.ingest = ingest;
        this.corrections = corrections;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** Takes each punch on its own, oldest first; a punch already received gives its first answer again. */
    public List<Outcome> receive(List<Punch> punches) {
        AppUser user = users.find(CurrentUser.id());
        List<Outcome> out = new ArrayList<>();
        punches.stream().sorted(java.util.Comparator.comparing(Punch::at)).forEach(p -> {
            try {
                out.add(tx.execute(s -> one(user, p)));
            } catch (DataAccessException e) {
                // Sent twice at the same moment: the other request has it.
                out.add(previous(user, p.clientId()));
            } catch (RuntimeException e) {
                // Nothing kept: the phone keeps the punch and sends it again later.
                out.add(new Outcome(p.clientId(), "RETRY", e.getMessage()));
            }
        });
        // The answers in the person's language; what is stored stays in English, like the audit log.
        return out.stream().map(o -> new Outcome(o.clientId(), o.outcome(),
                o.detail() == null ? null : cd.hrpay.i18n.Texts.translate(o.detail()))).toList();
    }

    private Outcome previous(AppUser user, String clientId) {
        return jdbc.sql("select client_id, outcome, detail from offline_punch where user_id = ? and client_id = ?")
                .params(user.getId(), clientId).query(Outcome.class).optional()
                .orElse(new Outcome(clientId, "REFUSED", "Try again."));
    }

    private Outcome one(AppUser user, Punch p) {
        var seen = jdbc.sql("select client_id, outcome, detail from offline_punch where user_id = ? and client_id = ?")
                .params(user.getId(), p.clientId()).query(Outcome.class).optional();
        if (seen.isPresent()) {
            return seen.get();
        }
        Site site = user.getSite();
        OffsetDateTime now = OffsetDateTime.now(clock);
        String mode = site == null ? "OFF" : site.getOfflineCheckin();
        if (site == null || !site.isActive()) {
            return save(user, p, "REFUSED", "You are not assigned to an active site. Contact HR.", null, null);
        }
        if (mode.equals("OFF")) {
            return save(user, p, "REFUSED", "Check-ins without a connection are not allowed at your site.", null, null);
        }
        if (p.at().isAfter(now.plus(CLOCK_SKEW))) {
            return save(user, p, "REFUSED", "The phone's clock is ahead: set it to the right time.", null, null);
        }
        if (p.at().isBefore(now.minus(MAX_AGE))) {
            return save(user, p, "REFUSED", "Older than three days: ask for a correction instead.", null, null);
        }
        boolean inArea = inArea(site, p);
        if (mode.equals("IN_AREA") && inArea) {
            ClockIngest.Outcome o = ingest.record(user, p.type(), p.at(), ClockEvent.Source.OFFLINE, site.getId(),
                    "Offline check-in (location in the site's area)", user.getEmail());
            if (o.event() != null) {
                if (p.lat() != null) {
                    o.event().setLocation(p.lat(), p.lng(), p.accuracyM());
                }
                return save(user, p, "RECORDED", null, o.event().getId(), null);
            }
            return save(user, p, "REFUSED", "Not recorded: %s.".formatted(o.ignored()), null, null);
        }
        // Everything else goes to the manager, with what the phone knew.
        ZoneId zone = CheckInService.zone(site);
        ZonedDateTime local = p.at().atZoneSameInstant(zone);
        String where = p.lat() == null ? "location not available"
                : inArea ? "location inside the site's area" : "location outside the site's area";
        String reason = "%s made offline on the phone at %s (%s).".formatted(p.type() == ClockEvent.Type.IN ? "Check-in" : "Check-out",
                local.toLocalTime().withNano(0), where);
        if (!CurrentUser.has("attendance.correct.request")) {
            return save(user, p, "REFUSED", "You cannot ask for corrections.", null, null);
        }
        var r = corrections.create(null, local.toLocalDate(), "ADD", p.type(), local.toLocalTime().withSecond(0).withNano(0), null, reason);
        return save(user, p, "REVIEW", "Sent to your manager for approval.", null, r.id());
    }

    private static boolean inArea(Site site, Punch p) {
        if (p.lat() == null || p.lng() == null || p.accuracyM() == null || p.accuracyM() > site.getMaxAccuracyM()) {
            return false;
        }
        GeoFence fence = site.geoFence();
        return fence != null && fence.contains(p.lat().doubleValue(), p.lng().doubleValue());
    }

    private Outcome save(AppUser user, Punch p, String outcome, String detail, Long eventId, Long requestId) {
        jdbc.sql("""
                insert into offline_punch (user_id, client_id, type, occurred_at, lat, lng, accuracy_m, outcome, detail,
                    clock_event_id, correction_request_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(user.getId(), p.clientId(), p.type().name(), p.at(), p.lat(), p.lng(), p.accuracyM(), outcome, detail,
                        eventId, requestId).update();
        audit.record("attendance.offline_received", "user", user.getId(), Map.of("type", p.type().name(), "at", p.at().toString(),
                "outcome", outcome));
        return new Outcome(p.clientId(), outcome, detail);
    }
}
