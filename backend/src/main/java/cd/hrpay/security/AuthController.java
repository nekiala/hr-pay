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
                              java.util.List<cd.hrpay.platform.Entitlements.Notice> notices, String country,
                              String language, String localCurrency) {}

    /** A provider administrator looking at a company on a support visit. */
    public record SupportInfo(long companyId, String companyName, java.time.OffsetDateTime expiresAt) {}

    /** An accounting firm's member working in a client company. */
    public record FirmInfo(long linkId, String firmName, long clientId, String clientName) {}

    /** language: the one the person sees the app in (theirs, else the company's); ownLanguage: theirs, or null. */
    public record Me(Long id, String email, String firstName, String lastName, Set<String> roles,
                     Set<String> permissions, boolean mustChangePassword, boolean privacyAccepted, CompanyInfo company,
                     SupportInfo support, String language, String ownLanguage, FirmInfo firm, boolean firmMember) {
        static Me of(AppUser u, cd.hrpay.platform.Entitlements.Current c) {
            Set<String> permissions = new java.util.TreeSet<>();
            Authorities.of(u).forEach(a -> permissions.add(a.getAuthority()));
            return of(u, c, permissions, null);
        }

        static Me of(AppUser u, cd.hrpay.platform.Entitlements.Current c, Set<String> permissions, SupportInfo support) {
            CompanyInfo company = c == null ? null : new CompanyInfo(c.companyId(), c.companyName(), c.mode().name(),
                    c.planName(), c.modules(), c.maxEmployees(), c.employees(), c.locked(), c.readOnly(), c.notices(),
                    c.country(), c.language(), c.localCurrency());
            String language = u.getLanguage() != null ? u.getLanguage() : c == null ? "fr" : c.language();
            return new Me(u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(), u.roleCodes(),
                    permissions, u.isMustChangePassword(), u.getPrivacyAcceptedAt() != null, company, support, language,
                    u.getLanguage(), null, false);
        }

        Me withFirm(FirmInfo f, boolean member) {
            return new Me(id, email, firstName, lastName, roles, permissions, mustChangePassword, privacyAccepted, company,
                    support, language, ownLanguage, f, member);
        }
    }

    public record Providers(boolean password, boolean google, boolean microsoft) {}

    /** The answer to a password that needs a second step: what to ask for. */
    public record TwoStepNeeded(String method, boolean setup, boolean codesAvailable, java.util.List<String> sentTo) {}

    public record CodeInput(@NotBlank String code) {}

    public record SetupInput(@NotBlank String method) {}

    public record ConfirmInput(@NotBlank String method, @NotBlank String code, String challenge) {}

    public record Confirmed(java.util.List<String> recoveryCodes, Me me) {}

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
    private final TwoStep twoStep;
    private final UserSessions sessions;
    private final SignInSteps steps;
    private final cd.hrpay.firm.Firms firms;

    public AuthController(AuthenticationManager authenticationManager, SecurityContextRepository contextRepository,
                          UserRepository users, UserService userService, LoginAttempts attempts,
                          AuditService audit, ObjectProvider<ClientRegistrationRepository> googleClients,
                          Tenants tenants, TransactionTemplate tx, cd.hrpay.platform.Entitlements entitlements,
                          TwoStep twoStep, UserSessions sessions, SignInSteps steps, cd.hrpay.firm.Firms firms) {
        this.firms = firms;
        this.steps = steps;
        this.twoStep = twoStep;
        this.sessions = sessions;
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
        java.util.Set<String> ids = new java.util.HashSet<>();
        if (googleClients.getIfAvailable() instanceof Iterable<?> registrations) {
            for (Object r : registrations) {
                ids.add(((org.springframework.security.oauth2.client.registration.ClientRegistration) r).getRegistrationId());
            }
        }
        return new Providers(true, ids.contains("google"), ids.contains("microsoft"));
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
        java.util.Optional<TwoStep.Pending> pending = steps.afterFirstFactor(user, request, response, "password");
        if (pending.isPresent()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(needed(pending.get(), user));
        }
        return ResponseEntity.ok(Me.of(user, entitlements.current()));
    }

    private TwoStepNeeded needed(TwoStep.Pending p, AppUser user) {
        java.util.List<String> sentTo = p.method() == TwoStep.Method.CODE ? java.util.List.of(mask(user.getEmail())) : java.util.List.of();
        return new TwoStepNeeded(p.method() == null ? null : p.method().name(), p.setup(), twoStep.codesAvailable(user.getCompanyId()), sentTo);
    }

    private static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? email : email.charAt(0) + "•••" + email.substring(at);
    }

    private Me finish(AppUser user, HttpServletRequest request, HttpServletResponse response, String method) {
        steps.finish(user, request, response, method);
        return Me.of(user, entitlements.current());
    }

    // ---- the second step ----

    /** The sign-in waiting for its second step on this browser; it lasts 10 minutes. */
    private TwoStep.Pending pending(HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        Object p = session == null ? null : session.getAttribute(TwoStep.SESSION_KEY);
        if (!(p instanceof TwoStep.Pending pending)
                || pending.startedAt().isBefore(OffsetDateTime.now().minusMinutes(10))) {
            throw new BusinessRuleException("Sign in again with your password.");
        }
        return pending;
    }

    private <T> T inCompany(TwoStep.Pending p, java.util.function.Function<AppUser, T> work) {
        return tenants.callAs(p.companyId(), () -> tx.execute(s -> {
            AppUser user = users.findById(p.userId()).filter(AppUser::isActive)
                    .orElseThrow(() -> new BusinessRuleException("This account is disabled. Contact HR."));
            return work.apply(user);
        }));
    }

    /** The step this browser waits for, after a Google or Microsoft sign-in sent it back to the sign-in page. */
    @GetMapping("/two-step")
    public TwoStepNeeded waiting(HttpServletRequest request) {
        TwoStep.Pending p = pending(request);
        return inCompany(p, user -> needed(p, user));
    }

    @PostMapping("/two-step")
    public ResponseEntity<?> verifyTwoStep(@Valid @RequestBody CodeInput body, HttpServletRequest request,
                                           HttpServletResponse response) {
        TwoStep.Pending p = pending(request);
        if (p.setup()) {
            throw new BusinessRuleException("Set up the second step first.");
        }
        return inCompany(p, user -> {
            if (twoStep.verify(p, user, body.code())) {
                return ResponseEntity.ok(finish(user, request, response, p.via() + "+" + p.method().name().toLowerCase()));
            }
            TwoStep.Pending next = p.withAttempt();
            audit.recordAs(user.getEmail(), "auth.two_step_failed", "user", user.getId(), Map.of("attempt", next.attempts()));
            if (next.attempts() >= TwoStep.MAX_ATTEMPTS) {
                request.getSession().removeAttribute(TwoStep.SESSION_KEY);
                return problem(HttpStatus.UNAUTHORIZED, "Too many wrong codes. Sign in again with your password.");
            }
            request.getSession().setAttribute(TwoStep.SESSION_KEY, next);
            return problem(HttpStatus.UNAUTHORIZED, "The code is not correct.");
        });
    }

    @PostMapping("/two-step/resend")
    public TwoStepNeeded resend(HttpServletRequest request) {
        TwoStep.Pending p = pending(request);
        return inCompany(p, user -> {
            TwoStep.Pending next = twoStep.resend(p, user);
            request.getSession().setAttribute(TwoStep.SESSION_KEY, next);
            return needed(next, user);
        });
    }

    /** Starts setting up the second step: during a sign-in that requires it, or from My account. */
    @PostMapping("/two-step/setup")
    public TwoStep.Setup setup(@Valid @RequestBody SetupInput body, HttpServletRequest request) {
        TwoStep.Method method = method(body.method());
        Long signedIn = CurrentUser.id();
        if (signedIn != null) {
            return tx.execute(s -> twoStep.beginSetup(userService.find(signedIn), method));
        }
        TwoStep.Pending p = pending(request);
        if (!p.setup()) {
            throw new BusinessRuleException("Enter the code of your second step.");
        }
        return inCompany(p, user -> twoStep.beginSetup(user, method));
    }

    @PostMapping("/two-step/confirm")
    public Confirmed confirm(@Valid @RequestBody ConfirmInput body, HttpServletRequest request, HttpServletResponse response) {
        TwoStep.Method method = method(body.method());
        Long signedIn = CurrentUser.id();
        if (signedIn != null) {
            return tx.execute(s -> {
                AppUser user = userService.find(signedIn);
                return new Confirmed(twoStep.confirm(user, method, body.code(), body.challenge()), Me.of(user, entitlements.current()));
            });
        }
        TwoStep.Pending p = pending(request);
        if (!p.setup()) {
            throw new BusinessRuleException("Enter the code of your second step.");
        }
        return inCompany(p, user -> {
            java.util.List<String> codes = twoStep.confirm(user, method, body.code(), body.challenge());
            return new Confirmed(codes, finish(user, request, response, p.via() + "+" + method.name().toLowerCase()));
        });
    }

    private static TwoStep.Method method(String m) {
        try {
            return TwoStep.Method.valueOf(m);
        } catch (RuntimeException e) {
            throw new BusinessRuleException("Choose an authenticator app or a code by email.");
        }
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
        cd.hrpay.firm.Firms.Visit firmVisit = cd.hrpay.firm.Firms.current();
        if (firmVisit != null) {
            // A member of an accounting firm, working in a client.
            AppUser user = tenants.callAs(firmVisit.firmId(), () -> {
                AppUser u = userService.find(id);
                u.roleCodes();
                return u;
            });
            Set<String> permissions = new java.util.TreeSet<>();
            org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()
                    .getAuthorities().forEach(a -> permissions.add(a.getAuthority()));
            return Me.of(user, entitlements.current(), permissions, null).withFirm(new FirmInfo(firmVisit.linkId(),
                    firmVisit.firmName(), firmVisit.clientId(), firmVisit.clientName()), true);
        }
        Me me = Me.of(userService.find(id), entitlements.current());
        return firms.isFirm(cd.hrpay.tenancy.TenantContext.companyId()) ? me.withFirm(null, true) : me;
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePassword body, HttpServletRequest request) {
        userService.changeOwnPassword(CurrentUser.id(), body.currentPassword(), body.newPassword());
        // A new password signs out the other browsers, in case the old one was known to someone else.
        sessions.endAll(CurrentUser.id(), request.getSession().getId(), CurrentUser.email());
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
