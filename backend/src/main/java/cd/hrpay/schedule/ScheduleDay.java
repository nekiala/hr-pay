package cd.hrpay.schedule;

import jakarta.persistence.Embeddable;
import java.time.LocalTime;

@Embeddable
public class ScheduleDay {

    private int weekday;
    private boolean workday;
    private LocalTime startTime;
    private LocalTime endTime;
    private boolean lunchEnabled;
    private LocalTime lunchStart;
    private Integer lunchMinutes;
    private boolean lunchPaid;

    protected ScheduleDay() {}

    public ScheduleDay(int weekday, boolean workday, LocalTime startTime, LocalTime endTime, boolean lunchEnabled,
                       LocalTime lunchStart, Integer lunchMinutes, boolean lunchPaid) {
        this.weekday = weekday;
        this.workday = workday;
        this.startTime = workday ? startTime : null;
        this.endTime = workday ? endTime : null;
        this.lunchEnabled = workday && lunchEnabled;
        this.lunchStart = this.lunchEnabled ? lunchStart : null;
        this.lunchMinutes = this.lunchEnabled ? lunchMinutes : null;
        this.lunchPaid = this.lunchEnabled && lunchPaid;
    }

    /** Paid minutes on this day if the employee works the full schedule. */
    public int scheduledMinutes() {
        if (!workday) {
            return 0;
        }
        int span = (int) java.time.Duration.between(startTime, endTime).toMinutes();
        return lunchEnabled && !lunchPaid ? span - lunchMinutes : span;
    }

    public int getWeekday() { return weekday; }
    public boolean isWorkday() { return workday; }
    public LocalTime getStartTime() { return startTime; }
    public LocalTime getEndTime() { return endTime; }
    public boolean isLunchEnabled() { return lunchEnabled; }
    public LocalTime getLunchStart() { return lunchStart; }
    public Integer getLunchMinutes() { return lunchMinutes; }
    public boolean isLunchPaid() { return lunchPaid; }
}
