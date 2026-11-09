package in.apnidukan.auth.service;

import in.apnidukan.auth.domain.User;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** When a real person's verify (real code) takes an email from the account holding it (AuthService.releasable). */
class ReleasableEmailTest {

    private static final Instant NOW = Instant.parse("2026-11-08T05:00:00Z");

    private static User testAccountWith(String email) {
        User u = new User(UUID.randomUUID(), "9111111111", NOW);
        u.markTestAccount();
        if (email != null) {
            u.verifyEmail(email, NOW);
        }
        return u;
    }

    @Test
    void aTestAccountStillHoldingExactlyThisVerifiedEmailLetsItGo() {
        assertThat(AuthService.releasable(testAccountWith("qa@gmail.com"), "qa@gmail.com")).isTrue();
    }

    @Test
    void aRealAccountNeverDoes() {
        User real = new User(UUID.randomUUID(), "9000000003", NOW);
        real.verifyEmail("qa@gmail.com", NOW);
        assertThat(AuthService.releasable(real, "qa@gmail.com")).isFalse();
    }

    @Test
    void aTestAccountMadeAnAdminMeanwhileDoesnt() {
        User holder = testAccountWith("qa@gmail.com");
        holder.makeAdmin(in.apnidukan.auth.domain.AdminRole.ADMIN, in.apnidukan.auth.domain.AdminDesignation.FIELD_AGENT,
                NOW);
        assertThat(AuthService.releasable(holder, "qa@gmail.com")).isFalse();
    }

    @Test
    void notWhenItNoLongerHoldsTheEmailVerified() {
        User cleared = testAccountWith("qa@gmail.com");
        cleared.releaseEmail(NOW);
        assertThat(AuthService.releasable(cleared, "qa@gmail.com")).isFalse();
        assertThat(AuthService.releasable(testAccountWith(null), "qa@gmail.com")).isFalse();
    }

    @Test
    void notWhenItHoldsAnotherAddressNow() {
        assertThat(AuthService.releasable(testAccountWith("other@gmail.com"), "qa@gmail.com")).isFalse();
    }

    @Test
    void notWhenItsNumbersRealHolderClaimedIt() {
        User claimed = testAccountWith("qa@gmail.com");
        claimed.claimByRealHolder(NOW);
        claimed.verifyEmail("qa@gmail.com", NOW);   // and verified it for real since
        assertThat(AuthService.releasable(claimed, "qa@gmail.com")).isFalse();
    }
}
