package cd.hrpay.skills;

import cd.hrpay.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Skills: catalogue, skills per job, people's levels, the gap matrix, search and certifications. */
@RestController
public class SkillsController {

    public record SkillBody(@NotNull @Pattern(regexp = "TECHNICAL|BEHAVIOURAL|LANGUAGE|TOOL") String family,
                            @NotBlank @Size(max = 150) String name, @Size(max = 1000) String description,
                            @Size(max = 300) String level1, @Size(max = 300) String level2, @Size(max = 300) String level3,
                            @Size(max = 300) String level4, boolean active) {}

    public record NeedBody(@NotNull Long skillId, @NotNull @Min(1) @Max(4) Integer level) {}

    public record LevelBody(@NotNull Long skillId, @Min(1) @Max(4) Integer level, @Size(max = 500) String note) {}

    public record GapBody(@NotNull Long userId, @NotNull Long skillId) {}

    private final Skills skills;

    public SkillsController(Skills skills) {
        this.skills = skills;
    }

    @GetMapping("/api/skills")
    public List<Skills.Skill> list(@RequestParam(defaultValue = "false") boolean all) {
        return skills.skills(all);
    }

    @PostMapping("/api/skills")
    @PreAuthorize("hasAuthority('skills.manage')")
    public Skills.Skill create(@Valid @RequestBody SkillBody b) {
        return skills.save(null, input(b));
    }

    @PutMapping("/api/skills/{id}")
    @PreAuthorize("hasAuthority('skills.manage')")
    public Skills.Skill update(@PathVariable long id, @Valid @RequestBody SkillBody b) {
        return skills.save(id, input(b));
    }

    private static Skills.SkillInput input(SkillBody b) {
        return new Skills.SkillInput(b.family(), b.name(), b.description(), b.level1(), b.level2(), b.level3(), b.level4(), b.active());
    }

    @GetMapping("/api/skills/jobs")
    @PreAuthorize("hasAuthority('skills.manage')")
    public List<Skills.Job> jobs() {
        return skills.jobs();
    }

    @GetMapping("/api/skills/jobs/{id}")
    @PreAuthorize("hasAuthority('skills.manage')")
    public Skills.JobView job(@PathVariable long id) {
        return skills.job(id);
    }

    @PutMapping("/api/skills/jobs/{id}")
    @PreAuthorize("hasAuthority('skills.manage')")
    public Skills.JobView setJob(@PathVariable long id, @RequestBody List<@Valid NeedBody> body) {
        return skills.setJob(id, body.stream().map(n -> new Skills.Need(n.skillId(), n.level())).toList());
    }

    @GetMapping("/api/skills/courses")
    public List<Skills.CourseSkill> courseSkills() {
        return skills.courseSkills();
    }

    @PutMapping("/api/skills/courses/{courseId}")
    public List<Skills.CourseSkill> setCourse(@PathVariable long courseId, @RequestBody List<Long> skillIds) {
        return skills.setCourse(courseId, skillIds);
    }

    @GetMapping("/api/skills/me")
    public Skills.Profile mine() {
        return skills.profile(CurrentUser.id());
    }

    @PutMapping("/api/skills/me")
    public Skills.Profile selfAssess(@RequestBody List<@Valid LevelBody> body) {
        return skills.selfAssess(levels(body));
    }

    @GetMapping("/api/skills/people/{userId}")
    public Skills.Profile profile(@PathVariable long userId) {
        return skills.profile(userId);
    }

    @PutMapping("/api/skills/people/{userId}")
    public Skills.Profile confirm(@PathVariable long userId, @RequestBody List<@Valid LevelBody> body) {
        return skills.confirm(userId, levels(body));
    }

    private static List<Skills.LevelInput> levels(List<LevelBody> body) {
        return body.stream().map(l -> new Skills.LevelInput(l.skillId(), l.level(), l.note())).toList();
    }

    @GetMapping("/api/skills/reviews")
    public List<Skills.Review> reviews() {
        return skills.reviews();
    }

    @GetMapping("/api/skills/matrix")
    public Skills.Matrix matrix(@RequestParam(required = false) Long unitId) {
        return skills.matrix(unitId);
    }

    @PostMapping("/api/skills/gaps/needs")
    public Map<String, Integer> toNeeds(@RequestBody List<@Valid GapBody> body) {
        return Map.of("added", skills.toNeeds(body.stream().map(g -> new Skills.Gap(g.userId(), g.skillId())).toList()));
    }

    @GetMapping("/api/skills/search")
    @PreAuthorize("hasAuthority('skills.manage')")
    public List<Skills.Holder> search(@RequestParam long skillId, @RequestParam(defaultValue = "1") @Min(1) @Max(4) int minLevel) {
        return skills.search(skillId, minLevel);
    }

    @GetMapping("/api/skills/certifications")
    @PreAuthorize("hasAuthority('skills.manage')")
    public List<Skills.Certification> certifications() {
        return skills.certifications();
    }
}
