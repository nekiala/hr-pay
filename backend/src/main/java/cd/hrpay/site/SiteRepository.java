package cd.hrpay.site;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SiteRepository extends JpaRepository<Site, Long> {
    boolean existsByCodeIgnoreCase(String code);

    java.util.Optional<Site> findByCodeIgnoreCase(String code);

    List<Site> findAllByOrderByNameAsc();
}
