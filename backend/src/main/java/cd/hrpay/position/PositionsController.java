package cd.hrpay.position;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.security.CurrentUser;
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
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Job descriptions, career ladders and approved positions (/positions page), and what others see of them. */
@RestController
@PreAuthorize("hasAuthority('jobs.manage')")
public class PositionsController {

    private static final String CODE = "[A-Za-z0-9_-]{1,32}";

    private final JobCatalogue jobs;
    private final Positions positions;
    private final JobSheetPdf sheets;
    private final AuditService audit;

    private final cd.hrpay.org.OrgStructure org;

    public PositionsController(JobCatalogue jobs, Positions positions, JobSheetPdf sheets, AuditService audit,
                               cd.hrpay.org.OrgStructure org) {
        this.org = org;
        this.jobs = jobs;
        this.positions = positions;
        this.sheets = sheets;
        this.audit = audit;
    }

    // ---- families and jobs ---------------------------------------------------------------------------------------

    public record FamilyInput(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 150) String name,
                              boolean active) {}

    @GetMapping("/api/job-catalogue/families")
    public List<JobCatalogue.Family> families() {
        return jobs.families();
    }

    @PostMapping("/api/job-catalogue/families")
    public JobCatalogue.Family addFamily(@Valid @RequestBody FamilyInput in) {
        return jobs.saveFamily(null, in.code(), in.name(), in.active());
    }

    @PutMapping("/api/job-catalogue/families/{id}")
    public JobCatalogue.Family saveFamily(@PathVariable long id, @Valid @RequestBody FamilyInput in) {
        return jobs.saveFamily(id, in.code(), in.name(), in.active());
    }

    @DeleteMapping("/api/job-catalogue/families/{id}")
    public void deleteFamily(@PathVariable long id) {
        jobs.deleteFamily(id);
    }

    public record JobInput(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 150) String name,
                           boolean active, Long familyId, @Size(max = 1000) String purpose, @Size(max = 4000) String duties,
                           @Size(max = 300) String education, @Size(max = 300) String experience,
                           @Size(max = 1000) String skills, @Size(max = 1000) String conditions, Long minGradeId,
                           Long maxGradeId, Long reportsToId, boolean unskilled) {
        JobCatalogue.Input toInput() {
            return new JobCatalogue.Input(code, name, active, familyId, purpose, duties, education, experience, skills,
                    conditions, minGradeId, maxGradeId, reportsToId, unskilled);
        }
    }

    @GetMapping("/api/job-catalogue")
    public List<JobCatalogue.Job> jobs() {
        return jobs.jobs();
    }

    @PostMapping("/api/job-catalogue")
    public JobCatalogue.Job addJob(@Valid @RequestBody JobInput in) {
        return jobs.save(null, in.toInput());
    }

    @PutMapping("/api/job-catalogue/{id}")
    public JobCatalogue.Job saveJob(@PathVariable long id, @Valid @RequestBody JobInput in) {
        return jobs.save(id, in.toInput());
    }

    @GetMapping("/api/job-catalogue/{id}/versions")
    public List<JobCatalogue.Version> versions(@PathVariable long id) {
        return jobs.versions(id);
    }

    @GetMapping("/api/job-catalogue/{id}/pdf")
    public ResponseEntity<byte[]> sheet(@PathVariable long id, @RequestParam(required = false) Integer version) {
        jobs.job(id);
        return pdf(sheets.render(id, null, version), "fiche-de-poste-" + jobs.job(id).code().toLowerCase() + ".pdf");
    }

    /** The job description of a person's contract (HR, or the person). */
    @GetMapping("/api/job-catalogue/people/{userId}/pdf")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> personSheet(@PathVariable long userId) {
        if (!CurrentUser.has("jobs.manage") && !CurrentUser.has("user.read") && userId != CurrentUser.id()) {
            throw new org.springframework.security.access.AccessDeniedException("Not allowed.");
        }
        long[] job = jobs.jobOf(userId).orElseThrow(() -> new NotFoundException("This person's contract has no job."));
        audit.record("job.sheet_printed", "user", userId, Map.of("job", job[0]));
        return pdf(sheets.render(job[0], userId, job[1] == 0 ? null : (int) job[1]), "fiche-de-poste.pdf");
    }

    public record MyJob(long occupationId, String name, boolean described) {}

    /** The job of one's own contract, if it has one. */
    @GetMapping("/api/job-catalogue/mine")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MyJob> myJob() {
        return jobs.jobOf(CurrentUser.id()).map(j -> {
            JobCatalogue.Job job = jobs.job(j[0]);
            return ResponseEntity.ok(new MyJob(job.id(), job.name(), job.version() > 0));
        }).orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/api/job-catalogue/mine/pdf")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> mySheet() {
        return personSheet(CurrentUser.id());
    }

    private static ResponseEntity<byte[]> pdf(byte[] body, String name) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
                .header("X-Content-Type-Options", "nosniff").body(body);
    }

    // ---- ladders -------------------------------------------------------------------------------------------------

    public record PathInput(@NotNull Long fromId, @NotNull Long toId, @DecimalMin("0") @DecimalMax("50") BigDecimal minYears,
                            @DecimalMin("0") @DecimalMax("100") BigDecimal minScore, @Size(max = 300) String requirement) {}

    @GetMapping("/api/job-catalogue/paths")
    public List<JobCatalogue.Path> paths() {
        return jobs.paths();
    }

    @PostMapping("/api/job-catalogue/paths")
    public JobCatalogue.Path addPath(@Valid @RequestBody PathInput in) {
        return jobs.savePath(null, in.fromId(), in.toId(), in.minYears(), in.minScore(), in.requirement());
    }

    @PutMapping("/api/job-catalogue/paths/{id}")
    public JobCatalogue.Path savePath(@PathVariable long id, @Valid @RequestBody PathInput in) {
        return jobs.savePath(id, in.fromId(), in.toId(), in.minYears(), in.minScore(), in.requirement());
    }

    @DeleteMapping("/api/job-catalogue/paths/{id}")
    public void deletePath(@PathVariable long id) {
        jobs.deletePath(id);
    }

    @GetMapping("/api/job-catalogue/people/{userId}/next-steps")
    @PreAuthorize("hasAuthority('jobs.manage') or hasAuthority('user.read')")
    public JobCatalogue.NextSteps nextSteps(@PathVariable long userId) {
        return jobs.nextSteps(userId);
    }

    @GetMapping("/api/job-catalogue/ready")
    public List<JobCatalogue.Ready> ready() {
        return jobs.readyForPromotion();
    }

    // ---- positions -----------------------------------------------------------------------------------------------

    public record BudgetInput(@NotNull @Min(2000) @Max(2100) Integer year, @NotNull Long unitId, @NotNull Long occupationId,
                              @NotNull @Min(0) @Max(10000) Integer posts, @DecimalMin("0") BigDecimal budgetCost,
                              @Size(max = 300) String note) {}

    public record CopyInput(@Min(2000) @Max(2100) Integer fromYear, @NotNull @Min(2000) @Max(2100) Integer toYear,
                            boolean fromFilled) {}

    @GetMapping("/api/positions")
    public Positions.Headcount headcount(@RequestParam int year) {
        return positions.headcount(year);
    }

    @PostMapping("/api/positions")
    public Positions.Budget addBudget(@Valid @RequestBody BudgetInput in) {
        return positions.save(null, in.year(), in.unitId(), in.occupationId(), in.posts(), in.budgetCost(), in.note());
    }

    @PutMapping("/api/positions/{id}")
    public Positions.Budget saveBudget(@PathVariable long id, @Valid @RequestBody BudgetInput in) {
        return positions.save(id, in.year(), in.unitId(), in.occupationId(), in.posts(), in.budgetCost(), in.note());
    }

    @DeleteMapping("/api/positions/{id}")
    public void deleteBudget(@PathVariable long id) {
        positions.delete(id);
    }

    @PostMapping("/api/positions/copy")
    public Map<String, Integer> copy(@Valid @RequestBody CopyInput in) {
        int from = in.fromYear() == null ? in.toYear() - 1 : in.fromYear();
        return Map.of("lines", positions.copy(from, in.toYear(), in.fromFilled()));
    }

    @GetMapping("/api/positions/vacancies")
    public List<Positions.Vacancy> vacancies(@RequestParam int year) {
        return positions.vacancies(year);
    }

    /** Budgeted and filled posts of each unit, for the org chart. */
    @GetMapping("/api/positions/units")
    @PreAuthorize("isAuthenticated()")
    public List<Positions.UnitCount> unitCounts(@RequestParam int year) {
        return positions.unitCounts(year);
    }

    /** The warning before a contract or an assignment: no free post of that job in that unit. */
    @GetMapping("/api/positions/check")
    @PreAuthorize("hasAuthority('jobs.manage') or hasAuthority('salary.write') or hasAuthority('org.manage')")
    public Map<String, String> check(@RequestParam(required = false) Long userId, @RequestParam(required = false) Long unitId,
                                     @RequestParam Long occupationId, @RequestParam(required = false) LocalDate on) {
        Long unit = unitId != null ? unitId : userId == null ? null
                : org.currentAssignment(userId).map(cd.hrpay.org.OrgStructure.Assignment::unitId).orElse(null);
        return positions.check(userId, unit, occupationId, on).map(w -> Map.of("warning", w)).orElse(Map.of());
    }
}
