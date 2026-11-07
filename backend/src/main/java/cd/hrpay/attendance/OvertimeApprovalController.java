package cd.hrpay.attendance;

import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.overtime.OvertimeDecisions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@cd.hrpay.platform.RequiresModule("ATTENDANCE")
@RequestMapping("/api/overtime")
public class OvertimeApprovalController {

    public record DecisionInput(@NotNull Long userId, @NotNull LocalDate workDate, boolean approve, Integer minutes,
                                @Size(max = 300) String note) {}

    private final OvertimeApprovals approvals;
    private final Clock clock;

    public OvertimeApprovalController(OvertimeApprovals approvals, Clock clock) {
        this.approvals = approvals;
        this.clock = clock;
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAnyAuthority('overtime.approve.all', 'overtime.approve.team')")
    public List<OvertimeApprovals.PendingDay> pending(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Africa/Kinshasa")));
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) > 62) {
            throw new BusinessRuleException("Choose a period of at most 63 days.");
        }
        return approvals.pending(start, end);
    }

    @PostMapping("/decisions")
    @PreAuthorize("hasAnyAuthority('overtime.approve.all', 'overtime.approve.team')")
    public OvertimeDecisions.Decision decide(@Valid @RequestBody DecisionInput body) {
        return approvals.decide(body.userId(), body.workDate(), body.approve(), body.minutes(), body.note());
    }
}
