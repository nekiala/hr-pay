package cd.hrpay.security;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.user.AppUser;
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

    public record Me(Long id, String email, String firstName, String lastName, Set<String> roles,
                     Set<String> permissions, boolean mustChangePassword, boolean privacyAccepted) {
        static Me of(AppUser u) {
            return new Me(u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(), u.roleCodes(),
                    u.permissionCodes(), u.isMustChangePassword(), u.getPrivacyAcceptedAt() != null);
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

    public AuthController(AuthenticationManager authenticationManager, SecurityContextRepository contextRepository,
                          UserRepository users, UserService userService, LoginAttempts attempts,
                          AuditService audit, ObjectProvider<ClientRegistrationRepository> googleClients) {
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
    @Transactional
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                                   HttpServletResponse response) {
        String email = body.email().trim().toLowerCase();
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
        return ResponseEntity.ok(Me.of(user));
    }

    @GetMapping("/me")
    @Transactional(readOnly = true)
    public Me me() {
        Long id = CurrentUser.id();
        if (id == null) {
            throw new BusinessRuleException("Not signed in.");
        }
        return Me.of(userService.find(id));
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
