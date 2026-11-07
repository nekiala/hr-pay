package cd.hrpay.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * "Sign in with Google" and "Sign in with Microsoft", each turned on by its client id. Microsoft uses the
 * multi-organisation endpoint: any work account can reach the app, and each company accepts only its own tenant.
 */
@Configuration
class OidcClients {

    @Bean
    @ConditionalOnExpression("'${app.oidc.google.client-id:}' != '' or '${app.oidc.microsoft.client-id:}' != ''")
    ClientRegistrationRepository clientRegistrationRepository(
            @Value("${app.oidc.google.client-id:}") String googleId, @Value("${app.oidc.google.client-secret:}") String googleSecret,
            @Value("${app.oidc.microsoft.client-id:}") String microsoftId,
            @Value("${app.oidc.microsoft.client-secret:}") String microsoftSecret) {
        List<ClientRegistration> clients = new ArrayList<>();
        if (!googleId.isBlank()) {
            clients.add(CommonOAuth2Provider.GOOGLE.getBuilder("google").clientId(googleId).clientSecret(googleSecret).build());
        }
        if (!microsoftId.isBlank()) {
            String base = "https://login.microsoftonline.com/organizations";
            clients.add(ClientRegistration.withRegistrationId("microsoft")
                    .clientId(microsoftId).clientSecret(microsoftSecret)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .scope("openid", "profile", "email")
                    .authorizationUri(base + "/oauth2/v2.0/authorize")
                    .tokenUri(base + "/oauth2/v2.0/token")
                    .jwkSetUri("https://login.microsoftonline.com/common/discovery/v2.0/keys")
                    // No issuer here: it names each customer's tenant, checked against the company's own.
                    .userNameAttributeName("sub")
                    .clientName("Microsoft")
                    .build());
        }
        return new InMemoryClientRegistrationRepository(clients);
    }
}
