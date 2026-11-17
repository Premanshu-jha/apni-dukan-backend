package in.apnidukan.kirana.service;

import in.apnidukan.common.context.ShopChangeListener;
import in.apnidukan.kirana.repo.KiranaSettingsStore;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * What the kirana module does when the shops module changes a shop (in the same transaction). Only a hand-over matters
 * here: the UPI ID is the owner's who set it (his bank account), so when he leaves the shop (handed over, to a helper or
 * to someone new) it is forgotten and the walk-in QR stops paying him; the new owner sets his own. Bills, stock and the khata book stay with the shop. Salons are never kiranas: nothing happens.
 */
@Component
class KiranaShopChanges implements ShopChangeListener {

    private final KiranaSettingsStore settings;

    KiranaShopChanges(KiranaSettingsStore settings) {
        this.settings = settings;
    }

    @Override
    public void becameOwner(UUID shopId, UUID userId) {
        // the old owner's leaving (memberRemoved, right after) clears his UPI ID
    }

    @Override
    public void locationChanged(UUID shopId) {
        // nothing at the counter depends on the address
    }

    @Override
    public void suspended(UUID shopId) {
        // the shop's own counter keeps working while it is off the customer app
    }

    @Override
    public void memberRemoved(UUID shopId, UUID userId) {
        // Only an owner sets the UPI ID: the one who set it leaving means the shop was handed over. A helper leaving
        // changes nothing (access is read from the membership on every call). No row (a salon): nothing to do
        settings.clearUpiIdSetBy(shopId, userId);
    }

    @Override
    public void closed(UUID shopId) {
        // a closed shop's rows stay, like every other closed shop's
    }
}
