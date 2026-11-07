package cd.hrpay.attendance;

import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.Notifications;
import cd.hrpay.ops.JobLocks;
import cd.hrpay.schedule.WorkCalendar;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reminds a person still checked in half an hour after their scheduled end of day, once a day. */
@Component
public class CheckOutReminders {

    static final Duration AFTER_END = Duration.ofMinutes(30);

    private final JobLocks jobs;
    private final JdbcClient jdbc;
    private final UserService users;
    private final WorkCalendar calendar;
    private final Notifications notifications;
    private final Clock clock;

    public CheckOutReminders(JobLocks jobs, JdbcClient jdbc, UserService users, WorkCalendar calendar, Notifications notifications,
                             Clock clock) {
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.users = users;
        this.calendar = calendar;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.reminders.every:PT10M}", initialDelayString = "PT2M")
    public void scheduled() {
        jobs.runForEachCompany("reminders.check_out", Duration.ofMinutes(5), this::run);
    }

    public int run() {
        record Open(long userId, LocalDate workDate) {}
        // The last event of each person is a check-in of a recent day.
        List<Open> open = jdbc.sql("""
                select e.user_id, e.work_date from clock_event e
                where e.type = 'IN' and e.voided_at is null and e.work_date >= current_date - 1
                  and not exists (select 1 from clock_event o where o.user_id = e.user_id and o.voided_at is null
                                  and o.occurred_at > e.occurred_at)""").query(Open.class).list();
        int sent = 0;
        for (Open o : open) {
            AppUser user = users.find(o.userId());
            ZoneId zone = CheckInService.zone(user.getSite());
            WorkCalendar.DayPlan plan = calendar.plan(user, o.workDate());
            if (!plan.workday() || plan.end() == null) {
                continue;
            }
            OffsetDateTime end = o.workDate().atTime(plan.end()).atZone(zone).toOffsetDateTime();
            if (OffsetDateTime.now(clock).isBefore(end.plus(AFTER_END))) {
                continue;
            }
            int fresh = jdbc.sql("insert into reminder_sent (user_id, kind, on_day) values (?, 'check_out', ?) on conflict do nothing")
                    .params(o.userId(), o.workDate()).update();
            if (fresh > 0) {
                notifications.send(o.userId(), "reminder.check_out", () -> Texts.t("Still checked in"),
                        () -> Texts.t("Your day ended at %s. Remember to check out.", plan.end().toString()), "/check-in");
                sent++;
            }
        }
        return sent;
    }
}
