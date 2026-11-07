package cd.hrpay.integration;

import cd.hrpay.tenancy.Tenants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Serializable;
import java.security.Principal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Signs in a request of the public API by its access key ({@code Authorization: Bearer hrp_...} or
 * {@code X-API-Key}), for the key's company and with the key's permissions only. No session is made.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    /** Who an API request acts as; its name ("api:Sage export") is what the audit log shows. */
    public record ApiPrincipal(long keyId, String name) implements Principal, Serializable {
        @Override
        public String getName() {
            return "api:" + name;
        }
    }

    static final int PER_MINUTE = 600;

    private final ApiKeys keys;
    private final Tenants tenants;
    private final Map<Long, Window> windows = new ConcurrentHashMap<>();

    private record Window(long minute, AtomicInteger count) {}

    public ApiKeyFilter(ApiKeys keys, Tenants tenants) {
        this.keys = keys;
        this.tenants = tenants;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().endsWith("/api/v1/openapi.json");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader("X-API-Key");
        String auth = request.getHeader("Authorization");
        if (token == null && auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = auth.substring(7).trim();
        }
        Optional<ApiKeys.Caller> caller = keys.authenticate(token, request.getRemoteAddr());
        if (caller.isEmpty()) {
            problem(response, 401, "A valid API key is needed: send it as Authorization: Bearer hrp_...");
            return;
        }
        ApiKeys.Caller c = caller.get();
        long minute = System.currentTimeMillis() / 60_000;
        Window w = windows.compute(c.keyId(), (k, old) -> old == null || old.minute() != minute ? new Window(minute, new AtomicInteger()) : old);
        if (w.count().incrementAndGet() > PER_MINUTE) {
            response.setHeader("Retry-After", "60");
            problem(response, 429, "Too many requests with this key: at most " + PER_MINUTE + " a minute.");
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new ApiPrincipal(c.keyId(), c.name()), null,
                c.permissions().stream().map(SimpleGrantedAuthority::new).toList()));
        SecurityContextHolder.setContext(context);
        try {
            tenants.callAs(c.companyId(), () -> {
                try {
                    chain.doFilter(request, response);
                } catch (IOException | ServletException e) {
                    throw new Wrapped(e);
                }
                return null;
            });
        } catch (Wrapped e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw (ServletException) e.getCause();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static final class Wrapped extends RuntimeException {
        Wrapped(Exception cause) {
            super(cause);
        }
    }

    private static void problem(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"status\":" + status + ",\"detail\":\"" + detail.replace("\"", "'") + "\"}");
    }
}
