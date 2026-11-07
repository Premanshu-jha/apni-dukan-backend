package in.apnidukan.common.context;

import java.util.UUID;

/**
 * Implemented by the shops module, for sign-in: a test account (made in OTP test mode) was claimed by its number's
 * real holder with an SMS code, so nothing the testers did in a shop may stay with it.
 */
public interface TestAccountShops {

    /**
     * Ends the account's shop membership, if it has one: a helper or barber leaves as if removed (walk-in access, a
     * barber's sign-in, the salon's messages and every session go), and a shop it owns is closed (the testers' shop:
     * the holder never registered it). Runs inside the caller's transaction; takes the shop's row before anything of
     * the person's.
     */
    void endFor(UUID userId);
}
