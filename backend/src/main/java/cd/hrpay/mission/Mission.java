package cd.hrpay.mission;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/** Work away from the site on a given day, approved in advance or after the fact. */
@Entity
public class Mission {

    public enum Status { PENDING, APPROVED, REJECTED, CANCELLED, CLOSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    private LocalDate workDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private String location;
    private String purpose;
    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;
    private String requestedBy;
    private OffsetDateTime requestedAt = OffsetDateTime.now();
    private String decidedBy;
    private OffsetDateTime decidedAt;
    private String decisionNote;
    /** SHORT (this entity's own missions) or TRAVEL (whole days, handled by {@link TravelMissions}). */
    @jakarta.persistence.Column(insertable = false, updatable = false)
    private String kind;

    protected Mission() {}

    public Mission(Long userId, LocalDate workDate, LocalTime startTime, LocalTime endTime, String location,
                   String purpose, String requestedBy) {
        this.userId = userId;
        this.workDate = workDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.location = location;
        this.purpose = purpose;
        this.requestedBy = requestedBy;
    }

    public void decide(Status status, String by, String note) {
        this.status = status;
        this.decidedBy = by;
        this.decidedAt = OffsetDateTime.now();
        this.decisionNote = note;
    }

    public boolean isShort() { return kind == null || "SHORT".equals(kind); }

    public boolean isActive() { return status == Status.PENDING || status == Status.APPROVED; }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public LocalDate getWorkDate() { return workDate; }
    public LocalTime getStartTime() { return startTime; }
    public LocalTime getEndTime() { return endTime; }
    public String getLocation() { return location; }
    public String getPurpose() { return purpose; }
    public Status getStatus() { return status; }
    public String getRequestedBy() { return requestedBy; }
    public OffsetDateTime getRequestedAt() { return requestedAt; }
    public String getDecidedBy() { return decidedBy; }
    public OffsetDateTime getDecidedAt() { return decidedAt; }
    public String getDecisionNote() { return decisionNote; }
}
