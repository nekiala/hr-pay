package cd.hrpay.evaluation;

import cd.hrpay.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Individual and unit objectives (/objectives page). Who may do what is checked in {@link Objectives}. */
@RestController
@cd.hrpay.platform.RequiresModule("EVALUATIONS")
@PreAuthorize("isAuthenticated()")
public class ObjectiveController {

    public record ObjectiveInput(Long userId, Long orgUnitId, @Min(2000) @Max(2100) Integer year, Long parentId,
                                 @NotBlank @Size(max = 200) String title, @Size(max = 400) String measure,
                                 @Size(max = 200) String target, @DecimalMin("0") @DecimalMax("100") BigDecimal weight,
                                 LocalDate dueDate) {}

    public record AckInput(@NotNull @Min(2000) @Max(2100) Integer year, @Size(max = 1000) String comment) {}

    public record CheckinInput(@NotNull @Min(0) @Max(100) Integer progress, @Size(max = 1000) String comment,
                               @Size(max = 400) String developmentNeed) {}

    private final Objectives objectives;

    public ObjectiveController(Objectives objectives) {
        this.objectives = objectives;
    }

    private int year(Integer year) {
        return year != null ? year : objectives.today().getYear();
    }

    @GetMapping("/api/objectives/mine")
    public Objectives.Person mine(@RequestParam(required = false) Integer year) {
        return objectives.person(CurrentUser.id(), year(year));
    }

    @PostMapping("/api/objectives/mine/acknowledge")
    public Objectives.Person acknowledge(@Valid @RequestBody AckInput in) {
        return objectives.acknowledge(in.year(), in.comment());
    }

    @GetMapping("/api/objectives/people/{userId}")
    public Objectives.Person person(@PathVariable long userId, @RequestParam(required = false) Integer year) {
        return objectives.person(userId, year(year));
    }

    @PostMapping("/api/objectives/people/{userId}/share")
    @PreAuthorize("hasAuthority('evaluation.manage') or hasAuthority('evaluation.score.team')")
    public Objectives.Person share(@PathVariable long userId, @RequestParam(required = false) Integer year) {
        return objectives.share(userId, year(year));
    }

    @GetMapping("/api/objectives/team")
    @PreAuthorize("hasAuthority('evaluation.manage') or hasAuthority('evaluation.approve') or hasAuthority('evaluation.score.team')")
    public List<Objectives.TeamRow> team(@RequestParam(required = false) Integer year) {
        return objectives.team(year(year));
    }

    @GetMapping("/api/objectives/units")
    @PreAuthorize("hasAuthority('evaluation.manage') or hasAuthority('evaluation.approve') or hasAuthority('evaluation.score.team')")
    public List<Objectives.Objective> units(@RequestParam(required = false) Integer year) {
        return objectives.unitObjectives(year(year));
    }

    @GetMapping("/api/objectives/overview")
    @PreAuthorize("hasAuthority('evaluation.manage') or hasAuthority('evaluation.approve')")
    public Objectives.Overview overview(@RequestParam(required = false) Integer year) {
        return objectives.overview(year(year));
    }

    @PostMapping("/api/objectives")
    @PreAuthorize("hasAuthority('evaluation.manage') or hasAuthority('evaluation.score.team')")
    public Objectives.Objective add(@Valid @RequestBody ObjectiveInput in) {
        return objectives.save(null, toInput(in));
    }

    @PutMapping("/api/objectives/{id}")
    @PreAuthorize("hasAuthority('evaluation.manage') or hasAuthority('evaluation.score.team')")
    public Objectives.Objective save(@PathVariable long id, @Valid @RequestBody ObjectiveInput in) {
        return objectives.save(id, toInput(in));
    }

    @DeleteMapping("/api/objectives/{id}")
    @PreAuthorize("hasAuthority('evaluation.manage') or hasAuthority('evaluation.score.team')")
    public void remove(@PathVariable long id) {
        objectives.remove(id);
    }

    @PutMapping("/api/objectives/{id}/checkin")
    @PreAuthorize("hasAuthority('evaluation.manage') or hasAuthority('evaluation.score.team')")
    public Objectives.Objective checkin(@PathVariable long id, @Valid @RequestBody CheckinInput in) {
        return objectives.checkin(id, in.progress(), in.comment(), in.developmentNeed());
    }

    private Objectives.Input toInput(ObjectiveInput in) {
        return new Objectives.Input(in.userId(), in.orgUnitId(), year(in.year()), in.parentId(), in.title(), in.measure(),
                in.target(), in.weight(), in.dueDate());
    }
}
