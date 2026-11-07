package in.apnidukan.notifications.service;

import in.apnidukan.notifications.domain.Channel;
import in.apnidukan.notifications.domain.NotificationJob;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationSendJobTest {

    @Test
    void sendsAreLimitedPerPushServiceHost() {
        assertThat(NotificationSendJob.hostOf("https://fcm.googleapis.com/fcm/send/abc")).isEqualTo("fcm.googleapis.com");
        assertThat(NotificationSendJob.hostOf("https://web.push.apple.com/QGd")).isEqualTo("web.push.apple.com");
        assertThat(NotificationSendJob.hostOf("not a url")).isEqualTo("push");
    }

    @Test
    void aClaimTakesOnlyWhatFitsBeforeTheDeadline() {
        assertThat(NotificationSendJob.claimSize(500, 8, 180_000, 1000)).isEqualTo(500);
        assertThat(NotificationSendJob.claimSize(500, 8, 180_000, 5000)).isEqualTo(230);
        assertThat(NotificationSendJob.claimSize(500, 8, 1000, 60_000)).isEqualTo(8);
    }

    @Test
    void salonMessagesAreForStaffBookingMessagesForCustomers() {
        assertThat(NotificationSendJob.isStaffMessage(job("SALON_NEW_BOOKING"))).isTrue();
        assertThat(NotificationSendJob.isStaffMessage(job("SHOP_APPROVED"))).isTrue();
        assertThat(NotificationSendJob.isStaffMessage(job("BOOKING_CANCELLED_BY_SALON"))).isFalse();
    }

    private static NotificationJob job(String type) {
        return new NotificationJob(UUID.randomUUID(), UUID.randomUUID(), Channel.SMS, type, "t", "b", null,
                Map.of(), 1);
    }
}
