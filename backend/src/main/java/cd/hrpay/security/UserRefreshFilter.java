package cd.hrpay.security;

import cd.hrpay.platform.SupportAccess;
import cd.hrpay.tenancy.TenantContext;
import cd.hrpay.tenancy.Tenants;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
    private final SupportAccess support;
    private final JdbcClient jdbc;

    UserRefreshFilter(UserRepository users, Tenants tenants, SupportAccess support, JdbcClient jdbc) {
        this.users = users;
        this.tenants = tenants;
        this.support = support;
        this.jdbc = jdbc;
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
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(SupportAccess.SESSION_KEY) instanceof SupportAccess.Visit visit) {
            if (supportVisit(request, response, chain, company, session, visit)) {
                return;
            }
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

    /**
     * A provider administrator on a support visit: signed in as themselves, in their own company, then working in
     * the visited company with read permissions only (writes are refused by the read-only guard). Returns false, and
     * ends the visit, when the person lost the right or the company closed the access.
     */
    private boolean supportVisit(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
                                 Long company, HttpSession session, SupportAccess.Visit visit)
            throws IOException, ServletException {
        tenants.callAs(company, () -> {
            refresh(request);
            return null;
        });
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean allowed = auth != null && auth.getPrincipal() instanceof AuthenticatedUser
                && TenantContext.isProvider(company) && CurrentUser.has("platform.manage") && support.valid(visit);
        if (!allowed) {
            if (request.getSession(false) != null) {
                session.removeAttribute(SupportAccess.SESSION_KEY);
            }
            return false;
        }
        AuthenticatedUser me = (AuthenticatedUser) auth.getPrincipal();
        try {
            tenants.callAs(visit.companyId(), () -> {
                List<GrantedAuthority> read = jdbc.sql("select code from permission order by code").query(String.class).list()
                        .stream().filter(code -> !TenantContext.PLATFORM_PERMISSIONS.contains(code))
                        .<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(me, null, read));
                SecurityContextHolder.setContext(context);
                SupportAccess.markCurrent(request, visit);
                if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
                    support.log(visit, me.email(), request.getMethod(), request.getRequestURI().substring(request.getContextPath().length()));
                }
                try {
                    chain.doFilter(request, response);
                } catch (IOException | ServletException e) {
                    throw new Wrapped(e);
                }
                return null;
            });
        } catch (Wrapped w) {
            if (w.getCause() instanceof IOException io) {
                throw io;
            }
            throw (ServletException) w.getCause();
        }
        return true;
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
        refresh(request);
        chain.doFilter(request, response);
    }

    private void refresh(HttpServletRequest request) {
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
