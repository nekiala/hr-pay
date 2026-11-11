package cd.hrpay.mission;

import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.tenancy.Tenants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@cd.hrpay.platform.RequiresModule("ATTENDANCE")
public class TravelMissionController {

    public record DecisionInput(boolean approve, @Size(max = 300) String note) {}

    public record ReasonInput(@Size(max = 300) String reason) {}

    public record AmountInput(@NotNull BigDecimal amount, @Size(max = 300) String note) {}

    public record ReportInput(@NotNull @Size(max = 10000) String report) {}

    public record ExpenseInput(@NotNull LocalDate spentOn, @NotNull String category, @NotNull @Size(max = 200) String description,
                               @NotNull BigDecimal amount, @Size(max = 200) String fileName, @Size(max = 100) String contentType,
                               String base64) {}

    public record ScaleInput(Long categoryId, @NotNull String zone, @NotNull BigDecimal amount, BigDecimal lodgingAmount,
                             @NotNull String currency) {}

    private final TravelMissions missions;
    private final OrderPdf pdf;
    private final Tenants tenants;
    private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();

    public TravelMissionController(TravelMissions missions, OrderPdf pdf, Tenants tenants) {
        this.missions = missions;
        this.pdf = pdf;
        this.tenants = tenants;
    }

    @GetMapping("/api/travel-missions/mine")
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public List<TravelMissions.View> mine() {
        return missions.mine();
    }

    @GetMapping("/api/travel-missions/to-decide")
    @PreAuthorize("hasAnyAuthority('mission.approve.all', 'mission.approve.team')")
    @Transactional(readOnly = true)
    public List<TravelMissions.View> toDecide() {
        return missions.toDecide();
    }

    @GetMapping("/api/travel-missions")
    @PreAuthorize("hasAnyAuthority('mission.manage', 'mission.approve.all', 'mission.approve.team')")
    @Transactional(readOnly = true)
    public List<TravelMissions.View> all(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        if (to.isBefore(from) || from.plusYears(2).isBefore(to)) {
            throw new BusinessRuleException("Choose a period of two years at most.");
        }
        return missions.all(from, to);
    }

    @GetMapping("/api/travel-missions/{id}")
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public TravelMissions.Detail detail(@PathVariable long id) {
        return missions.detail(id);
    }

    @PostMapping("/api/travel-missions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('mission.request', 'mission.manage')")
    public List<TravelMissions.View> create(@RequestBody TravelMissions.Input in) {
        return missions.create(in);
    }

    @PostMapping("/api/travel-missions/{id}/decision")
    @PreAuthorize("hasAnyAuthority('mission.approve.all', 'mission.approve.team')")
    public TravelMissions.View decide(@PathVariable long id, @Valid @RequestBody DecisionInput b) {
        return missions.decide(id, b.approve(), b.note());
    }

    @PostMapping("/api/travel-missions/{id}/cancel")
    @PreAuthorize("isAuthenticated()")
    public TravelMissions.View cancel(@PathVariable long id, @Valid @RequestBody(required = false) ReasonInput b) {
        return missions.cancel(id, b == null ? null : b.reason());
    }

    @PostMapping("/api/travel-missions/{id}/amend")
    @PreAuthorize("hasAuthority('mission.manage')")
    public TravelMissions.View amend(@PathVariable long id, @RequestBody TravelMissions.Amendment a) {
        return missions.amend(id, a);
    }

    @GetMapping("/api/travel-missions/{id}/order.pdf")
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> order(@PathVariable long id) {
        byte[] bytes = pdf.render(id);
        String number = missions.detail(id).mission().number();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", ContentDisposition.inline()
                        .filename("ordre-de-mission-" + number + ".pdf", StandardCharsets.UTF_8).build().toString())
                .body(bytes);
    }

    @PostMapping("/api/travel-missions/{id}/advance")
    @PreAuthorize("hasAuthority('mission.manage')")
    public TravelMissions.Detail advance(@PathVariable long id, @Valid @RequestBody AmountInput b) {
        return missions.advance(id, b.amount(), b.note());
    }

    @PostMapping("/api/travel-missions/{id}/refund")
    @PreAuthorize("hasAuthority('mission.manage')")
    public TravelMissions.Detail refund(@PathVariable long id, @Valid @RequestBody AmountInput b) {
        return missions.refund(id, b.amount(), b.note());
    }

    @PostMapping("/api/travel-missions/{id}/payments/{paymentId}/cancel")
    @PreAuthorize("hasAuthority('mission.manage')")
    public TravelMissions.Detail cancelPayment(@PathVariable long id, @PathVariable long paymentId) {
        return missions.cancelPayment(id, paymentId);
    }

    @PostMapping("/api/travel-missions/{id}/expenses")
    @PreAuthorize("isAuthenticated()")
    public TravelMissions.Detail addExpense(@PathVariable long id, @Valid @RequestBody ExpenseInput b) {
        TravelMissions.Receipt receipt = null;
        if (b.base64() != null && !b.base64().isBlank()) {
            byte[] data;
            try {
                data = java.util.Base64.getDecoder().decode(b.base64().replaceFirst("^data:[^,]*,", ""));
            } catch (IllegalArgumentException e) {
                throw new BusinessRuleException("The receipt could not be read.");
            }
            receipt = new TravelMissions.Receipt(b.fileName() == null ? "receipt" : b.fileName(), b.contentType(), data);
        }
        return missions.addExpense(id, b.spentOn(), b.category(), b.description(), b.amount(), receipt);
    }

    @DeleteMapping("/api/travel-missions/{id}/expenses/{expenseId}")
    @PreAuthorize("isAuthenticated()")
    public TravelMissions.Detail deleteExpense(@PathVariable long id, @PathVariable long expenseId) {
        return missions.deleteExpense(id, expenseId);
    }

    @PostMapping("/api/travel-missions/{id}/expenses/{expenseId}/decision")
    @PreAuthorize("hasAuthority('mission.manage')")
    public TravelMissions.Detail decideExpense(@PathVariable long id, @PathVariable long expenseId,
                                               @Valid @RequestBody DecisionInput b) {
        return missions.decideExpense(id, expenseId, b.approve(), b.note());
    }

    @GetMapping("/api/travel-missions/{id}/expenses/{expenseId}/receipt")
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> receipt(@PathVariable long id, @PathVariable long expenseId) {
        TravelMissions.Receipt r = missions.receipt(id, expenseId);
        return ResponseEntity.ok()
                .header("Content-Type", r.type())
                .header("Content-Disposition", ContentDisposition.inline()
                        .filename(r.name() == null ? "receipt" : r.name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(r.data());
    }

    @PostMapping("/api/travel-missions/{id}/report")
    @PreAuthorize("isAuthenticated()")
    public TravelMissions.Detail report(@PathVariable long id, @Valid @RequestBody ReportInput b) {
        return missions.fileReport(id, b.report());
    }

    @PostMapping("/api/travel-missions/{id}/close")
    @PreAuthorize("hasAuthority('mission.manage')")
    public TravelMissions.Detail close(@PathVariable long id) {
        return missions.close(id);
    }

    @GetMapping("/api/travel-missions/settings")
    @PreAuthorize("isAuthenticated()")
    public TravelMissions.Settings settings() {
        return missions.settings();
    }

    @PutMapping("/api/travel-missions/settings")
    @PreAuthorize("hasAuthority('mission.manage')")
    public TravelMissions.Settings saveSettings(@RequestBody TravelMissions.Settings s) {
        return missions.saveSettings(s);
    }

    @GetMapping("/api/travel-missions/per-diem")
    @PreAuthorize("hasAnyAuthority('mission.manage', 'mission.approve.all')")
    public List<TravelMissions.Scale> scales() {
        return missions.scales();
    }

    @PostMapping("/api/travel-missions/per-diem")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('mission.manage')")
    public TravelMissions.Scale addScale(@Valid @RequestBody ScaleInput b) {
        return missions.saveScale(null, b.categoryId(), b.zone(), b.amount(), b.lodgingAmount(), b.currency());
    }

    @PutMapping("/api/travel-missions/per-diem/{id}")
    @PreAuthorize("hasAuthority('mission.manage')")
    public TravelMissions.Scale saveScale(@PathVariable long id, @Valid @RequestBody ScaleInput b) {
        return missions.saveScale(id, b.categoryId(), b.zone(), b.amount(), b.lodgingAmount(), b.currency());
    }

    @DeleteMapping("/api/travel-missions/per-diem/{id}")
    @PreAuthorize("hasAuthority('mission.manage')")
    public void deleteScale(@PathVariable long id) {
        missions.deleteScale(id);
    }

    /** The page the QR code of an ordre de mission opens: anyone (a border officer, a host) can check it. */
    @GetMapping("/api/public/mission-check/{token}")
    public TravelMissions.Check check(@PathVariable String token, HttpServletRequest request) {
        limit(request.getRemoteAddr());
        UUID id;
        try {
            id = UUID.fromString(token);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This ordre de mission is not known.");
        }
        Long company = tenants.callAsPlatform(() -> missions.companyOf(id));
        if (company == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This ordre de mission is not known.");
        }
        return tenants.callAs(company, () -> missions.check(id));
    }

    private void limit(String ip) {
        long now = System.currentTimeMillis();
        Deque<Long> times = recent.computeIfAbsent(ip == null ? "?" : ip, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > 60_000L) {
                times.pollFirst();
            }
            if (times.size() >= 30) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many checks; wait a minute.");
            }
            times.addLast(now);
        }
        if (recent.size() > 10_000) {
            recent.entrySet().removeIf(e -> e.getValue().isEmpty());
        }
    }
}
