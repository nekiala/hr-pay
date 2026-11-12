package cd.hrpay.attendance;

import cd.hrpay.notify.Notifications;
import cd.hrpay.ops.JobLocks;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every morning: two days before a decade ends, managers with overtime or corrections still waiting are told. */
@Component
public class DecadeReminders {

    private static final Logger log = LoggerFactory.getLogger(DecadeReminders.class);

    private final JobLocks jobs;
    private final AttendanceDecades decades;
    private final Notifications notifications;

    public DecadeReminders(JobLocks jobs, AttendanceDecades decades, Notifications notifications) {
        this.jobs = jobs;
        this.decades = decades;
        this.notifications = notifications;
    }

    @Scheduled(cron = "${app.decades.cron:0 45 6 * * *}", zone = "Africa/Kinshasa")
    public void scheduled() {
        jobs.runForEachCompany("attendance.decade_reminders", Duration.ofMinutes(30), this::run);
    }

    public void run() {
        int told = decades.remind(notifications);
        if (told > 0) {
            log.info("Decades: {} managers reminded of what waits before the decade ends", told);
        }
    }
}
