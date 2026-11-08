package cd.hrpay.security;

import cd.hrpay.user.AppUser;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public final class Authorities {

    private Authorities() {}

    /** Each permission code becomes one authority, so checks read hasAuthority('user.create'). */
    static List<GrantedAuthority> of(AppUser user) {
        boolean provider = cd.hrpay.tenancy.TenantContext.isProvider(user.getCompanyId());
        return user.permissionCodes().stream()
                .filter(code -> provider || !cd.hrpay.tenancy.TenantContext.PLATFORM_PERMISSIONS.contains(code))
                .<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
    }

    public static UsernamePasswordAuthenticationToken token(AppUser user) {
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail(),
                user.getFirstName() + " " + user.getLastName(), user.getCompanyId());
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, of(user));
    }
}
