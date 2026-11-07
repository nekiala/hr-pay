package cd.hrpay.user;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    Optional<AppUser> findByRegistrationNoIgnoreCase(String registrationNo);

    Optional<AppUser> findByPhone(String phone);

    java.util.List<AppUser> findByManagerId(Long managerId);

    @Query("""
            select u from AppUser u
            where :q is null
               or lower(u.email) like lower(concat('%', cast(:q as string), '%'))
               or lower(u.firstName) like lower(concat('%', cast(:q as string), '%'))
               or lower(u.lastName) like lower(concat('%', cast(:q as string), '%'))
               or lower(u.registrationNo) like lower(concat('%', cast(:q as string), '%'))
            """)
    Page<AppUser> search(String q, Pageable pageable);
}
