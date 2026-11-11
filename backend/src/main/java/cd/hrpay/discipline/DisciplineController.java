package cd.hrpay.discipline;

import cd.hrpay.security.CurrentUser;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The discipline register for HR and unit heads, and the employee's own cases. */
@RestController
public class DisciplineController {

    private final Discipline discipline;

    public DisciplineController(Discipline discipline) {
        this.discipline = discipline;
    }

    public record OpenInput(@NotNull Long userId, LocalDate factsDate, LocalDate knownDate, String facts, String ruleBroken,
                            LocalDate latenessMonth) {}

    public record ReviewInput(boolean accept, String note) {}

    public record HearingInput(LocalDateTime at, String place) {}

    public record HeardInput(LocalDate on, boolean attended, String statement, String note) {}

    public record DecisionInput(Long sanctionTypeId, String note, LocalDate suspensionFrom, Integer suspensionDays) {}

    public record LetterInput(String title, String body) {}

    public record ReasonInput(String reason) {}

    public record TypeInput(String name, int weight, boolean active) {}

    public record ContestInput(String text) {}

    @GetMapping("/api/discipline/cases")
    @PreAuthorize("hasAnyAuthority('discipline.manage', 'discipline.propose')")
    public List<Discipline.Case> list(@RequestParam(required = false) String status, @RequestParam(required = false) Long userId) {
        return discipline.list(status, userId);
    }

    @GetMapping("/api/discipline/cases/{id}")
    @PreAuthorize("hasAnyAuthority('discipline.manage', 'discipline.propose')")
    public Discipline.Case find(@PathVariable long id) {
        return discipline.find(id);
    }

    @PostMapping("/api/discipline/cases")
    @PreAuthorize("hasAnyAuthority('discipline.manage', 'discipline.propose')")
    public Discipline.Case open(@RequestBody OpenInput b) {
        return discipline.open(b.userId(), b.factsDate(), b.knownDate(), b.facts(), b.ruleBroken(), b.latenessMonth());
    }

    @PostMapping("/api/discipline/cases/{id}/review")
    @PreAuthorize("hasAuthority('discipline.manage')")
    public Discipline.Case review(@PathVariable long id, @RequestBody ReviewInput b) {
        return discipline.review(id, b.accept(), b.note());
    }

    @PostMapping("/api/discipline/cases/{id}/hearing")
    @PreAuthorize("hasAuthority('discipline.manage')")
    public Discipline.Case convene(@PathVariable long id, @RequestBody HearingInput b) {
        return discipline.convene(id, b.at(), b.place());
    }

    @PostMapping("/api/discipline/cases/{id}/heard")
    @PreAuthorize("hasAuthority('discipline.manage')")
    public Discipline.Case heard(@PathVariable long id, @RequestBody HeardInput b) {
        return discipline.heard(id, b.on(), b.attended(), b.statement(), b.note());
    }

    @PostMapping("/api/discipline/cases/{id}/decision")
    @PreAuthorize("hasAuthority('discipline.decide')")
    public Discipline.Case decide(@PathVariable long id, @RequestBody DecisionInput b) {
        return discipline.decide(id, b.sanctionTypeId(), b.note(), b.suspensionFrom(), b.suspensionDays());
    }

    @PutMapping("/api/discipline/cases/{id}/letters/{which}")
    @PreAuthorize("hasAuthority('discipline.manage')")
    public Discipline.Case editLetter(@PathVariable long id, @PathVariable String which, @RequestBody LetterInput b) {
        return discipline.editLetter(id, which, b.title(), b.body());
    }

    @GetMapping("/api/discipline/cases/{id}/letters/{which}")
    @PreAuthorize("hasAuthority('discipline.manage')")
    public cd.hrpay.lifecycle.Letters.Letter letter(@PathVariable long id, @PathVariable String which) {
        return discipline.letter(id, which);
    }

    @GetMapping("/api/discipline/cases/{id}/letters/{which}/file")
    @PreAuthorize("hasAuthority('discipline.manage')")
    public org.springframework.http.ResponseEntity<byte[]> letterFile(@PathVariable long id, @PathVariable String which) {
        return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "inline; filename=\"discipline-" + id + "-" + which + ".pdf\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(discipline.letterFile(id, which));
    }

    @PostMapping("/api/discipline/cases/{id}/letters/{which}/send")
    @PreAuthorize("hasAuthority('discipline.manage')")
    public Discipline.Case sendLetter(@PathVariable long id, @PathVariable String which) {
        return discipline.sendLetter(id, which);
    }

    @PostMapping("/api/discipline/cases/{id}/cancel")
    @PreAuthorize("hasAuthority('discipline.manage')")
    public Discipline.Case cancel(@PathVariable long id, @RequestBody ReasonInput b) {
        return discipline.cancel(id, b.reason());
    }

    @GetMapping("/api/discipline/types")
    @PreAuthorize("hasAnyAuthority('discipline.manage', 'discipline.decide', 'discipline.propose', 'report.hr')")
    public List<Discipline.SanctionType> types() {
        return discipline.types();
    }

    @PutMapping("/api/discipline/types/{id}")
    @PreAuthorize("hasAuthority('discipline.manage')")
    public Discipline.SanctionType saveType(@PathVariable long id, @RequestBody TypeInput b) {
        return discipline.saveType(id, b.name(), b.weight(), b.active());
    }

    @GetMapping("/api/discipline/settings")
    @PreAuthorize("hasAnyAuthority('discipline.manage', 'discipline.decide')")
    public Discipline.Settings settings() {
        return discipline.settings();
    }

    @PutMapping("/api/discipline/settings")
    @PreAuthorize("hasAuthority('discipline.manage')")
    public Discipline.Settings saveSettings(@RequestBody Discipline.Settings b) {
        return discipline.saveSettings(b);
    }

    @GetMapping("/api/my/discipline")
    public List<Discipline.Case> mine() {
        return discipline.mine(CurrentUser.id());
    }

    @PostMapping("/api/my/discipline/{id}/contest")
    public Discipline.Case contest(@PathVariable long id, @RequestBody ContestInput b) {
        return discipline.contest(id, CurrentUser.id(), b.text());
    }
}
