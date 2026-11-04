package in.apnidukan.common.context;

import java.util.UUID;

/**
 * Told by the auth module, inside its transaction, when every session of a person ends at once: "sign out
 * everywhere", or the account moved to a new phone number (recovery). A lost or shared device must stop getting
 * this person's messages too, not only stop being signed in.
 */
public interface SignOutEverywhereListener {
    void signedOutEverywhere(UUID userId);
}
