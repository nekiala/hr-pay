package cd.hrpay.payroll;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Raising grade bases to the legal minimum, people kept at the same net (shown first, then applied). */
@RestController
@RequestMapping("/api/grades/legal-minimum")
public class LegalMinimumController {

    private final LegalMinimumGrades grades;

    public LegalMinimumController(LegalMinimumGrades grades) {
        this.grades = grades;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('masterdata.manage') and hasAuthority('salary.write')")
    public LegalMinimumGrades.Result preview(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from) {
        return grades.preview(from);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('masterdata.manage') and hasAuthority('salary.write')")
    public LegalMinimumGrades.Result apply(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from) {
        return grades.apply(from);
    }
}
