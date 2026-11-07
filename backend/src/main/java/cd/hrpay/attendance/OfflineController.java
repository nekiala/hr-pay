package cd.hrpay.attendance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Where the phone app sends the check-ins it kept while offline. */
@RestController
@RequestMapping("/api/attendance/offline")
public class OfflineController {

    public record PunchInput(@NotBlank @Pattern(regexp = "[A-Za-z0-9-]{8,64}") String clientId, @NotNull ClockEvent.Type type,
                             @NotNull OffsetDateTime at, @DecimalMin("-90") @DecimalMax("90") BigDecimal lat,
                             @DecimalMin("-180") @DecimalMax("180") BigDecimal lng, @Min(0) @Max(100_000) Integer accuracyM) {}

    public record Batch(@NotEmpty @Size(max = 20) List<@Valid PunchInput> punches) {}

    private final OfflinePunches offline;

    public OfflineController(OfflinePunches offline) {
        this.offline = offline;
    }

    @PostMapping
    public List<OfflinePunches.Outcome> receive(@Valid @RequestBody Batch batch) {
        return offline.receive(batch.punches().stream()
                .map(p -> new OfflinePunches.Punch(p.clientId(), p.type(), p.at(), p.lat(), p.lng(), p.accuracyM())).toList());
    }
}
