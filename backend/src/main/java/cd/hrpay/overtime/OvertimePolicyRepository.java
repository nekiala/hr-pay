package cd.hrpay.overtime;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OvertimePolicyRepository extends JpaRepository<OvertimePolicy, Long> {

    Optional<OvertimePolicy> findFirstByCountryCodeAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
            String countryCode, LocalDate date);

    Optional<OvertimePolicy> findByCountryCodeAndEffectiveFrom(String countryCode, LocalDate effectiveFrom);

    List<OvertimePolicy> findByCountryCodeOrderByEffectiveFromDesc(String countryCode);
}
