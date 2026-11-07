package cd.hrpay.attendance;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "clock_event")
public class ClockEvent {

    public enum Type { IN, OUT }

    public enum Source { SELF, HR_MANUAL, AUTO_CLOSE, MISSION, BIOMETRIC, API }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    @Enumerated(EnumType.STRING)
    private Type type;
    private OffsetDateTime occurredAt;
    private LocalDate workDate;
    private Long siteId;
    @Enumerated(EnumType.STRING)
    private Source source;
    private String ip;
    private BigDecimal lat;
    private BigDecimal lng;
    @jakarta.persistence.Column(name = "accuracy_m")
    private Integer accuracyM;
    private boolean qrVerified;
    private Long trustedDeviceId;
    private Long missionId;
    private String note;
    private String createdBy;
    private OffsetDateTime createdAt = OffsetDateTime.now();
    private OffsetDateTime voidedAt;
    private String voidedBy;
    private String voidReason;

    protected ClockEvent() {}

    public ClockEvent(Long userId, Type type, OffsetDateTime occurredAt, LocalDate workDate, Source source,
                      String createdBy) {
        this.userId = userId;
        this.type = type;
        this.occurredAt = occurredAt;
        this.workDate = workDate;
        this.source = source;
        this.createdBy = createdBy;
    }

    public void voidIt(String by, String reason) {
        this.voidedAt = OffsetDateTime.now();
        this.voidedBy = by;
        this.voidReason = reason;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Type getType() { return type; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public LocalDate getWorkDate() { return workDate; }
    public Long getSiteId() { return siteId; }
    public void setSiteId(Long siteId) { this.siteId = siteId; }
    public Source getSource() { return source; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public BigDecimal getLat() { return lat; }
    public BigDecimal getLng() { return lng; }
    public Integer getAccuracyM() { return accuracyM; }
    public void setLocation(BigDecimal lat, BigDecimal lng, Integer accuracyM) {
        this.lat = lat;
        this.lng = lng;
        this.accuracyM = accuracyM;
    }
    public boolean isQrVerified() { return qrVerified; }
    public void setQrVerified(boolean qrVerified) { this.qrVerified = qrVerified; }
    public Long getTrustedDeviceId() { return trustedDeviceId; }
    public void setTrustedDeviceId(Long trustedDeviceId) { this.trustedDeviceId = trustedDeviceId; }
    public Long getMissionId() { return missionId; }
    public void setMissionId(Long missionId) { this.missionId = missionId; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getCreatedBy() { return createdBy; }
    public OffsetDateTime getVoidedAt() { return voidedAt; }
    public String getVoidedBy() { return voidedBy; }
    public String getVoidReason() { return voidReason; }
}
