package cd.hrpay.attendance;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.notify.OtpService;
import cd.hrpay.site.GeoFence;
import cd.hrpay.site.Site;
import cd.hrpay.user.AppUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Check-in and check-out. The server decides: it uses its own clock, the request's IP address, the
 * reported GPS position, the kiosk QR token and the trusted-device cookie. The first check-in of the
 * day also needs a one-time code; later ones in the same day do not.
 */
@Service
public class CheckInService {

    public record Presence(String ip, BigDecimal lat, BigDecimal lng, Integer accuracyM, String qrToken,
                           String deviceToken) {}

    public record Verified(long siteId, boolean qrVerified, Long trustedDeviceId, String ip, BigDecimal lat,
                           BigDecimal lng, Integer accuracyM) {}

    public sealed interface StartResult permits CheckedIn, CodeSent {}

    public record CheckedIn(ClockEvent event) implements StartResult {}

    public record CodeSent(UUID challengeId, List<String> channels, OffsetDateTime expiresAt) implements StartResult {}

    static final String PURPOSE = "CHECK_IN";

    private final ClockEventRepository events;
    private final TrustedDevices devices;
    private final QrTokens qr;
    private final OtpService otp;
    private final AuditService audit;
    private final JsonMapper json;

    public CheckInService(ClockEventRepository events, TrustedDevices devices, QrTokens qr, OtpService otp,
                          AuditService audit, JsonMapper json) {
        this.events = events;
        this.devices = devices;
        this.qr = qr;
        this.otp = otp;
        this.audit = audit;
        this.json = json;
    }

    public Optional<ClockEvent> openCheckIn(Long userId) {
        return events.findLast(userId).filter(e -> e.getType() == ClockEvent.Type.IN);
    }

    @Transactional
    public StartResult startCheckIn(AppUser user, Presence presence) {
        if (openCheckIn(user.getId()).isPresent()) {
            throw new BusinessRuleException("You are already checked in.");
        }
        Verified verified = verify(user, presence, "check_in");
        LocalDate today = LocalDate.now(zone(user.getSite()));
        boolean codeDoneToday = events.findForPeriod(user.getId(), today, today).stream()
                .anyMatch(e -> e.getType() == ClockEvent.Type.IN && e.getSource() == ClockEvent.Source.SELF);
        if (codeDoneToday) {
            return new CheckedIn(record(user, ClockEvent.Type.IN, OffsetDateTime.now(), verified));
        }
        OtpService.Challenge challenge = otp.issue(user, PURPOSE, json.writeValueAsString(verified));
        return new CodeSent(challenge.id(), challenge.deliveries().stream()
                .filter(d -> !d.status().equals("FAILED")).map(d -> d.channel().name()).toList(),
                challenge.expiresAt());
    }

    @Transactional
    public ClockEvent confirmCheckIn(AppUser user, UUID challengeId, String code) {
        OtpService.Verified ok = otp.verify(challengeId, user, PURPOSE, code);
        if (openCheckIn(user.getId()).isPresent()) {
            throw new BusinessRuleException("You are already checked in.");
        }
        Verified verified = json.readValue(ok.contextJson(), Verified.class);
        // Arrival time is when the employee started, not when the code arrived.
        return record(user, ClockEvent.Type.IN, ok.createdAt(), verified);
    }

    @Transactional
    public ClockEvent checkOut(AppUser user, Presence presence) {
        openCheckIn(user.getId())
                .orElseThrow(() -> new BusinessRuleException("You are not checked in."));
        Verified verified = verify(user, presence, "check_out");
        return record(user, ClockEvent.Type.OUT, OffsetDateTime.now(), verified);
    }

    private ClockEvent record(AppUser user, ClockEvent.Type type, OffsetDateTime at, Verified v) {
        LocalDate workDate = type == ClockEvent.Type.OUT
                ? openCheckIn(user.getId()).map(ClockEvent::getWorkDate).orElse(at.atZoneSameInstant(zone(user.getSite())).toLocalDate())
                : at.atZoneSameInstant(zone(user.getSite())).toLocalDate();
        ClockEvent e = new ClockEvent(user.getId(), type, at, workDate, ClockEvent.Source.SELF, user.getEmail());
        e.setSiteId(v.siteId());
        e.setIp(v.ip());
        e.setLocation(v.lat(), v.lng(), v.accuracyM());
        e.setQrVerified(v.qrVerified());
        e.setTrustedDeviceId(v.trustedDeviceId());
        events.save(e);
        if (v.trustedDeviceId() != null) {
            devices.touch(v.trustedDeviceId());
        }
        audit.record(type == ClockEvent.Type.IN ? "attendance.check_in" : "attendance.check_out", "user", user.getId(),
                Map.of("eventId", e.getId(), "at", at.toString(), "qr", v.qrVerified(),
                        "trustedDevice", v.trustedDeviceId() != null));
        return e;
    }

    /** Applies the site's rules. Every refusal is audited with its reason. */
    Verified verify(AppUser user, Presence p, String action) {
        Site site = user.getSite();
        if (site == null || !site.isActive()) {
            throw reject(user, action, "no_site", "You are not assigned to an active site. Contact HR.", p);
        }
        Optional<TrustedDevices.Device> device = devices.find(p.deviceToken())
                .filter(d -> d.siteId() == site.getId());
        if (site.getIpRanges().isEmpty() || p.ip() == null || !site.allowsIp(p.ip())) {
            throw reject(user, action, "ip", "You must be connected to the office network of " + site.getName() + ".", p);
        }
        boolean qrOk = p.qrToken() != null && qr.verify(site.getCode(), site.getKioskKey(), p.qrToken());
        if (site.isQrRequired() && device.isEmpty() && !qrOk) {
            throw reject(user, action, "qr", "Scan the QR code shown at the entrance.", p);
        }
        if (site.isGpsRequired() && device.isEmpty()) {
            if (p.lat() == null || p.lng() == null) {
                throw reject(user, action, "gps_missing", "Allow location access to continue.", p);
            }
            if (p.accuracyM() == null || p.accuracyM() > site.getMaxAccuracyM()) {
                throw reject(user, action, "gps_accuracy",
                        "Your location is not precise enough (" + p.accuracyM() + " m). Move closer to a window and retry.", p);
            }
            GeoFence fence = site.geoFence();
            if (fence == null || !fence.contains(p.lat().doubleValue(), p.lng().doubleValue())) {
                throw reject(user, action, "gps_outside", "You are outside the authorized area of " + site.getName() + ".", p);
            }
        }
        return new Verified(site.getId(), qrOk, device.map(TrustedDevices.Device::id).orElse(null), p.ip(), p.lat(),
                p.lng(), p.accuracyM());
    }

    private PresenceRejectedException reject(AppUser user, String action, String reason, String message, Presence p) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", action);
        details.put("reason", reason);
        details.put("ip", p.ip());
        details.put("lat", p.lat());
        details.put("lng", p.lng());
        details.put("accuracyM", p.accuracyM());
        audit.recordIndependently("attendance.rejected", "user", user.getId(), details);
        return new PresenceRejectedException(reason, message);
    }

    static ZoneId zone(Site site) {
        return ZoneId.of(site == null ? "Africa/Kinshasa" : site.getTimeZone());
    }
}
