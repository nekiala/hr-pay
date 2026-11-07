package cd.hrpay.schedule;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserScheduleRepository extends JpaRepository<UserSchedule, Long> {

    Optional<UserSchedule> findFirstByUserIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(Long userId,
                                                                                                 LocalDate date);

    Optional<UserSchedule> findByUserIdAndEffectiveFrom(Long userId, LocalDate effectiveFrom);

    List<UserSchedule> findByUserIdOrderByEffectiveFromDesc(Long userId);
}
