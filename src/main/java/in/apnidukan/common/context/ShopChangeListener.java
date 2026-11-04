package in.apnidukan.common.context;

import java.util.UUID;

/**
 * Told by the shops module when a shop's location changes (address, area, city, pincode or map pin), inside the
 * same transaction as the change. The salon module uses it to rewrite pending booking reminders, which carry the
 * address and the map link.
 */
public interface ShopChangeListener {

    void locationChanged(UUID shopId);
}
