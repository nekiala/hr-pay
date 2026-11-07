package cd.hrpay.privacy;

import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PersonalDataController {

    public record Ask(@NotBlank String field, @Size(max = 500) String current, @NotBlank @Size(max = 500) String requested,
                      @Size(max = 1000) String reason) {}

    public record Answer(@Size(max = 1000) String answer) {}

    public record Retention(int eraseAfterYears) {}

    private final PersonalData data;

    public PersonalDataController(PersonalData data) {
        this.data = data;
    }

    private static long me() {
        if (CurrentUser.id() == null || cd.hrpay.platform.SupportAccess.current() != null) {
            throw new BusinessRuleException("Not available on a support visit.");
        }
        return CurrentUser.id();
    }

    private static ResponseEntity<byte[]> file(long userId, String body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"my-data-" + userId + ".json\"")
                .contentType(MediaType.APPLICATION_JSON).body(body.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/api/account/data-export")
    public ResponseEntity<byte[]> myData() {
        long id = me();
        return file(id, data.export(id));
    }

    @GetMapping("/api/account/corrections")
    public List<PersonalData.Correction> myCorrections() {
        return data.corrections(me(), null);
    }

    @PostMapping("/api/account/corrections")
    public PersonalData.Correction ask(@Valid @RequestBody Ask body) {
        return data.ask(me(), body.field(), body.current(), body.requested(), body.reason());
    }

    // ---- HR ----

    @GetMapping("/api/users/{id}/data-export")
    @PreAuthorize("hasAuthority('privacy.manage')")
    public ResponseEntity<byte[]> dataOf(@PathVariable long id) {
        return file(id, data.export(id));
    }

    @GetMapping("/api/privacy/corrections")
    @PreAuthorize("hasAuthority('privacy.manage')")
    public List<PersonalData.Correction> corrections(@RequestParam(required = false) String status) {
        return data.corrections(null, status);
    }

    @PostMapping("/api/privacy/corrections/{id}/done")
    @PreAuthorize("hasAuthority('privacy.manage')")
    public PersonalData.Correction done(@PathVariable long id, @Valid @RequestBody Answer body) {
        return data.decide(id, true, body.answer());
    }

    @PostMapping("/api/privacy/corrections/{id}/refuse")
    @PreAuthorize("hasAuthority('privacy.manage')")
    public PersonalData.Correction refuse(@PathVariable long id, @Valid @RequestBody Answer body) {
        return data.decide(id, false, body.answer());
    }

    @GetMapping("/api/privacy/retention")
    @PreAuthorize("hasAuthority('privacy.manage')")
    public Map<String, Object> retention() {
        return Map.of("eraseAfterYears", data.retentionYears(), "due", data.due());
    }

    @PutMapping("/api/privacy/retention")
    @PreAuthorize("hasAuthority('privacy.manage')")
    public Map<String, Object> setRetention(@RequestBody Retention body) {
        data.setRetentionYears(body.eraseAfterYears());
        return retention();
    }

    @PostMapping("/api/privacy/erase/{userId}")
    @PreAuthorize("hasAuthority('privacy.manage')")
    public Map<String, Object> erase(@PathVariable long userId) {
        data.erase(userId);
        return retention();
    }
}
