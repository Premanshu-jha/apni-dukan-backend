package in.apnidukan.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Runs once per request: if there is a valid "Authorization: Bearer ..." token, marks the request as signed in.
 * A bad or expired token doesn't fail here; protected endpoints then answer 401 (see SecurityConfig).
 * Not a @Component on purpose, so Spring doesn't also register it as a plain servlet filter.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    static final String AUTH_ERROR = "auth.error";

    private final JwtService jwt;
    private final SessionRevocations revocations;

    public JwtAuthFilter(JwtService jwt, SessionRevocations revocations) {
        this.jwt = jwt;
        this.revocations = revocations;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                JwtService.ParsedToken parsed = jwt.parseToken(header.substring(7).trim());
                AuthPrincipal who = parsed.who();
                if (revocations.isRevoked(who.userId(), parsed.generation())) {
                    request.setAttribute(AUTH_ERROR, "SIGNED_OUT");
                    chain.doFilter(request, response);
                    return;
                }
                var authorities = who.admin()
                        ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                        : List.<SimpleGrantedAuthority>of();
                SecurityContextHolder.getContext()
                        .setAuthentication(new UsernamePasswordAuthenticationToken(who, null, authorities));
            } catch (JwtService.TokenExpiredException e) {
                request.setAttribute(AUTH_ERROR, "TOKEN_EXPIRED");
            } catch (JwtService.InvalidTokenException e) {
                request.setAttribute(AUTH_ERROR, "INVALID_TOKEN");
            }
        }
        chain.doFilter(request, response);
    }
}
