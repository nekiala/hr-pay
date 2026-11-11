package cd.hrpay.career;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
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

/** Promotions and transfers on the employee file. */
@RestController
@RequestMapping("/api/users/{userId}/career")
public class CareerController {

    private final CareerMoves moves;

    public CareerController(CareerMoves moves) {
        this.moves = moves;
    }

    public record MoveInput(@NotBlank String kind, @NotNull LocalDate effectiveOn, Long gradeId, Long occupationId,
                            Long unitId, Long siteId, @Min(0) @Max(6) Integer trialMonths, Boolean keepNet,
                            @Size(max = 300) String note) {}

    @GetMapping
    @PreAuthorize("hasAuthority('salary.read')")
    public List<CareerMoves.Move> list(@PathVariable long userId) {
        return moves.of(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('salary.write') and hasAuthority('org.manage')")
    public CareerMoves.Result move(@PathVariable long userId, @Valid @RequestBody MoveInput b) {
        return moves.move(userId, new CareerMoves.Input(b.kind(), b.effectiveOn(), b.gradeId(), b.occupationId(), b.unitId(),
                b.siteId(), b.trialMonths(), Boolean.TRUE.equals(b.keepNet()), b.note()));
    }
}
