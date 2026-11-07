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

    private final ShopMemberships memberships;

    /** Lazy: the shops module's services themselves use this provider (through files), so it is found when first used. */
    public JwtCurrentUserProvider(@org.springframework.context.annotation.Lazy ShopMemberships memberships) {
        this.memberships = memberships;
    }

    /**
     * The person acting for their shop, the shop and role read from the database (one query), not from the token: a
     * token can name a shop for up to 15 minutes after its owner closed it or handed it over (their sessions may not
     * have been ended, or Redis didn't take it), and must not upload to it or open its files then. 403 SHOP_REQUIRED
     * when the token names no shop, or a shop the person doesn't work in now (refresh the token); the role is the
     * database's.
     */
    @Override
    public CurrentUser requireShop() {
        CurrentUser token = get();
        ShopMemberships.Membership m = token.shopId() == null ? null
                : memberships.find(token.userId()).filter(x -> x.shopId().equals(token.shopId())).orElse(null);
        if (m == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SHOP_REQUIRED", "Register or join a shop first.");
        }
        return new CurrentUser(token.userId(), m.shopId(), m.role());
    }

    @Override
    public CurrentUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthPrincipal who)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "NOT_SIGNED_IN", "Sign in first.");
        }
        return new CurrentUser(who.userId(), who.shopId(), who.shopRole());
    }
}
