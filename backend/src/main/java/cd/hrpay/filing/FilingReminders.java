package cd.hrpay.filing;

import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.Notifications;
import cd.hrpay.ops.JobLocks;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every morning, the legal filings still to send bring one message to those who manage the declarations: a hiring or
 * departure on its reminder day (two days after it, the old 48 hours), five days and one day before it is due, then
 * weekly while late; a yearly filing five days and one day before, then weekly while late (two months at most).
 */
@Component
public class FilingReminders {

    private static final Logger log = LoggerFactory.getLogger(FilingReminders.class);

    private final JobLocks jobs;
    private final LegalFilings filings;
    private final Notifications notifications;
    private final JdbcClient jdbc;
    private final Clock clock;

    public FilingReminders(JobLocks jobs, LegalFilings filings, Notifications notifications, JdbcClient jdbc, Clock clock) {
        this.jobs = jobs;
        this.filings = filings;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.declarations.cron:0 30 6 * * *}", zone = "Africa/Kinshasa")
    public void scheduled() {
        jobs.runForEachCompany("filings.remind", Duration.ofMinutes(30), this::run);
    }

    static boolean remindToday(long daysLeft) {
        if (daysLeft == 5 || daysLeft == 1) {
            return true;
        }
        long late = -daysLeft;
        return late >= 1 && late <= 60 && (late - 1) % 7 == 0;
    }

    /** The reminders of the current company; the number of filings reminded. */
    public int run() {
        LocalDate today = LocalDate.now(clock);
        List<String> lines = new ArrayList<>();
        boolean anyLate = false;
        for (LegalFilings.Movement m : filings.movements()) {
            if (m.status() != LegalFilings.Status.TO_FILE) {
                continue;
            }
            if (today.equals(m.remindFrom()) || remindToday(m.daysLeft())) {
                anyLate |= m.daysLeft() < 0;
                lines.add(Texts.t("%s of %s (%s): by %s", m.kind().equals("HIRE") ? Texts.t("Hiring") : Texts.t("Departure"),
                        m.name(), Texts.day(m.eventDate()), Texts.day(m.due())));
            }
        }
        for (LegalFilings.Annual a : filings.annual()) {
            if (a.status() == LegalFilings.Status.TO_FILE && remindToday(a.daysLeft())) {
                anyLate |= a.daysLeft() < 0;
                lines.add(Texts.t("%s %d: by %s", a.kind().equals("WORKFORCE") ? Texts.t("Annual declaration of the workforce")
                        : Texts.t("Bilan social"), a.year(), Texts.day(a.due())));
            }
        }
        if (lines.isEmpty()) {
            return 0;
        }
        boolean late = anyLate;
        for (Long id : notifications.holders("declaration.manage")) {
            int fresh = jdbc.sql("insert into reminder_sent (user_id, kind, on_day) values (?, 'filing.due', ?) on conflict do nothing")
                    .params(id, today).update();
            if (fresh > 0) {
                notifications.send(id, "filing.due", () -> late ? Texts.t("Legal filings late") : Texts.t("Legal filings due"),
                        () -> String.join("; ", lines), "/payroll/legal-filings");
            }
        }
        log.info("Legal filings: {} reminded", lines.size());
        return lines.size();
    }
}
