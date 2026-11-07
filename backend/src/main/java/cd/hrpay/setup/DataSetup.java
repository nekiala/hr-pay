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

    @org.springframework.beans.factory.annotation.Autowired
    private cd.hrpay.schedule.WorkCalendar calendar;

    /** Sample check-ins for last month, so a payroll can be tried at once. Off in tests. */
    @org.springframework.beans.factory.annotation.Value("${app.sample-data.attendance:true}")
    private boolean sampleAttendance;
    private final UserRepository users;
    private final RoleRepository roles;
    private final SiteRepository sites;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final WorkScheduleRepository schedules;
    private final UserScheduleRepository assignments;
    private final cd.hrpay.salary.SalaryService salaries;
    private final cd.hrpay.leave.LeaveService leave;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private final cd.hrpay.payroll.EmployeeFile files;

    @org.springframework.beans.factory.annotation.Autowired
    private cd.hrpay.org.OrgStructure org;

    public DataSetup(AppProperties properties, UserRepository users, RoleRepository roles, SiteRepository sites,
                     PasswordEncoder passwordEncoder, AuditService audit, WorkScheduleRepository schedules,
                     UserScheduleRepository assignments, cd.hrpay.salary.SalaryService salaries,
                     cd.hrpay.leave.LeaveService leave, org.springframework.jdbc.core.simple.JdbcClient jdbc,
                     cd.hrpay.payroll.EmployeeFile files) {
        this.files = files;
        this.salaries = salaries;
        this.leave = leave;
        this.jdbc = jdbc;
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
        if (properties.sampleData() != null && properties.sampleData().enabled()) {
            addSampleStructure();
        }
        int placed = org.placeUnassigned();
        if (placed > 0) {
            log.info("Placed {} user(s) without a unit at the top of the structure.", placed);
        }
    }

    /**
     * A small sample structure, only while the structure is just its top unit: three Directions with their heads,
     * a vacant Service logistique holding the employees (so they report to the Directeur des opérations), a
     * Service paie and a Cellule informatique.
     */
    private void addSampleStructure() {
        if (jdbc.sql("select count(*) from org_unit").query(Integer.class).single() > 1) {
            return;
        }
        long top = jdbc.sql("select id from org_unit where parent_id is null").query(Long.class).single();
        java.util.function.BiFunction<String, String[], Long> unit = (level, a) -> jdbc.sql("""
                insert into org_unit (code, name, level_id, parent_id, valid_from, created_by)
                values (?, ?, (select id from org_level where code = ?), ?, date '2024-01-01', 'sample data') returning id""")
                .params(a[0], a[1], level, Long.valueOf(a[2])).query(Long.class).single();
        long drh = unit.apply("DIRECTION", new String[] {"DRH", "Direction des ressources humaines", String.valueOf(top)});
        long daf = unit.apply("DIRECTION", new String[] {"DAF", "Direction administrative et financière", String.valueOf(top)});
        long dop = unit.apply("DIRECTION", new String[] {"DOP", "Direction des opérations", String.valueOf(top)});
        long slog = unit.apply("SERVICE", new String[] {"SLOG", "Service logistique", String.valueOf(dop)});
        unit.apply("SERVICE", new String[] {"SPAIE", "Service paie", String.valueOf(drh)});
        long cinf = unit.apply("CELLULE", new String[] {"CINF", "Cellule informatique", String.valueOf(top)});
        Map<String, Long> placeOf = Map.of("hr@demo.hrpay.cd", drh, "finance@demo.hrpay.cd", daf,
                "manager@demo.hrpay.cd", dop, "employee1@demo.hrpay.cd", slog, "employee2@demo.hrpay.cd", slog,
                "employee3@demo.hrpay.cd", slog, "admin@demo.hrpay.cd", cinf);
        placeOf.forEach((email, unitId) -> users.findByEmailIgnoreCase(email).ifPresent(u -> {
            int n = jdbc.sql("update org_assignment set unit_id = ? where user_id = ? and valid_to is null")
                    .params(unitId, u.getId()).update();
            if (n == 0) {
                jdbc.sql("""
                        insert into org_assignment (user_id, unit_id, valid_from, reason, created_by)
                        values (?, ?, date '2024-01-15', 'HIRE', 'sample data')""").params(u.getId(), unitId).update();
            }
        }));
        Map<String, Long> heads = Map.of("hr@demo.hrpay.cd", drh, "finance@demo.hrpay.cd", daf, "manager@demo.hrpay.cd", dop);
        heads.forEach((email, unitId) -> users.findByEmailIgnoreCase(email).ifPresent(u -> jdbc.sql("""
                insert into org_head (unit_id, user_id, role, valid_from, created_by)
                values (?, ?, 'HEAD', date '2024-01-15', 'sample data')""").params(unitId, u.getId()).update()));
        log.info("Sample organizational structure created.");
    }

    private void loadSampleData(String password) {
        if (users.findByEmailIgnoreCase(SAMPLE_USERS.getFirst().email()).isEmpty()) {
            loadSampleUsers(password);
        }
        assignSampleSchedules();
        addSampleSalaries();
        assignSampleManager();
        assignSampleEmployment();
        addSampleContracts();
        allowLocalIpv6();
        if (sampleAttendance) {
            addSampleAttendance();
        }
    }

    /**
     * Last month's attendance for the sample staff, if they have none: on time every working day, except one late
     * arrival for employee2 and one absence for employee3. Lets HR try a payroll right after start-up.
     */
    private void addSampleAttendance() {
        java.time.ZoneId zone = java.time.ZoneId.of("Africa/Kinshasa");
        java.time.YearMonth month = java.time.YearMonth.now(zone).minusMonths(1);
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        for (SampleUser s : SAMPLE_USERS) {
            if (s.role().equals("ADMIN")) {
                continue;
            }
            AppUser user = users.findByEmailIgnoreCase(s.email()).orElse(null);
            if (user == null || jdbc.sql("select count(*) from clock_event where user_id = ? and work_date between ? and ?")
                    .params(user.getId(), from, to).query(Integer.class).single() > 0) {
                continue;
            }
            int workday = 0;
            for (var day : calendar.plan(user, from, to)) {
                if (!day.expectedAtWork() || day.start() == null || day.end() == null) {
                    continue;
                }
                workday++;
                if (s.email().startsWith("employee3") && workday == 5) {
                    continue;   // one absence
                }
                int late = s.email().startsWith("employee2") && workday == 3 ? 25 : -3;
                var in = day.date().atTime(day.start()).plusMinutes(late).atZone(zone).toOffsetDateTime();
                var out = day.date().atTime(day.end()).plusMinutes(4).atZone(zone).toOffsetDateTime();
                for (var e : List.of(Map.entry("IN", in), Map.entry("OUT", out))) {
                    jdbc.sql("""
                            insert into clock_event (user_id, type, occurred_at, work_date, site_id, source, note, created_by)
                            values (?, ?, ?, ?, ?, 'SELF', 'Sample data', 'sample-data')""")
                            .params(user.getId(), e.getKey(), e.getValue(), day.date(),
                                    user.getSite() == null ? null : user.getSite().getId())
                            .update();
                }
            }
        }
    }

    /** Browsers on the same machine may reach the API over ::1; the sample site allows it too. */
    private void allowLocalIpv6() {
        sites.findByCodeIgnoreCase("KIN-HQ").ifPresent(site -> {
            if (site.getIpRanges().stream().noneMatch(r -> r.getCidr().equals("0:0:0:0:0:0:0:1/128"))) {
                List<SiteIpRange> ranges = new java.util.ArrayList<>(site.getIpRanges().stream()
                        .map(r -> new SiteIpRange(r.getCidr(), r.getLabel())).toList());
                ranges.add(new SiteIpRange("0:0:0:0:0:0:0:1/128", "Local development (IPv6)"));
                site.replaceIpRanges(ranges);
            }
        });
    }

    /** Contracts, personal details and a family for the sample staff, so payroll can be tried. */
    private void addSampleContracts() {
        Map<String, String> gradeByRole = Map.of("EMPLOYEE", "G1B", "HR", "G2B", "FINANCE", "G2B", "MANAGER", "G3B");
        Map<String, String> occupationByRole = Map.of("EMPLOYEE", "ENGINEER", "HR", "HR_OFFICER", "FINANCE", "ACCOUNTANT",
                "MANAGER", "MANAGER");
        for (SampleUser s : SAMPLE_USERS) {
            if (s.role().equals("ADMIN")) {
                continue;
            }
            users.findByEmailIgnoreCase(s.email()).filter(u -> files.contracts(u.getId()).isEmpty()).ifPresent(u -> {
                long grade = jdbc.sql("select id from grade where code = ?").param(gradeByRole.get(s.role())).query(Long.class).single();
                Long occupation = jdbc.sql("select id from occupation where code = ?").param(occupationByRole.get(s.role()))
                        .query(Long.class).optional().orElse(null);
                files.startContract(u.getId(), "PERMANENT", LocalDate.of(2024, 1, 15), null, grade, occupation, false, null,
                        "Sample contract");
                boolean married = s.email().startsWith("employee1") || s.role().equals("MANAGER");
                files.saveProfile(u.getId(), new cd.hrpay.payroll.EmployeeFile.Profile(LocalDate.of(1988, 5, 12), null,
                        "Congolese", married ? "MARRIED" : "SINGLE", "CNSS-" + s.registrationNo(), "NIF-" + s.registrationNo(),
                        "BANK", "Rawbank (sample)", "0001-" + s.registrationNo(), null, null));
                if (married) {
                    files.addDependant(u.getId(), "Spouse of " + s.firstName(), "SPOUSE", LocalDate.of(1990, 3, 2), true, null);
                    files.addDependant(u.getId(), "First child", "CHILD", LocalDate.of(2015, 9, 1), true, null);
                    files.addDependant(u.getId(), "Second child", "CHILD", LocalDate.of(2019, 2, 20), true, null);
                }
            });
        }
    }

    /** Sample grade categories and hire dates, then this year's annual leave. */
    private void assignSampleEmployment() {
        for (SampleUser s : SAMPLE_USERS) {
            if (s.role().equals("ADMIN")) {
                continue;
            }
            String category = s.role().equals("MANAGER") ? "CAT3" : s.role().equals("EMPLOYEE") ? "CAT1" : "CAT2";
            Long categoryId = jdbc.sql("select id from grade_category where code = ?").param(category)
                    .query(Long.class).optional().orElse(null);
            users.findByEmailIgnoreCase(s.email()).filter(u -> u.getGradeCategoryId() == null).ifPresent(u -> {
                u.setGradeCategoryId(categoryId);
                u.setHireDate(LocalDate.of(2024, 1, 15));
            });
        }
        users.flush();
        leave.grantAnnual(LocalDate.now().getYear());
    }

    /** Sample employees report to the sample manager, so team approvals can be tried. */
    private void assignSampleManager() {
        users.findByEmailIgnoreCase("manager@demo.hrpay.cd").ifPresent(manager -> SAMPLE_USERS.stream()
                .filter(s -> s.role().equals("EMPLOYEE"))
                .forEach(s -> users.findByEmailIgnoreCase(s.email())
                        .filter(u -> u.getManagerId() == null)
                        .ifPresent(u -> u.setManagerId(manager.getId()))));
    }

    /** Fake gross salaries from 1 January 2026 so earnings to date can be tried; HR replaces them. */
    private void addSampleSalaries() {
        Map<String, String> gross = Map.of(
                "hr@demo.hrpay.cd", "2800", "finance@demo.hrpay.cd", "3000", "manager@demo.hrpay.cd", "3500",
                "employee1@demo.hrpay.cd", "4500", "employee2@demo.hrpay.cd", "1200", "employee3@demo.hrpay.cd", "950");
        gross.forEach((email, amount) -> users.findByEmailIgnoreCase(email).ifPresent(user -> {
            if (salaries.history(user.getId()).isEmpty()) {
                salaries.add(user.getId(), LocalDate.of(2026, 1, 1), new BigDecimal(amount), "USD", "Sample data",
                        "system");
            }
        }));
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
