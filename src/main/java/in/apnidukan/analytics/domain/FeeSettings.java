package in.apnidukan.analytics.domain;

import java.time.Instant;

/**
 * Google's fee rates the Analytics page estimates Google's part with (Google reports its fee per payment only in the
 * Play Console), in basis points (1500 = 15%): on Google Play's own billing, and on Razorpay picked in the Play Store
 * app (user choice billing). {@code version} goes up with every save (two super admins saving at once: the second is
 * told someone changed them).
 */
public record FeeSettings(int playFeeBps, int userChoiceFeeBps, int version, Instant changedAt, String changedByName) {
}
