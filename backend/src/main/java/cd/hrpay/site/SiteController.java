package cd.hrpay.site;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sites")
public class SiteController {

    public record IpRangeInput(@NotBlank @Size(max = 64) String cidr, @Size(max = 100) String label) {}

    public record SiteInput(
            @NotBlank @Pattern(regexp = "[A-Z0-9_-]{2,32}", message = "Use 2 to 32 capital letters, digits, - or _")
            String code,
            @NotBlank @Size(max = 150) String name,
            @NotBlank @Pattern(regexp = "[A-Z]{2}", message = "Use a two-letter country code") String countryCode,
            @NotBlank String timeZone,
            @DecimalMin("-90") @DecimalMax("90") BigDecimal centerLat,
            @DecimalMin("-180") @DecimalMax("180") BigDecimal centerLng,
            @Min(10) @Max(50_000) Integer radiusM,
            boolean gpsRequired,
            @Min(5) @Max(5_000) int maxAccuracyM,
            boolean active,
            @NotNull List<@Valid IpRangeInput> ipRanges) {}

    public record IpRangeView(Long id, String cidr, String label) {}

    public record SiteView(Long id, String code, String name, String countryCode, String timeZone,
                           BigDecimal centerLat, BigDecimal centerLng, Integer radiusM, boolean gpsRequired,
                           int maxAccuracyM, boolean active, List<IpRangeView> ipRanges) {
        static SiteView of(Site s) {
            return new SiteView(s.getId(), s.getCode(), s.getName(), s.getCountryCode(), s.getTimeZone(),
                    s.getCenterLat(), s.getCenterLng(), s.getRadiusM(), s.isGpsRequired(), s.getMaxAccuracyM(),
                    s.isActive(), s.getIpRanges().stream()
                            .map(r -> new IpRangeView(r.getId(), r.getCidr(), r.getLabel())).toList());
        }
    }

    private final SiteRepository sites;
    private final AuditService audit;

    public SiteController(SiteRepository sites, AuditService audit) {
        this.sites = sites;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('site.read') or hasAuthority('site.manage')")
    public List<SiteView> list() {
        return sites.findAllByOrderByNameAsc().stream().map(SiteView::of).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('site.read') or hasAuthority('site.manage')")
    public SiteView get(@PathVariable Long id) {
        return SiteView.of(find(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('site.manage')")
    @Transactional
    public SiteView create(@Valid @RequestBody SiteInput body) {
        if (sites.existsByCodeIgnoreCase(body.code())) {
            throw new ConflictException("A site with code " + body.code() + " already exists.");
        }
        Site site = new Site(body.code());
        apply(site, body);
        sites.save(site);
        audit.record("site.created", "site", site.getId(), describe(site));
        return SiteView.of(site);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('site.manage')")
    @Transactional
    public SiteView update(@PathVariable Long id, @Valid @RequestBody SiteInput body) {
        Site site = find(id);
        if (!site.getCode().equalsIgnoreCase(body.code())) {
            throw new BusinessRuleException("A site's code cannot be changed.");
        }
        Map<String, Object> before = describe(site);
        apply(site, body);
        sites.flush();
        audit.record("site.updated", "site", site.getId(), Map.of("before", before, "after", describe(site)));
        return SiteView.of(site);
    }

    private Site find(Long id) {
        return sites.findById(id).orElseThrow(() -> new NotFoundException("Site not found."));
    }

    private static void apply(Site site, SiteInput body) {
        try {
            ZoneId.of(body.timeZone());
        } catch (DateTimeException e) {
            throw new BusinessRuleException("Unknown time zone: " + body.timeZone());
        }
        boolean anyGeo = body.centerLat() != null || body.centerLng() != null || body.radiusM() != null;
        boolean fullGeo = body.centerLat() != null && body.centerLng() != null && body.radiusM() != null;
        if (anyGeo && !fullGeo) {
            throw new BusinessRuleException("A GPS area needs a latitude, a longitude and a radius.");
        }
        if (body.gpsRequired() && !fullGeo) {
            throw new BusinessRuleException("Set a GPS area before making GPS required.");
        }
        List<SiteIpRange> ranges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (IpRangeInput input : body.ipRanges()) {
            String normalized;
            try {
                normalized = IpRange.parse(input.cidr()).toString();
            } catch (IllegalArgumentException e) {
                throw new BusinessRuleException(e.getMessage());
            }
            if (seen.add(normalized)) {
                ranges.add(new SiteIpRange(normalized, input.label()));
            }
        }
        site.setName(body.name().trim());
        site.setCountryCode(body.countryCode());
        site.setTimeZone(body.timeZone());
        site.setGeoFence(body.centerLat(), body.centerLng(), body.radiusM());
        site.setGpsRequired(body.gpsRequired());
        site.setMaxAccuracyM(body.maxAccuracyM());
        site.setActive(body.active());
        site.replaceIpRanges(ranges);
    }

    private static Map<String, Object> describe(Site s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.getName());
        m.put("timeZone", s.getTimeZone());
        m.put("centerLat", s.getCenterLat());
        m.put("centerLng", s.getCenterLng());
        m.put("radiusM", s.getRadiusM());
        m.put("gpsRequired", s.isGpsRequired());
        m.put("maxAccuracyM", s.getMaxAccuracyM());
        m.put("active", s.isActive());
        m.put("ipRanges", s.getIpRanges().stream().map(SiteIpRange::getCidr).toList());
        return m;
    }
}
