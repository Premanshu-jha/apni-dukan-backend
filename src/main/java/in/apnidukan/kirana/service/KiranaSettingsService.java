package in.apnidukan.kirana.service;

import in.apnidukan.common.context.EditUnlocks;
import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.kirana.api.KiranaResponses.Settings;
import in.apnidukan.kirana.domain.UpiIds;
import in.apnidukan.kirana.repo.KiranaSettingsStore;
import in.apnidukan.kirana.service.KiranaAccess.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * The kirana's counter settings: its UPI ID, which the walk-in QR shows (customers pay the shop directly) and to which
 * Clozest's payouts will go once online orders start. (Registration's "has it set its UPI ID?" is KiranaFactsService.)
 */
@Service
public class KiranaSettingsService {

    private final KiranaAccess access;
    private final KiranaSettingsStore settings;
    private final ShopDirectory shops;
    private final EditUnlocks editUnlocks;
    private final Clock clock;

    public KiranaSettingsService(KiranaAccess access, KiranaSettingsStore settings, ShopDirectory shops,
                                 EditUnlocks editUnlocks, Clock clock) {
        this.access = access;
        this.settings = settings;
        this.shops = shops;
        this.editUnlocks = editUnlocks;
        this.clock = clock;
    }

    /** The shop's name (the QR's payee) and UPI ID, for the owner and helpers (the helper shows the QR at the counter). */
    @Transactional(readOnly = true)
    public Settings get(UUID userId) {
        Caller c = access.caller(userId);
        KiranaSettingsStore.Settings s = settings.find(c.shopId());
        return new Settings(shopName(c.shopId()), s.upiId(), s.upiUpdatedAt());
    }

    /**
     * Sets the shop's UPI ID (owner only: 403 OWNER_ONLY). Stored without spaces, lower case; 400 naming upiId if it
     * isn't in the form name@bank. Needed before Submit for visit. Once the shop is verified, only within minutes of
     * the owner entering the code sent to his phone (403 EDIT_LOCKED), like every other checked detail: the walk-in
     * QR pays into it, so a phone left signed in mustn't be able to change it. The field team sees it and when it was
     * last set on the shop's page.
     */
    @Transactional
    public Settings setUpiId(UUID userId, String typed) {
        Caller c = access.owner(userId);
        String upi = UpiIds.clean(typed);
        if (!UpiIds.valid(upi)) {
            throw KiranaChecks.invalid("upiId", "must look like name@bank, e.g. sharmastore@okhdfcbank");
        }
        Instant now = KiranaChecks.now(clock);
        KiranaSettingsStore.Settings s = settings.find(c.shopId());
        if (!upi.equals(s.upiId())) {
            if (shops.needsEditCode(c.shopId()) && editUnlocks.unlockedUntil(userId).isEmpty()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "EDIT_LOCKED",
                        "Your shop is verified: confirm it's you with the code we send to your phone first.");
            }
            settings.setUpiId(c.shopId(), upi, userId, now);
            s = new KiranaSettingsStore.Settings(upi, now);
        }
        return new Settings(shopName(c.shopId()), s.upiId(), s.upiUpdatedAt());
    }

    private String shopName(UUID shopId) {
        return shops.find(shopId).map(ShopDirectory.ShopCard::name)
                .orElseThrow(() -> ApiException.notFound("Shop"));
    }
}
