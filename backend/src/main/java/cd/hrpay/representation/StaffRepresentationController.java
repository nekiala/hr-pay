package cd.hrpay.representation;

import cd.hrpay.lifecycle.Files;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The staff representation page: delegates, elections and meetings, inspector authorisations, hours of the mandate. */
@RestController
@RequestMapping("/api/representation")
@PreAuthorize("hasAuthority('staff.representation')")
public class StaffRepresentationController {

    private final StaffRepresentation rep;

    public StaffRepresentationController(StaffRepresentation rep) {
        this.rep = rep;
    }

    public record DelegateInput(@NotNull Long userId, @NotBlank @Pattern(regexp = "TITULAR|ALTERNATE") String seat,
                                @Size(max = 150) String unionName, @NotNull LocalDate electedOn, LocalDate termEnd) {}

    public record EndInput(@NotNull LocalDate endedOn, @NotBlank @Size(max = 300) String reason) {}

    public record EventInput(@NotBlank @Pattern(regexp = "ELECTION|MEETING|RULES_SENT") String kind, @NotNull LocalDate heldOn,
                             LocalDate announcedOn, @Size(max = 200) String title, @Size(max = 4000) String note,
                             LocalDate inspectorCopyOn, @Size(max = 200) String fileName, @Size(max = 14_000_000) String content) {}

    public record CopyInput(LocalDate inspectorCopyOn) {}

    public record AuthorisationInput(@NotNull Long userId, @NotBlank @Pattern(regexp = "DISMISSAL|TRANSFER") String action,
                                     @NotNull LocalDate decidedOn, @NotBlank @Size(max = 100) String reference,
                                     Boolean granted, @Size(max = 500) String note, @Size(max = 200) String fileName,
                                     @Size(max = 14_000_000) String content) {}

    public record HoursInput(@NotNull Long userId, @NotNull LocalDate onDay,
                             @NotNull @DecimalMin("0.25") @DecimalMax("12") BigDecimal hours, @Size(max = 300) String note) {}

    @GetMapping
    public StaffRepresentation.Overview overview() {
        return rep.overview();
    }

    @PostMapping("/delegates")
    @ResponseStatus(HttpStatus.CREATED)
    public StaffRepresentation.Saved<StaffRepresentation.Delegate> addDelegate(@Valid @RequestBody DelegateInput b) {
        return rep.addDelegate(b.userId(), b.seat(), b.unionName(), b.electedOn(), b.termEnd());
    }

    @PostMapping("/delegates/{id}/end")
    public StaffRepresentation.Delegate endDelegate(@PathVariable long id, @Valid @RequestBody EndInput b) {
        return rep.endDelegate(id, b.endedOn(), b.reason());
    }

    @DeleteMapping("/delegates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDelegate(@PathVariable long id) {
        rep.deleteDelegate(id);
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    public StaffRepresentation.Saved<StaffRepresentation.Event> addEvent(@Valid @RequestBody EventInput b) {
        byte[] data = b.content() == null || b.content().isBlank() ? null : Files.decode(b.content());
        return rep.addEvent(b.kind(), b.heldOn(), b.announcedOn(), b.title(), b.note(), b.inspectorCopyOn(), b.fileName(), data);
    }

    @PutMapping("/events/{id}/inspector-copy")
    public StaffRepresentation.Event inspectorCopy(@PathVariable long id, @RequestBody CopyInput b) {
        return rep.setInspectorCopy(id, b.inspectorCopyOn());
    }

    @DeleteMapping("/events/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEvent(@PathVariable long id) {
        rep.deleteEvent(id);
    }

    @GetMapping("/events/{id}/file")
    public ResponseEntity<byte[]> eventFile(@PathVariable long id) {
        StaffRepresentation.File f = rep.eventFile(id);
        return Files.inline(f.name(), f.contentType(), f.data());
    }

    @PostMapping("/authorisations")
    @ResponseStatus(HttpStatus.CREATED)
    public StaffRepresentation.Authorisation addAuthorisation(@Valid @RequestBody AuthorisationInput b) {
        byte[] data = b.content() == null || b.content().isBlank() ? null : Files.decode(b.content());
        return rep.addAuthorisation(b.userId(), b.action(), b.decidedOn(), b.reference(), !Boolean.FALSE.equals(b.granted()),
                b.note(), b.fileName(), data);
    }

    @GetMapping("/authorisations/{id}/file")
    public ResponseEntity<byte[]> authorisationFile(@PathVariable long id) {
        StaffRepresentation.File f = rep.authorisationFile(id);
        return Files.inline(f.name(), f.contentType(), f.data());
    }

    @GetMapping("/hours")
    public StaffRepresentation.Hours hours(@RequestParam(required = false) YearMonth month) {
        return rep.hours(month == null ? YearMonth.now() : month);
    }

    @PostMapping("/hours")
    @ResponseStatus(HttpStatus.CREATED)
    public StaffRepresentation.Saved<StaffRepresentation.HoursEntry> addHours(@Valid @RequestBody HoursInput b) {
        return rep.addHours(b.userId(), b.onDay(), b.hours(), b.note());
    }

    @DeleteMapping("/hours/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteHours(@PathVariable long id) {
        rep.deleteHours(id);
    }

    /** Whether a person holds a mandate today: the employee file shows it. */
    @GetMapping("/people/{userId}")
    @PreAuthorize("hasAuthority('staff.representation') or hasAuthority('user.read')")
    public List<StaffRepresentation.Delegate> of(@PathVariable long userId) {
        return rep.mandateOn(userId, LocalDate.now()).stream().toList();
    }
}
