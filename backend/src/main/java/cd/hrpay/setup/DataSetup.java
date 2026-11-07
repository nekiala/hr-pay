package cd.hrpay.setup;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.AppProperties;
import cd.hrpay.role.Role;
import cd.hrpay.role.RoleRepository;
import cd.hrpay.schedule.UserSchedule;
import cd.hrpay.schedule.UserScheduleRepository;
import cd.hrpay.schedule.WorkSchedule;
import cd.hrpay.schedule.WorkScheduleRepository;
import cd.hrpay.site.Site;
import cd.hrpay.site.SiteIpRange;
import cd.hrpay.site.SiteRepository;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.PasswordPolicy;
import cd.hrpay.user.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first administrator from configuration when no user exists yet, and optionally loads
 * demo data (a Kinshasa site and one user per role) for development and demos.
 */
@Component
public class DataSetup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSetup.class);

    record SampleUser(String email, String registrationNo, String phone, String firstName, String lastName,
                      String role) {}

    static final List<SampleUser> SAMPLE_USERS = List.of(
            new SampleUser("admin@demo.hrpay.cd", "ADM-0001", "+243810000001", "Amani", "Kabongo", "ADMIN"),
            new SampleUser("hr@demo.hrpay.cd", "HR-0001", "+243810000002", "Grace", "Mbuyi", "HR"),
            new SampleUser("finance@demo.hrpay.cd", "FIN-0001", "+243810000003", "Patrick", "Ilunga", "FINANCE"),
            new SampleUser("manager@demo.hrpay.cd", "MGR-0001", "+243810000004", "Sarah", "Tshibangu", "MANAGER"),
            new SampleUser("employee1@demo.hrpay.cd", "EMP-0001", "+243810000005", "Jean", "Mukendi", "EMPLOYEE"),
            new SampleUser("employee2@demo.hrpay.cd", "EMP-0002", "+243810000006", "Esther", "Kalala", "EMPLOYEE"),
            new SampleUser("employee3@demo.hrpay.cd", "EMP-0003", "+243810000007", "David", "Lukusa", "EMPLOYEE"));

    private final AppProperties properties;
    private final UserRepository users;
    private final RoleRepository roles;
    private final SiteRepository sites;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final WorkScheduleRepository schedules;
    private final UserScheduleRepository assignments;

    public DataSetup(AppProperties properties, UserRepository users, RoleRepository roles, SiteRepository sites,
                     PasswordEncoder passwordEncoder, AuditService audit, WorkScheduleRepository schedules,
                     UserScheduleRepository assignments) {
        this.schedules = schedules;
        this.assignments = assignments;
        this.properties = properties;
        this.users = users;
        this.roles = roles;
        this.sites = sites;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (properties.sampleData() != null && properties.sampleData().enabled()) {
            loadSampleData(properties.sampleData().password());
        }
        AppProperties.Bootstrap bootstrap = properties.bootstrap();
        if (users.count() == 0 && bootstrap != null && hasText(bootstrap.adminEmail())
                && hasText(bootstrap.adminPassword())) {
            AppUser admin = new AppUser(bootstrap.adminEmail().trim().toLowerCase(), "ADM-0001", "+243000000000",
                    "Platform", "Administrator");
            admin.setPasswordHash(passwordEncoder.encode(PasswordPolicy.check(bootstrap.adminPassword())));
            admin.setMustChangePassword(true);
            admin.setRoles(Set.of(roles.findByCode("ADMIN").orElseThrow()));
            users.save(admin);
            audit.recordAs("system", "user.bootstrapped", "user", admin.getId(), Map.of("email", admin.getEmail()));
            log.info("Created first administrator {}. Update the phone number and change the password.",
                    admin.getEmail());
        }
    }

    private void loadSampleData(String password) {
        if (users.findByEmailIgnoreCase(SAMPLE_USERS.getFirst().email()).isEmpty()) {
            loadSampleUsers(password);
        }
        assignSampleSchedules();
    }

    /** Gives every sample user the standard schedule from 1 January 2026, if they have none. */
    private void assignSampleSchedules() {
        WorkSchedule standard = schedules.findByCode("STANDARD").orElse(null);
        if (standard == null) {
            return;
        }
        for (SampleUser s : SAMPLE_USERS) {
            users.findByEmailIgnoreCase(s.email()).ifPresent(user -> {
                if (assignments.findByUserIdOrderByEffectiveFromDesc(user.getId()).isEmpty()) {
                    assignments.save(new UserSchedule(user.getId(), standard, LocalDate.of(2026, 1, 1)));
                }
            });
        }
    }

    private void loadSampleUsers(String password) {
        Site site = new Site("KIN-HQ");
        site.setName("Kinshasa head office (sample)");
        site.setTimeZone("Africa/Kinshasa");
        site.setGeoFence(new BigDecimal("-4.303700"), new BigDecimal("15.310800"), 150);
        site.setGpsRequired(true);
        site.setMaxAccuracyM(100);
        site.replaceIpRanges(List.of(new SiteIpRange("203.0.113.0/24", "Sample office network"),
                new SiteIpRange("127.0.0.1/32", "Local development")));
        sites.save(site);

        String hash = passwordEncoder.encode(PasswordPolicy.check(password));
        for (SampleUser s : SAMPLE_USERS) {
            AppUser user = new AppUser(s.email(), s.registrationNo(), s.phone(), s.firstName(), s.lastName());
            user.setPasswordHash(hash);
            user.setSite(site);
            Role role = roles.findByCode(s.role()).orElseThrow();
            user.setRoles(Set.of(role));
            users.save(user);
        }
        audit.recordAs("system", "sample_data.loaded", "site", site.getId(), Map.of("users", SAMPLE_USERS.size()));
        log.warn("Loaded sample data: {} users on site {}. Do not use in production.", SAMPLE_USERS.size(),
                site.getCode());
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
