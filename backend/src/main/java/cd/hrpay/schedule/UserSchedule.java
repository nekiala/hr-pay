package cd.hrpay.schedule;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

@Entity
@Table(name = "user_schedule")
public class UserSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "schedule_id")
    private WorkSchedule schedule;
    private LocalDate effectiveFrom;

    protected UserSchedule() {}

    public UserSchedule(Long userId, WorkSchedule schedule, LocalDate effectiveFrom) {
        this.userId = userId;
        this.schedule = schedule;
        this.effectiveFrom = effectiveFrom;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public WorkSchedule getSchedule() { return schedule; }
    public void setSchedule(WorkSchedule schedule) { this.schedule = schedule; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
}
