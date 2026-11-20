package in.apnidukan.kirana.service;

import in.apnidukan.common.config.KiranaSwitch;
import in.apnidukan.common.context.ShopChangeListener;
import in.apnidukan.common.files.FileRefs;
import in.apnidukan.kirana.repo.KiranaSettingsStore;
import in.apnidukan.kirana.repo.OnlineStore;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * What the kirana module does when the shops module changes a shop (in the same transaction). Only a hand-over matters
 * here: the UPI ID is the owner's who set it (his bank account), so when he leaves the shop (handed over, to a helper or
 * to someone new) it is forgotten and the walk-in QR stops paying him; the new owner sets his own. The FSSAI licence he
 * added is his too (issued to him as the food business operator): forgotten as well, so online orders stop until the
 * new owner adds his own. Bills, stock, the khata book, orders and earnings stay with the shop. Salons are never
 * kiranas: nothing happens.
 */
@Component
class KiranaShopChanges implements ShopChangeListener {

    private final KiranaSettingsStore settings;
    private final OnlineStore online;
    private final FileRefs files;
    private final Clock clock;
    private final KiranaSwitch kirana;

    KiranaShopChanges(KiranaSettingsStore settings, OnlineStore online, FileRefs files, Clock clock,
                      KiranaSwitch kirana) {
        this.kirana = kirana;
        this.settings = settings;
        this.online = online;
        this.files = files;
        this.clock = clock;
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
        // changes nothing (access is read from the membership on every call). No row (a salon): nothing to do.
        // Kirana switched off: nothing either (no kirana can exist then)
        if (kirana.off()) {
            return;
        }
        settings.clearUpiIdSetBy(shopId, userId);
        online.forgetFssaiSetBy(shopId, userId, KiranaChecks.now(clock)).forEach(files::release);
    }

    @Override
    public void closed(UUID shopId) {
        // a closed shop's rows stay, like every other closed shop's
    }
}
