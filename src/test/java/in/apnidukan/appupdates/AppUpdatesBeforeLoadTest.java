package in.apnidukan.appupdates;

import in.apnidukan.appupdates.domain.AppName;
import in.apnidukan.appupdates.repo.ReleaseStore;
import in.apnidukan.appupdates.service.AppUpdates;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.files.AppBundleFiles;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Before the releases were read once (just started, the database slow): phones are told to try again later. */
class AppUpdatesBeforeLoadTest {

    @Test
    void phonesKeepTheirScreensUntilTheReleasesWereReadAndNothingIsCounted() {
        AppUpdates updates = new AppUpdates(mock(ReleaseStore.class), mock(AppBundleFiles.class));
        // Not "no bundle": that would send phones back to the screens their app came with
        assertThatThrownBy(() -> updates.check(AppName.PARTNER, new AppUpdates.Device(1, 240, false)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("SERVICE_BUSY");
                    assertThat(e.getRetryAfterSeconds()).isPositive();
                });
        assertThat(updates.plausibleVersion(AppName.PARTNER, 240)).isFalse();
    }
}
