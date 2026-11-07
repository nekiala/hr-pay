package cd.hrpay.security;

import cd.hrpay.user.AppUser;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

final class Authorities {

    private Authorities() {}

    /** Each permission code becomes one authority, so checks read hasAuthority('user.create'). */
    static List<GrantedAuthority> of(AppUser user) {
        return user.permissionCodes().stream().<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
    }

    static UsernamePasswordAuthenticationToken token(AppUser user) {
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail(),
                user.getFirstName() + " " + user.getLastName());
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, of(user));
    }
}
