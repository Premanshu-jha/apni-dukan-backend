package in.apnidukan.helpdesk.service;

import in.apnidukan.common.error.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageTextTest {

    @Test
    void keepsLineBreaksDropsHiddenCharactersAndTrims() {
        assertThat(MessageText.clean("m", "  Hello" + (char) 0x202E + "\r\nthere  \n\n\n\n\nbye ", 1, 100))
                .isEqualTo("Hello\nthere\n\n\nbye");
    }

    @Test
    void refusesTooShortOrLong() {
        assertThatThrownBy(() -> MessageText.clean("message", "   hi   ", 10, 100))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).getDetails().get("message"))
                .isEqualTo("write 10 to 100 characters");
    }

    @Test
    void aSubjectIsOneLineAndMadeFromTheMessageWhenMissing() {
        assertThat(MessageText.line("subject", " Booking \n issue ", 2, 100)).isEqualTo("Booking issue");
        assertThat(MessageText.subjectFrom("Short one", 100)).isEqualTo("Short one");
        String made = MessageText.subjectFrom("word ".repeat(40), 100);
        assertThat(made).endsWith("…").hasSizeLessThanOrEqualTo(100);
    }
}
