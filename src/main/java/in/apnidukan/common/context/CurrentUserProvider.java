package in.apnidukan.common.context;

import in.apnidukan.common.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Gives services the current user and shop, taken from the sign-in token.
 * Services never read the user or shop from the request body.
 */
public interface CurrentUserProvider {

    /** The signed-in person; their shop may still be null (not registered yet). */
    CurrentUser get();

    /** The signed-in person acting for a shop. 403 SHOP_REQUIRED if they don't have one yet. */
    default CurrentUser requireShop() {
        CurrentUser user = get();
        if (user.shopId() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SHOP_REQUIRED", "Register or join a shop first.");
        }
        return user;
    }
}
