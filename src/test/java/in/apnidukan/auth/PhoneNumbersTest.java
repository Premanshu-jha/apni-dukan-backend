package in.apnidukan.auth;

import in.apnidukan.auth.service.PhoneNumbers;
import in.apnidukan.common.error.ApiException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhoneNumbersTest {

    @ParameterizedTest
    @ValueSource(strings = {"9848012345", "+91 98480 12345", "+919848012345", "09848012345", "98480-12345"})
    void acceptsTheWaysPeopleTypeAMobileNumber(String input) {
        assertThat(PhoneNumbers.normalize(input)).isEqualTo("9848012345");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12345", "5848012345", "98480123456789", "abcdefghij"})
    void refusesAnythingElse(String input) {
        assertThatThrownBy(() -> PhoneNumbers.normalize(input)).isInstanceOf(ApiException.class);
    }
}
