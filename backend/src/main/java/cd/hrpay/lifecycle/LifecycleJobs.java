package cd.hrpay.lifecycle;

import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.Notifications;
import cd.hrpay.ops.JobLocks;
import cd.hrpay.security.UserSessions;
import cd.hrpay.user.UserService;
import cd.hrpay.user.UserStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every morning: reminders for documents about to expire and for documents still waiting for a signature, trials and
 * fixed-term contracts coming to their end, the staff representation's dates, and the
 * accounts of people who left closed once they no longer need them (their last month's payroll is closed, so their
 * last payslip is out, or 45 days after their last day).
 */
@Component
public class LifecycleJobs {

    private static final Logger log = LoggerFactory.getLogger(LifecycleJobs.class);

    static final int CLOSE_AFTER_DAYS = 45;

    private final JobLocks jobs;
    private final Documents documents;
    private final Notifications notifications;
    private final UserService users;
    private final UserSessions sessions;
    private final JdbcClient jdbc;
    private final Clock clock;
    private cd.hrpay.career.ContractFollowup followup;
    private cd.hrpay.representation.StaffRepresentation representation;
    private Onboarding onboarding;

    @org.springframework.beans.factory.annotation.Autowired
    void setOnboarding(@org.springframework.context.annotation.Lazy Onboarding onboarding) {
        this.onboarding = onboarding;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setFollowUps(@org.springframework.context.annotation.Lazy cd.hrpay.career.ContractFollowup followup,
                      @org.springframework.context.annotation.Lazy cd.hrpay.representation.StaffRepresentation representation) {
        this.followup = followup;
        this.representation = representation;
    }

    public LifecycleJobs(JobLocks jobs, Documents documents, Notifications notifications, UserService users, UserSessions sessions,
                         JdbcClient jdbc, Clock clock) {
        this.jobs = jobs;
        this.documents = documents;
        this.notifications = notifications;
        this.users = users;
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.lifecycle.cron:0 0 6 * * *}", zone = "Africa/Kinshasa")
    public void scheduled() {
        jobs.runForEachCompany("lifecycle.daily", Duration.ofMinutes(30), this::run);
    }

    public void run() {
        int reminded = documents.remind();
        int letters = remindLetters();
        int closed = closeAccounts();
        int contracts = followup == null ? 0 : followup.run();
        int staff = representation == null ? 0 : representation.run();
        int onboard = onboarding == null ? 0 : onboarding.remind();
        if (reminded + letters + closed + contracts + staff + onboard > 0) {
            log.info("Lifecycle: {} expiry reminders, {} signature reminders, {} accounts closed, {} contract follow-ups, {} staff representation reminders, {} onboarding reminders",
                    reminded, letters, closed, contracts, staff, onboard);
        }
    }

    /** A document waiting for its signature for two days or more brings a reminder every third day. */
    int remindLetters() {
        record Waiting(long userId, String title) {}
        LocalDate today = LocalDate.now(clock);
        List<Waiting> waiting = jdbc.sql("""
                select l.user_id, l.title from employee_letter l
                where l.status = 'SENT' and l.requires_signature and l.sent_at < now() - interval '2 days'
                  and not exists (select 1 from reminder_sent r where r.user_id = l.user_id and r.kind = 'letter.sign'
                                  and r.on_day > cast(? as date) - 3)""").param(today).query(Waiting.class).list();
        for (Waiting w : waiting) {
            notifications.send(w.userId(), "letter.reminder", () -> Texts.t("A document to sign"),
                    () -> Texts.t("%s still waits for your signature.", w.title()), "/my/documents");
            jdbc.sql("insert into reminder_sent (user_id, kind, on_day) values (?, 'letter.sign', ?) on conflict do nothing")
                    .params(w.userId(), today).update();
        }
        return waiting.size();
    }

    int closeAccounts() {
        LocalDate today = LocalDate.now(clock);
        List<Long> leavers = jdbc.sql("""
                select d.user_id from departure d join app_user u on u.id = d.user_id
                where d.status = 'CONFIRMED' and u.status = 'ACTIVE' and d.last_day < ?
                  and (d.last_day < cast(? as date) - ?
                       or exists (select 1 from payroll_run r where r.month = date_trunc('month', d.last_day)::date
                                  and r.status = 'CLOSED'))""").params(today, today, CLOSE_AFTER_DAYS).query(Long.class).list();
        for (long id : leavers) {
            users.setStatus(id, UserStatus.DISABLED);
            sessions.endAll(id, null, "system");
        }
        return leavers.size();
    }
}
