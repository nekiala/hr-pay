package cd.hrpay.billing;

import cd.hrpay.platform.SupportAccess;
import cd.hrpay.security.CurrentUser;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Ends a support visit and brings the provider's administrator back to their own company. */
@RestController
public class SupportController {

    private final SupportAccess support;

    public SupportController(SupportAccess support) {
        this.support = support;
    }

    @PostMapping("/api/support/end")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void end(HttpSession session) {
        if (session.getAttribute(SupportAccess.SESSION_KEY) instanceof SupportAccess.Visit visit) {
            session.removeAttribute(SupportAccess.SESSION_KEY);
            support.end(visit, CurrentUser.email());
        }
    }
}
