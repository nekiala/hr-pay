package cd.hrpay.security;

import cd.hrpay.audit.AuditService;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * What happens once a person proved who they are with a first factor (password, Google or Microsoft): either the
 * browser waits for the second step, or the person is signed in on a fresh session.
 */
@Component
public class SignInSteps {

    private final SecurityContextRepository contextRepository;
    private final UserRepository users;
    private final TwoStep twoStep;
    private final UserSessions sessions;
    private final AuditService audit;

    public SignInSteps(SecurityContextRepository contextRepository, UserRepository users, TwoStep twoStep,
                       UserSessions sessions, AuditService audit) {
        this.contextRepository = contextRepository;
        this.users = users;
        this.twoStep = twoStep;
        this.sessions = sessions;
        this.audit = audit;
    }

    /** The first factor passed: returns the second step to ask for, or signs the person in (empty). */
    public Optional<TwoStep.Pending> afterFirstFactor(AppUser user, HttpServletRequest request,
                                                      HttpServletResponse response, String via) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        Optional<TwoStep.Pending> pending = twoStep.start(user, user.getCompanyId(), via);
        if (pending.isPresent()) {
            // Nothing is signed in until the second step is done.
            SecurityContextHolder.clearContext();
            contextRepository.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
            request.getSession().setAttribute(TwoStep.SESSION_KEY, pending.get());
            audit.recordAs(user.getEmail(), "auth.first_factor_ok", "user", user.getId(),
                    Map.of("method", via, "next", "two-step"));
            return pending;
        }
        finish(user, request, response, via);
        return Optional.empty();
    }

    /** Signs the person in on this browser, once every step is done. */
    public void finish(AppUser user, HttpServletRequest request, HttpServletResponse response, String method) {
        user.setLastLoginAt(OffsetDateTime.now());
        users.save(user);
        if (request.getSession(false) != null) {
            request.getSession().removeAttribute(TwoStep.SESSION_KEY);
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(Authorities.token(user));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        sessions.record(request, user.getId(), method);
        audit.recordAs(user.getEmail(), "auth.login", "user", user.getId(), Map.of("method", method));
    }
}
