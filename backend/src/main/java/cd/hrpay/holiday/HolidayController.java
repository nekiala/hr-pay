package cd.hrpay.holiday;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/holidays")
public class HolidayController {

    public record DefinitionInput(@NotBlank @Size(max = 150) String name, @Min(1) @Max(12) int usualMonth,
                                  @Min(1) @Max(31) int usualDay, boolean paid, boolean active) {}

    public record DefinitionView(Long id, String countryCode, String name, int usualMonth, int usualDay, boolean paid,
                                 boolean active) {
        static DefinitionView of(HolidayDefinition d) {
            return new DefinitionView(d.getId(), d.getCountryCode(), d.getName(), d.getUsualMonth(), d.getUsualDay(),
                    d.isPaid(), d.isActive());
        }
    }

    public record OccurrenceView(Long id, Long definitionId, String name, int year, LocalDate officialDate,
                                 LocalDate observedDate, boolean moved, boolean paid, String announcementRef,
                                 String setBy, OffsetDateTime setAt) {
        static OccurrenceView of(HolidayOccurrence o) {
            return new OccurrenceView(o.getId(), o.getDefinition().getId(), o.getDefinition().getName(), o.getYear(),
                    o.getOfficialDate(), o.getObservedDate(), !o.getObservedDate().equals(o.getOfficialDate()),
                    o.getDefinition().isPaid(), o.getAnnouncementRef(), o.getSetBy(), o.getSetAt());
        }
    }

    public record MoveInput(@NotNull LocalDate observedDate, @Size(max = 255) String announcementRef) {}

    private final HolidayDefinitionRepository definitions;
    private final HolidayService service;
    private final AuditService audit;

    public HolidayController(HolidayDefinitionRepository definitions, HolidayService service, AuditService audit) {
        this.definitions = definitions;
        this.service = service;
        this.audit = audit;
    }

    /** Any signed-in user can see the holiday calendar. */
    @GetMapping
    public List<OccurrenceView> year(@RequestParam(defaultValue = "CD") String country,
                                     @RequestParam(required = false) Integer year) {
        int y = year == null ? LocalDate.now().getYear() : year;
        return service.year(country, y).stream().map(OccurrenceView::of).toList();
    }

    @PutMapping("/occurrences/{id}")
    @PreAuthorize("hasAuthority('holiday.manage')")
    public OccurrenceView move(@PathVariable Long id, @Valid @RequestBody MoveInput body) {
        return OccurrenceView.of(service.move(id, body.observedDate(), body.announcementRef()));
    }

    @GetMapping("/definitions")
    public List<DefinitionView> definitions(@RequestParam(defaultValue = "CD") String country) {
        return definitions.findByCountryCodeOrderByUsualMonthAscUsualDayAsc(country).stream()
                .map(DefinitionView::of).toList();
    }

    @PostMapping("/definitions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('holiday.manage')")
    @Transactional
    public DefinitionView create(@RequestParam(defaultValue = "CD") String country,
                                 @Valid @RequestBody DefinitionInput body) {
        HolidayDefinition d = new HolidayDefinition(country);
        apply(d, body);
        try {
            definitions.saveAndFlush(d);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessRuleException("A holiday already exists on that date.");
        }
        audit.record("holiday.created", "holiday_definition", d.getId(), Map.of("name", d.getName()));
        return DefinitionView.of(d);
    }

    @PutMapping("/definitions/{id}")
    @PreAuthorize("hasAuthority('holiday.manage')")
    @Transactional
    public DefinitionView update(@PathVariable Long id, @Valid @RequestBody DefinitionInput body) {
        HolidayDefinition d = definitions.findById(id).orElseThrow(() -> new NotFoundException("Holiday not found."));
        apply(d, body);
        audit.record("holiday.updated", "holiday_definition", d.getId(),
                Map.of("name", d.getName(), "month", d.getUsualMonth(), "day", d.getUsualDay(), "active", d.isActive()));
        return DefinitionView.of(d);
    }

    private static void apply(HolidayDefinition d, DefinitionInput body) {
        try {
            MonthDay.of(body.usualMonth(), body.usualDay());
        } catch (DateTimeException e) {
            throw new BusinessRuleException("That day does not exist in that month.");
        }
        d.setName(body.name().trim());
        d.setUsualMonth(body.usualMonth());
        d.setUsualDay(body.usualDay());
        d.setPaid(body.paid());
        d.setActive(body.active());
    }
}
