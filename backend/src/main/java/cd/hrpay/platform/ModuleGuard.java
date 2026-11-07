package cd.hrpay.platform;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.server.ResponseStatusException;

/** Refuses changes in a module the company does not have, or while the licence is locked. */
@Configuration
public class ModuleGuard implements WebMvcConfigurer, HandlerInterceptor {

    static final Map<String, String> NAMES = Map.of("ATTENDANCE", "Attendance", "PAYROLL", "Payroll",
            "EVALUATIONS", "Evaluations");

    private final Entitlements entitlements;

    public ModuleGuard(Entitlements entitlements) {
        this.entitlements = entitlements;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || isRead(request.getMethod())) {
            return true;
        }
        RequiresModule module = method.getBeanType().getAnnotation(RequiresModule.class);
        // The employer's details and logo live beside payments but belong to every company.
        if (module == null || request.getRequestURI().startsWith(request.getContextPath() + "/api/company")) {
            return true;
        }
        Entitlements.Current current = entitlements.current();
        if (current == null) {
            return true; // public endpoints (the kiosk) check their own company
        }
        String name = NAMES.getOrDefault(module.value(), module.value());
        if (!current.has(module.value())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your plan does not include " + name + ". Ask your provider to add it.");
        }
        if (current.locked()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The licence has ended: changes in " + name + " are stopped until the renewal is installed.");
        }
        return true;
    }

    private static boolean isRead(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }
}
