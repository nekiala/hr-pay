package cd.hrpay.holiday;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.security.CurrentUser;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HolidayService {

    private final HolidayDefinitionRepository definitions;
    private final HolidayOccurrenceRepository occurrences;
    private final AuditService audit;

    /** Published after a holiday's observed date changes, so payroll can settle closed months. */
    public record Moved(String holiday, LocalDate from, LocalDate to) {}

    private org.springframework.context.ApplicationEventPublisher events;

    @org.springframework.beans.factory.annotation.Autowired
    void setEvents(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }

    public HolidayService(HolidayDefinitionRepository definitions, HolidayOccurrenceRepository occurrences,
                          AuditService audit) {
        this.definitions = definitions;
        this.occurrences = occurrences;
        this.audit = audit;
    }

    /** Creates any missing occurrences for the year (observed = official date), then returns the year. */
    @Transactional
    public List<HolidayOccurrence> year(String country, int year) {
        boolean missing = definitions.findByCountryCodeOrderByUsualMonthAscUsualDayAsc(country).stream()
                .anyMatch(d -> d.isActive() && occurrences.findByDefinitionIdAndYear(d.getId(), year).isEmpty());
        if (missing) {
            // In a transaction of its own: the caller may only be reading, and two first views may race.
            create.executeWithoutResult(s -> jdbc.sql("""
                    insert into holiday_occurrence (definition_id, year, official_date, observed_date)
                    select id, :year, make_date(:year, usual_month, usual_day), make_date(:year, usual_month, usual_day)
                    from holiday_definition
                    where country_code = :country and active
                      and (usual_month <> 2 or usual_day <> 29 or (:year % 4 = 0 and (:year % 100 <> 0 or :year % 400 = 0)))
                    on conflict do nothing""").param("year", year).param("country", country).update());
        }
        return occurrences.findByCountryAndYear(country, year);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    private org.springframework.transaction.support.TransactionTemplate create;

    @org.springframework.beans.factory.annotation.Autowired
    void setTransactions(org.springframework.transaction.PlatformTransactionManager manager) {
        create = new org.springframework.transaction.support.TransactionTemplate(manager);
        create.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Observed holiday dates between two dates (inclusive), creating occurrences for the years involved. */
    @Transactional
    public Set<LocalDate> observedDates(String country, LocalDate from, LocalDate to) {
        for (int y = from.getYear(); y <= to.getYear(); y++) {
            year(country, y);
        }
        return occurrences.findObserved(country, from, to).stream()
                .map(HolidayOccurrence::getObservedDate).collect(Collectors.toSet());
    }

    @Transactional
    public HolidayOccurrence move(Long occurrenceId, LocalDate observed, String announcement) {
        HolidayOccurrence o = occurrences.findById(occurrenceId)
                .orElseThrow(() -> new NotFoundException("Holiday not found."));
        if (Math.abs(observed.getYear() - o.getYear()) > 1) {
            throw new BusinessRuleException("The observed date must be close to the official date.");
        }
        LocalDate before = o.getObservedDate();
        o.moveTo(observed, announcement, SecurityContextHolder.getContext().getAuthentication().getName());
        audit.record("holiday.moved", "holiday_occurrence", o.getId(), Map.of("holiday", o.getDefinition().getName(),
                "year", o.getYear(), "from", before.toString(), "to", observed.toString(),
                "announcement", announcement == null ? "" : announcement, "userId", String.valueOf(CurrentUser.id())));
        if (!before.equals(observed)) {
            events.publishEvent(new Moved(o.getDefinition().getName(), before, observed));
        }
        return o;
    }
}
