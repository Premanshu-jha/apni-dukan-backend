package in.apnidukan.shops.service;

import static org.assertj.core.api.Assertions.assertThat;

import in.apnidukan.shops.domain.ShopStatus;
import org.junit.jupiter.api.Test;

class LicenceRemovalTextTest {

    @Test
    void onlyALiveSalonIsToldItStaysOpenForBookings() {
        assertThat(ShopAdminService.afterRemoval(ShopStatus.APPROVED)).contains("stays open for bookings");
        assertThat(ShopAdminService.afterRemoval(ShopStatus.DRAFT)).doesNotContain("open")
                .contains("registration");
        assertThat(ShopAdminService.afterRemoval(ShopStatus.SUSPENDED)).doesNotContain("open");
        assertThat(ShopAdminService.afterRemoval(ShopStatus.PENDING_VISIT)).doesNotContain("open");
    }
}
