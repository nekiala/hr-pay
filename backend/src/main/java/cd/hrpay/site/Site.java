package cd.hrpay.site;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "site")
public class Site {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String code;
    private String name;
    private String countryCode = "CD";
    private String timeZone;
    private BigDecimal centerLat;
    private BigDecimal centerLng;
    @Column(name = "radius_m")
    private Integer radiusM;
    private boolean gpsRequired = true;
    @Column(name = "max_accuracy_m")
    private int maxAccuracyM = 100;
    private boolean active = true;
    private boolean qrRequired;
    private String kioskKey;
    private OffsetDateTime createdAt = OffsetDateTime.now();
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @OneToMany(mappedBy = "site", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("cidr")
    private List<SiteIpRange> ipRanges = new ArrayList<>();

    protected Site() {}

    public Site(String code) {
        this.code = code;
        this.kioskKey = newKioskKey();
    }

    public static String newKioskKey() {
        byte[] bytes = new byte[24];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    @PreUpdate
    void touch() { updatedAt = OffsetDateTime.now(); }

    /** True when the address is inside one of the site's allowed ranges. */
    public boolean allowsIp(String ip) {
        return ipRanges.stream().anyMatch(r -> IpRange.parse(r.getCidr()).contains(ip));
    }

    public GeoFence geoFence() {
        return radiusM == null ? null : new GeoFence(centerLat.doubleValue(), centerLng.doubleValue(), radiusM);
    }

    /**
     * Replaces the allowed ranges. Existing rows with the same CIDR are kept and relabelled, so the
     * unique (site, cidr) constraint is never hit by Hibernate inserting before it deletes.
     */
    public void replaceIpRanges(List<SiteIpRange> ranges) {
        java.util.Map<String, SiteIpRange> wanted = new java.util.LinkedHashMap<>();
        ranges.forEach(r -> wanted.put(r.getCidr(), r));
        ipRanges.removeIf(existing -> !wanted.containsKey(existing.getCidr()));
        for (SiteIpRange existing : ipRanges) {
            existing.setLabel(wanted.remove(existing.getCidr()).getLabel());
        }
        for (SiteIpRange range : wanted.values()) {
            range.setSite(this);
            ipRanges.add(range);
        }
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }
    public BigDecimal getCenterLat() { return centerLat; }
    public BigDecimal getCenterLng() { return centerLng; }
    public Integer getRadiusM() { return radiusM; }
    public void setGeoFence(BigDecimal lat, BigDecimal lng, Integer radius) {
        this.centerLat = lat;
        this.centerLng = lng;
        this.radiusM = radius;
    }
    public boolean isGpsRequired() { return gpsRequired; }
    public void setGpsRequired(boolean gpsRequired) { this.gpsRequired = gpsRequired; }
    public int getMaxAccuracyM() { return maxAccuracyM; }
    public void setMaxAccuracyM(int maxAccuracyM) { this.maxAccuracyM = maxAccuracyM; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public List<SiteIpRange> getIpRanges() { return ipRanges; }
    public boolean isQrRequired() { return qrRequired; }
    public void setQrRequired(boolean qrRequired) { this.qrRequired = qrRequired; }
    public String getKioskKey() { return kioskKey; }
    public void setKioskKey(String kioskKey) { this.kioskKey = kioskKey; }
}
