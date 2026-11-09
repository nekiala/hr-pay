package cd.hrpay.notify;

import cd.hrpay.audit.AuditService;
import cd.hrpay.i18n.Texts;
import cd.hrpay.org.OrgStructure;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Turns what happens in HR Pay (the audit log's actions) into notifications for the people concerned: the
 * manager who must approve, the employee whose request was decided or whose payslip is ready. Runs in the same
 * transaction as the action, so a notification exists only if the action does.
 */
@Component
public class NotificationEvents {

    private static final Logger log = LoggerFactory.getLogger(NotificationEvents.class);

    private final Notifications notifications;
    private final JdbcClient jdbc;
    private final OrgStructure org;

    public NotificationEvents(Notifications notifications, JdbcClient jdbc, OrgStructure org) {
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.org = org;
    }

    @EventListener
    public void on(AuditService.Recorded r) {
        try {
            handle(r);
        } catch (RuntimeException e) {
            // A notification that cannot be made never stops the action itself.
            log.warn("No notification for {}: {}", r.action(), e.getMessage());
        }
    }

    private void handle(AuditService.Recorded r) {
        Map<String, ?> d = r.details() == null ? Map.of() : r.details();
        switch (r.action()) {
            case "leave.requested" -> {
                long userId = number(d.get("userId"));
                String who = name(userId);
                approverOf(userId).ifPresentOrElse(
                        boss -> notifications.send(boss, "leave.to_approve", () -> Texts.t("Leave to approve"),
                                () -> Texts.t("%s asks for leave from %s to %s.", who, day(d.get("start")), day(d.get("end"))), "/leave"),
                        () -> notifications.sendToHolders("leave.manage", "leave.to_approve", () -> Texts.t("Leave to approve"),
                                () -> Texts.t("%s asks for leave from %s to %s.", who, day(d.get("start")), day(d.get("end"))), "/leave"));
            }
            case "leave.approved", "leave.rejected" -> {
                record Req(long userId, LocalDate startDate, LocalDate endDate, String status) {}
                Req q = jdbc.sql("select user_id, start_date, end_date, status from leave_request where id = ?")
                        .param(number(r.entityId())).query(Req.class).single();
                if (q.status().equals("AWAITING_HR")) {
                    notifications.sendToHolders("leave.manage", "leave.to_approve", () -> Texts.t("Leave to approve"),
                            () -> Texts.t("%s's leave from %s to %s was approved by the manager and waits for HR.", name(q.userId()),
                                    Texts.day(q.startDate()), Texts.day(q.endDate())), "/leave");
                } else if (q.status().equals("APPROVED")) {
                    notifications.send(q.userId(), "leave.approved", () -> Texts.t("Leave approved"),
                            () -> Texts.t("Your leave from %s to %s is approved.", Texts.day(q.startDate()), Texts.day(q.endDate())), "/leave");
                } else if (q.status().equals("REJECTED")) {
                    notifications.send(q.userId(), "leave.rejected", () -> Texts.t("Leave refused"),
                            () -> Texts.t("Your leave from %s to %s was refused.", Texts.day(q.startDate()), Texts.day(q.endDate())), "/leave");
                }
            }
            case "correction_request.created" -> {
                long userId = number(r.entityId());
                if (userId == notNull(cd.hrpay.security.CurrentUser.id())) {
                    approverOf(userId).ifPresent(boss -> notifications.send(boss, "correction.to_approve",
                            () -> Texts.t("Attendance correction to approve"),
                            () -> Texts.t("%s asks to correct %s.", name(userId), day(d.get("workDate"))), "/corrections"));
                }
            }
            case "payroll.closed" -> {
                YearMonth month = YearMonth.parse(String.valueOf(d.get("month")));
                List<Long> people = jdbc.sql("""
                        select p.user_id from payslip p join payroll_run r on r.id = p.run_id where r.id = ?""")
                        .param(number(r.entityId())).query(Long.class).list();
                for (long userId : people) {
                    notifications.send(userId, "payslip.ready", () -> Texts.t("Your payslip is ready"),
                            () -> Texts.t("The payslip for %s can be read in HR Pay.", Texts.month(month)), "/my/payslips");
                }
            }
            default -> { }
        }
    }

    private Optional<Long> approverOf(long userId) {
        return org.managerOf(userId).map(OrgStructure.Boss::userId).filter(id -> id != userId);
    }

    private String name(long userId) {
        return jdbc.sql("select person_name(first_name, middle_name, last_name) from app_user where id = ?").param(userId)
                .query(String.class).optional().orElse("?");
    }

    private static String day(Object iso) {
        try {
            return Texts.day(LocalDate.parse(String.valueOf(iso)));
        } catch (RuntimeException e) {
            return String.valueOf(iso);
        }
    }

    private static long number(Object o) {
        return o instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(o));
    }

    private static long notNull(Long id) {
        return id == null ? -1 : id;
    }
}
