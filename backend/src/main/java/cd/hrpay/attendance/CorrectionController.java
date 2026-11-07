package cd.hrpay.attendance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@cd.hrpay.platform.RequiresModule("ATTENDANCE")
@RequestMapping("/api/attendance")
public class CorrectionController {

    public record EntryInput(@NotNull ClockEvent.Type type, @NotNull LocalDateTime at, LocalDate workDate,
                             @NotBlank @Size(max = 500) String note) {}

    public record VoidInput(@NotBlank @Size(max = 500) String reason) {}

    private final CorrectionService corrections;

    public CorrectionController(CorrectionService corrections) {
        this.corrections = corrections;
    }

    @PostMapping("/users/{userId}/events")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('attendance.correct')")
    public AttendanceController.EventView add(@PathVariable Long userId, @Valid @RequestBody EntryInput body) {
        return AttendanceController.EventView.of(
                corrections.add(userId, body.type(), body.at(), body.workDate(), body.note()));
    }

    @PostMapping("/events/{id}/void")
    @PreAuthorize("hasAuthority('attendance.correct')")
    public AttendanceController.EventView voidEvent(@PathVariable Long id, @Valid @RequestBody VoidInput body) {
        return AttendanceController.EventView.of(corrections.voidEvent(id, body.reason()));
    }
}
