package in.apnidukan.common.context;

/**
 * Gives services the current user and shop. Services never read the shop from the request body.
 * Today: {@link DevHeaderCurrentUserProvider} (development only).
 * Milestone 2: a JWT-based implementation replaces it, with no change to the services.
 */
public interface CurrentUserProvider {

    CurrentUser get();
}
