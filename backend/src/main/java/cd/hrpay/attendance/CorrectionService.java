package cd.hrpay.attendance;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * HR corrections. Events are never edited or deleted: a wrong one is voided with a reason and, if needed, a
 * manual one is added. Nobody corrects their own attendance.
 */
@Service
public class CorrectionService {

    /** Two events of the same type closer than this on the same day are treated as the same event. */
    static final Duration DUPLICATE_WINDOW = Duration.ofMinutes(30);

    private final ClockEventRepository events;
    private final UserService users;
    private final AuditService audit;
    private final Clock clock;

    public CorrectionService(ClockEventRepository events, UserService users, AuditService audit, Clock clock) {
        this.events = events;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public ClockEvent add(Long userId, ClockEvent.Type type, LocalDateTime localTime, LocalDate workDate, String note) {
        AppUser user = users.find(userId);
        notSelf(user);
        ZoneId zone = CheckInService.zone(user.getSite());
        OffsetDateTime at = localTime.atZone(zone).toOffsetDateTime();
        LocalDate date = workDate != null ? workDate : localTime.toLocalDate();
        if (at.toInstant().isAfter(clock.instant())) {
            throw new BusinessRuleException("The time cannot be in the future.");
        }
        if (Math.abs(Duration.between(date.atStartOfDay(), localTime).toHours()) > 36) {
            throw new BusinessRuleException("The time must be on or close to the work date.");
        }
        List<ClockEvent> day = events.findForPeriod(userId, date, date);
        for (ClockEvent e : day) {
            if (e.getType() == type && Duration.between(e.getOccurredAt(), at).abs().compareTo(DUPLICATE_WINDOW) < 0) {
                throw new ConflictException("A " + (type == ClockEvent.Type.IN ? "check-in" : "check-out")
                        + " is already recorded at " + e.getOccurredAt().atZoneSameInstant(zone).toLocalTime()
                        + " that day. Void it first if it is wrong.");
            }
        }
        // The day must still read IN, OUT, IN, OUT... after the new event.
        List<ClockEvent> after = new ArrayList<>(day);
        ClockEvent added = new ClockEvent(userId, type, at, date, ClockEvent.Source.HR_MANUAL, CurrentUser.email());
        after.add(added);
        after.sort(Comparator.comparing(ClockEvent::getOccurredAt));
        for (int i = 0; i < after.size(); i++) {
            ClockEvent.Type expected = i % 2 == 0 ? ClockEvent.Type.IN : ClockEvent.Type.OUT;
            if (after.get(i).getType() != expected) {
                throw new BusinessRuleException("This would leave two " + (expected == ClockEvent.Type.IN
                        ? "check-outs" : "check-ins") + " in a row on " + date + ". Check the day's entries.");
            }
        }
        added.setSiteId(user.getSite() == null ? null : user.getSite().getId());
        added.setNote(note.trim());
        events.save(added);
        audit.record("attendance.manual_entry", "user", userId, Map.of("eventId", added.getId(),
                "type", type.name(), "at", at.toString(), "workDate", date.toString(), "note", note.trim()));
        return added;
    }

    @Transactional
    public ClockEvent voidEvent(Long eventId, String reason) {
        ClockEvent e = events.findById(eventId).orElseThrow(() -> new NotFoundException("Entry not found."));
        notSelf(users.find(e.getUserId()));
        if (e.getVoidedAt() != null) {
            throw new ConflictException("This entry is already voided.");
        }
        e.voidIt(CurrentUser.email(), reason.trim());
        audit.record("attendance.voided", "user", e.getUserId(), Map.of("eventId", e.getId(),
                "type", e.getType().name(), "at", e.getOccurredAt().toString(), "reason", reason.trim()));
        return e;
    }

    private static void notSelf(AppUser user) {
        if (user.getId().equals(CurrentUser.id())) {
            throw new AccessDeniedException("You cannot correct your own attendance.");
        }
    }
}
