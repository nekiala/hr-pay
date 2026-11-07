package cd.hrpay.security;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.tenancy.Tenants;
import cd.hrpay.user.AppUser;
import org.springframework.transaction.support.TransactionTemplate;
import cd.hrpay.user.UserRepository;
import cd.hrpay.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    public record ChangePassword(@NotBlank String currentPassword, @NotBlank String newPassword) {}

    public record CompanyInfo(long id, String name, String mode, String plan, Set<String> modules, Integer maxEmployees,
                              int employees, boolean locked, String readOnly,
                              java.util.List<cd.hrpay.platform.Entitlements.Notice> notices) {}

    /** A provider administrator looking at a company on a support visit. */
    public record SupportInfo(long companyId, String companyName, java.time.OffsetDateTime expiresAt) {}

    public record Me(Long id, String email, String firstName, String lastName, Set<String> roles,
                     Set<String> permissions, boolean mustChangePassword, boolean privacyAccepted, CompanyInfo company,
                     SupportInfo support) {
        static Me of(AppUser u, cd.hrpay.platform.Entitlements.Current c) {
            Set<String> permissions = new java.util.TreeSet<>();
            Authorities.of(u).forEach(a -> permissions.add(a.getAuthority()));
            return of(u, c, permissions, null);
        }

        static Me of(AppUser u, cd.hrpay.platform.Entitlements.Current c, Set<String> permissions, SupportInfo support) {
            CompanyInfo company = c == null ? null : new CompanyInfo(c.companyId(), c.companyName(), c.mode().name(),
                    c.planName(), c.modules(), c.maxEmployees(), c.employees(), c.locked(), c.readOnly(), c.notices());
            return new Me(u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(), u.roleCodes(),
                    permissions, u.isMustChangePassword(), u.getPrivacyAcceptedAt() != null, company, support);
        }
    }

    public record Providers(boolean password, boolean google) {}

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;
    private final UserRepository users;
    private final UserService userService;
    private final LoginAttempts attempts;
    private final AuditService audit;
    private final ObjectProvider<ClientRegistrationRepository> googleClients;
    private final Tenants tenants;
    private final TransactionTemplate tx;
    private final cd.hrpay.platform.Entitlements entitlements;

    public AuthController(AuthenticationManager authenticationManager, SecurityContextRepository contextRepository,
                          UserRepository users, UserService userService, LoginAttempts attempts,
                          AuditService audit, ObjectProvider<ClientRegistrationRepository> googleClients,
                          Tenants tenants, TransactionTemplate tx, cd.hrpay.platform.Entitlements entitlements) {
        this.tenants = tenants;
        this.tx = tx;
        this.entitlements = entitlements;
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
        this.users = users;
        this.userService = userService;
        this.attempts = attempts;
        this.audit = audit;
        this.googleClients = googleClients;
    }

    /** Sets the XSRF-TOKEN cookie; the app calls this once before its first POST. */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName());
    }

    @GetMapping("/providers")
    public Providers providers() {
        return new Providers(true, googleClients.getIfAvailable() != null);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                                   HttpServletResponse response) {
        String email = body.email().trim().toLowerCase();
        // The email finds the company; everything after works within it. An unknown email belongs to none.
        Long company = tenants.companyOfEmail(email);
        java.util.function.Supplier<ResponseEntity<?>> work = () -> tx.execute(s -> signIn(email, body, request, response));
        return company == null ? tenants.callAsPlatform(work) : tenants.callAs(company, work);
    }

    private ResponseEntity<?> signIn(String email, LoginRequest body, HttpServletRequest request,
                                     HttpServletResponse response) {
        if (attempts.isBlocked(email)) {
            audit.recordAs(email, "auth.login_blocked", "user", null, Map.of("method", "password"));
            return problem(HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts. Try again in 15 minutes.");
        }
        try {
            authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email,
                    body.password()));
        } catch (DisabledException e) {
            audit.recordAs(email, "auth.login_failed", "user", null, Map.of("method", "password", "reason", "disabled"));
            return problem(HttpStatus.UNAUTHORIZED, "This account is disabled. Contact HR.");
        } catch (AuthenticationException e) {
            attempts.failed(email);
            audit.recordAs(email, "auth.login_failed", "user", null,
                    Map.of("method", "password", "reason", "bad_credentials"));
            return problem(HttpStatus.UNAUTHORIZED, "Email or password is not correct.");
        }
        attempts.succeeded(email);
        AppUser user = users.findByEmailIgnoreCase(email).orElseThrow();
        user.setLastLoginAt(OffsetDateTime.now());

        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(Authorities.token(user));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        audit.recordAs(user.getEmail(), "auth.login", "user", user.getId(), Map.of("method", "password"));
        return ResponseEntity.ok(Me.of(user, entitlements.current()));
    }

    @GetMapping("/me")
    @Transactional(readOnly = true)
    public Me me() {
        Long id = CurrentUser.id();
        if (id == null) {
            throw new BusinessRuleException("Not signed in.");
        }
        cd.hrpay.platform.SupportAccess.Visit visit = cd.hrpay.platform.SupportAccess.current();
        if (visit != null) {
            // The visitor is a user of the provider's company, working in the visited one.
            AppUser user = tenants.callAs(cd.hrpay.tenancy.TenantContext.providerCompany(), () -> {
                AppUser u = userService.find(id);
                u.roleCodes(); // loads the roles while the provider's rows are visible
                return u;
            });
            Set<String> permissions = new java.util.TreeSet<>();
            org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()
                    .getAuthorities().forEach(a -> permissions.add(a.getAuthority()));
            return Me.of(user, entitlements.current(), permissions,
                    new SupportInfo(visit.companyId(), visit.companyName(), visit.expiresAt()));
        }
        return Me.of(userService.find(id), entitlements.current());
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePassword body) {
        userService.changeOwnPassword(CurrentUser.id(), body.currentPassword(), body.newPassword());
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
