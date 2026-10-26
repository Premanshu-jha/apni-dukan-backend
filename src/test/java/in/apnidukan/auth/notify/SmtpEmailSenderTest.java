package in.apnidukan.auth.notify;

import in.apnidukan.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

class SmtpEmailSenderTest {

    private final JavaMailSender gmail = mock(JavaMailSender.class);

    @Test
    void sendsFromTheGmailAccountByDefault() {
        new SmtpEmailSender(gmail, "shop.test@gmail.com", "app-password", "")
                .send("owner@gmail.com", "Your code", "Your code is 123456");

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(gmail).send(sent.capture());
        assertThat(sent.getValue().getFrom()).isEqualTo("shop.test@gmail.com");
        assertThat(sent.getValue().getTo()).containsExactly("owner@gmail.com");
        assertThat(sent.getValue().getText()).contains("123456");
    }

    @Test
    void gmailRefusingTellsTheAppToTryAgain() {
        doThrow(new MailSendException("535 bad credentials")).when(gmail).send(any(SimpleMailMessage.class));
        SmtpEmailSender email = new SmtpEmailSender(gmail, "shop.test@gmail.com", "app-password", "");

        assertThatThrownBy(() -> email.send("owner@gmail.com", "Your code", "Your code is 123456"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("EMAIL_FAILED"));
    }

    @Test
    void missingGmailSettingsStopTheAppWithAClearMessage() {
        assertThatThrownBy(() -> new SmtpEmailSender(gmail, "", "", ""))
                .hasMessageContaining("MAIL_USERNAME");
    }
}
