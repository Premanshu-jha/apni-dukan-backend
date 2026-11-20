package in.apnidukan.helpdesk.service;

import in.apnidukan.common.notify.Language;
import in.apnidukan.helpdesk.domain.Ticket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ticket emails: English byte for byte as they always were; another language for every fixed word, with the
 * "[CLZ-1001]" of the subject, the support person's reply and the quoted messages exactly as they are.
 */
class TicketEmailsTest {

    private static Ticket ticket(String subject) {
        return new Ticket(UUID.randomUUID(), 1001, null, null, subject, null, null, null, null, null, null, null, 0,
                true);
    }

    /** Every fixed English sentence the emails have. */
    private static final List<String> ENGLISH = List.of("We've got your request", "Our support team",
            "Your message:", "To add anything", "Clozest Support", "We've marked ticket", "Reply to this email",
            "You wrote:", "Your request", "We sent", "Hi");

    @Test
    void englishIsWhatItAlwaysWas() {
        Ticket t = ticket("Payout");
        assertThat(TicketEmails.ackSubject(t, Language.EN)).isEqualTo("[CLZ-1001] We've got your request");
        assertThat(TicketEmails.ackBody(t, "Asha Rao", "Line one\nline two", Language.EN)).isEqualTo("Hi Asha,\n\n"
                + "We've got your request to Clozest support and created ticket CLZ-1001. Our support team will get "
                + "back to you as soon as we can, usually within a working day.\n\n"
                + "Your message:\n> Line one\n> line two\n\n"
                + "To add anything (a photo or screenshot too), just reply to this email (keep CLZ-1001 in the "
                + "subject).\n\nClozest Support");
        assertThat(TicketEmails.ackBody(t, null, null, Language.EN)).startsWith("Hi,\n\nWe've got")
                .doesNotContain("Your message");
        assertThat(TicketEmails.replySubject(t, Language.EN)).isEqualTo("Re: [CLZ-1001] Payout");
        assertThat(TicketEmails.replySubject(ticket("(no subject)"), Language.EN))
                .isEqualTo("Re: [CLZ-1001] Your request");
        assertThat(TicketEmails.replyBody(t, "Asha", "Done.", true, "Neha Singh", "Where is it?", Language.EN))
                .isEqualTo("Hi Asha,\n\nDone.\n\nWe've marked ticket CLZ-1001 as resolved. If you still need help, "
                        + "just reply to this email and it will open again.\n\nNeha, Clozest Support\n\n"
                        + "You wrote:\n> Where is it?");
        assertThat(TicketEmails.replyBody(t, null, "Done.", false, null, null, Language.EN))
                .isEqualTo("Hi,\n\nDone.\n\nReply to this email if you have anything to add.\n\nClozest Support");
        assertThat(TicketEmails.filesNote(1, Language.EN)).isEqualTo("(We sent 1 file with this reply. See them in "
                + "the Clozest app: Need help, then My requests.)");
        assertThat(TicketEmails.filesNote(3, Language.EN)).startsWith("(We sent 3 files with this reply.");
        // The older calls are English
        assertThat(TicketEmails.ackBody(t, "Asha", "x")).isEqualTo(TicketEmails.ackBody(t, "Asha", "x", Language.EN));
        assertThat(TicketEmails.replySubject(t)).isEqualTo(TicketEmails.replySubject(t, Language.EN));
    }

    @Test
    void everyOtherLanguageKeepsTheNumberTheReplyAndTheQuotesAndHasNoEnglishWords() {
        Ticket t = ticket("(no subject)");
        for (Language l : Language.values()) {
            if (l == Language.EN) {
                continue;
            }
            String subject = TicketEmails.ackSubject(t, l);
            assertThat(subject).as(l.code()).startsWith("[CLZ-1001] ");
            assertThat(TicketEmails.replySubject(t, l)).as(l.code()).startsWith("Re: [CLZ-1001] ");
            String ack = TicketEmails.ackBody(t, "Asha Rao", "My refund?", l);
            String reply = TicketEmails.replyBody(t, "Asha Rao", "We have refunded it.\nSorry!", true, "Neha Singh",
                    "My refund?", l);
            String files = TicketEmails.filesNote(2, l);
            assertThat(ack).as(l.code()).contains("Asha").doesNotContain("Rao").contains("CLZ-1001")
                    .contains("\n> My refund?\n\n");
            assertThat(reply).as(l.code()).contains("\n\nWe have refunded it.\nSorry!\n\n").contains("Neha, Clozest ")
                    .contains("CLZ-1001").endsWith("\n> My refund?").doesNotContain("Singh");
            assertThat(files).as(l.code()).contains("2").contains("Clozest");
            for (String english : ENGLISH) {
                assertThat(subject + ack + TicketEmails.replySubject(t, l) + files).as(l.code())
                        .doesNotContain(english);
                assertThat(reply.replace("We have refunded it.", "")).as(l.code()).doesNotContain(english);
            }
        }
    }

    @Test
    void noLanguageIsEnglish() {
        Ticket t = ticket("Payout");
        assertThat(TicketEmails.usable(null)).isEqualTo(Language.EN);
        assertThat(TicketEmails.ackBody(t, "Asha", null, null)).isEqualTo(TicketEmails.ackBody(t, "Asha", null));
        assertThat(TicketEmails.replyBody(t, "Asha", "x", false, null, null, null))
                .isEqualTo(TicketEmails.replyBody(t, "Asha", "x", false, null, null));
        for (Language l : Language.values()) {
            assertThat(TicketEmails.usable(l)).isEqualTo(l);   // every language has every text
        }
    }
}
