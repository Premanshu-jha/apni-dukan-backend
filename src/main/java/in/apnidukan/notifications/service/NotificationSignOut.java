package in.apnidukan.notifications.service;

import in.apnidukan.common.context.SignOutEverywhereListener;
import in.apnidukan.notifications.repo.PushSubscriptionStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Signed out everywhere: forget every browser that gets this person's notifications. Each device registers again
 * when the person signs in on it (the app sends its subscription after every sign-in).
 */
@Component
class NotificationSignOut implements SignOutEverywhereListener {

    private final PushSubscriptionStore devices;

    NotificationSignOut(PushSubscriptionStore devices) {
        this.devices = devices;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void signedOutEverywhere(UUID userId) {
        devices.deleteAll(userId);
    }
}
