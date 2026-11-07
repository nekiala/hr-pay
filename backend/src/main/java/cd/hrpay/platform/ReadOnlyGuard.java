package cd.hrpay.platform;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.server.ResponseStatusException;

/**
 * Refuses every change while a company is suspended (an unpaid invoice, or the provider's decision) and during the
 * provider's support visits. Reading and exporting keep working, and so do signing in, changing one's password and,
 * for a suspended company, its Billing page.
 */
@Configuration
public class ReadOnlyGuard implements WebMvcConfigurer, HandlerInterceptor {

    private final Entitlements entitlements;

    public ReadOnlyGuard(Entitlements entitlements) {
        this.entitlements = entitlements;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        if (!(handler instanceof HandlerMethod) || "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (SupportAccess.current() != null) {
            if (path.equals("/api/support/end") || path.equals("/api/auth/logout")) {
                return true;
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Support access is read-only: nothing can be changed during a support visit.");
        }
        if (path.startsWith("/api/auth/") || path.startsWith("/api/billing") || path.equals("/api/privacy/accept")) {
            return true;
        }
        Entitlements.Current current = entitlements.current();
        if (current != null && current.isReadOnly()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, current.readOnly());
        }
        return true;
    }
}
