package in.apnidukan.common.notify;

import in.apnidukan.common.notify.Notifications.AndList;
import in.apnidukan.common.notify.Notifications.Day;
import in.apnidukan.common.notify.Notifications.DayTime;
import in.apnidukan.common.notify.Notifications.Text;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The message texts of every language say the same things as English, with the same blanks, in 0-9 digits. */
class NoticeTextsTest {

    @Test
    void everyLanguageHasEveryTextWithTheSameBlanks() {
        Set<String> english = NoticeTexts.keys(Language.EN);
        assertThat(english).hasSizeGreaterThan(100);
        for (Language l : Language.values()) {
            if (l == Language.EN) {
                continue;
            }
            assertThat(NoticeTexts.keys(l)).as(l.code()).isEqualTo(english);
            for (String key : english) {
                String text = NoticeTexts.template(l, key).orElseThrow();
                assertThat(text).as(l.code() + " " + key).isNotBlank();
                assertThat(blanks(text)).as(l.code() + " " + key)
                        .isEqualTo(blanks(NoticeTexts.template(Language.EN, key).orElseThrow()));
                assertThat(text).as(l.code() + " " + key + ": digits 0-9").doesNotContainPattern("[०-९౦-౯௦-௯೦-೯]");
            }
            // A salon's move rewrites the reminders from the parts between {time}, {code} and {until}
            String reminder = NoticeTexts.template(l, "BOOKING_REMINDER.body").orElseThrow();
            assertThat(reminder).as(l.code()).startsWith("{time}");
            assertThat(reminder.indexOf("{code}")).as(l.code()).isLessThan(reminder.indexOf("{until}"));
        }
    }

    @Test
    void englishSaysWhatTheCodeAlwaysSaid() {
        Instant t = Instant.parse("2026-11-20T12:30:00Z");
        assertThat(NoticeTexts.render(Language.EN, "BOOKING_CONFIRMED.body", Map.of("salon", "Sharma Hair",
                "when", new DayTime(t), "services", "Haircut", "booking", "AB12")))
                .contains("Sharma Hair, Fri 20 Nov, 6:00 PM, Haircut. Booking AB12. Pay at the salon.");
        assertThat(NoticeTexts.render(Language.EN, "BOOKING_RATE_VISIT.body", Map.of("services",
                new AndList(List.of("Haircut", "Beard trim", "Facial")), "salon", "S")))
                .contains("Rate your Haircut, Beard trim and Facial at S. It helps others choose, and tells the "
                        + "salon what went well.");
        assertThat(NoticeTexts.render(Language.EN, "SHOP_APPROVED.body", Map.of("name", "N", "trial",
                Text.of("SHOP_APPROVED.trial", Map.of("day", new Day(LocalDate.of(2026, 12, 20), "d MMM yyyy"),
                        "price", 500)))))
                .contains("N is approved. Customers nearby can find it now. Your free trial runs until 20 Dec 2026; "
                        + "then ₹500 a month. Nothing to pay now.");
    }

    @Test
    void otherLanguagesGetTheirDayNamesTheSameTimeAndTheirWordsForPieces() {
        Instant t = Instant.parse("2026-11-20T12:30:00Z");
        assertThat(NoticeTexts.render(Language.HI, "BOOKING_CONFIRMED.body", Map.of("salon", "Sharma Hair",
                "when", new DayTime(t), "services", "Haircut", "booking", "AB12")))
                .contains("Sharma Hair, शुक्र 20 नव॰, 6:00 PM, Haircut। बुकिंग AB12। पेमेंट सैलून में करें।");
        assertThat(NoticeTexts.render(Language.MR, "review.reasons", Map.of("list",
                List.of(Text.of("reason.LICENCE_EXPIRED"), Text.of("reason.ADDRESS_WRONG")), "note", "")))
                .contains("ट्रेड लायसन्सची मुदत संपली आहे, पत्ता चुकीचा आहे.");
        // A value the text needs but wasn't given: nothing (the English goes out instead)
        assertThat(NoticeTexts.render(Language.HI, "BOOKING_CONFIRMED.body", Map.of("salon", "x"))).isEmpty();
    }

    @Test
    void aDayPatternIsMadeOnceForEachLanguage() {
        assertThat(NoticeTexts.dayFormat(Language.HI, "d MMM yyyy"))
                .isSameAs(NoticeTexts.dayFormat(Language.HI, "d MMM yyyy"));
        assertThat(NoticeTexts.dayFormat(Language.HI, "d MMM yyyy"))
                .isNotSameAs(NoticeTexts.dayFormat(Language.TE, "d MMM yyyy"));
        // And it writes what a new formatter would
        LocalDate day = LocalDate.of(2027, 3, 31);
        for (Language l : Language.values()) {
            assertThat(NoticeTexts.render(l, "SHOP_LICENCE_EXPIRING.body", Map.of("day", new Day(day, "d MMM yyyy"))))
                    .as(l.code()).get().asString().contains(java.time.format.DateTimeFormatter
                            .ofPattern("d MMM yyyy", l.locale()).format(day));
        }
    }

    private static Set<String> blanks(String text) {
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("\\{([^}]*)}").matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
