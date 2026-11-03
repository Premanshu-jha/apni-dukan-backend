package in.apnidukan.notifications.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Notification titles, bodies and SMS variables are shortened safely. */
class NotificationTextTest {

    @Test
    void longTextIsShortenedWithoutBreakingAnEmoji() {
        assertThat(NotificationService.cut("abc", 5)).isEqualTo("abc");
        assertThat(NotificationService.cut("abcdef", 5)).isEqualTo("abcd…");
        // A smiley is two Java chars; cutting at 5 would leave half of it, so the cut moves before it
        assertThat(NotificationService.cut("abc\uD83D\uDE00xyz", 5)).isEqualTo("abc…");
    }

    @Test
    void smsVariablesAreCutWithoutAnEllipsis() {
        assertThat(NotificationService.cutPlain("Sharma Hair Studio and Spa, Kondapur", 30))
                .isEqualTo("Sharma Hair Studio and Spa, Ko");
        assertThat(NotificationService.cutPlain("x".repeat(29) + "\uD83D\uDE00", 30)).isEqualTo("x".repeat(29));
    }
}
