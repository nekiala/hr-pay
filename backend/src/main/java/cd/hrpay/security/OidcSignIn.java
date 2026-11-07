package cd.hrpay.security;

import cd.hrpay.audit.AuditService;
import cd.hrpay.tenancy.Tenants;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sign-in with Google or Microsoft 365. Neither creates accounts: the email must belong to an active account an
 * administrator registered. Google must say the email is verified; Microsoft must sign the person in from the
 * company's own Microsoft tenant, set on the Company page, because a Microsoft email claim alone proves nothing.
 * After the provider, the person goes through the same second step as with a password.
 */
class OidcSignIn extends OidcUserService implements AuthenticationSuccessHandler {

    private final UserRepository users;
    private final AuditService audit;
    private final Tenants tenants;
    private final JdbcClient jdbc;
    private final SignInSteps steps;
    private final TransactionTemplate tx;

    OidcSignIn(UserRepository users, AuditService audit, Tenants tenants, JdbcClient jdbc, SignInSteps steps,
               TransactionTemplate tx) {
        this.users = users;
        this.audit = audit;
        this.tenants = tenants;
        this.jdbc = jdbc;
        this.steps = steps;
        this.tx = tx;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser oidc = super.loadUser(request);
        String provider = request.getClientRegistration().getRegistrationId();
        String email = email(provider, oidc);
        if (email == null) {
            throw reject(provider, "email_not_verified", oidc.getEmail());
        }
        Long company = tenants.companyOfEmail(email);
        if (company == null) {
            throw tenants.callAsPlatform(() -> reject(provider, "not_registered", email));
        }
        return tenants.callAs(company, () -> {
            if ("microsoft".equals(provider)) {
                String expected = jdbc.sql("select microsoft_tenant from company where id = ?").param(company)
                        .query(String.class).optional().orElse(null);
                String tid = oidc.getClaimAsString("tid");
                String issuer = oidc.getIssuer() == null ? null : oidc.getIssuer().toString();
                if (expected == null || tid == null || !expected.equalsIgnoreCase(tid)
                        || !("https://login.microsoftonline.com/" + tid + "/v2.0").equals(issuer)) {
                    throw reject(provider, "wrong_tenant", email);
                }
            }
            AppUser user = users.findByEmailIgnoreCase(email).orElseThrow(() -> reject(provider, "not_registered", email));
            if (!user.isActive()) {
                throw reject(provider, "disabled", email);
            }
            // The email the account was found by, whatever claim it came from.
            Map<String, Object> claims = new java.util.HashMap<>(oidc.getUserInfo() == null ? Map.of() : oidc.getUserInfo().getClaims());
            claims.put("sub", oidc.getSubject());
            claims.put("hrpay_email", user.getEmail());
            return new DefaultOidcUser(Authorities.of(user), oidc.getIdToken(),
                    new org.springframework.security.oauth2.core.oidc.OidcUserInfo(claims), "hrpay_email");
        });
    }

    /** The provider's email for the person, only when the provider vouches for it. */
    private static String email(String provider, OidcUser oidc) {
        if ("microsoft".equals(provider)) {
            // Work accounts: the sign-in name, an address in one of the tenant's verified domains.
            String name = oidc.getClaimAsString("preferred_username");
            return name != null && name.contains("@") ? name.trim().toLowerCase() : null;
        }
        return oidc.getEmail() != null && Boolean.TRUE.equals(oidc.getEmailVerified()) ? oidc.getEmail().trim().toLowerCase() : null;
    }

    /** The provider said yes: the person now goes through the second step, or is signed in. */
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OidcUser oidc = (OidcUser) authentication.getPrincipal();
        String provider = authentication instanceof OAuth2AuthenticationToken t ? t.getAuthorizedClientRegistrationId() : "oidc";
        String email = oidc.getAttribute("hrpay_email");
        Long company = tenants.companyOfEmail(email);
        Optional<TwoStep.Pending> pending = tenants.callAs(company, () -> tx.execute(s -> {
            AppUser user = users.findByEmailIgnoreCase(email).orElseThrow();
            return steps.afterFirstFactor(user, request, response, provider);
        }));
        response.sendRedirect(pending.isPresent() ? "/login?step=two" : "/");
    }

    private OAuth2AuthenticationException reject(String provider, String reason, String email) {
        audit.recordAs(email, "auth.login_failed", "user", null, Map.of("method", provider, "reason", reason));
        return new OAuth2AuthenticationException(new OAuth2Error(reason));
    }
}
