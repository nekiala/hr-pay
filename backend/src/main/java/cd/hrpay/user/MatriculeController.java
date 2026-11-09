package cd.hrpay.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The employee ID (matricule) format: administrators set it, whoever creates people sees whether it is on. */
@RestController
@RequestMapping("/api/matricule-format")
public class MatriculeController {

    public record FormatInput(boolean enabled, @NotBlank @Size(max = 60) String pattern, boolean resetYearly,
                              @Min(1) @Max(999_999_999) int nextSeq) {}

    public record PreviewInput(@NotBlank @Size(max = 60) String pattern, @Min(1) @Max(999_999_999) int nextSeq,
                               @Size(max = 20) String site) {}

    private final Matricules matricules;

    public MatriculeController(Matricules matricules) {
        this.matricules = matricules;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('matricule.manage', 'user.create', 'user.update')")
    public Matricules.Format format() {
        return matricules.format();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('matricule.manage')")
    public Matricules.Format save(@Valid @RequestBody FormatInput b) {
        return matricules.save(b.enabled(), b.pattern(), b.resetYearly(), b.nextSeq());
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('matricule.manage')")
    public Map<String, String> preview(@Valid @RequestBody PreviewInput b) {
        return Map.of("value", matricules.preview(b.pattern(), b.nextSeq(), b.site()));
    }
}
