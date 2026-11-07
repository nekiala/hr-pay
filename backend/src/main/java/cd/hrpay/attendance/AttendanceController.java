package cd.hrpay.attendance;

import cd.hrpay.security.CurrentUser;
import cd.hrpay.site.Site;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/attendance")
public class AttendanceController {

    public record PresenceInput(@DecimalMin("-90") @DecimalMax("90") BigDecimal lat,
                                @DecimalMin("-180") @DecimalMax("180") BigDecimal lng,
                                Integer accuracyM, String qrToken) {}

    public record ConfirmInput(@NotNull UUID challengeId, @NotBlank String code) {}

    public record EventView(Long id, ClockEvent.Type type, OffsetDateTime occurredAt, LocalDate workDate,
                            ClockEvent.Source source, boolean qrVerified, boolean trustedDevice, String note) {
        public static EventView of(ClockEvent e) {
            return new EventView(e.getId(), e.getType(), e.getOccurredAt(), e.getWorkDate(), e.getSource(),
                    e.isQrVerified(), e.getTrustedDeviceId() != null, e.getNote());
        }
    }

    public record SiteRules(String name, String timeZone, boolean qrRequired, boolean gpsRequired, int maxAccuracyM) {}

    public record Status(boolean checkedIn, OffsetDateTime since, SiteRules site, boolean trustedDevice,
                         String trustedDeviceName, List<EventView> today, MissionNow mission) {}

    /** An approved mission happening now: the day can be ended from the mission's location. */
    public record MissionNow(Long id, String location, java.time.LocalTime startTime, java.time.LocalTime endTime) {}

    public record StartResponse(String status, EventView event, UUID challengeId, List<String> channels,
                                OffsetDateTime expiresAt) {}

    private final CheckInService checkIn;
    private final ClockEventRepository events;
    private final TrustedDevices devices;
    private final UserService users;
    private final cd.hrpay.mission.MissionService missions;

    public AttendanceController(CheckInService checkIn, ClockEventRepository events, TrustedDevices devices,
                                UserService users, cd.hrpay.mission.MissionService missions) {
        this.missions = missions;
        this.checkIn = checkIn;
        this.events = events;
        this.devices = devices;
        this.users = users;
    }

    @GetMapping("/status")
    @Transactional(readOnly = true)
    public Status status(@CookieValue(name = TrustedDevices.COOKIE, required = false) String deviceToken) {
        AppUser user = users.find(CurrentUser.id());
        Site site = user.getSite();
        LocalDate today = LocalDate.now(CheckInService.zone(site));
        var open = checkIn.openCheckIn(user.getId());
        var device = devices.find(deviceToken).filter(d -> site != null && d.siteId() == site.getId());
        return new Status(open.isPresent(), open.map(ClockEvent::getOccurredAt).orElse(null),
                site == null ? null : new SiteRules(site.getName(), site.getTimeZone(), site.isQrRequired(),
                        site.isGpsRequired(), site.getMaxAccuracyM()),
                device.isPresent(), device.map(TrustedDevices.Device::name).orElse(null),
                events.findForPeriod(user.getId(), today.minusDays(1), today).stream()
                        .filter(e -> e.getWorkDate().equals(today) || open.map(o -> o.getWorkDate().equals(e.getWorkDate())).orElse(false))
                        .map(EventView::of).toList(),
                missions.activeMission(user, OffsetDateTime.now())
                        .map(m -> new MissionNow(m.getId(), m.getLocation(), m.getStartTime(), m.getEndTime()))
                        .orElse(null));
    }

    @PostMapping("/check-in")
    public StartResponse checkIn(@Valid @RequestBody PresenceInput body, HttpServletRequest request,
                                 @CookieValue(name = TrustedDevices.COOKIE, required = false) String deviceToken) {
        AppUser user = users.find(CurrentUser.id());
        return switch (checkIn.startCheckIn(user, presence(body, request, deviceToken))) {
            case CheckInService.CheckedIn done -> new StartResponse("CHECKED_IN", EventView.of(done.event()), null, null, null);
            case CheckInService.CodeSent sent -> new StartResponse("CODE_SENT", null, sent.challengeId(), sent.channels(),
                    sent.expiresAt());
        };
    }

    @PostMapping("/check-in/confirm")
    public EventView confirm(@Valid @RequestBody ConfirmInput body) {
        AppUser user = users.find(CurrentUser.id());
        return EventView.of(checkIn.confirmCheckIn(user, body.challengeId(), body.code()));
    }

    @PostMapping("/check-out")
    public EventView checkOut(@Valid @RequestBody PresenceInput body, HttpServletRequest request,
                              @CookieValue(name = TrustedDevices.COOKIE, required = false) String deviceToken) {
        AppUser user = users.find(CurrentUser.id());
        return EventView.of(checkIn.checkOut(user, presence(body, request, deviceToken)));
    }

    private static CheckInService.Presence presence(PresenceInput body, HttpServletRequest request, String deviceToken) {
        return new CheckInService.Presence(request.getRemoteAddr(), body.lat(), body.lng(), body.accuracyM(),
                body.qrToken(), deviceToken);
    }
}
