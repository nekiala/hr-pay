package cd.hrpay.platform;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.org.OrgStructure;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.tenancy.TenantContext;
import cd.hrpay.tenancy.Tenants;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The provider's console for the companies on this installation: create a company from a country pack with its
 * first administrator, change its plan, modules and status, and (on premise) install the licence. Only the
 * provider's own administrators get here; companies never see each other.
 */
@RestController
@RequestMapping("/api/platform")
@PreAuthorize("hasAuthority('platform.manage')")
public class PlatformController {

    public record PlanView(String code, String name, Integer maxEmployees) {}

    public record CompanyView(long id, String name, String countryCode, String planCode, String planName,
                              Integer maxEmployees, List<String> modules, String status, int employees,
                              OffsetDateTime createdAt, boolean provider, String suspendedReason,
                              String subscriptionStatus, String cycle, java.time.LocalDate trialEnds,
                              java.time.LocalDate nextInvoiceOn, int openInvoices, int overdueInvoices,
                              OffsetDateTime lastSignIn, boolean supportOpen) {}

    public record AdminInput(@NotBlank @Size(max = 100) String firstName, @NotBlank @Size(max = 100) String lastName,
                             @NotBlank @Email @Size(max = 320) String email, @NotBlank String phone) {}

    public record NewCompany(@NotBlank @Size(max = 150) String name, @NotBlank String countryCode,
                             @NotBlank String planCode, @NotEmpty Set<String> modules, @Valid @NotNull AdminInput admin) {}

    public record Created(CompanyView company, String adminEmail, String temporaryPassword) {}

    public record CompanyChange(@NotBlank String planCode, @NotEmpty Set<String> modules, @NotBlank String status) {}

    public record LicenceInput(@NotBlank String content) {}

    public record LicenceView(Entitlements.Mode mode, Licences.State state, Licences.Licence licence,
                              java.time.LocalDate evaluationEnds, java.time.LocalDate locksOn, long daysLeft,
                              String message, int companies) {}

    private final JdbcClient jdbc;
    private final Tenants tenants;
    private final TransactionTemplate tx;
    private final CountryPacks packs;
    private final UserService users;
    private final OrgStructure org;
    private final AuditService audit;
    private final Licences licences;
    private final Entitlements entitlements;
    private final cd.hrpay.billing.Billing billing;
    private final java.time.Clock clock;

    public PlatformController(JdbcClient jdbc, Tenants tenants, TransactionTemplate tx, CountryPacks packs,
                              UserService users, OrgStructure org, AuditService audit, Licences licences,
                              Entitlements entitlements, cd.hrpay.billing.Billing billing, java.time.Clock clock) {
        this.billing = billing;
        this.clock = clock;
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.tx = tx;
        this.packs = packs;
        this.users = users;
        this.org = org;
        this.audit = audit;
        this.licences = licences;
        this.entitlements = entitlements;
    }

    @GetMapping("/plans")
    public List<PlanView> plans() {
        requireProvider();
        return jdbc.sql("select code, name, max_employees from plan order by display_order").query(PlanView.class).list();
    }

    @GetMapping("/countries")
    public Map<String, String> countries() {
        requireProvider();
        return CountryPacks.COUNTRIES;
    }

    @GetMapping("/companies")
    public List<CompanyView> companies() {
        requireProvider();
        return tenants.callAsPlatform(() -> jdbc.sql(SELECT + " order by c.id").query(this::view).list());
    }

    @PostMapping("/companies")
    @ResponseStatus(HttpStatus.CREATED)
    public Created create(@Valid @RequestBody NewCompany body) {
        requireProvider();
        String plan = checkPlan(body.planCode());
        String[] modules = checkModules(body.modules());
        if (!CountryPacks.COUNTRIES.containsKey(body.countryCode())) {
            throw new BusinessRuleException("There is no set-up for this country yet.");
        }
        String email = body.admin().email().trim().toLowerCase();
        if (tenants.companyOfEmail(email) != null) {
            throw new ConflictException("This email address is already used.");
        }
        if (entitlements.mode() == Entitlements.Mode.ON_PREMISE) {
            Licences.Status status = tenants.callAsPlatform(licences::status);
            Integer max = status.licence() == null ? Integer.valueOf(1) : status.licence().maxCompanies();
            if (max != null && companyCount() >= max) {
                throw new BusinessRuleException("The licence covers " + max + " company(ies). Ask your provider for a larger licence.");
            }
        }
        String password = temporaryPassword();
        String by = CurrentUser.email();
        long id = tx.execute(s -> {
            long companyId = tenants.callAsPlatform(() -> jdbc.sql("""
                    insert into company (name, address, country_code, plan_code, modules, created_by)
                    values (?, ?, ?, ?, ?, ?) returning id""")
                    .params(body.name().trim(), CountryPacks.COUNTRIES.get(body.countryCode()), body.countryCode(), plan,
                            modules, by)
                    .query(Long.class).single());
            tenants.runAs(companyId, () -> {
                packs.apply(body.countryCode());
                AppUser admin = users.create(new UserService.UserData(email, "ADM-0001", body.admin().phone(),
                        body.admin().firstName().trim(), body.admin().lastName().trim(), null), List.of("ADMIN", "HR"), password);
                long top = jdbc.sql("select id from org_unit where parent_id is null").query(Long.class).single();
                org.assign(admin.getId(), top, null, "HIRE", "First administrator of the company");
                audit.recordAs(by, "company.created", "company", companyId, Map.of("name", body.name().trim(),
                        "plan", plan, "admin", email));
            });
            if (entitlements.mode() == Entitlements.Mode.SAAS) {
                billing.startTrial(companyId, java.time.LocalDate.now(clock));
            }
            return companyId;
        });
        audit.record("company.created", "company", id, Map.of("name", body.name().trim(), "plan", plan,
                "modules", List.of(modules), "admin", email));
        return new Created(find(id), email, password);
    }

    @PutMapping("/companies/{id}")
    public CompanyView change(@PathVariable long id, @Valid @RequestBody CompanyChange body) {
        requireProvider();
        String plan = checkPlan(body.planCode());
        String[] modules = checkModules(body.modules());
        if (!Set.of("ACTIVE", "SUSPENDED").contains(body.status())) {
            throw new BusinessRuleException("Unknown status.");
        }
        if (TenantContext.isProvider(id) && !"ACTIVE".equals(body.status())) {
            throw new BusinessRuleException("The provider's own company cannot be suspended.");
        }
        find(id);
        // A suspension by hand is the provider's; returning to active lifts any suspension, unpaid invoices included.
        tenants.callAsPlatform(() -> jdbc.sql("""
                update company set plan_code = ?, modules = ?,
                       suspended_reason = case when ? = 'ACTIVE' then null when status = 'ACTIVE' then 'MANUAL' else suspended_reason end,
                       suspended_at = case when ? = 'ACTIVE' then null when status = 'ACTIVE' then now() else suspended_at end,
                       status = ? where id = ?""")
                .params(plan, modules, body.status(), body.status(), body.status(), id).update());
        audit.record("company.plan_changed", "company", id, Map.of("plan", plan, "modules", List.of(modules),
                "status", body.status()));
        return find(id);
    }

    @GetMapping("/licence")
    public LicenceView licence() {
        requireProvider();
        Licences.Status s = tenants.callAsPlatform(licences::status);
        return new LicenceView(entitlements.mode(), s.state(), s.licence(), s.evaluationEnds(), s.locksOn(), s.daysLeft(),
                s.message(), companyCount());
    }

    @PostMapping("/licence")
    public LicenceView installLicence(@Valid @RequestBody LicenceInput body) {
        requireProvider();
        Licences.Licence l = tenants.callAsPlatform(() -> licences.install(body.content(), CurrentUser.email()));
        audit.record("licence.installed", "licence", l.id(), Map.of("licensee", l.licensee(),
                "expires", l.expires().toString(), "modules", l.modules()));
        return licence();
    }

    private static final String SELECT = """
            select c.id, c.name, c.country_code, c.plan_code, p.name as plan_name, p.max_employees, c.modules, c.status,
                   (select count(*) from app_user u where u.company_id = c.id and u.status = 'ACTIVE') as employees,
                   c.created_at, c.suspended_reason, s.status as subscription_status, s.cycle, s.trial_ends,
                   s.next_invoice_on,
                   (select count(*) from invoice i where i.company_id = c.id and i.status = 'OPEN') as open_invoices,
                   (select count(*) from invoice i where i.company_id = c.id and i.status = 'OPEN'
                      and i.due_on < current_date) as overdue_invoices,
                   (select max(u.last_login_at) from app_user u where u.company_id = c.id) as last_sign_in,
                   exists (select 1 from support_grant g where g.company_id = c.id and g.revoked_at is null
                           and g.expires_at > now()) as support_open
            from company c join plan p on p.code = c.plan_code left join subscription s on s.company_id = c.id""";

    private CompanyView view(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        long id = rs.getLong("id");
        return new CompanyView(id, rs.getString("name"), rs.getString("country_code"), rs.getString("plan_code"),
                rs.getString("plan_name"), (Integer) rs.getObject("max_employees"),
                List.of((String[]) rs.getArray("modules").getArray()), rs.getString("status"), rs.getInt("employees"),
                rs.getObject("created_at", OffsetDateTime.class), TenantContext.isProvider(id), rs.getString("suspended_reason"),
                rs.getString("subscription_status"), rs.getString("cycle"),
                rs.getObject("trial_ends", java.time.LocalDate.class), rs.getObject("next_invoice_on", java.time.LocalDate.class),
                rs.getInt("open_invoices"), rs.getInt("overdue_invoices"),
                rs.getObject("last_sign_in", OffsetDateTime.class), rs.getBoolean("support_open"));
    }

    private CompanyView find(long id) {
        return tenants.callAsPlatform(() -> jdbc.sql(SELECT + " where c.id = ?").param(id).query(this::view).optional())
                .orElseThrow(() -> new NotFoundException("Unknown company."));
    }

    private int companyCount() {
        return tenants.callAsPlatform(() -> jdbc.sql("select count(*) from company").query(Integer.class).single());
    }

    private String checkPlan(String code) {
        boolean known = jdbc.sql("select count(*) from plan where code = ?").param(code).query(Integer.class).single() > 0;
        if (!known) {
            throw new BusinessRuleException("Unknown plan.");
        }
        return code;
    }

    private static String[] checkModules(Set<String> modules) {
        if (!Licences.MODULES.containsAll(modules)) {
            throw new BusinessRuleException("Unknown module. Choose among Attendance, Payroll and Evaluations.");
        }
        return new TreeSet<>(modules).toArray(String[]::new);
    }

    /** platform.manage only works in the provider's company; this also stops a copied permission elsewhere. */
    private static void requireProvider() {
        if (!TenantContext.isProvider(TenantContext.companyId())) {
            throw new AccessDeniedException("Only the provider manages companies.");
        }
    }

    private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Meets the password policy: upper and lower case, a digit and a symbol. Changed at first sign-in. */
    static String temporaryPassword() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            b.append(LETTERS.charAt(RANDOM.nextInt(LETTERS.length())));
        }
        return "Hp" + b + "7!";
    }
}
