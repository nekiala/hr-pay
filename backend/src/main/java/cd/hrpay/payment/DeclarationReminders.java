package cd.hrpay.payment;

import cd.hrpay.firm.Firms;
import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.Notifications;
import cd.hrpay.ops.JobLocks;
import cd.hrpay.tenancy.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every morning, declarations still open bring a reminder: five days and one day before they fall due, then the day
 * after and every week while they are late (for two months at most). It goes to those who manage the declarations
 * and to the accounting firm working for the company. One message a day lists them all.
 */
@Component
public class DeclarationReminders {

    private static final Logger log = LoggerFactory.getLogger(DeclarationReminders.class);

    static final int LATE_FOR_DAYS = 60;

    private final JobLocks jobs;
    private final DeclarationCalendar calendar;
    private final Notifications notifications;
    private final Firms firms;
    private final JdbcClient jdbc;
    private final Clock clock;

    public DeclarationReminders(JobLocks jobs, DeclarationCalendar calendar, Notifications notifications, Firms firms,
                                JdbcClient jdbc, Clock clock) {
        this.jobs = jobs;
        this.calendar = calendar;
        this.notifications = notifications;
        this.firms = firms;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.declarations.cron:0 30 6 * * *}", zone = "Africa/Kinshasa")
    public void scheduled() {
        jobs.runForEachCompany("declarations.remind", Duration.ofMinutes(30), this::run);
    }

    /** Whether an open declaration brings a reminder today: 5 or 1 day before, the day after, then weekly. */
    static boolean remindToday(long daysLeft) {
        if (daysLeft == 5 || daysLeft == 1) {
            return true;
        }
        long late = -daysLeft;
        return late >= 1 && late <= LATE_FOR_DAYS && (late - 1) % 7 == 0;
    }

    /** The reminders of the current company; the number of declarations reminded. */
    public int run() {
        LocalDate today = LocalDate.now(clock);
        YearMonth now = YearMonth.now(clock);
        List<DeclarationCalendar.Item> due = calendar.items(now.minusMonths(3), now).stream()
                .filter(DeclarationCalendar.Item::open).filter(i -> remindToday(i.daysLeft())).toList();
        if (due.isEmpty()) {
            return 0;
        }
        List<DeclarationCalendar.Item> late = due.stream().filter(i -> i.daysLeft() < 0).toList();
        java.util.function.Supplier<String> list = () -> due.stream().map(i -> Texts.t("%s %s: %s on %s", i.kind(), i.month(),
                        i.filed() == null ? Texts.t("declare") : Texts.t("pay"), Texts.day(i.nextDue())))
                .collect(Collectors.joining("; "));
        java.util.function.Supplier<String> title = () -> late.isEmpty() ? Texts.t("Declarations due soon") : Texts.t("Declarations late");
        String link = "/payroll/declarations/calendar";
        for (Long id : notifications.holders("declaration.manage")) {
            int fresh = jdbc.sql("insert into reminder_sent (user_id, kind, on_day) values (?, 'declaration.due', ?) on conflict do nothing")
                    .params(id, today).update();
            if (fresh > 0) {
                notifications.send(id, "declaration.due", title, list, link);
            }
        }
        Long company = TenantContext.companyId();
        String name = jdbc.sql("select name from company where id = current_company_id()").query(String.class).optional().orElse("");
        if (company != null) {
            firms.tellFirmsOf(company, "declaration.due", () -> Texts.t("%s: %s", name, title.get()), list);
        }
        log.info("Declarations: {} reminded, {} late", due.size(), late.size());
        return due.size();
    }
}
