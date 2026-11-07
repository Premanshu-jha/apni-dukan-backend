package in.apnidukan.notifications.service;

import in.apnidukan.common.context.AccountDeletionListener;
import in.apnidukan.notifications.repo.NotificationStore;
import in.apnidukan.notifications.repo.PushSubscriptionStore;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Someone deleted their account: nothing more goes to them. Every message still waiting is cancelled, the messages
 * others got about them are emptied now, their own are emptied by AccountWipeJob (a busy salon owner can have lakhs:
 * not in this request), and their devices are forgotten. Runs after the other modules, which may cancel things on the way (a booking).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class NotificationAccountDeletion implements AccountDeletionListener {

    private final NotificationStore jobs;
    private final PushSubscriptionStore devices;
    private final InboxVersions versions;

    NotificationAccountDeletion(NotificationStore jobs, PushSubscriptionStore devices, InboxVersions versions) {
        this.versions = versions;
        this.jobs = jobs;
        this.devices = devices;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void accountDeleted(UUID userId, Instant now) {
        jobs.cancelPendingForUser(userId, now);
        versions.bumpAfterCommit(jobs.blankAbout(userId));   // others' lists changed
        jobs.queueWipe(userId, now);   // their own messages: emptied by AccountWipeJob within a minute
        devices.deleteAll(userId);
    }
}
