package in.apnidukan.files.service;

import in.apnidukan.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhotoCleaningTest {

    @Test
    void whenEveryTurnIsTakenAPhotoWaitsThenGetsTryAgainAndATurnFreedLetsTheNextOneThrough() throws Exception {
        PhotoCleaning cleaning = new PhotoCleaning(2, Duration.ofMillis(50));
        cleaning.turns.acquire(2);   // two photos being drawn right now
        assertThatThrownBy(() -> cleaning.clean(new byte[] {1, 2, 3}))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("PHOTOS_BUSY");
                    assertThat(e.getStatus().value()).isEqualTo(503);
                });
        cleaning.turns.release();
        // Its turn now: these bytes aren't a photo, so it is the usual "couldn't be read", and the turn comes back
        assertThatThrownBy(() -> cleaning.clean(new byte[] {1, 2, 3}))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("UNREADABLE_PHOTO"));
        assertThat(cleaning.turns.availablePermits()).isEqualTo(1);
    }

    @Test
    void lessThanOneAtOnceIsRefusedAtStartup() {
        assertThatThrownBy(() -> new PhotoCleaning(0)).isInstanceOf(IllegalStateException.class);
    }
}
