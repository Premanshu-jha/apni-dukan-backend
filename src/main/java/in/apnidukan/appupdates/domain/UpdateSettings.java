package in.apnidukan.appupdates.domain;

import java.time.Instant;

/**
 * One row of app_update_settings.
 *
 * @param minVersionCode   phones with an older app see "Please update to continue"; null: none have to
 * @param storeVersionCode the newest app version live on the Play Store; phones with a newer one (in Google's review)
 *                         get no screen updates; null: no version is live yet, so no Play Store phone gets them
 * @param version          grows with every change: a save naming an older one is refused (two super admins at once)
 */
public record UpdateSettings(AppName app, Integer minVersionCode, Integer storeVersionCode, int version,
                             Instant changedAt, String changedByName) {
}
