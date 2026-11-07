package cd.hrpay.evaluation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@cd.hrpay.platform.RequiresModule("EVALUATIONS")
@RequestMapping("/api")
public class EvaluationController {

    public record CriterionInput(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String code,
                                 @NotBlank @Size(max = 150) String name, @Size(max = 400) String description,
                                 @NotNull @Pattern(regexp = "MANAGER|ATTENDANCE") String source, boolean active) {}

    public record WeightInput(@NotNull Long criterionId,
                              @NotNull @DecimalMin("0.01") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal weight) {}

    public record TemplateInput(@NotBlank @Size(max = 150) String name,
                                @NotNull @Pattern(regexp = "MONTHLY|QUARTERLY|SEMI_ANNUAL") String frequency,
                                Long gradeCategoryId, Long orgUnitId, boolean active, @NotEmpty List<@Valid WeightInput> criteria) {

        Map<Long, BigDecimal> weights() {
            Map<Long, BigDecimal> m = new LinkedHashMap<>();
            criteria.forEach(c -> m.put(c.criterionId(), c.weight()));
            return m;
        }
    }

    public record StartInput(@NotNull Long userId, LocalDate inPeriod) {}

    public record ScoreInput(@NotNull Long criterionId,
                             @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal score,
                             @Size(max = 500) String comment) {}

    public record ScoresInput(@NotNull List<@Valid ScoreInput> scores, @Size(max = 1000) String managerComment,
                              boolean submit) {}

    public record AnswerInput(boolean acknowledge, @Size(max = 1000) String comment) {}

    public record ApproveInput(@Size(max = 1000) String hrComment) {}

    private final Evaluations evaluations;
    private final ThirteenthMonth thirteenth;
    private final Clock clock;

    public EvaluationController(Evaluations evaluations, ThirteenthMonth thirteenth, Clock clock) {
        this.evaluations = evaluations;
        this.thirteenth = thirteenth;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneId.of("Africa/Kinshasa")));
    }

    // ---- setup -----------------------------------------------------------------------------------------------

    @GetMapping("/evaluation-criteria")
    @PreAuthorize("hasAuthority('evaluation.manage') or hasAuthority('evaluation.approve') or hasAuthority('evaluation.score.team')")
    public List<Evaluations.Criterion> criteria() {
        return evaluations.criteria();
    }

    @PostMapping("/evaluation-criteria")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('evaluation.manage')")
    public Evaluations.Criterion addCriterion(@Valid @RequestBody CriterionInput b) {
        return evaluations.saveCriterion(null, b.code().trim(), b.name().trim(), b.description(), b.source(), b.active());
    }

    @PutMapping("/evaluation-criteria/{id}")
    @PreAuthorize("hasAuthority('evaluation.manage')")
    public Evaluations.Criterion updateCriterion(@PathVariable long id, @Valid @RequestBody CriterionInput b) {
        return evaluations.saveCriterion(id, b.code().trim(), b.name().trim(), b.description(), b.source(), b.active());
    }

    @GetMapping("/evaluation-templates")
    @PreAuthorize("hasAuthority('evaluation.manage') or hasAuthority('evaluation.approve') or hasAuthority('evaluation.score.team')")
    public List<Evaluations.Template> templates() {
        return evaluations.templates();
    }

    @PostMapping("/evaluation-templates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('evaluation.manage')")
    public Evaluations.Template addTemplate(@Valid @RequestBody TemplateInput b) {
        return evaluations.saveTemplate(null, b.name().trim(), b.frequency(), b.gradeCategoryId(), b.orgUnitId(), b.active(), b.weights());
    }

    @PutMapping("/evaluation-templates/{id}")
    @PreAuthorize("hasAuthority('evaluation.manage')")
    public Evaluations.Template updateTemplate(@PathVariable long id, @Valid @RequestBody TemplateInput b) {
        return evaluations.saveTemplate(id, b.name().trim(), b.frequency(), b.gradeCategoryId(), b.orgUnitId(), b.active(), b.weights());
    }

    // ---- evaluations -----------------------------------------------------------------------------------------

    @GetMapping("/evaluations")
    @PreAuthorize("isAuthenticated()")
    public List<Evaluations.Evaluation> list(@RequestParam(defaultValue = "mine") String scope,
                                             @RequestParam(required = false) String status) {
        return evaluations.list(scope, status);
    }

    @GetMapping("/evaluations/to-do")
    @PreAuthorize("hasAuthority('evaluation.manage') or hasAuthority('evaluation.approve') or hasAuthority('evaluation.score.team')")
    public List<Map<String, Object>> toDo(@RequestParam(required = false) LocalDate on) {
        return evaluations.toEvaluate(on != null ? on : today());
    }

    @GetMapping("/evaluations/{id}")
    @PreAuthorize("isAuthenticated()")
    public Evaluations.Evaluation get(@PathVariable long id) {
        return evaluations.get(id);
    }

    @PostMapping("/evaluations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('evaluation.manage') or hasAuthority('evaluation.approve') or hasAuthority('evaluation.score.team')")
    public Evaluations.Evaluation start(@Valid @RequestBody StartInput b) {
        return evaluations.start(b.userId(), b.inPeriod() != null ? b.inPeriod() : today());
    }

    @PutMapping("/evaluations/{id}/scores")
    @PreAuthorize("hasAuthority('evaluation.manage') or hasAuthority('evaluation.approve') or hasAuthority('evaluation.score.team')")
    public Evaluations.Evaluation scores(@PathVariable long id, @Valid @RequestBody ScoresInput b) {
        return evaluations.saveScores(id, b.scores().stream()
                .map(s -> new Evaluations.ScoreInput(s.criterionId(), s.score(), s.comment())).toList(),
                b.managerComment(), b.submit());
    }

    @PostMapping("/evaluations/{id}/answer")
    @PreAuthorize("isAuthenticated()")
    public Evaluations.Evaluation answer(@PathVariable long id, @Valid @RequestBody AnswerInput b) {
        return evaluations.answer(id, b.acknowledge(), b.comment());
    }

    @PostMapping("/evaluations/{id}/approve")
    @PreAuthorize("hasAuthority('evaluation.approve')")
    public Evaluations.Evaluation approve(@PathVariable long id, @Valid @RequestBody ApproveInput b) {
        return evaluations.approve(id, b.hrComment());
    }

    // ---- 13th month ------------------------------------------------------------------------------------------

    @GetMapping("/thirteenth-month")
    @PreAuthorize("hasAuthority('salary.read')")
    public Map<String, Object> thirteenthMonth(@RequestParam(required = false) Integer year) {
        int y = year != null ? year : today().getYear();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", y);
        out.put("settings", thirteenth.settings());
        out.put("rows", thirteenth.preview(y));
        return out;
    }
}
