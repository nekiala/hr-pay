package cd.hrpay.security;

import cd.hrpay.tenancy.Tenants;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reloads the signed-in user on every request, so that a disabled account is signed out at once and a
 * role or permission change applies on the next request instead of at the next login.
 */
class UserRefreshFilter extends OncePerRequestFilter {

    private final UserRepository users;
    private final Tenants tenants;

    UserRefreshFilter(UserRepository users, Tenants tenants) {
        this.users = users;
        this.tenants = tenants;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // The rest of the request works for the signed-in user's company only.
        Long company = companyOf(SecurityContextHolder.getContext().getAuthentication());
        if (company == null) {
            refreshAndContinue(request, response, chain);
            return;
        }
        try {
            tenants.callAs(company, () -> {
                try {
                    refreshAndContinue(request, response, chain);
                    return null;
                } catch (IOException | ServletException e) {
                    throw new Wrapped(e);
                }
            });
        } catch (Wrapped w) {
            if (w.getCause() instanceof IOException io) {
                throw io;
            }
            throw (ServletException) w.getCause();
        }
    }

    private static final class Wrapped extends RuntimeException {
        Wrapped(Exception cause) {
            super(cause);
        }
    }

    private Long companyOf(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        if (auth.getPrincipal() instanceof AuthenticatedUser principal) {
            return principal.companyId();
        }
        if (auth.getPrincipal() instanceof OidcUser oidc) {
            return tenants.companyOfEmail(oidc.getEmail());
        }
        return null;
    }

    private void refreshAndContinue(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            Optional<AppUser> user = resolve(auth);
            if (user.isPresent() && user.get().isActive()) {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(Authorities.token(user.get()));
                SecurityContextHolder.setContext(context);
            } else if (user.isPresent() || auth.getPrincipal() instanceof AuthenticatedUser
                    || auth.getPrincipal() instanceof OidcUser) {
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
            }
        }
        chain.doFilter(request, response);
    }

    private Optional<AppUser> resolve(Authentication auth) {
        if (auth.getPrincipal() instanceof AuthenticatedUser principal) {
            return users.findById(principal.id());
        }
        if (auth.getPrincipal() instanceof OidcUser oidc && oidc.getEmail() != null) {
            return users.findByEmailIgnoreCase(oidc.getEmail());
        }
        return Optional.empty();
    }
}
