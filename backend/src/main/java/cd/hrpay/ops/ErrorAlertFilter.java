package cd.hrpay.ops;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tells the administrators about errors nobody handled (the ones that end as "500"). Expected problems (a rule
 * broken, a missing permission, a bad form) are answered by ApiExceptionHandler and never reach this filter.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ErrorAlertFilter extends OncePerRequestFilter {

    private final SystemAlerts alerts;

    public ErrorAlertFilter(SystemAlerts alerts) {
        this.alerts = alerts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            Throwable cause = e instanceof ServletException && e.getCause() != null ? e.getCause() : e;
            alerts.raise("ERROR", fingerprint(cause),
                    "Error on " + request.getMethod() + " " + request.getRequestURI() + ": " + cause.getClass().getSimpleName()
                            + (cause.getMessage() == null ? "" : ": " + cause.getMessage()),
                    trace(cause));
            throw e;
        }
    }

    /** The exception type and the first line of our own code it came through, so one bug is one alert. */
    static String fingerprint(Throwable e) {
        for (StackTraceElement f : e.getStackTrace()) {
            if (f.getClassName().startsWith("cd.hrpay.")) {
                return e.getClass().getName() + "@" + f.getClassName() + ":" + f.getLineNumber();
            }
        }
        return e.getClass().getName();
    }

    static String trace(Throwable e) {
        StringWriter out = new StringWriter();
        e.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
