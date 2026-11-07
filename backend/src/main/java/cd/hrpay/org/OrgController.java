package cd.hrpay.org;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.Csv;
import cd.hrpay.security.CurrentUser;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/org")
public class OrgController {

    public record LevelInput(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String code, @NotBlank @Size(max = 100) String name,
                             @Min(1) @Max(20) int rank, @NotBlank @Size(max = 100) String headTitle, Boolean staff,
                             Boolean active) {}

    public record UnitInput(@NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{1,32}") String code, @NotBlank @Size(max = 150) String name,
                            @NotNull Long levelId, Long parentId, @Size(max = 40) String costCentre, LocalDate validFrom) {}

    public record DateInput(@NotNull LocalDate on) {}

    public record HeadInput(@NotNull Long userId, @NotNull @Pattern(regexp = "HEAD|DEPUTY") String role, Boolean interim,
                            @Size(max = 100) String title, LocalDate from) {}

    public record EndHeadInput(@NotNull @Pattern(regexp = "HEAD|DEPUTY") String role, LocalDate on) {}

    public record AssignInput(@NotNull Long unitId, LocalDate from,
                              @NotNull @Pattern(regexp = "HIRE|TRANSFER|PROMOTION|REORGANISATION|CORRECTION") String reason,
                              @Size(max = 300) String note) {}

    public record BulkInput(@NotEmpty List<Long> userIds, @NotNull Long unitId, LocalDate from,
                            @NotNull @Pattern(regexp = "TRANSFER|PROMOTION|REORGANISATION|CORRECTION") String reason,
                            @Size(max = 300) String note) {}

    public record ImportInput(@NotBlank @Size(max = 1_000_000) String csv) {}

    private final OrgStructure org;
    private final OrgCsv csv;
    private final AuditService audit;

    public OrgController(OrgStructure org, OrgCsv csv, AuditService audit) {
        this.org = org;
        this.csv = csv;
        this.audit = audit;
    }

    // ---- levels ----------------------------------------------------------------------------------------------

    @GetMapping("/levels")
    @PreAuthorize("isAuthenticated()")
    public List<OrgStructure.Level> levels() {
        return org.levels();
    }

    @PostMapping("/levels")
    @PreAuthorize("hasAuthority('org.manage')")
    public OrgStructure.Level createLevel(@Valid @RequestBody LevelInput b) {
        return org.saveLevel(null, b.code(), b.name(), b.rank(), b.headTitle(), Boolean.TRUE.equals(b.staff()), !Boolean.FALSE.equals(b.active()));
    }

    @PutMapping("/levels/{id}")
    @PreAuthorize("hasAuthority('org.manage')")
    public OrgStructure.Level updateLevel(@PathVariable long id, @Valid @RequestBody LevelInput b) {
        return org.saveLevel(id, b.code(), b.name(), b.rank(), b.headTitle(), Boolean.TRUE.equals(b.staff()), !Boolean.FALSE.equals(b.active()));
    }

    // ---- units -----------------------------------------------------------------------------------------------

    /** The chart is open to everyone; headcounts need user.read. */
    @GetMapping("/units")
    @PreAuthorize("isAuthenticated()")
    public List<OrgStructure.Unit> units(@RequestParam(defaultValue = "false") boolean closed) {
        return org.units(closed, CurrentUser.has("user.read"));
    }

    @GetMapping("/units/{id}")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> unit(@PathVariable long id) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean people = CurrentUser.has("user.read");
        OrgStructure.Unit u = org.units(true, people).stream().filter(x -> x.id() == id).findFirst()
                .orElseThrow(() -> new cd.hrpay.common.NotFoundException("Unit not found."));
        out.put("unit", u);
        out.put("heads", org.headHistory(id));
        if (people) {
            out.put("members", org.people().stream().filter(m -> m.unitId() == id).toList());
        }
        return out;
    }

    @PostMapping("/units")
    @PreAuthorize("hasAuthority('org.manage')")
    public OrgStructure.Unit createUnit(@Valid @RequestBody UnitInput b) {
        return org.createUnit(b.code(), b.name(), b.levelId(), b.parentId(), b.costCentre(), b.validFrom());
    }

    @PutMapping("/units/{id}")
    @PreAuthorize("hasAuthority('org.manage')")
    public OrgStructure.Unit updateUnit(@PathVariable long id, @Valid @RequestBody UnitInput b) {
        return org.updateUnit(id, b.name(), b.levelId(), b.parentId(), b.costCentre());
    }

    @PostMapping("/units/{id}/close")
    @PreAuthorize("hasAuthority('org.manage')")
    public OrgStructure.Unit close(@PathVariable long id, @Valid @RequestBody DateInput b) {
        return org.closeUnit(id, b.on());
    }

    @PutMapping("/units/{id}/head")
    @PreAuthorize("hasAuthority('org.manage')")
    public OrgStructure.Unit setHead(@PathVariable long id, @Valid @RequestBody HeadInput b) {
        return org.setHead(id, b.userId(), b.role(), Boolean.TRUE.equals(b.interim()), b.title(), b.from());
    }

    @PostMapping("/units/{id}/head/end")
    @PreAuthorize("hasAuthority('org.manage')")
    public OrgStructure.Unit endHead(@PathVariable long id, @Valid @RequestBody EndHeadInput b) {
        return org.endHead(id, b.role(), b.on());
    }

    @GetMapping("/units.csv")
    @PreAuthorize("isAuthenticated()")
    public void export(HttpServletResponse response) throws IOException {
        PrintWriter out = Csv.start(response, "org-structure.csv");
        Csv.row(out, OrgCsv.HEADER.toArray());
        for (List<String> r : csv.export()) {
            Csv.row(out, r.toArray());
        }
        out.flush();
    }

    @PostMapping("/units/import")
    @PreAuthorize("hasAuthority('org.manage')")
    public OrgCsv.Result importUnits(@Valid @RequestBody ImportInput b) {
        OrgCsv.Result r = csv.importCsv(b.csv());
        audit.record("org_structure.imported", "org_unit", null, Map.of("created", r.created(), "updated", r.updated(),
                "heads", r.headsSet()));
        return r;
    }

    // ---- people ----------------------------------------------------------------------------------------------

    @GetMapping("/people")
    @PreAuthorize("hasAuthority('user.read')")
    public List<OrgStructure.Member> people() {
        return org.people();
    }

    @GetMapping("/users/{userId}/assignments")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> assignments(@PathVariable long userId) {
        if (!CurrentUser.has("user.read") && !Long.valueOf(userId).equals(CurrentUser.id())) {
            throw new AccessDeniedException("Not allowed.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("history", org.history(userId));
        out.put("manager", org.managerOf(userId).orElse(null));
        out.put("heads", org.headedBy(userId));
        return out;
    }

    @PostMapping("/users/{userId}/assignments")
    @PreAuthorize("hasAuthority('org.manage')")
    public List<OrgStructure.Assignment> assign(@PathVariable long userId, @Valid @RequestBody AssignInput b) {
        return org.assign(userId, b.unitId(), b.from(), b.reason(), b.note());
    }

    @PostMapping("/assignments/bulk")
    @PreAuthorize("hasAuthority('org.manage')")
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> bulk(@Valid @RequestBody BulkInput b) {
        for (Long u : b.userIds()) {
            org.assign(u, b.unitId(), b.from(), b.reason(), b.note());
        }
        return Map.of("moved", b.userIds().size());
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> me() {
        return assignments(CurrentUser.id());
    }
}
