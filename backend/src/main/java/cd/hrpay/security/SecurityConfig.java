package cd.hrpay.security;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.AppProperties;
import cd.hrpay.tenancy.Tenants;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** The public API: access keys, no session, no CSRF (no browser cookie is involved). */
    @Bean
    @org.springframework.core.annotation.Order(1)
    SecurityFilterChain publicApiChain(HttpSecurity http, cd.hrpay.integration.ApiKeys keys, Tenants tenants) throws Exception {
        http
                .securityMatcher("/api/v1/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .securityContext(c -> c.securityContextRepository(new org.springframework.security.web.context.RequestAttributeSecurityContextRepository()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/openapi.json").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable())
                .addFilterBefore(new cd.hrpay.integration.ApiKeyFilter(keys, tenants), AuthorizationFilter.class);
        return http.build();
    }

    /**
     * The website's public tools (the salary calculator): anyone, from any site, without a session or a cookie, so
     * no CSRF token either. Each answer is limited per address by the controller.
     */
    @Bean
    @org.springframework.core.annotation.Order(3)
    SecurityFilterChain websiteToolsChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/public/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .securityContext(c -> c.securityContextRepository(new org.springframework.security.web.context.RequestAttributeSecurityContextRepository()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable());
        return http.build();
    }

    /** Biometric time clocks: known by their serial number, they cannot sign in or carry a CSRF token. */
    @Bean
    @org.springframework.core.annotation.Order(2)
    SecurityFilterChain timeClockChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/iclock/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable());
        return http.build();
    }

    @Bean
    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain api(HttpSecurity http, UserRepository users, AuditService audit, Tenants tenants,
                            ObjectProvider<ClientRegistrationRepository> googleClients,
                            SecurityContextRepository contextRepository, cd.hrpay.platform.SupportAccess support,
                            org.springframework.jdbc.core.simple.JdbcClient jdbc, SignInSteps steps,
                            org.springframework.transaction.support.TransactionTemplate tx, UserSessions sessions,
                            cd.hrpay.firm.Firms firms) throws Exception {
        http
                .securityContext(c -> c.securityContextRepository(contextRepository))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .cors(cors -> {})
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/auth/csrf", "/api/auth/providers", "/api/auth/two-step", "/api/auth/two-step/**", "/api/auth/password-reset", "/api/auth/password-reset/confirm", "/api/kiosk",
                                "/api/status", "/api/version", "/api/legal/documents", "/api/legal/documents/*", "/api/trials").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").authenticated()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((req, res, a) -> res.setStatus(HttpStatus.NO_CONTENT.value())))
                .addFilterBefore(new UserRefreshFilter(users, tenants, support, jdbc, sessions, firms), AuthorizationFilter.class);

        if (googleClients.getIfAvailable() != null) {
            OidcSignIn oidc = new OidcSignIn(users, audit, tenants, jdbc, steps, tx);
            http.oauth2Login(o -> o
                    .userInfoEndpoint(u -> u.oidcUserService(oidc))
                    .successHandler(oidc)
                    .failureHandler((req, res, ex) -> res.sendRedirect("/login?error=" + (
                            ex instanceof org.springframework.security.oauth2.core.OAuth2AuthenticationException o2
                                    ? o2.getError().getErrorCode() : "provider"))));
        }
        return http.build();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(UserRepository users) {
        return email -> {
            AppUser user = users.findByEmailIgnoreCase(email)
                    .filter(u -> u.getPasswordHash() != null)
                    .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
            return User.withUsername(user.getEmail())
                    .password(user.getPasswordHash())
                    .disabled(!user.isActive())
                    .authorities(Authorities.of(user))
                    .build();
        };
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.corsOrigins());
        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // The website's tools answer any site, without cookies; registered first, so it wins over "/api/**".
        CorsConfiguration website = new CorsConfiguration();
        website.addAllowedOriginPattern("*");
        website.addAllowedMethod("GET");
        website.addAllowedMethod("POST");
        website.addAllowedHeader("Content-Type");
        website.setAllowCredentials(false);
        source.registerCorsConfiguration("/api/public/**", website);
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
