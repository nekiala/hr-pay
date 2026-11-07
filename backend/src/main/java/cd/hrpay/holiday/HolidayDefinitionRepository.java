package cd.hrpay.holiday;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HolidayDefinitionRepository extends JpaRepository<HolidayDefinition, Long> {
    List<HolidayDefinition> findByCountryCodeOrderByUsualMonthAscUsualDayAsc(String countryCode);
}
