package cd.hrpay.schedule;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkScheduleRepository extends JpaRepository<WorkSchedule, Long> {
    boolean existsByCodeIgnoreCase(String code);

    Optional<WorkSchedule> findByCode(String code);

    List<WorkSchedule> findAllByOrderByNameAsc();
}
