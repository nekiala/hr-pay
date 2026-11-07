package cd.hrpay.attendance;

import cd.hrpay.audit.AuditService;
import cd.hrpay.mission.MissionService;
import cd.hrpay.schedule.WorkCalendar;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closes days that were never ended, once the day is over: at the approved mission's end if there is one,
 * otherwise at the schedule's end. The entry is marked as automatic so HR can review it.
 */
@Service
public class AutoClose {

    private static final Logger log = LoggerFactory.getLogger(AutoClose.class);

    private final JdbcClient jdbc;
    private final ClockEventRepository events;
    private final UserService users;
    private final WorkCalendar calendar;
    private final MissionService missions;
    private final AuditService audit;
    private final Clock clock;

    public AutoClose(JdbcClient jdbc, ClockEventRepository events, UserService users, WorkCalendar calendar,
                     MissionService missions, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.events = events;
        this.users = users;
        this.calendar = calendar;
        this.missions = missions;
        this.audit = audit;
        this.clock = clock;
    }

    private cd.hrpay.ops.JobLocks locks;
    private org.springframework.transaction.support.TransactionTemplate tx;

    @org.springframework.beans.factory.annotation.Autowired
    void setLocks(cd.hrpay.ops.JobLocks locks, org.springframework.transaction.PlatformTransactionManager txManager) {
        this.locks = locks;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "${app.auto-close-every:PT15M}")
    public void scheduled() {
        locks.run("auto-close", java.time.Duration.ofMinutes(10), () -> {
            Integer closed = tx.execute(s -> run());
            if (closed != null && closed > 0) {
                log.info("Auto-closed {} open day(s)", closed);
            }
        });
    }

    /** Returns how many days were closed. */
    @Transactional
    public int run() {
        List<Long> openIds = jdbc.sql("""
                select id from (
                    select distinct on (user_id) id, type from clock_event
                    where voided_at is null order by user_id, occurred_at desc
                ) last where type = 'IN'""").query(Long.class).list();
        int closed = 0;
        for (Long id : openIds) {
            if (closeIfPast(events.findById(id).orElseThrow())) {
                closed++;
            }
        }
        return closed;
    }

    /** Closes this open check-in if its work date is before today. Returns whether it did. */
    @Transactional
    public boolean closeIfPast(ClockEvent in) {
        AppUser user = users.find(in.getUserId());
        ZoneId zone = CheckInService.zone(user.getSite());
        LocalDate today = LocalDate.now(clock.withZone(zone));
        if (!in.getWorkDate().isBefore(today)) {
            return false;
        }
        var mission = missions.approvedOn(user.getId(), in.getWorkDate());
        if (mission.isPresent() && missions.closeDayIfOver(mission.get(), "system").isPresent()) {
            return true;
        }
        WorkCalendar.DayPlan plan = calendar.plan(user, in.getWorkDate());
        OffsetDateTime end = plan.workday() && plan.end() != null
                ? in.getWorkDate().atTime(plan.end()).atZone(zone).toOffsetDateTime() : in.getOccurredAt();
        if (end.isBefore(in.getOccurredAt())) {
            end = in.getOccurredAt();
        }
        ClockEvent out = new ClockEvent(user.getId(), ClockEvent.Type.OUT, end, in.getWorkDate(),
                ClockEvent.Source.AUTO_CLOSE, "system");
        out.setSiteId(in.getSiteId());
        out.setNote("No end of day recorded; closed automatically at " + (end.equals(in.getOccurredAt())
                ? "the check-in time" : "the schedule end") + ". HR should confirm.");
        events.save(out);
        audit.recordAs("system", "attendance.auto_close", "user", user.getId(),
                Map.of("eventId", out.getId(), "workDate", in.getWorkDate().toString(), "at", end.toString()));
        return true;
    }
}
