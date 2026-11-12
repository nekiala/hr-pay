package cd.hrpay.lifecycle;

import cd.hrpay.tenancy.Tenants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Onboarding: the new hire's one-time link (no sign-in), HR's and the manager's follow-up, the welcome page, trial reviews. */
@RestController
public class OnboardingController {

    public record AnswersInput(@Valid @NotNull Onboarding.Answers answers, Boolean submit) {}

    public record UploadInput(@NotNull Long documentTypeId, @NotBlank @Size(max = 200) String fileName,
                              @NotBlank @Size(max = 100) String contentType, @NotBlank @Size(max = 7_000_000) String data) {}

    public record StartInput(@NotNull LocalDate startOn) {}

    public record CheckInput(@NotNull Boolean accept, @Size(max = 300) String reason, LocalDate expiresOn) {}

    public record BuddyInput(Long buddyUserId) {}

    public record TextInput(@Size(max = 8000) String text) {}

    public record ReviewInput(@NotNull @Min(1) @Max(5) Integer rating, @Size(max = 2000) String strengths,
                              @Size(max = 2000) String improvements, @Pattern(regexp = "CONFIRM|END") String recommendation,
                              @Size(max = 2000) String comment) {}

    private final Onboarding onboarding;
    private final Tenants tenants;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public OnboardingController(Onboarding onboarding, Tenants tenants) {
        this.onboarding = onboarding;
        this.tenants = tenants;
    }

    // ---- the one-time link (public)

    @GetMapping("/api/public/onboarding/{token}")
    public Onboarding.PreStart preStart(@PathVariable String token, HttpServletRequest request) {
        limit(request);
        UUID t = token(token);
        return tenants.callAs(companyOf(t), () -> onboarding.preStart(t));
    }

    // POST, not PUT or DELETE: the public pages answer only GET and POST across sites (CORS).
    @PostMapping("/api/public/onboarding/{token}")
    public Onboarding.PreStart saveAnswers(@PathVariable String token, @Valid @RequestBody AnswersInput in, HttpServletRequest request) {
        limit(request);
        UUID t = token(token);
        return tenants.callAs(companyOf(t), () -> onboarding.saveAnswers(t, in.answers(), Boolean.TRUE.equals(in.submit())));
    }

    @PostMapping("/api/public/onboarding/{token}/documents")
    public Onboarding.PreStart upload(@PathVariable String token, @Valid @RequestBody UploadInput in, HttpServletRequest request) {
        limit(request);
        UUID t = token(token);
        byte[] data = Files.decode(in.data());
        return tenants.callAs(companyOf(t), () -> onboarding.upload(t, in.documentTypeId(), in.fileName(), in.contentType(), data));
    }

    @PostMapping("/api/public/onboarding/{token}/documents/{id}/remove")
    public Onboarding.PreStart removeUpload(@PathVariable String token, @PathVariable long id, HttpServletRequest request) {
        limit(request);
        UUID t = token(token);
        return tenants.callAs(companyOf(t), () -> onboarding.removeUpload(t, id));
    }

    private Long companyOf(UUID token) {
        Long id = tenants.callAsPlatform(() -> onboarding.companyOfToken(token));
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This link is no longer valid. Ask HR for a new one.");
        }
        return id;
    }

    private static UUID token(String token) {
        try {
            return UUID.fromString(token);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This link is no longer valid. Ask HR for a new one.");
        }
    }

    private void limit(HttpServletRequest request) {
        String ip = request.getRemoteAddr() == null ? "?" : request.getRemoteAddr();
        long now = System.currentTimeMillis();
        Deque<Long> times = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > 60_000L) {
                times.pollFirst();
            }
            if (times.size() >= 60) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests; try again later.");
            }
            times.addLast(now);
        }
        if (hits.size() > 10_000) {
            hits.entrySet().removeIf(e -> e.getValue().isEmpty());
        }
    }

    // ---- HR and managers

    @GetMapping("/api/onboarding")
    public List<Onboarding.Row> list(@RequestParam(defaultValue = "false") boolean closed) {
        return onboarding.list(closed);
    }

    @GetMapping("/api/onboarding/overview")
    @PreAuthorize("hasAuthority('lifecycle.manage')")
    public Onboarding.Overview overview() {
        return onboarding.overview();
    }

    @GetMapping("/api/onboarding/{userId}")
    public Onboarding.Detail detail(@PathVariable long userId) {
        return onboarding.detail(userId);
    }

    @PostMapping("/api/onboarding/{userId}")
    @PreAuthorize("hasAuthority('lifecycle.manage')")
    public Onboarding.Detail start(@PathVariable long userId, @Valid @RequestBody StartInput in) {
        return onboarding.start(userId, in.startOn());
    }

    @PostMapping("/api/onboarding/{userId}/link")
    @PreAuthorize("hasAuthority('lifecycle.manage')")
    public Onboarding.Detail sendLink(@PathVariable long userId) {
        return onboarding.sendLink(userId);
    }

    @PostMapping("/api/onboarding/{userId}/apply-details")
    @PreAuthorize("hasAuthority('lifecycle.manage')")
    public Onboarding.Detail applyDetails(@PathVariable long userId) {
        return onboarding.applyDetails(userId);
    }

    @PostMapping("/api/onboarding/documents/{id}/check")
    @PreAuthorize("hasAuthority('lifecycle.manage')")
    public Onboarding.Detail check(@PathVariable long id, @Valid @RequestBody CheckInput in) {
        return onboarding.check(id, in.accept(), in.reason(), in.expiresOn());
    }

    @GetMapping("/api/onboarding/documents/{id}/file")
    @PreAuthorize("hasAuthority('lifecycle.manage')")
    public ResponseEntity<byte[]> file(@PathVariable long id) {
        Documents.File f = onboarding.sentFile(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(f.fileName()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(f.data());
    }

    public record TickInput(@NotNull Boolean done) {}

    @PostMapping("/api/onboarding/{userId}/steps/{itemId}")
    public Onboarding.Detail tick(@PathVariable long userId, @PathVariable long itemId, @Valid @RequestBody TickInput in) {
        return onboarding.tickStep(userId, itemId, in.done());
    }

    @GetMapping("/api/onboarding/{userId}/colleagues")
    public List<Onboarding.Colleague> colleagues(@PathVariable long userId) {
        return onboarding.colleagues(userId);
    }

    @PutMapping("/api/onboarding/{userId}/buddy")
    public Onboarding.Detail buddy(@PathVariable long userId, @RequestBody BuddyInput in) {
        return onboarding.setBuddy(userId, in.buddyUserId());
    }

    @PostMapping("/api/onboarding/{userId}/close")
    @PreAuthorize("hasAuthority('lifecycle.manage')")
    public Onboarding.Detail close(@PathVariable long userId) {
        return onboarding.close(userId);
    }

    @GetMapping("/api/onboarding/welcome-text")
    @PreAuthorize("hasAuthority('lifecycle.manage')")
    public TextInput welcomeText() {
        return new TextInput(onboarding.welcomeText());
    }

    @PutMapping("/api/onboarding/welcome-text")
    @PreAuthorize("hasAuthority('lifecycle.manage')")
    public TextInput saveWelcomeText(@Valid @RequestBody TextInput in) {
        return new TextInput(onboarding.saveWelcomeText(in.text()));
    }

    // ---- the new hire's welcome page

    @GetMapping("/api/onboarding/me")
    public ResponseEntity<Onboarding.Welcome> welcome() {
        Onboarding.Welcome w = onboarding.welcome();
        return w == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(w);
    }

    @PostMapping("/api/onboarding/me/seen")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void welcomeSeen() {
        onboarding.welcomeSeen();
    }

    // ---- trial reviews

    @GetMapping("/api/trial-reviews/mine")
    public List<Onboarding.Review> myReviews() {
        return onboarding.myReviews();
    }

    @PutMapping("/api/trial-reviews/{id}")
    public Onboarding.Review saveReview(@PathVariable long id, @Valid @RequestBody ReviewInput in) {
        return onboarding.saveReview(id, in.rating(), in.strengths(), in.improvements(), in.recommendation(), in.comment());
    }
}
