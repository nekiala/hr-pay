package cd.hrpay.role;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByCode(String code);

    boolean existsByCodeIgnoreCase(String code);

    List<Role> findAllByOrderByNameAsc();

    @Query(value = "select count(*) from user_role where role_id = :roleId", nativeQuery = true)
    long countUsers(Long roleId);
}
