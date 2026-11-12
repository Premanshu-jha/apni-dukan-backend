package in.apnidukan.common.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Who may call what:
 * - sign-in endpoints (OTP, refresh, logout, recovery) are open, and so is the support inbox (POST
 *   /api/v1/helpdesk/inbound), which checks the Email Worker's signature itself;
 * - everything else needs a valid access token;
 * - /api/v1/admin/** additionally needs an admin (field team) account.
 * No server sessions and no cookies: every request carries its token, so CSRF protection isn't needed.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwt,
                                                   SessionRevocations revocations) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/otp/send",
                                "/api/v1/auth/otp/verify",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout",
                                "/api/v1/auth/recover/start",
                                "/api/v1/auth/recover/complete",
                                // Signed by the Email Worker (HMAC), checked by InboundEmailController
                                "/api/v1/helpdesk/inbound").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) -> {
                            Object reason = request.getAttribute(JwtAuthFilter.AUTH_ERROR);
                            if ("TOKEN_EXPIRED".equals(reason)) {
                                writeError(response, 401, "TOKEN_EXPIRED", "Your session has expired. Refresh it.");
                            } else if ("SIGNED_OUT".equals(reason)) {
                                writeError(response, 401, "SIGNED_OUT", "You were signed out. Sign in again.");
                            } else if ("INVALID_TOKEN".equals(reason)) {
                                writeError(response, 401, "INVALID_TOKEN", "Sign in again.");
                            } else {
                                writeError(response, 401, "NOT_SIGNED_IN", "Sign in first.");
                            }
                        })
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(response, 403, "FORBIDDEN", "You are not allowed to do this.")))
                .addFilterBefore(new JwtAuthFilter(jwt, revocations), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** Which browser addresses (the shop app) may call this API. From app.cors.allowed-origins, comma-separated. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") String origins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).filter(o -> !o.isEmpty()).toList());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // If-None-Match / ETag: the apps ask "changed since?" when they refresh (EtagConfig)
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "If-None-Match"));
        cors.setExposedHeaders(List.of("Retry-After", "ETag"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    /** Stops Spring Boot from creating its default "user" with a generated password: we only use phone OTP. */
    @Bean
    public UserDetailsService noPasswordUsers() {
        return username -> {
            throw new UsernameNotFoundException(username);
        };
    }

    private static void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"details\":{}}");
    }
}
