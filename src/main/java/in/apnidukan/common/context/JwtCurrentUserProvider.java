package in.apnidukan.common.context;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.security.AuthPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Reads the signed-in person from Spring Security, which JwtAuthFilter filled from the access token. */
@Component
public class JwtCurrentUserProvider implements CurrentUserProvider {

    @Override
    public CurrentUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthPrincipal who)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "NOT_SIGNED_IN", "Sign in first.");
        }
        return new CurrentUser(who.userId(), who.shopId());
    }
}
