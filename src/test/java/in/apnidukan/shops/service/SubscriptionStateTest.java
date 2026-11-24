package in.apnidukan.shops.service;

import in.apnidukan.common.notify.Notifications;
import in.apnidukan.shops.repo.ShopMemberRepository;
import in.apnidukan.shops.repo.SubscriptionStore;
import in.apnidukan.shops.repo.SubscriptionStore.Row;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** What the partner app is told about a salon's subscription, at each point in its life. */
class SubscriptionStateTest {

    private static final Instant NOW = Instant.parse("2026-11-10T04:30:00Z");
    private static final Instant TRIAL_END = NOW.plus(Duration.ofDays(10));

    private final SubscriptionService service = new SubscriptionService(mock(SubscriptionStore.class),
            mock(ShopMemberRepository.class),
            new RazorpayGateway("rzp_test_1", "s", "h", "plan_1", URI.create("http://127.0.0.1:1")),
            mock(GooglePlay.class), mock(PlayReports.class), mock(Notifications.class), mock(PlatformTransactionManager.class), Clock.fixed(NOW, ZoneOffset.UTC),
            true, "https://partner.clozest.in/icon-512.png", java.util.List.of());

    private static Row row(Instant paidUntil, String status) {
        Instant access = paidUntil != null && paidUntil.isAfter(TRIAL_END) ? paidUntil : TRIAL_END;
        return new Row(UUID.randomUUID(), TRIAL_END, paidUntil, access, SubscriptionStore.bookableAfter(access), null,
                null, status, null, null, 0, null);
    }

    @Test
    void beforeApprovalThereIsNothingToPay() {
        SubscriptionService.View v = service.view(null, true, NOW);
        assertThat(v.state()).isEqualTo("NONE");
        assertThat(v.canPay()).isFalse();
    }

    @Test
    void theTrialThenThreeDaysGraceThenHidden() {
        assertThat(service.view(row(null, null), true, NOW).state()).isEqualTo("TRIAL");
        assertThat(service.view(row(null, null), true, TRIAL_END).state()).isEqualTo("GRACE");
        // Hidden from midnight, India time, ending the third day after the trial: Fri 20 Nov 10:00 → 24 Nov 00:00
        Instant hidden = Instant.parse("2026-11-23T18:30:00Z");
        assertThat(SubscriptionStore.bookableAfter(TRIAL_END)).isEqualTo(hidden);
        assertThat(service.view(row(null, null), true, hidden.minusSeconds(1)).state()).isEqualTo("GRACE");
        assertThat(service.view(row(null, null), true, hidden).state()).isEqualTo("HIDDEN");
    }

    @Test
    void hidingStartsAtMidnightIndiaTimeHoweverLateTheTrialEnded() {
        // Ends 23:50 on 1 Dec: hidden from midnight starting 5 Dec. Ends 00:10 on 2 Dec: from midnight starting 6 Dec
        assertThat(SubscriptionStore.bookableAfter(Instant.parse("2026-12-01T18:20:00Z")))
                .isEqualTo(Instant.parse("2026-12-04T18:30:00Z"));
        assertThat(SubscriptionStore.bookableAfter(Instant.parse("2026-12-01T18:40:00Z")))
                .isEqualTo(Instant.parse("2026-12-05T18:30:00Z"));
    }

    @Test
    void autoPaySetUpAfterTheTrialEndedIsOnFromThenNotOverdue() {
        // The trial ended 5 days ago; the owner pays now: AutoPay on while Razorpay's first charge comes through
        Instant now = TRIAL_END.plus(Duration.ofDays(5));
        Row late = new Row(UUID.randomUUID(), TRIAL_END, null, TRIAL_END, SubscriptionStore.bookableAfter(TRIAL_END),
                "sub_1", now.minusSeconds(30), "AUTHENTICATED", null, null, 0, null);
        assertThat(service.view(late, true, now).state()).isEqualTo("AUTOPAY");
        assertThat(service.view(late, true, now).autoPayFailed()).isFalse();
        // ...but not if no charge came within a day of that
        assertThat(service.view(late, true, now.plus(Duration.ofDays(1))).autoPayFailed()).isTrue();
    }

    @Test
    void aFailedOrOverdueAutoPayIsNotAutoPayAndSaysSo() {
        SubscriptionService.View failed = service.view(row(null, "PENDING"), true, NOW);
        assertThat(failed.state()).isEqualTo("TRIAL");
        assertThat(failed.autoPayFailed()).isTrue();
        assertThat(failed.canPay()).isTrue();
        // AutoPay set up, but no charge a day after the trial ended
        SubscriptionService.View late = service.view(row(null, "ACTIVE"), true, TRIAL_END.plus(Duration.ofDays(1)));
        assertThat(late.state()).isEqualTo("GRACE");
        assertThat(late.autoPayFailed()).isTrue();
        SubscriptionService.View onTime = service.view(row(null, "ACTIVE"), true, TRIAL_END.plus(Duration.ofHours(23)));
        assertThat(onTime.state()).isEqualTo("AUTOPAY");
        assertThat(onTime.autoPayFailed()).isFalse();
    }

    @Test
    void paidAheadWithoutAutoPayIsPaidAndAutoPayOnNeedsNothing() {
        Instant paid = TRIAL_END.plus(Duration.ofDays(30));
        assertThat(service.view(row(paid, "HALTED"), true, NOW.plus(Duration.ofDays(20))).state()).isEqualTo("PAID");
        SubscriptionService.View on = service.view(row(paid, "ACTIVE"), true, NOW);
        assertThat(on.state()).isEqualTo("AUTOPAY");
        assertThat(on.canPay()).isFalse();
        assertThat(service.view(row(null, "AUTHENTICATED"), true, NOW).state()).isEqualTo("AUTOPAY");
        // A charge only for the trial's own period counts as the trial still
        assertThat(service.view(row(TRIAL_END, "CANCELLED"), true, NOW).state()).isEqualTo("TRIAL");
    }

    @Test
    void onlyTheOwnerCanPay() {
        assertThat(service.view(row(null, null), true, NOW).canPay()).isTrue();
        assertThat(service.view(row(null, null), false, NOW).canPay()).isFalse();
    }

    @Test
    void theFirstChargeIsTheTrialsEndOrAtOnceWhenThatIsAlmostHere() {
        assertThat(SubscriptionService.firstCharge(row(null, null), NOW)).isEqualTo(TRIAL_END);
        assertThat(SubscriptionService.firstCharge(row(null, null), TRIAL_END.minus(Duration.ofMinutes(5)))).isNull();
        assertThat(SubscriptionService.firstCharge(row(null, null), TRIAL_END.plusSeconds(1))).isNull();
    }
}
