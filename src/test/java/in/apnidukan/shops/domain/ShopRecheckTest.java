package in.apnidukan.shops.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A live shop's changes are re-checked while it stays live (V41). */
class ShopRecheckTest {

    private static final Instant T0 = Instant.parse("2026-11-12T04:00:00Z");

    private static Shop liveSalon() {
        Shop shop = new Shop(1001, ShopType.SALON, new ShopDetails("Sharma Salon", "Ravi Sharma", "Shop 4, Main Road",
                "Kondapur", "Hyderabad", "500084", new BigDecimal("17.465000"), new BigDecimal("78.364000"), null,
                null), T0);
        shop.setPhotos(UUID.randomUUID(), UUID.randomUUID(), T0);
        shop.setVideo(UUID.randomUUID(), T0);
        shop.submitForVisit(T0);
        shop.approve(T0);
        return shop;
    }

    @Test
    void aLiveShopStaysLiveAndWaitsOnceForItsReCheck() {
        Shop shop = liveSalon();
        assertThat(shop.waitingForVisit()).isFalse();

        assertThat(shop.requestRecheck(T0.plusSeconds(60))).isTrue();
        assertThat(shop.getStatus()).isEqualTo(ShopStatus.APPROVED);
        assertThat(shop.getRecheckSince()).isEqualTo(T0.plusSeconds(60));
        assertThat(shop.waitingForVisit()).isTrue();
        assertThat(ReviewGroup.of(shop.getStatus(), shop.getStatusReason(), true, true)).isEqualTo(ReviewGroup.RECHECK);

        // A second change while it waits keeps its place in the queue
        assertThat(shop.requestRecheck(T0.plusSeconds(120))).isFalse();
        assertThat(shop.getRecheckSince()).isEqualTo(T0.plusSeconds(60));

        // Checked: still live, nothing waits
        shop.approve(T0.plusSeconds(180));
        assertThat(shop.getStatus()).isEqualTo(ShopStatus.APPROVED);
        assertThat(shop.getRecheckSince()).isNull();
        assertThat(shop.waitingForVisit()).isFalse();
        assertThat(ReviewGroup.of(shop.getStatus(), null, true, false)).isEqualTo(ReviewGroup.LIVE);
        assertThatThrownBy(() -> shop.approve(T0.plusSeconds(240))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyALiveShopWaitsForAReCheck() {
        Shop draft = new Shop(1002, ShopType.SALON, new ShopDetails("New Cuts", "Asha", "Shop 1, Lane 2", "Kondapur",
                "Hyderabad", "500084", new BigDecimal("17.465000"), new BigDecimal("78.364000"), null, null), T0);
        assertThat(draft.requestRecheck(T0)).isFalse();   // checked at its first visit anyway
        assertThat(draft.getRecheckSince()).isNull();
        assertThat(draft.addressLocked()).isFalse();   // not verified yet: the owner moves it
        assertThatThrownBy(() -> draft.askToFix("Wrong pin.", T0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFixAskedForIsAnsweredByTheOwnersNextChangeAndAVerifiedAddressIsLocked() {
        Shop shop = liveSalon();
        assertThat(shop.addressLocked()).isTrue();   // approved: only the field team moves it
        shop.requestRecheck(T0);
        shop.askToFix("The GSTIN doesn't match.", T0.plusSeconds(60));
        assertThat(shop.getStatus()).isEqualTo(ShopStatus.APPROVED);
        assertThat(shop.getStatusReason()).isEqualTo("The GSTIN doesn't match.");
        assertThat(shop.requestRecheck(T0.plusSeconds(120))).isFalse();   // same place in the queue
        assertThat(shop.getStatusReason()).isNull();   // answered
        assertThat(shop.getRecheckSince()).isEqualTo(T0);

        shop.approve(T0.plusSeconds(180));
        assertThatThrownBy(() -> shop.askToFix("Again", T0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aReCheckApprovalDoesntMarkANewDocumentChecked() {
        Shop shop = liveSalon();
        shop.setRegistrationDoc(RegistrationDocType.TRADE_LICENCE, UUID.randomUUID(), T0);
        shop.requestRecheck(T0);
        shop.approve(T0.plusSeconds(60));
        assertThat(shop.getRegistrationDocCheckedAt()).isNull();   // it waits for its own check
    }

    @Test
    void aLiveReCheckIsNeverSentBackAndSuspendingOrClosingEndsTheWait() {
        Shop live = liveSalon();
        live.requestRecheck(T0);
        assertThatThrownBy(() -> live.reject("No.", T0)).isInstanceOf(IllegalStateException.class);

        Shop suspended = liveSalon();
        suspended.requestRecheck(T0);
        suspended.suspend("Shut for months.", T0);
        assertThat(suspended.getRecheckSince()).isNull();
        assertThat(suspended.waitingForVisit()).isFalse();

        Shop closed = liveSalon();
        closed.requestRecheck(T0);
        closed.close(null, T0);
        assertThat(closed.getRecheckSince()).isNull();
    }
}
