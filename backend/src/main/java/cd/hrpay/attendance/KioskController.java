package cd.hrpay.attendance;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.AppProperties;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.site.Site;
import cd.hrpay.site.SiteRepository;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class KioskController {

    public record KioskToken(String siteName, String url, long secondsLeft) {}

    public record KioskLink(String url) {}

    public record DeviceInput(@NotBlank @Size(max = 100) String name) {}

    private final SiteRepository sites;
    private final QrTokens qr;
    private final TrustedDevices devices;
    private final AppProperties properties;
    private final AuditService audit;

    public KioskController(SiteRepository sites, QrTokens qr, TrustedDevices devices, AppProperties properties,
                           AuditService audit) {
        this.sites = sites;
        this.qr = qr;
        this.devices = devices;
        this.properties = properties;
        this.audit = audit;
    }

    /** Public: the kiosk screen polls this with the site's secret key and draws the URL as a QR code. */
    @GetMapping("/kiosk")
    @Transactional(readOnly = true)
    public KioskToken token(@RequestParam String site, @RequestParam String key) {
        Site s = sites.findByCodeIgnoreCase(site)
                .filter(x -> MessageDigest.isEqual(x.getKioskKey().getBytes(StandardCharsets.UTF_8),
                        key.getBytes(StandardCharsets.UTF_8)))
                .orElseThrow(() -> new NotFoundException("Unknown kiosk."));
        String url = properties.publicUrl() + "/check-in?qr=" + URLEncoder.encode(qr.issue(s.getCode(), s.getKioskKey()),
                StandardCharsets.UTF_8);
        return new KioskToken(s.getName(), url, qr.secondsLeft());
    }

    @GetMapping("/sites/{id}/kiosk-link")
    @PreAuthorize("hasAuthority('site.manage')")
    @Transactional(readOnly = true)
    public KioskLink kioskLink(@PathVariable Long id) {
        Site s = find(id);
        return new KioskLink(properties.publicUrl() + "/kiosk?site=" + s.getCode() + "&key=" + s.getKioskKey());
    }

    @PostMapping("/sites/{id}/kiosk-key")
    @PreAuthorize("hasAuthority('site.manage')")
    @Transactional
    public KioskLink regenerate(@PathVariable Long id) {
        Site s = find(id);
        s.setKioskKey(Site.newKioskKey());
        audit.record("site.kiosk_key_rotated", "site", id, null);
        return new KioskLink(properties.publicUrl() + "/kiosk?site=" + s.getCode() + "&key=" + s.getKioskKey());
    }

    @GetMapping("/sites/{id}/devices")
    @PreAuthorize("hasAuthority('site.manage')")
    public List<TrustedDevices.Device> devices(@PathVariable Long id) {
        return devices.list(find(id).getId());
    }

    /** Registers the browser making this request as a shared office device of the site. */
    @PostMapping("/sites/{id}/devices")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('site.manage')")
    public Map<String, String> register(@PathVariable Long id, @Valid @RequestBody DeviceInput body,
                                        HttpServletResponse response) {
        String token = devices.register(find(id).getId(), body.name().trim());
        ResponseCookie cookie = ResponseCookie.from(TrustedDevices.COOKIE, token).httpOnly(true).path("/")
                .sameSite("Lax").maxAge(Duration.ofDays(3650)).build();
        response.addHeader("Set-Cookie", cookie.toString());
        return Map.of("status", "registered");
    }

    @PostMapping("/sites/{id}/devices/{deviceId}/deactivate")
    @PreAuthorize("hasAuthority('site.manage')")
    public Map<String, String> deactivate(@PathVariable Long id, @PathVariable Long deviceId) {
        devices.deactivate(find(id).getId(), deviceId);
        return Map.of("status", "deactivated");
    }

    private Site find(Long id) {
        return sites.findById(id).orElseThrow(() -> new NotFoundException("Site not found."));
    }
}
