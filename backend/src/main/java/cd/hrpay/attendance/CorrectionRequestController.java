package cd.hrpay.attendance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@cd.hrpay.platform.RequiresModule("ATTENDANCE")
@RequestMapping("/api/corrections")
public class CorrectionRequestController {

    public record CreateInput(Long userId, @NotNull LocalDate workDate, @NotNull @Pattern(regexp = "ADD|VOID") String action,
                              ClockEvent.Type type, LocalTime time, Long eventId,
                              @NotBlank @Size(max = 500) String reason) {}

    public record NoteInput(@Size(max = 300) String note) {}

    private final CorrectionRequests requests;

    public CorrectionRequestController(CorrectionRequests requests) {
        this.requests = requests;
    }

    @GetMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    public List<CorrectionRequests.Request> mine() {
        return requests.mine();
    }

    @GetMapping("/entries")
    @PreAuthorize("isAuthenticated()")
    public List<CorrectionRequests.Entry> entries(@org.springframework.web.bind.annotation.RequestParam(required = false) Long userId,
                                                  @org.springframework.web.bind.annotation.RequestParam LocalDate date) {
        return requests.entries(userId, date);
    }

    @GetMapping("/to-decide")
    @PreAuthorize("hasAnyAuthority('attendance.correct', 'attendance.correct.propose')")
    public List<CorrectionRequests.Request> toDecide() {
        return requests.toDecide();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('attendance.correct.request', 'attendance.correct.propose')")
    public CorrectionRequests.Request create(@Valid @RequestBody CreateInput b) {
        return requests.create(b.userId(), b.workDate(), b.action(), b.type(), b.time(), b.eventId(), b.reason());
    }

    @PostMapping("/{id}/propose")
    @PreAuthorize("hasAuthority('attendance.correct.propose')")
    public CorrectionRequests.Request propose(@PathVariable long id, @Valid @RequestBody NoteInput b) {
        return requests.propose(id, b.note());
    }

    @PostMapping("/{id}/apply")
    @PreAuthorize("hasAuthority('attendance.correct')")
    public CorrectionRequests.Request apply(@PathVariable long id, @Valid @RequestBody NoteInput b) {
        return requests.apply(id, b.note());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyAuthority('attendance.correct', 'attendance.correct.propose')")
    public CorrectionRequests.Request reject(@PathVariable long id, @Valid @RequestBody NoteInput b) {
        return requests.reject(id, b.note());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("isAuthenticated()")
    public CorrectionRequests.Request cancel(@PathVariable long id) {
        return requests.cancel(id);
    }
}
