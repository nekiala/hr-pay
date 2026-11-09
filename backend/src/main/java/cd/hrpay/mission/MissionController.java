package cd.hrpay.mission;

import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@cd.hrpay.platform.RequiresModule("ATTENDANCE")
@RequestMapping("/api/missions")
public class MissionController {

    public record MissionInput(@NotNull LocalDate workDate, @NotNull LocalTime startTime, @NotNull LocalTime endTime,
                               @NotBlank @Size(max = 200) String location, @NotBlank @Size(max = 500) String purpose) {}

    public record DecisionInput(boolean approve, @Size(max = 300) String note) {}

    public record MissionView(Long id, Long userId, String employeeName, String registrationNo, LocalDate workDate,
                              LocalTime startTime, LocalTime endTime, String location, String purpose,
                              Mission.Status status, OffsetDateTime requestedAt, String decidedBy,
                              OffsetDateTime decidedAt, String decisionNote) {}

    private final MissionService missions;
    private final UserService users;

    public MissionController(MissionService missions, UserService users) {
        this.missions = missions;
        this.users = users;
    }

    private MissionView view(Mission m) {
        AppUser u = users.find(m.getUserId());
        return new MissionView(m.getId(), m.getUserId(), u.fullName(),
                u.getRegistrationNo(), m.getWorkDate(), m.getStartTime(), m.getEndTime(), m.getLocation(),
                m.getPurpose(), m.getStatus(), m.getRequestedAt(), m.getDecidedBy(), m.getDecidedAt(),
                m.getDecisionNote());
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAuthority('mission.request')")
    @Transactional(readOnly = true)
    public List<MissionView> mine() {
        return missions.mine().stream().map(this::view).toList();
    }

    @GetMapping("/to-decide")
    @PreAuthorize("hasAnyAuthority('mission.approve.all', 'mission.approve.team')")
    @Transactional(readOnly = true)
    public List<MissionView> toDecide() {
        return missions.toDecide().stream().map(this::view).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('mission.request')")
    @Transactional
    public MissionView request(@Valid @RequestBody MissionInput body) {
        return view(missions.request(users.find(CurrentUser.id()), body.workDate(), body.startTime(),
                body.endTime(), body.location(), body.purpose()));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('mission.request')")
    @Transactional
    public MissionView cancel(@PathVariable Long id) {
        return view(missions.cancel(id));
    }

    @PostMapping("/{id}/decision")
    @PreAuthorize("hasAnyAuthority('mission.approve.all', 'mission.approve.team')")
    @Transactional
    public MissionView decide(@PathVariable Long id, @Valid @RequestBody DecisionInput body) {
        return view(missions.decide(id, body.approve(), body.note()));
    }
}
