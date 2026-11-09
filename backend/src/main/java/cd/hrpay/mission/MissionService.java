package cd.hrpay.mission;

import cd.hrpay.attendance.ClockEvent;
import cd.hrpay.attendance.ClockEventRepository;
import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Missions let an employee finish the day away from the site. An approved mission allows checking out from
 * anywhere during it, and closes a day still open at the mission's end time.
 */
@Service
public class MissionService {

    private final MissionRepository missions;
    private final ClockEventRepository events;
    private final UserService users;
    private final AuditService audit;
    private final Clock clock;
    private cd.hrpay.org.OrgStructure org;

    @org.springframework.beans.factory.annotation.Autowired
    void setOrg(cd.hrpay.org.OrgStructure org) {
        this.org = org;
    }

    private cd.hrpay.user.Contracts contracts;

    @org.springframework.beans.factory.annotation.Autowired
    void setContracts(cd.hrpay.user.Contracts contracts) {
        this.contracts = contracts;
    }

    public MissionService(MissionRepository missions, ClockEventRepository events, UserService users,
                          AuditService audit, Clock clock) {
        this.missions = missions;
        this.events = events;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    static ZoneId zone(AppUser user) {
        return ZoneId.of(user.getSite() == null ? "Africa/Kinshasa" : user.getSite().getTimeZone());
    }

    @Transactional
    public Mission request(AppUser user, LocalDate date, LocalTime start, LocalTime end, String location,
                           String purpose) {
        if (!end.isAfter(start)) {
            throw new BusinessRuleException("The mission must end after it starts.");
        }
        LocalDate today = LocalDate.now(clock.withZone(zone(user)));
        if (date.isBefore(today.minusDays(7)) || date.isAfter(today.plusDays(90))) {
            throw new BusinessRuleException("Choose a date from 7 days ago to 90 days ahead.");
        }
        contracts.require(user.getId(), date);
        boolean overlaps = missions.findByUserIdAndWorkDate(user.getId(), date).stream()
                .filter(Mission::isActive)
                .anyMatch(m -> start.isBefore(m.getEndTime()) && m.getStartTime().isBefore(end));
        if (overlaps) {
            throw new ConflictException("You already have a mission at that time on " + date + ".");
        }
        Mission m = missions.save(new Mission(user.getId(), date, start, end, location.trim(), purpose.trim(),
                user.getEmail()));
        audit.record("mission.requested", "mission", m.getId(), Map.of("userId", user.getId(),
                "date", date.toString(), "start", start.toString(), "end", end.toString()));
        return m;
    }

    @Transactional
    public Mission cancel(Long id) {
        Mission m = find(id);
        if (!m.getUserId().equals(CurrentUser.id())) {
            throw new AccessDeniedException("Only the employee can cancel their mission.");
        }
        if (m.getStatus() != Mission.Status.PENDING) {
            throw new BusinessRuleException("Only a pending mission can be cancelled.");
        }
        m.decide(Mission.Status.CANCELLED, CurrentUser.email(), null);
        audit.record("mission.cancelled", "mission", m.getId(), Map.of());
        return m;
    }

    public boolean canDecide(Mission m) {
        if (m.getUserId().equals(CurrentUser.id())) {
            return false;
        }
        if (CurrentUser.has("mission.approve.all")) {
            return true;
        }
        return CurrentUser.has("mission.approve.team")
                && org.inTeam(CurrentUser.id(), m.getUserId());
    }

    @Transactional
    public Mission decide(Long id, boolean approve, String note) {
        Mission m = find(id);
        if (!canDecide(m)) {
            throw new AccessDeniedException("You cannot decide on this mission.");
        }
        if (m.getStatus() != Mission.Status.PENDING) {
            throw new BusinessRuleException("This mission was already decided.");
        }
        if (!approve && (note == null || note.isBlank())) {
            throw new BusinessRuleException("Give a reason for the rejection.");
        }
        m.decide(approve ? Mission.Status.APPROVED : Mission.Status.REJECTED, CurrentUser.email(),
                note == null || note.isBlank() ? null : note.trim());
        audit.record(approve ? "mission.approved" : "mission.rejected", "mission", m.getId(),
                note == null ? Map.of() : Map.of("note", note));
        if (approve) {
            closeDayIfOver(m, CurrentUser.email());
        }
        return m;
    }

    /** If the mission is over and the employee's day is still open, ends the day at the mission's end. */
    @Transactional
    public Optional<ClockEvent> closeDayIfOver(Mission m, String by) {
        AppUser user = users.find(m.getUserId());
        OffsetDateTime end = m.getWorkDate().atTime(m.getEndTime()).atZone(zone(user)).toOffsetDateTime();
        if (end.toInstant().isAfter(clock.instant())) {
            return Optional.empty();
        }
        Optional<ClockEvent> open = events.findLast(user.getId())
                .filter(e -> e.getType() == ClockEvent.Type.IN && e.getWorkDate().equals(m.getWorkDate())
                        && e.getOccurredAt().isBefore(end));
        return open.map(in -> {
            ClockEvent out = new ClockEvent(user.getId(), ClockEvent.Type.OUT, end, m.getWorkDate(),
                    ClockEvent.Source.MISSION, by);
            out.setMissionId(m.getId());
            out.setNote("End of mission: " + m.getLocation());
            events.save(out);
            audit.recordAs(by, "attendance.mission_close", "user", user.getId(),
                    Map.of("missionId", m.getId(), "eventId", out.getId(), "at", end.toString()));
            return out;
        });
    }

    /** The approved mission covering this moment, if any. */
    public Optional<Mission> activeMission(AppUser user, OffsetDateTime at) {
        LocalDate date = at.atZoneSameInstant(zone(user)).toLocalDate();
        LocalTime time = at.atZoneSameInstant(zone(user)).toLocalTime();
        return missions.findByUserIdAndWorkDate(user.getId(), date).stream()
                .filter(m -> m.getStatus() == Mission.Status.APPROVED)
                .filter(m -> !time.isBefore(m.getStartTime().minusMinutes(30)) && !time.isAfter(m.getEndTime().plusHours(2)))
                .findFirst();
    }

    public List<Mission> mine() {
        return missions.findByUserIdOrderByWorkDateDescStartTimeDesc(CurrentUser.id());
    }

    public List<Mission> toDecide() {
        List<Mission> list = CurrentUser.has("mission.approve.all") ? missions.findPending()
                : CurrentUser.has("mission.approve.team") ? missions.findPendingForUsers(org.teamOf(CurrentUser.id())) : List.of();
        return list.stream().filter(m -> !m.getUserId().equals(CurrentUser.id())).toList();
    }

    public Optional<Mission> approvedOn(Long userId, LocalDate date) {
        return missions.findByUserIdAndWorkDate(userId, date).stream()
                .filter(m -> m.getStatus() == Mission.Status.APPROVED)
                .max(java.util.Comparator.comparing(Mission::getEndTime));
    }

    private Mission find(Long id) {
        return missions.findById(id).orElseThrow(() -> new NotFoundException("Mission not found."));
    }
}
