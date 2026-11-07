package cd.hrpay.security;

import cd.hrpay.audit.AuditService;
import cd.hrpay.tenancy.Tenants;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepts a Google sign-in only for a verified email address that an administrator already registered,
 * on an active account. Google never creates accounts.
 */
class GoogleOidcUserService extends OidcUserService {

    private final UserRepository users;
    private final AuditService audit;
    private final Tenants tenants;

    GoogleOidcUserService(UserRepository users, AuditService audit, Tenants tenants) {
        this.users = users;
        this.audit = audit;
        this.tenants = tenants;
    }

    @Override
    @Transactional
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser google = super.loadUser(request);
        String email = google.getEmail();
        if (email == null || !Boolean.TRUE.equals(google.getEmailVerified())) {
            throw reject("email_not_verified", email);
        }
        Long company = tenants.companyOfEmail(email);
        if (company == null) {
            throw tenants.callAsPlatform(() -> reject("not_registered", email));
        }
        return tenants.callAs(company, () -> {
            AppUser user = users.findByEmailIgnoreCase(email).orElseThrow(() -> reject("not_registered", email));
            if (!user.isActive()) {
                throw reject("disabled", email);
            }
            user.setLastLoginAt(OffsetDateTime.now());
            users.save(user);
            audit.recordAs(user.getEmail(), "auth.login", "user", user.getId(), Map.of("method", "google"));
            return new DefaultOidcUser(Authorities.of(user), google.getIdToken(), google.getUserInfo(), "email");
        });
    }

    private OAuth2AuthenticationException reject(String reason, String email) {
        audit.recordAs(email, "auth.login_failed", "user", null, Map.of("method", "google", "reason", reason));
        return new OAuth2AuthenticationException(new OAuth2Error(reason));
    }
}
