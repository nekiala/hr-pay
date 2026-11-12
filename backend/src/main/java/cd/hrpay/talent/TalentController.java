package cd.hrpay.talent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Critical posts, successors, the talent review and the coverage report (confidential; checks in Talent). */
@RestController
public class TalentController {

    public record PostBody(@NotNull Long unitId, boolean head, Long occupationId, @Size(max = 200) String title,
                           @Size(max = 500) String reason) {}

    public record SuccessorBody(@NotNull Long userId, @NotNull @Pattern(regexp = "NOW|ONE_TWO|THREE_PLUS") String readiness,
                                @Size(max = 500) String note) {}

    public record ReadinessBody(@NotNull @Pattern(regexp = "NOW|ONE_TWO|THREE_PLUS") String readiness, @Size(max = 500) String note) {}

    public record ActionBody(@NotNull @Pattern(regexp = "COURSE|MISSION|MENTOR|ACTING|OTHER") String kind,
                             @Size(max = 300) String title, Long courseId, Long mentorUserId, LocalDate dueOn) {}

    public record DoneBody(boolean done) {}

    public record ReviewBody(@NotNull @Min(2000) @Max(2100) Integer year, @NotBlank @Size(max = 150) String name,
                             Long categoryId, Long unitId, @Size(max = 1000) String note) {}

    public record SettleBody(@Min(1) @Max(3) Integer performance, @Size(max = 500) String performanceReason,
                             @Min(1) @Max(3) Integer potential, @Pattern(regexp = "LOW|MEDIUM|HIGH") String flightRisk,
                             @Pattern(regexp = "NOW|LATER|NO") String mobility, @Size(max = 1000) String note) {}

    public record SettingsBody(@Min(40) @Max(80) int retirementAge, @Min(1) @Max(99) int performanceLow,
                               @Min(1) @Max(100) int performanceHigh) {}

    public record ProposeBody(@NotNull @Min(1) @Max(3) Integer potential, @NotBlank @Size(max = 500) String reason) {}

    private final Talent talent;

    public TalentController(Talent talent) {
        this.talent = talent;
    }

    @GetMapping("/api/talent/access")
    public Map<String, Boolean> access() {
        return Map.of("talent", talent.canSee());
    }

    @GetMapping("/api/talent/posts")
    public List<Talent.Post> posts() {
        return talent.posts();
    }

    @PostMapping("/api/talent/posts")
    public Talent.Post createPost(@Valid @RequestBody PostBody b) {
        return talent.savePost(null, new Talent.PostInput(b.unitId(), b.head(), b.occupationId(), b.title(), b.reason()));
    }

    @PutMapping("/api/talent/posts/{id}")
    public Talent.Post updatePost(@PathVariable long id, @Valid @RequestBody PostBody b) {
        return talent.savePost(id, new Talent.PostInput(b.unitId(), b.head(), b.occupationId(), b.title(), b.reason()));
    }

    @DeleteMapping("/api/talent/posts/{id}")
    public void removePost(@PathVariable long id) {
        talent.removePost(id);
    }

    @PostMapping("/api/talent/posts/suggest")
    public Map<String, Integer> suggest() {
        return Map.of("added", talent.suggest());
    }

    @GetMapping("/api/talent/posts/{id}/candidates")
    public List<Talent.Candidate> candidates(@PathVariable long id) {
        return talent.candidates(id);
    }

    @PostMapping("/api/talent/posts/{id}/successors")
    public Talent.Post addSuccessor(@PathVariable long id, @Valid @RequestBody SuccessorBody b) {
        return talent.addSuccessor(id, b.userId(), b.readiness(), b.note());
    }

    @PutMapping("/api/talent/successors/{id}")
    public Talent.Post updateSuccessor(@PathVariable long id, @Valid @RequestBody ReadinessBody b) {
        return talent.updateSuccessor(id, b.readiness(), b.note());
    }

    @DeleteMapping("/api/talent/successors/{id}")
    public Talent.Post removeSuccessor(@PathVariable long id) {
        return talent.removeSuccessor(id);
    }

    @PostMapping("/api/talent/successors/{id}/actions")
    public Talent.Post addAction(@PathVariable long id, @Valid @RequestBody ActionBody b) {
        return talent.addAction(id, b.kind(), b.title(), b.courseId(), b.mentorUserId(), b.dueOn());
    }

    @PostMapping("/api/talent/actions/{id}/done")
    public Talent.Post done(@PathVariable long id, @RequestBody DoneBody b) {
        return talent.doneAction(id, b.done());
    }

    @GetMapping("/api/talent/reviews")
    public List<Talent.Review> reviews() {
        return talent.reviews();
    }

    @PostMapping("/api/talent/reviews")
    public Talent.ReviewView open(@Valid @RequestBody ReviewBody b) {
        return talent.open(b.year(), b.name(), b.categoryId(), b.unitId(), b.note());
    }

    @GetMapping("/api/talent/reviews/{id}")
    public Talent.ReviewView review(@PathVariable long id) {
        return talent.review(id);
    }

    @PostMapping("/api/talent/reviews/{id}/close")
    public Talent.ReviewView close(@PathVariable long id) {
        return talent.close(id);
    }

    @PutMapping("/api/talent/members/{id}")
    public Talent.ReviewView settle(@PathVariable long id, @Valid @RequestBody SettleBody b) {
        return talent.settle(id, new Talent.Settle(b.performance(), b.performanceReason(), b.potential(), b.flightRisk(),
                b.mobility(), b.note()));
    }

    @GetMapping("/api/talent/proposals")
    public List<Talent.Proposal> proposals() {
        return talent.proposals();
    }

    @PutMapping("/api/talent/proposals/{memberId}")
    public List<Talent.Proposal> propose(@PathVariable long memberId, @Valid @RequestBody ProposeBody b) {
        return talent.propose(memberId, b.potential(), b.reason());
    }

    @GetMapping("/api/talent/settings")
    public Talent.Settings settings() {
        return talent.settings();
    }

    @PutMapping("/api/talent/settings")
    public Talent.Settings saveSettings(@Valid @RequestBody SettingsBody b) {
        return talent.saveSettings(new Talent.Settings(b.retirementAge(), b.performanceLow(), b.performanceHigh()));
    }

    @GetMapping("/api/talent/coverage")
    public Talent.Coverage coverage() {
        return talent.coverage();
    }

    @GetMapping("/api/talent/people/{userId}")
    public Talent.PersonView person(@PathVariable long userId) {
        return talent.person(userId);
    }
}
