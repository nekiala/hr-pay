package cd.hrpay.recruitment;

import cd.hrpay.lifecycle.Files;
import cd.hrpay.payment.Company;
import cd.hrpay.tenancy.Tenants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The public careers page (no sign-in), its settings for HR, and the job offers staff see in the app. */
@RestController
public class CareersController {

    public record SettingsInput(@Size(max = 40) String slug, @NotNull Boolean enabled) {}

    /** {@code website} is a field people never see: a robot that fills it is told "thank you" and nothing is saved. */
    public record ApplyInput(@NotBlank @Size(max = 100) String firstName, @NotBlank @Size(max = 100) String lastName,
                             @NotBlank @Email @Size(max = 254) String email,
                             @NotBlank @Pattern(regexp = "\\+?[0-9\\s()-]{8,20}") String phone,
                             @Size(max = 4000) String coverLetter, Boolean keepForFuture, Boolean consent,
                             @Size(max = 200) String cvName, @Size(max = 100) String cvType, @Size(max = 7_000_000) String cv,
                             @Size(max = 60) String channel, @Size(max = 200) String website) {}

    public record NoteInput(@Size(max = 2000) String note) {}

    private final Careers careers;
    private final Tenants tenants;
    private final Company company;
    private final Map<String, Deque<Long>> pageHits = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> applies = new ConcurrentHashMap<>();

    public CareersController(Careers careers, Tenants tenants, Company company) {
        this.careers = careers;
        this.tenants = tenants;
        this.company = company;
    }

    // ---- settings (HR)

    @GetMapping("/api/recruitment/careers")
    @PreAuthorize("hasAuthority('recruitment.manage')")
    public Careers.Settings settings() {
        return careers.settings();
    }

    @PutMapping("/api/recruitment/careers")
    @PreAuthorize("hasAuthority('recruitment.manage')")
    public Careers.Settings saveSettings(@Valid @RequestBody SettingsInput in) {
        return careers.saveSettings(in.slug(), in.enabled());
    }

    // ---- the public page

    @GetMapping("/api/public/careers/{slug}")
    public Careers.Page page(@PathVariable String slug, HttpServletRequest request) {
        limit(pageHits, request, 120, 60_000L);
        return as(slug, careers::page);
    }

    @GetMapping("/api/public/careers/{slug}/offers/{id}")
    public Careers.Ad ad(@PathVariable String slug, @PathVariable long id, HttpServletRequest request) {
        limit(pageHits, request, 120, 60_000L);
        return as(slug, () -> careers.ad(id));
    }

    @GetMapping("/api/public/careers/{slug}/logo")
    public ResponseEntity<byte[]> logo(@PathVariable String slug, HttpServletRequest request) {
        limit(pageHits, request, 120, 60_000L);
        return as(slug, () -> company.logo().map(l -> ResponseEntity.ok().contentType(MediaType.parseMediaType(l.contentType()))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1))).body(l.data()))
                .orElseGet(() -> ResponseEntity.notFound().build()));
    }

    @PostMapping("/api/public/careers/{slug}/offers/{id}/apply")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void apply(@PathVariable String slug, @PathVariable long id, @Valid @RequestBody ApplyInput in,
                      HttpServletRequest request) {
        limit(applies, request, 5, 3_600_000L);
        if (in.website() != null && !in.website().isBlank()) {
            return;
        }
        byte[] cv = in.cv() == null || in.cv().isBlank() ? null : Files.decode(in.cv());
        Careers.ApplyInput data = new Careers.ApplyInput(in.firstName(), in.lastName(), in.email(), in.phone(), in.coverLetter(),
                Boolean.TRUE.equals(in.keepForFuture()), Boolean.TRUE.equals(in.consent()), in.cvName(), in.cvType(), cv,
                in.channel());
        as(slug, () -> {
            careers.apply(id, data);
            return null;
        });
    }

    @GetMapping("/api/public/careers/withdraw/{token}")
    public Careers.Withdrawal withdrawal(@PathVariable String token, HttpServletRequest request) {
        limit(pageHits, request, 120, 60_000L);
        UUID t = token(token);
        return tenants.callAs(companyOfToken(t), () -> careers.withdrawal(t));
    }

    @PostMapping("/api/public/careers/withdraw/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@PathVariable String token, HttpServletRequest request) {
        limit(applies, request, 20, 3_600_000L);
        UUID t = token(token);
        tenants.runAs(companyOfToken(t), () -> careers.withdraw(t));
    }

    private Long companyOfToken(UUID token) {
        Long id = tenants.callAsPlatform(() -> careers.companyOfToken(token));
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This link is no longer valid.");
        }
        return id;
    }

    private static UUID token(String token) {
        try {
            return UUID.fromString(token);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This link is no longer valid.");
        }
    }

    private <T> T as(String slug, Supplier<T> work) {
        Long id = tenants.callAsPlatform(() -> careers.companyOf(slug));
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This careers page does not exist.");
        }
        return tenants.callAs(id, work);
    }

    private static void limit(Map<String, Deque<Long>> map, HttpServletRequest request, int max, long windowMs) {
        String ip = request.getRemoteAddr() == null ? "?" : request.getRemoteAddr();
        long now = System.currentTimeMillis();
        Deque<Long> times = map.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > windowMs) {
                times.pollFirst();
            }
            if (times.size() >= max) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests; try again later.");
            }
            times.addLast(now);
        }
        if (map.size() > 10_000) {
            map.entrySet().removeIf(e -> e.getValue().isEmpty());
        }
    }

    // ---- job offers for staff

    @GetMapping("/api/job-offers")
    public List<Careers.JobOffer> jobOffers() {
        return careers.jobOffers();
    }

    @GetMapping("/api/job-offers/{id}")
    public Careers.JobOfferAd jobOffer(@PathVariable long id) {
        return careers.jobOffer(id);
    }

    @PostMapping("/api/job-offers/{id}/apply")
    public Careers.JobOfferAd applyInternal(@PathVariable long id, @Valid @RequestBody NoteInput in) {
        return careers.applyInternal(id, in.note());
    }

    @PostMapping("/api/job-offers/{id}/withdraw")
    public Careers.JobOfferAd withdrawInternal(@PathVariable long id) {
        return careers.withdrawInternal(id);
    }
}
