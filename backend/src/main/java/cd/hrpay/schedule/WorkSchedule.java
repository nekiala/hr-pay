package cd.hrpay.schedule;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "work_schedule")
public class WorkSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String code;
    private String name;
    private int graceMinutes;
    private boolean deductLateArrival = true;
    private boolean deductEarlyDeparture = true;
    private boolean active = true;
    private OffsetDateTime createdAt = OffsetDateTime.now();
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "schedule_day", joinColumns = @JoinColumn(name = "schedule_id"))
    @OrderBy("weekday")
    private List<ScheduleDay> days = new ArrayList<>();

    protected WorkSchedule() {}

    public WorkSchedule(String code) {
        this.code = code;
    }

    @PreUpdate
    void touch() { updatedAt = OffsetDateTime.now(); }

    public ScheduleDay day(DayOfWeek dayOfWeek) {
        return days.stream().filter(d -> d.getWeekday() == dayOfWeek.getValue()).findFirst()
                .orElse(new ScheduleDay(dayOfWeek.getValue(), false, null, null, false, null, null, false));
    }

    public int weeklyMinutes() {
        return days.stream().mapToInt(ScheduleDay::scheduledMinutes).sum();
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getGraceMinutes() { return graceMinutes; }
    public void setGraceMinutes(int graceMinutes) { this.graceMinutes = graceMinutes; }
    public boolean isDeductLateArrival() { return deductLateArrival; }
    public void setDeductLateArrival(boolean v) { this.deductLateArrival = v; }
    public boolean isDeductEarlyDeparture() { return deductEarlyDeparture; }
    public void setDeductEarlyDeparture(boolean v) { this.deductEarlyDeparture = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public List<ScheduleDay> getDays() { return days; }
    public void setDays(List<ScheduleDay> newDays) {
        days.clear();
        days.addAll(newDays);
    }
}
