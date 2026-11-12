package cd.hrpay.training;

import cd.hrpay.lifecycle.Files;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

/** Training, the annual plan, sessions, requests, the effect check, and apprentices. */
@RestController
public class TrainingController {

    public record CourseBody(@NotBlank @Size(max = 200) String title, @NotNull @Pattern(regexp = "INTERNAL|INPP|EXTERNAL") String providerKind,
                             @Size(max = 200) String providerName, @NotNull @Pattern(regexp = "CLASSROOM|ON_THE_JOB|ONLINE") String mode,
                             @NotNull @DecimalMin("0.5") @DecimalMax("2000") BigDecimal hours,
                             @DecimalMin("0") BigDecimal costPerPerson, @Size(max = 3) String currency,
                             @Size(max = 2000) String description, boolean active) {}

    public record NeedBody(Long userId, Long unitId, @NotBlank @Size(max = 300) String topic, Long courseId,
                           @Size(max = 1000) String reason, @Pattern(regexp = "HIGH|MEDIUM|LOW") String priority,
                           @Min(2000) @Max(2100) Integer year) {}

    public record OpinionBody(@NotNull @Pattern(regexp = "SUPPORT|OPPOSE") String opinion, @Size(max = 1000) String comment) {}

    public record DecisionBody(@NotNull Boolean accept, @Size(max = 1000) String comment,
                               @Pattern(regexp = "HIGH|MEDIUM|LOW") String priority) {}

    public record NeedChange(@Pattern(regexp = "OPEN|DROPPED") String status, Long planLineId) {}

    public record PlanBody(@DecimalMin("0") BigDecimal budget, @Size(max = 3) String currency, @Size(max = 2000) String note) {}

    public record ConsultBody(@NotNull LocalDate consultedOn, @Size(max = 2000) String opinion) {}

    public record LineBody(Long courseId, @Size(max = 300) String topic, Long categoryId, Long unitId,
                           @Min(1) @Max(4) Integer quarter, @Min(1) @Max(10000) int people, @DecimalMin("0") BigDecimal budget,
                           boolean nationalManagers, @Size(max = 500) String note) {}

    public record SessionBody(@NotNull Long courseId, Long planLineId, @NotNull LocalDate startsOn, @NotNull LocalDate endsOn,
                              @Size(max = 200) String place, @Size(max = 200) String trainer,
                              @DecimalMin("0") BigDecimal actualCost, @Size(max = 3) String currency, @Size(max = 1000) String note) {}

    public record EnrolBody(List<Long> userIds, List<Long> needIds) {}

    public record DoneBody(List<Long> present, @DecimalMin("0") BigDecimal actualCost) {}

    public record FileBody(@NotBlank @Size(max = 200) String fileName, @NotBlank @Size(max = 14_000_000) String data) {}

    public record RateBody(@NotNull @Min(1) @Max(5) Integer rating, @Size(max = 1000) String comment) {}

    public record EffectBody(@NotNull @Pattern(regexp = "YES|PARTLY|NO") String effect, @Size(max = 1000) String comment) {}

    public record SettingsBody(@Size(max = 200) String lead, @Min(1) @Max(12) int effectMonths,
                               @NotNull @Pattern(regexp = "NONE|SALARY") String apprenticeContributions,
                               @Min(0) @Max(60) int workerEducationDays, @Min(0) @Max(90) int workerEducationNoticeDays) {}

    public record ApprenticeBody(@Size(max = 200) String trade, Long masterUserId, LocalDate onemVisaOn,
                                 @Size(max = 60) String onemVisaNo, LocalDate medicalOn, LocalDate waiverOn,
                                 LocalDate allowanceReviewOn, LocalDate endCertificateOn, LocalDate endReportedOn,
                                 @Min(0) @Max(24) Integer stayMonths, @Size(max = 1000) String note) {}

    private final Training training;
    private final Apprentices apprentices;

    public TrainingController(Training training, Apprentices apprentices) {
        this.training = training;
        this.apprentices = apprentices;
    }

    private static Training.CourseInput input(CourseBody b) {
        return new Training.CourseInput(b.title(), b.providerKind(), b.providerName(), b.mode(), b.hours(), b.costPerPerson(),
                b.currency(), b.description(), b.active());
    }

    // ---- courses (everyone sees the active catalogue, to ask for a course)

    @GetMapping("/api/training/courses")
    public List<Training.Course> courses(@RequestParam(defaultValue = "false") boolean all) {
        return training.courses(all && Training.isHr());
    }

    @PostMapping("/api/training/courses")
    @PreAuthorize("hasAuthority('training.manage')")
    public Training.Course createCourse(@Valid @RequestBody CourseBody b) {
        return training.saveCourse(null, input(b));
    }

    @PutMapping("/api/training/courses/{id}")
    @PreAuthorize("hasAuthority('training.manage')")
    public Training.Course updateCourse(@PathVariable long id, @Valid @RequestBody CourseBody b) {
        return training.saveCourse(id, input(b));
    }

    // ---- needs and requests

    @GetMapping("/api/training/needs")
    public List<Training.Need> needs(@RequestParam int year) {
        return training.needs(year);
    }

    @PostMapping("/api/training/needs")
    public Training.Need ask(@Valid @RequestBody NeedBody b) {
        return training.ask(new Training.NeedInput(b.userId(), b.unitId(), b.topic(), b.courseId(), b.reason(), b.priority(), b.year()));
    }

    @PostMapping("/api/training/needs/{id}/opinion")
    public Training.Need opinion(@PathVariable long id, @Valid @RequestBody OpinionBody b) {
        return training.opinion(id, b.opinion(), b.comment());
    }

    @PostMapping("/api/training/needs/{id}/decision")
    @PreAuthorize("hasAuthority('training.manage')")
    public Training.Need decide(@PathVariable long id, @Valid @RequestBody DecisionBody b) {
        return training.decide(id, b.accept(), b.comment(), b.priority());
    }

    @PostMapping("/api/training/needs/{id}/change")
    @PreAuthorize("hasAuthority('training.manage')")
    public Training.Need change(@PathVariable long id, @Valid @RequestBody NeedChange b) {
        return training.setNeed(id, b.status(), b.planLineId());
    }

    public record Collected(int added) {}

    @PostMapping("/api/training/needs/collect")
    @PreAuthorize("hasAuthority('training.manage')")
    public Collected collect(@RequestParam int year) {
        return new Collected(training.collect(year));
    }

    // ---- the plan

    @GetMapping("/api/training/plans/{year}")
    @PreAuthorize("hasAuthority('training.manage')")
    public Training.PlanView plan(@PathVariable int year) {
        return training.plan(year);
    }

    @PutMapping("/api/training/plans/{year}")
    @PreAuthorize("hasAuthority('training.manage')")
    public Training.PlanView savePlan(@PathVariable int year, @Valid @RequestBody PlanBody b) {
        return training.savePlan(year, b.budget(), b.currency(), b.note());
    }

    @PostMapping("/api/training/plans/{year}/consult")
    @PreAuthorize("hasAuthority('training.manage')")
    public Training.PlanView consult(@PathVariable int year, @Valid @RequestBody ConsultBody b) {
        return training.consult(year, b.consultedOn(), b.opinion());
    }

    @PostMapping("/api/training/plans/{year}/approve")
    @PreAuthorize("hasAuthority('training.manage')")
    public Training.PlanView approve(@PathVariable int year) {
        return training.approve(year);
    }

    @PostMapping("/api/training/plans/{year}/close")
    @PreAuthorize("hasAuthority('training.manage')")
    public Training.PlanView close(@PathVariable int year) {
        return training.close(year);
    }

    private static Training.LineInput line(LineBody b) {
        return new Training.LineInput(b.courseId(), b.topic(), b.categoryId(), b.unitId(), b.quarter(), b.people(), b.budget(),
                b.nationalManagers(), b.note());
    }

    @PostMapping("/api/training/plans/{year}/lines")
    @PreAuthorize("hasAuthority('training.manage')")
    public Training.PlanView addLine(@PathVariable int year, @Valid @RequestBody LineBody b) {
        return training.saveLine(year, null, line(b));
    }

    @PutMapping("/api/training/plans/{year}/lines/{id}")
    @PreAuthorize("hasAuthority('training.manage')")
    public Training.PlanView updateLine(@PathVariable int year, @PathVariable long id, @Valid @RequestBody LineBody b) {
        return training.saveLine(year, id, line(b));
    }

    @DeleteMapping("/api/training/lines/{id}")
    @PreAuthorize("hasAuthority('training.manage')")
    public Training.PlanView deleteLine(@PathVariable long id) {
        return training.deleteLine(id);
    }

    // ---- sessions

    @GetMapping("/api/training/sessions")
    @PreAuthorize("hasAuthority('training.manage')")
    public List<Training.Session> sessions(@RequestParam int year) {
        return training.sessions(year);
    }

    @GetMapping("/api/training/sessions/{id}")
    @PreAuthorize("hasAuthority('training.manage')")
    public Training.SessionView session(@PathVariable long id) {
        return training.session(id);
    }

    private static Training.SessionInput session(SessionBody b) {
        return new Training.SessionInput(b.courseId(), b.planLineId(), b.startsOn(), b.endsOn(), b.place(), b.trainer(),
                b.actualCost(), b.currency(), b.note());
    }

    @PostMapping("/api/training/sessions")
    @PreAuthorize("hasAuthority('training.manage')")
    public Training.SessionView createSession(@Valid @RequestBody SessionBody b) {
        return training.saveSession(null, session(b));
    }

    @PutMapping("/api/training/sessions/{id}")
    @PreAuthorize("hasAuthority('training.manage')")
    public Training.SessionView updateSession(@PathVariable long id, @Valid @RequestBody SessionBody b) {
        return training.saveSession(id, session(b));
    }

    @PostMapping("/api/training/sessions/{id}/attendees")
    @PreAuthorize("hasAuthority('training.manage')")
    public Training.SessionView enrol(@PathVariable long id, @RequestBody EnrolBody b) {
        return training.enrol(id, b.userIds(), b.needIds());
    }

    @DeleteMapping("/api/training/sessions/{id}/attendees/{userId}")
    @PreAuthorize("hasAuthority('training.manage')")
    public Training.SessionView unenrol(@PathVariable long id, @PathVariable long userId) {
        return training.unenrol(id, userId);
    }

    @PostMapping("/api/training/sessions/{id}/done")
    @PreAuthorize("hasAuthority('training.manage')")
    public Training.SessionView done(@PathVariable long id, @Valid @RequestBody DoneBody b) {
        return training.done(id, b.present(), b.actualCost());
    }

    @PostMapping("/api/training/sessions/{id}/cancel")
    @PreAuthorize("hasAuthority('training.manage')")
    public Training.SessionView cancel(@PathVariable long id) {
        return training.cancel(id);
    }

    @PostMapping("/api/training/attendees/{id}/certificate")
    @PreAuthorize("hasAuthority('training.manage')")
    public Training.SessionView certificate(@PathVariable long id, @Valid @RequestBody FileBody b) {
        return training.certificate(id, b.fileName(), Files.decode(b.data()));
    }

    // ---- the employee, the manager

    @GetMapping("/api/training/me")
    public Training.Mine mine() {
        return training.mine();
    }

    @PostMapping("/api/training/attendees/{id}/rating")
    public Training.Mine rate(@PathVariable long id, @Valid @RequestBody RateBody b) {
        return training.rate(id, b.rating(), b.comment());
    }

    @GetMapping("/api/training/effects")
    public List<Training.EffectDue> effects() {
        return training.effectsDue();
    }

    @PostMapping("/api/training/attendees/{id}/effect")
    public List<Training.EffectDue> effect(@PathVariable long id, @Valid @RequestBody EffectBody b) {
        return training.effect(id, b.effect(), b.comment());
    }

    @GetMapping("/api/training/people/{userId}")
    public List<Training.History> history(@PathVariable long userId) {
        return training.history(userId);
    }

    // ---- reports and settings

    @GetMapping("/api/training/report")
    @PreAuthorize("hasAuthority('training.manage')")
    public Training.Report report(@RequestParam int year) {
        return training.report(year);
    }

    @GetMapping("/api/training/settings")
    @PreAuthorize("hasAuthority('training.manage')")
    public Training.Settings settings() {
        return training.settings();
    }

    @PutMapping("/api/training/settings")
    @PreAuthorize("hasAuthority('training.manage')")
    public Training.Settings saveSettings(@Valid @RequestBody SettingsBody b) {
        return training.saveSettings(new Training.Settings(b.lead(), b.effectMonths(), b.apprenticeContributions(),
                b.workerEducationDays(), b.workerEducationNoticeDays()));
    }

    // ---- apprentices

    @GetMapping("/api/apprentices")
    public List<Apprentices.Apprentice> apprentices(@RequestParam(defaultValue = "false") boolean ended) {
        return apprentices.list(ended);
    }

    @GetMapping("/api/apprentices/{contractId}")
    public Apprentices.Apprentice apprentice(@PathVariable long contractId) {
        return apprentices.find(contractId);
    }

    @PutMapping("/api/apprentices/{contractId}")
    public Apprentices.Apprentice saveApprentice(@PathVariable long contractId, @Valid @RequestBody ApprenticeBody b) {
        return apprentices.save(contractId, new Apprentices.Input(b.trade(), b.masterUserId(), b.onemVisaOn(), b.onemVisaNo(),
                b.medicalOn(), b.waiverOn(), b.allowanceReviewOn(), b.endCertificateOn(), b.endReportedOn(), b.stayMonths(),
                b.note()));
    }
}
