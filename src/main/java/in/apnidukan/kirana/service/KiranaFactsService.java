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

    public KiranaFactsService(KiranaSettingsStore settings) {
        this.settings = settings;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasUpiId(UUID shopId) {
        return settings.hasUpiId(shopId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UpiId> upiId(UUID shopId) {
        KiranaSettingsStore.Settings s = settings.find(shopId);
        return s.upiId() == null ? Optional.empty() : Optional.of(new UpiId(s.upiId(), s.upiUpdatedAt()));
    }
}
