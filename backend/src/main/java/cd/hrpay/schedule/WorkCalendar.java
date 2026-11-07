package cd.hrpay.schedule;

import cd.hrpay.holiday.HolidayService;
import cd.hrpay.user.AppUser;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Answers "what was this employee expected to work on this day?" from schedules and observed holidays. */
@Service
public class WorkCalendar {

    /** One day of an employee's plan. Holidays on workdays are paid but not worked. */
    public record DayPlan(LocalDate date, boolean workday, boolean holiday, LocalTime start, LocalTime end,
                          boolean lunchEnabled, LocalTime lunchStart, int lunchMinutes, boolean lunchPaid,
                          int scheduledMinutes, int graceMinutes, boolean deductLate, boolean deductEarly,
                          Long scheduleId) {

        public boolean expectedAtWork() {
            return workday && !holiday;
        }
    }

    private final UserScheduleRepository assignments;
    private final HolidayService holidays;

    public WorkCalendar(UserScheduleRepository assignments, HolidayService holidays) {
        this.assignments = assignments;
        this.holidays = holidays;
    }

    public Optional<WorkSchedule> scheduleOn(Long userId, LocalDate date) {
        return assignments.findFirstByUserIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(userId, date)
                .map(UserSchedule::getSchedule);
    }

    @Transactional
    public DayPlan plan(AppUser user, LocalDate date) {
        return plan(user, date, date).getFirst();
    }

    @Transactional
    public List<DayPlan> plan(AppUser user, LocalDate from, LocalDate to) {
        String country = user.getSite() == null ? "CD" : user.getSite().getCountryCode();
        Set<LocalDate> holidayDates = holidays.observedDates(country, from, to);
        List<DayPlan> result = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            Optional<WorkSchedule> schedule = scheduleOn(user.getId(), d);
            boolean holiday = holidayDates.contains(d);
            if (schedule.isEmpty()) {
                result.add(new DayPlan(d, false, holiday, null, null, false, null, 0, false, 0, 0, false, false, null));
                continue;
            }
            WorkSchedule s = schedule.get();
            ScheduleDay day = s.day(d.getDayOfWeek());
            result.add(new DayPlan(d, day.isWorkday(), holiday, day.getStartTime(), day.getEndTime(),
                    day.isLunchEnabled(), day.getLunchStart(), day.getLunchMinutes() == null ? 0 : day.getLunchMinutes(),
                    day.isLunchPaid(), day.scheduledMinutes(), s.getGraceMinutes(), s.isDeductLateArrival(),
                    s.isDeductEarlyDeparture(), s.getId()));
        }
        return result;
    }

    /** Paid minutes the schedule expects over a period, counting holidays on workdays as paid. */
    public static int scheduledMinutes(List<DayPlan> plans) {
        return plans.stream().filter(DayPlan::workday).mapToInt(DayPlan::scheduledMinutes).sum();
    }
}
