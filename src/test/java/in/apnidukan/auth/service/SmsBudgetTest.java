package in.apnidukan.auth.service;

import in.apnidukan.auth.repo.UserRepository;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.security.RateLimiter;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The SMS budget decides before a new code replaces the old one (requireRoom), and only counts once the code is made
 * (spend): a send refused after that would leave the person with no working code at all.
 */
class SmsBudgetTest {

    private final RateLimiter limits = mock(RateLimiter.class);
    private final SmsBudget budget = new SmsBudget(limits, mock(UserRepository.class),
            new SignInLimits(200, 20, 600, 60, 500), 10_000);
    private final SmsBudget.To to = SmsBudget.To.signedIn("9000000001");

    @Test
    void aCodeAlreadyMadeIsAlwaysSentEvenIfTheBudgetFilledUpMeanwhile() {
        when(limits.current(SmsBudget.DAY)).thenReturn(499L);
        assertThatCode(() -> budget.requireRoom(to)).doesNotThrowAnyException();
        // Another server sent the last one between the check and this send
        when(limits.current(SmsBudget.DAY)).thenReturn(500L);
        when(limits.hit(eq(SmsBudget.DAY), any(Duration.class))).thenReturn(501L);
        assertThatCode(() -> budget.spend(to)).doesNotThrowAnyException();
        verify(limits).hit(SmsBudget.DAY, Duration.ofHours(24));
        verify(limits).hit(SmsBudget.HOUR, Duration.ofHours(1));
        // The next one is refused before its code is made, so the code the person has keeps working
        assertThatThrownBy(() -> budget.requireRoom(to)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("SMS_PAUSED"));
    }
}
