package com.tapestry.balancevault.security;

import java.util.Arrays;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.tapestry.balancevault.support.GlobalErrorHandler;

/**
 * Deny by default: only login, onboarding and the static SPA are public; every /api route needs a session and then
 * an explicit permission check in its controller (AccessGuard). State-changing calls need the session's CSRF token
 * in the X-CSRF-TOKEN header (returned by GET /api/auth/me).
 */
@Configuration
public class SecurityConfig {
    private static final String[] PUBLIC_POSTS = {"/api/auth/login", "/api/onboarding/lookup", "/api/onboarding/accept"};

    // No 'unsafe-inline', no remote origins. No upgrade-insecure-requests and no HSTS: the app is served over plain HTTP.
    private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; "
            + "frame-ancestors 'none'";

    /** The HTTP security chain. */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JdbcClient jdbc) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, PUBLIC_POSTS).permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                // Public posts have no session to bind a token to; they need JSON bodies (no form CSRF), and the
                // cross-site check in RequestContextFilter plus SameSite=Strict cookies cover them.
                .csrf(csrf -> csrf.ignoringRequestMatchers(PUBLIC_POSTS))
                // The session stores only a user id; SessionAuthFilter rebuilds the principal on each request.
                .securityContext(sc -> sc.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> GlobalErrorHandler.write(res, 401, "Sign in required", ex))
                        .accessDeniedHandler((req, res, ex) -> GlobalErrorHandler.write(res, 403, ex.getMessage(), ex)))
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .httpStrictTransportSecurity(HeadersConfigurer.HstsConfig::disable)
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .addFilterBefore(new SessionAuthFilter(jdbc), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    /** Replaces Boot's generated in-memory user: the only way in is POST /api/auth/login. */
    @Bean
    AuthenticationManager authenticationManager() {
        return authentication -> {
            throw new ProviderNotFoundException("BalanceVault signs users in through /api/auth/login only");
        };
    }

    /** Password encoder using standard BCrypt hashing. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** CORS configuration allowing trusted origins, standard banking API headers, and credentials. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        String allowed = System.getenv("CORS_ALLOWED_ORIGINS");
        if (allowed == null || allowed.isBlank()) {
            allowed = System.getenv("FRONTEND_URL");
        }
        if (allowed != null && !allowed.isBlank()) {
            List<String> origins = Arrays.stream(allowed.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            config.setAllowedOriginPatterns(origins);
        } else {
            config.setAllowedOriginPatterns(List.of("*"));
        }
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("X-CSRF-TOKEN", "Content-Type", "Authorization", "Accept", "Origin", "X-Requested-With"));
        config.setExposedHeaders(List.of("X-Request-Id", "X-CSRF-TOKEN"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
