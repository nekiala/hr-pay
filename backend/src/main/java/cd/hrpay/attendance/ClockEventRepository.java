package cd.hrpay.attendance;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ClockEventRepository extends JpaRepository<ClockEvent, Long> {

    @Query("select e from ClockEvent e where e.userId = :userId and e.voidedAt is null order by e.occurredAt desc limit 1")
    Optional<ClockEvent> findLast(Long userId);

    @Query("""
            select e from ClockEvent e
            where e.userId = :userId and e.voidedAt is null and e.workDate between :from and :to
            order by e.occurredAt""")
    List<ClockEvent> findForPeriod(Long userId, LocalDate from, LocalDate to);

    @Query("""
            select e from ClockEvent e
            where e.userId = :userId and e.voidedAt is null and e.occurredAt between :from and :to
            order by e.occurredAt""")
    List<ClockEvent> findBetween(Long userId, OffsetDateTime from, OffsetDateTime to);
}
