package cd.hrpay.payroll;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/raises")
public class RaiseController {

    private final AnniversaryRaises raises;
    private final Clock clock;

    public RaiseController(AnniversaryRaises raises, Clock clock) {
        this.raises = raises;
        this.clock = clock;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('salary.read')")
    public List<AnniversaryRaises.Raise> list() {
        return raises.list(200);
    }

    /** Runs today's check now instead of waiting for the morning job. */
    @PostMapping("/apply")
    @PreAuthorize("hasAuthority('salary.write')")
    public AnniversaryRaises.Outcome apply() {
        return raises.applyDue(LocalDate.now(clock.withZone(ZoneId.of("Africa/Kinshasa"))), null);
    }

    @PostMapping("/notify")
    @PreAuthorize("hasAuthority('salary.write')")
    public Map<String, Integer> notifyHr() {
        return Map.of("listed", raises.sendSummary());
    }
}
