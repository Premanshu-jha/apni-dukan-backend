package in.apnidukan.analytics.api;

import in.apnidukan.analytics.service.ActivityMarks;
import in.apnidukan.common.security.AuthPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Marks a signed-in person as active today ({@link ActivityMarks}) for the analytics' active-user counts: runs after
 * the security chain, so the sign-in is known; only API calls; nothing is written in the request.
 */
@Component
public class ActivityFilter extends OncePerRequestFilter {

    public static final String CLIENT_HEADER = "X-Clozest-Client";

    private final ActivityMarks marks;

    public ActivityFilter(ActivityMarks marks) {
        this.marks = marks;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith("/api/v1/") || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof AuthPrincipal who) {
                // The header is the app's word: only someone on the team counts as the team's activity, only someone
                // in a salon as a salon's
                ActivityMarks.side(request.getHeader(CLIENT_HEADER), request.getHeader("Origin"))
                        .filter(side -> !"ADMIN".equals(side.audience()) || who.admin())
                        // A salon's side only for someone in a salon (a customer sending partner-app isn't one)
                        .filter(side -> !"PARTNER".equals(side.audience()) || who.shopId() != null)
                        .ifPresent(side -> marks.mark(who.userId(), side));
            }
        } catch (RuntimeException e) {
            // Counting must never stand in a request's way
        }
        chain.doFilter(request, response);
    }
}
