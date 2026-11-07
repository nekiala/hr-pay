package cd.hrpay.security;

import java.io.Serializable;
import java.security.Principal;

/** The principal stored in the security context for every signed-in user, whatever the login method. */
public record AuthenticatedUser(Long id, String email, String displayName) implements Principal, Serializable {
    @Override
    public String getName() {
        return email;
    }
}
