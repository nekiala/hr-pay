package cd.hrpay.mission;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MissionRepository extends JpaRepository<Mission, Long> {

    List<Mission> findByUserIdOrderByWorkDateDescStartTimeDesc(Long userId);

    List<Mission> findByUserIdAndWorkDate(Long userId, LocalDate workDate);

    @Query("select m from Mission m where m.status = cd.hrpay.mission.Mission.Status.PENDING order by m.workDate, m.startTime")
    List<Mission> findPending();

    @Query("""
            select m from Mission m where m.status = cd.hrpay.mission.Mission.Status.PENDING
            and m.userId in (select u.id from AppUser u where u.managerId = :managerId)
            order by m.workDate, m.startTime""")
    List<Mission> findPendingForTeam(Long managerId);
}
