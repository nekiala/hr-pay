package cd.hrpay.attendance;

import cd.hrpay.audit.AuditService;
import cd.hrpay.user.AppUser;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Check-ins and check-outs that come from outside the app: a biometric time clock or another system through the API.
 * The site's network and location checks do not apply (the clock is the proof of presence). A punch without a
 * direction is a check-in when the day is closed and a check-out when it is open. The day must still read IN, OUT,
 * IN, OUT after the punch, or the punch is left out with the reason.
 */
@Service
public class ClockIngest {

    public record Outcome(ClockEvent event, String ignored) {
        static Outcome ignored(String why) {
            return new Outcome(null, why);
        }
    }

    /** A second punch this close to the first is the same punch (a finger placed twice). */
    private static final Duration REPEAT = Duration.ofMinutes(2);
    /** A check-out after midnight belongs to the day still open when that day began at most this long before. */
    private static final Duration NIGHT_SHIFT = Duration.ofHours(16);

    private final ClockEventRepository events;
    private final AutoClose autoClose;
    private final AuditService audit;

    private cd.hrpay.user.Contracts contracts;

    @org.springframework.beans.factory.annotation.Autowired
    void setContracts(cd.hrpay.user.Contracts contracts) {
        this.contracts = contracts;
    }

    public ClockIngest(ClockEventRepository events, AutoClose autoClose, AuditService audit) {
        this.events = events;
        this.autoClose = autoClose;
        this.audit = audit;
    }

    @Transactional
    public Outcome record(AppUser user, ClockEvent.Type wanted, OffsetDateTime at, ClockEvent.Source source, Long siteId,
                          String note, String by) {
        ZoneId zone = CheckInService.zone(user.getSite());
        Optional<ClockEvent> last = events.findLast(user.getId());
        if (last.isPresent() && Duration.between(last.get().getOccurredAt(), at).abs().compareTo(REPEAT) < 0) {
            return Outcome.ignored("repeat");
        }
        LocalDate date = at.atZoneSameInstant(zone).toLocalDate();
        List<ClockEvent> day = new ArrayList<>(events.findForPeriod(user.getId(), date, date));
        // After midnight, with yesterday's day still open: the check-out of the night shift.
        if (day.isEmpty() && last.isPresent() && last.get().getType() == ClockEvent.Type.IN
                && last.get().getOccurredAt().isBefore(at)
                && Duration.between(last.get().getOccurredAt(), at).compareTo(NIGHT_SHIFT) < 0
                && wanted != ClockEvent.Type.IN) {
            date = last.get().getWorkDate();
            day = new ArrayList<>(events.findForPeriod(user.getId(), date, date));
        }
        long before = day.stream().filter(e -> e.getOccurredAt().isBefore(at)).count();
        ClockEvent.Type type = wanted != null ? wanted : before % 2 == 0 ? ClockEvent.Type.IN : ClockEvent.Type.OUT;
        if (type == ClockEvent.Type.IN && contracts != null && !contracts.on(user.getId(), date)) {
            return Outcome.ignored("no contract");   // nobody works without a contract
        }
        if (type == ClockEvent.Type.IN && last.isPresent() && last.get().getType() == ClockEvent.Type.IN
                && last.get().getWorkDate().isBefore(date)) {
            // A day forgotten open before today is closed first, as for a check-in in the app.
            autoClose.closeIfPast(last.get());
            day = new ArrayList<>(events.findForPeriod(user.getId(), date, date));
        }
        ClockEvent added = new ClockEvent(user.getId(), type, at, date, source, by);
        List<ClockEvent> after = new ArrayList<>(day);
        after.add(added);
        after.sort(Comparator.comparing(ClockEvent::getOccurredAt));
        for (int i = 0; i < after.size(); i++) {
            ClockEvent.Type expected = i % 2 == 0 ? ClockEvent.Type.IN : ClockEvent.Type.OUT;
            if (after.get(i).getType() != expected) {
                return Outcome.ignored(type == ClockEvent.Type.IN ? "already checked in" : "not checked in");
            }
        }
        added.setSiteId(siteId != null ? siteId : user.getSite() == null ? null : user.getSite().getId());
        added.setNote(note);
        events.save(added);
        audit.recordAs(by, type == ClockEvent.Type.IN ? "attendance.check_in" : "attendance.check_out", "user", user.getId(),
                Map.of("eventId", added.getId(), "at", at.toString(), "source", source.name()));
        return new Outcome(added, null);
    }
}
