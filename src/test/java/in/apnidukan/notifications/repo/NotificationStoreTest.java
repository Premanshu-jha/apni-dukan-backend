package in.apnidukan.notifications.repo;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** SMS variables survive the trip through their text column, whatever characters they hold. */
class NotificationStoreTest {

    @Test
    void smsVariablesRoundTrip() {
        Map<String, String> vars = Map.of("SALON", "Sharma & Sons = Hair", "WHEN", "Tue 10 Nov, 6:30 PM",
                "REASON", "बिजली नहीं है ✂️");
        String stored = NotificationStore.encode(vars);
        assertThat(NotificationStore.decode(stored)).isEqualTo(vars);
        assertThat(NotificationStore.decode(null)).isEmpty();
    }
}
