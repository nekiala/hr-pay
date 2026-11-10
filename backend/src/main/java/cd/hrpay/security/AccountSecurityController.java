package cd.hrpay.security;

import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A person's own sign-in security (second step, signed-in browsers), and an administrator's resets of them. */
@RestController
public class AccountSecurityController {

    public record PasswordInput(@NotBlank String password) {}

    public record Ended(int count) {}

    /** null: follow the company's language. */
    public record LanguageInput(@jakarta.validation.constraints.Pattern(regexp = "fr|en") String language) {}

    /** How people of the company may sign in besides a password. */
    public record SignInSettings(String microsoftTenant, boolean microsoftAvailable, boolean googleAvailable,
                                 java.util.List<String> googleDomains, boolean googleOnly) {}

    /** The company's Google Workspace domains, and whether people on them must use Google. */
    public record GoogleInput(java.util.List<String> domains, Boolean googleOnly) {
        boolean only() {
            return Boolean.TRUE.equals(googleOnly);
        }
    }

    public record SignInInput(@jakarta.validation.constraints.Pattern(
            regexp = "^$|^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
            message = "Paste the tenant ID (directory ID) from the Microsoft Entra admin centre") String microsoftTenant) {}

    private final TwoStep twoStep;
    private final UserSessions sessions;
    private final UserService users;
    private final PasswordEncoder encoder;

    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private final AuthController auth;
    private final cd.hrpay.audit.AuditService audit;
    private final GoogleDomains googleDomains;

    public AccountSecurityController(TwoStep twoStep, UserSessions sessions, UserService users, PasswordEncoder encoder,
                                     org.springframework.jdbc.core.simple.JdbcClient jdbc, AuthController auth,
                                     cd.hrpay.audit.AuditService audit, GoogleDomains googleDomains) {
        this.googleDomains = googleDomains;
        this.jdbc = jdbc;
        this.auth = auth;
        this.audit = audit;
        this.twoStep = twoStep;
        this.sessions = sessions;
        this.users = users;
        this.encoder = encoder;
    }

    private AppUser me() {
        Long id = CurrentUser.id();
        if (id == null || cd.hrpay.platform.SupportAccess.current() != null) {
            throw new BusinessRuleException("Not available on a support visit.");
        }
        return users.find(id);
    }

    /** Asking for the password again before a change that weakens or replaces the second step. */
    private void checkPassword(AppUser user, String password) {
        if (user.getPasswordHash() != null && !encoder.matches(password, user.getPasswordHash())) {
            throw new BusinessRuleException("The password is not correct.");
        }
    }

    @org.springframework.web.bind.annotation.PutMapping("/api/account/language")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void language(@Valid @RequestBody LanguageInput body) {
        AppUser user = me();
        user.setLanguage(body.language());
    }

    @GetMapping("/api/company/language")
    @PreAuthorize("hasAuthority('company.manage')")
    public LanguageInput companyLanguage() {
        return new LanguageInput(jdbc.sql("select language from company where id = current_company_id()").query(String.class).single());
    }

    /** The company's language: the default for its people, and the language of its payslips, declarations and letters. */
    @org.springframework.web.bind.annotation.PutMapping("/api/company/language")
    @PreAuthorize("hasAuthority('company.manage')")
    @Transactional
    public LanguageInput setCompanyLanguage(@Valid @RequestBody LanguageInput body) {
        if (body.language() == null) {
            throw new BusinessRuleException("Choose French or English.");
        }
        jdbc.sql("update company set language = ? where id = current_company_id()").param(body.language()).update();
        audit.record("company.language_set", "company", cd.hrpay.tenancy.TenantContext.companyId(), Map.of("language", body.language()));
        return companyLanguage();
    }

    @GetMapping("/api/account/two-step")
    @Transactional(readOnly = true)
    public TwoStep.Status status() {
        return twoStep.status(me());
    }

    @PostMapping("/api/account/two-step/off")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void turnOff(@Valid @RequestBody PasswordInput body) {
        AppUser user = me();
        checkPassword(user, body.password());
        if (twoStep.required(user)) {
            throw new BusinessRuleException("Your role requires a second step: you can change it, not turn it off.");
        }
        twoStep.remove(user, "self");
    }

    @PostMapping("/api/account/two-step/recovery-codes")
    @Transactional
    public Map<String, List<String>> renewCodes(@Valid @RequestBody PasswordInput body) {
        AppUser user = me();
        checkPassword(user, body.password());
        return Map.of("recoveryCodes", twoStep.newRecoveryCodes(user));
    }

    @GetMapping("/api/account/sessions")
    public List<UserSessions.View> mySessions(HttpServletRequest request) {
        return sessions.list(me().getId(), request.getSession().getId());
    }

    @DeleteMapping("/api/account/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void endMine(@PathVariable String id) {
        sessions.end(me().getId(), id, CurrentUser.email());
    }

    @PostMapping("/api/account/sessions/end-others")
    @Transactional
    public Ended endOthers(HttpServletRequest request) {
        return new Ended(sessions.endAll(me().getId(), request.getSession().getId(), CurrentUser.email()));
    }

    // ---- an administrator, for someone else ----

    /** For a lost phone without recovery codes: the person sets the second step up again at their next sign-in. */
    @PostMapping("/api/users/{id}/two-step/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('role.assign')")
    @Transactional
    public void reset(@PathVariable Long id) {
        AppUser user = users.find(id);
        if (user.getId().equals(CurrentUser.id())) {
            throw new BusinessRuleException("Another administrator must reset your own second step.");
        }
        twoStep.remove(user, CurrentUser.email());
        sessions.endAll(user.getId(), null, CurrentUser.email());
    }

    @GetMapping("/api/users/{id}/sessions")
    @PreAuthorize("hasAuthority('user.disable')")
    public List<UserSessions.View> sessionsOf(@PathVariable Long id) {
        return sessions.list(users.find(id).getId(), null);
    }

    @PostMapping("/api/users/{id}/sessions/end")
    @PreAuthorize("hasAuthority('user.disable')")
    @Transactional
    public Ended endAllOf(@PathVariable Long id) {
        return new Ended(sessions.endAll(users.find(id).getId(), null, CurrentUser.email()));
    }

    @GetMapping("/api/company/sign-in")
    @PreAuthorize("hasAuthority('role.manage')")
    public SignInSettings signIn() {
        AuthController.Providers p = auth.providers();
        String tenant = jdbc.sql("select microsoft_tenant from company where id = current_company_id()").query(String.class)
                .optional().orElse(null);
        return new SignInSettings(tenant, p.microsoft(), p.google(), googleDomains.list(), googleDomains.googleOnly());
    }

    @org.springframework.web.bind.annotation.PutMapping("/api/company/sign-in/google")
    @PreAuthorize("hasAuthority('role.manage')")
    @Transactional
    public SignInSettings saveGoogle(@RequestBody GoogleInput body) {
        if (body.only() && !auth.providers().google()) {
            throw new BusinessRuleException("Google sign-in is not turned on for this installation, so it cannot be required.");
        }
        googleDomains.replace(body.domains(), body.only(), CurrentUser.email());
        audit.record("company.google_domains_set", "company", cd.hrpay.tenancy.TenantContext.companyId(),
                Map.of("domains", String.join(",", googleDomains.list()), "googleOnly", body.only()));
        return signIn();
    }

    /** The company's Google domains, for HR creating accounts (who can sign in with Google). */
    @GetMapping("/api/company/google-domains")
    @PreAuthorize("hasAnyAuthority('user.create', 'user.update', 'role.manage')")
    public Map<String, Object> googleDomains() {
        return Map.of("domains", googleDomains.list(), "available", auth.providers().google(), "googleOnly", googleDomains.googleOnly());
    }

    @org.springframework.web.bind.annotation.PutMapping("/api/company/sign-in")
    @PreAuthorize("hasAuthority('role.manage')")
    @Transactional
    public SignInSettings saveSignIn(@Valid @RequestBody SignInInput body) {
        String tenant = body.microsoftTenant() == null || body.microsoftTenant().isBlank() ? null : body.microsoftTenant().toLowerCase();
        jdbc.sql("update company set microsoft_tenant = ? where id = current_company_id()").param(tenant).update();
        audit.record("company.microsoft_tenant_set", "company", cd.hrpay.tenancy.TenantContext.companyId(),
                Map.of("tenant", tenant == null ? "" : tenant));
        return signIn();
    }
}
