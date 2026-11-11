package in.apnidukan.shops.service;

import in.apnidukan.common.context.PhoneChangeListener;
import in.apnidukan.shops.repo.ShopHandoverStore;
import in.apnidukan.shops.repo.ShopInviteStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * A person moved their account to a new number: invitations and hand-over requests waiting for the old number are
 * cancelled, so the old number's next holder never sees them, and the shops that sent them never see the new number
 * (they can send them again). One from the person's own shop to the new number is cancelled too.
 */
@Component
class ShopPhoneChange implements PhoneChangeListener {

    private final ShopInviteStore invites;
    private final ShopHandoverStore handovers;
    private final Clock clock;

    ShopPhoneChange(ShopInviteStore invites, ShopHandoverStore handovers, Clock clock) {
        this.invites = invites;
        this.handovers = handovers;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void phoneChanged(UUID userId, String oldPhone, String newPhone) {
        invites.cancelForPhoneChange(userId, oldPhone, newPhone, clock.instant());
        handovers.cancelForPhoneChange(userId, oldPhone, newPhone, clock.instant());
    }
}
