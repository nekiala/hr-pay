package cd.hrpay.holiday;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface HolidayOccurrenceRepository extends JpaRepository<HolidayOccurrence, Long> {

    @Query("""
            select o from HolidayOccurrence o
            where o.definition.countryCode = :country and o.year = :year
            order by o.observedDate""")
    List<HolidayOccurrence> findByCountryAndYear(String country, int year);

    @Query("""
            select o from HolidayOccurrence o
            where o.definition.countryCode = :country and o.observedDate between :from and :to
              and o.definition.active = true""")
    List<HolidayOccurrence> findObserved(String country, LocalDate from, LocalDate to);

    Optional<HolidayOccurrence> findByDefinitionIdAndYear(Long definitionId, int year);
}
