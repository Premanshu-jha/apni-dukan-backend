package in.apnidukan.auth;

import in.apnidukan.auth.service.Emails;
import in.apnidukan.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class EmailsTest {

    @Test
    void trimsAndLowerCases() {
        assertThat(Emails.normalize("  Sharma.Store+bills@Gmail.COM ")).isEqualTo("sharma.store+bills@gmail.com");
        assertThat(Emails.normalize("owner@shop.co.in")).isEqualTo("owner@shop.co.in");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "plain", "@gmail.com", "a@b", "a@@b.com", "a@b@c.com", "a b@gmail.com",
            "a@-shop.com", "a@shop-.com", "a@shop..com", "a@shop.c", "a@shop.123", "a,b@gmail.com"})
    void rejectsWhatIsntAnEmail(String input) {
        assertThatThrownBy(() -> Emails.normalize(input)).isInstanceOf(ApiException.class);
    }

    @Test
    void craftedInputCannotMakeTheCheckSlow() {
        String evil = "a@" + "a.".repeat(120) + "!";
        assertTimeoutPreemptively(Duration.ofMillis(200),
                () -> assertThatThrownBy(() -> Emails.normalize(evil)).isInstanceOf(ApiException.class));
    }
}
