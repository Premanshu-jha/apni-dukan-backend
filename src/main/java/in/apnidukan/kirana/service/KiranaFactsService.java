package in.apnidukan.kirana.service;

import in.apnidukan.common.context.KiranaFacts;
import in.apnidukan.kirana.repo.KiranaSettingsStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * What registration and the field team's page ask about a kirana's counter setup. Depends on the settings store only:
 * the shops module (ShopService) needs it, and the kirana's access check needs the shops module, so anything more here
 * would make a loop Spring can't build.
 */
@Service
public class KiranaFactsService implements KiranaFacts {

    private final KiranaSettingsStore settings;
    private final in.apnidukan.kirana.repo.ProductStore products;
    private final DataKart gs1;
    private final java.time.Clock clock;

    public KiranaFactsService(KiranaSettingsStore settings, in.apnidukan.kirana.repo.ProductStore products,
                              DataKart gs1, java.time.Clock clock) {
        this.gs1 = gs1;
        this.settings = settings;
        this.products = products;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void shareCheckedPhotos(UUID shopId) {
        products.shareCheckedPhotos(shopId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasUpiId(UUID shopId) {
        return settings.hasUpiId(shopId);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Map<String, String> registrationGaps(UUID shopId) {
        KiranaSettingsStore.Settings s = settings.find(shopId);
        java.util.Map<String, String> gaps = new java.util.LinkedHashMap<>();
        if (s.upiId() == null) {
            gaps.put("upiId", "add the shop's UPI ID");
        }
        if (s.sellsFood() == null) {
            gaps.put("sellsFood", "say whether the shop sells any food items");
        } else if (s.sellsFood()) {
            String gap = KiranaSettingsStore.fssaiGap(settings.fssai(shopId), KiranaChecks.today(clock.instant()));
            if (gap != null) {
                gaps.put("fssai", gap);
            }
        }
        return gaps;
    }

    @Override
    @Transactional(readOnly = true)
    public Fssai fssai(UUID shopId) {
        return settings.fssai(shopId);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductGaps productGaps(UUID shopId) {
        long[] counts = products.countedTotals(shopId);
        in.apnidukan.kirana.repo.ProductStore.PhotoTotals photos = products.photoTotals(shopId, gs1.configured());
        // A photo being checked, or a picture GS1 is still looking for: nothing for the owner to do yet
        return new ProductGaps(counts[0], counts[1], photos.missing(), photos.checking() + photos.finding(),
                counts[1] + photos.missing() == 0 ? java.util.List.of() : products.notReady(shopId, 10));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasCounterData(UUID shopId) {
        return settings.hasCounterData(shopId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean usesFile(UUID fileId) {
        return settings.fssaiUsesFile(fileId);
    }

    @Override
    @Transactional(readOnly = true)
    public Boolean sellsFood(UUID shopId) {
        return settings.find(shopId).sellsFood();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UpiId> upiId(UUID shopId) {
        KiranaSettingsStore.Settings s = settings.find(shopId);
        return s.upiId() == null ? Optional.empty() : Optional.of(new UpiId(s.upiId(), s.upiUpdatedAt()));
    }
}
