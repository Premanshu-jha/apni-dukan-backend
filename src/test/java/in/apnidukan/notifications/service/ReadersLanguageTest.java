package in.apnidukan.notifications.service;

import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.notify.Notifications.Notice;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

/**
 * A message in its reader's language: title and body together in that language, or both in English, never one of
 * each; the English is compared with en.properties only when strict (the tests), never in production.
 */
class ReadersLanguageTest {

    private static final UUID READER = UUID.randomUUID();

    private static NotificationService service(boolean strict) {
        UserDirectory users = Mockito.mock(UserDirectory.class);
        Mockito.when(users.languages(any())).thenReturn(Map.of(READER, "hi"));
        return new NotificationService(null, null, null, null, null, users, "", strict);
    }

    private static Notice notice(String title, String body, Map<String, ?> values) {
        return new Notice(READER, "BOOKING_CONFIRMED", null, Instant.now(), title, body, null, Map.of())
                .in("BOOKING_CONFIRMED", values);
    }

    @Test
    void aBodyThatCantBeWrittenInTheLanguageKeepsItsTitleInEnglishToo() {
        // The Hindi title needs only {salon}; the Hindi body needs values this notice lacks: both stay English
        Notice n = notice("Booked at Sharma Hair", "Sharma Hair, some words.", Map.of("salon", "Sharma Hair"));
        Notice out = service(false).inReadersLanguage(List.of(n)).get(0);
        assertThat(out.title()).isEqualTo("Booked at Sharma Hair");
        assertThat(out.body()).isEqualTo("Sharma Hair, some words.");
    }

    @Test
    void withEveryValueBothAreInTheLanguage() {
        Notice n = notice("Booked at S", "S, Fri 20 Nov, 6:00 PM, Haircut. Booking AB12. Pay at the salon.",
                Map.of("salon", "S", "when", new in.apnidukan.common.notify.Notifications.DayTime(
                        Instant.parse("2026-11-20T12:30:00Z")), "services", "Haircut", "booking", "AB12"));
        Notice out = service(false).inReadersLanguage(List.of(n)).get(0);
        assertThat(out.title()).doesNotContain("Booked at");
        assertThat(out.body()).contains("बुकिंग AB12");
    }

    @Test
    void theEnglishIsCheckedAgainstTheTextsOnlyWhenStrict() {
        Notice differs = notice("Not what en.properties says", "Nor this.", Map.of("salon", "S"));
        // Production: no check, the message goes as it is
        assertThat(service(false).inReadersLanguage(List.of(differs))).hasSize(1);
        // The tests: a difference fails
        assertThatThrownBy(() -> service(true).inReadersLanguage(List.of(differs)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BOOKING_CONFIRMED");
    }
}
