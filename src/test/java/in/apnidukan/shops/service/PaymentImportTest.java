package in.apnidukan.shops.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.jobs.JobLeases;
import in.apnidukan.shops.repo.PaymentLedger;
import in.apnidukan.shops.repo.PaymentLedger.KnownSubscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The one-time import of past Razorpay payments, with Razorpay and the ledger faked. */
class PaymentImportTest {

    private static final UUID SHOP = UUID.fromString("c0000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-25T08:00:00Z");

    private final PaymentLedger ledger = mock(PaymentLedger.class);
    private final RazorpayGateway razorpay = mock(RazorpayGateway.class);
    private final Set<String> done = new HashSet<>();
    private PaymentImport importer;

    @BeforeEach
    void setUp() {
        when(razorpay.configured()).thenReturn(true);
        when(razorpay.testMode()).thenReturn(false);
        when(ledger.done(anyString())).thenAnswer(a -> done.contains(a.<String>getArgument(0)));
        org.mockito.Mockito.doAnswer(a -> done.add(a.getArgument(0))).when(ledger).markDone(anyString(), any());
        when(ledger.shopExists(SHOP)).thenReturn(true);
        when(ledger.razorpaySource(any(), anyString())).thenReturn(PaymentLedger.Source.WEBSITE);
        when(ledger.charge(any(), any())).thenReturn(true);
        importer = new PaymentImport(ledger, razorpay, mock(JobLeases.class), mock(PlatformTransactionManager.class),
                Clock.fixed(NOW, ZoneOffset.UTC), false);
    }

    @Test
    void aSubscriptionRazorpayDoesntKnowIsSkippedAndTheImportStillFinishes() {
        // Razorpay lists sub_A for our plan (a replaced one we no longer store); we also have sub_TEST from the test keys
        when(razorpay.planSubscriptions(0)).thenReturn(List.of(new RazorpayGateway.PlanSubscription("sub_A",
                SHOP.toString())));
        when(ledger.knownRazorpaySubscriptions()).thenReturn(List.of(new KnownSubscription(SHOP, "sub_TEST")));
        when(razorpay.paidInvoices("sub_A", 0)).thenReturn(List.of(new RazorpayGateway.PaidInvoice("pay_1",
                NOW.plusSeconds(86_400))));
        when(razorpay.payment("pay_1")).thenReturn(new RazorpayGateway.Payment("pay_1", 50_000, 1_180L,
                NOW.minusSeconds(86_400), "captured", 0));
        when(razorpay.paidInvoices("sub_TEST", 0)).thenThrow(new RazorpayGateway.UnknownToRazorpay("/v1/invoices"));

        assertThat(importer.runOnce()).isEqualTo(1);
        assertThat(done).contains("razorpay-import", "razorpay-import:sub_A", "razorpay-import:sub_TEST");
        // Done for good: nothing is asked again
        importer.runOnce();
        verify(razorpay, org.mockito.Mockito.times(1)).planSubscriptions(0);
    }

    @Test
    void razorpayNotAnsweringLeavesItForTheNextRoundWhichGoesOnWhereItStopped() {
        when(razorpay.planSubscriptions(0)).thenReturn(List.of(
                new RazorpayGateway.PlanSubscription("sub_A", SHOP.toString()),
                new RazorpayGateway.PlanSubscription("sub_B", SHOP.toString()),
                new RazorpayGateway.PlanSubscription("sub_X", "not-a-shop")));
        when(ledger.knownRazorpaySubscriptions()).thenReturn(List.of());
        when(razorpay.paidInvoices("sub_A", 0)).thenReturn(List.of());
        when(razorpay.paidInvoices("sub_B", 0)).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "PAYMENTS_UNAVAILABLE", "busy"));

        importer.runOnce();
        assertThat(done).containsExactly("razorpay-import:sub_A");
        // The next round: sub_A isn't read again, sub_B is; the salon-less one was never in the list
        org.mockito.Mockito.doReturn(List.of()).when(razorpay).paidInvoices("sub_B", 0);
        importer.runOnce();
        verify(razorpay, org.mockito.Mockito.times(1)).paidInvoices(eq("sub_A"), anyInt());
        verify(razorpay, never()).paidInvoices(eq("sub_X"), anyInt());
        assertThat(done).contains("razorpay-import", "razorpay-import:sub_B");
    }

    @Test
    void testKeysAreNeverImported() {
        when(razorpay.testMode()).thenReturn(true);
        assertThat(importer.runOnce()).isZero();
        verify(razorpay, never()).planSubscriptions(anyInt());
    }

    @Test
    void aGooglePlayPeriodIsDatedWhenItWasPaidNotWhenWeFirstSawIt() {
        Instant asked = Instant.parse("2026-11-25T08:00:00Z");
        // Bought on 20 Oct, first seen today (a check after deploy): paid on 20 Oct
        assertThat(PlaySubscriptions.paidAt(Instant.parse("2026-11-20T10:00:00Z"), asked))
                .isEqualTo(Instant.parse("2026-10-20T10:00:00Z"));
        // A renewal seen at once: never later than when we asked
        assertThat(PlaySubscriptions.paidAt(Instant.parse("2026-12-26T08:00:00Z"), asked)).isEqualTo(asked);
        assertThat(PlaySubscriptions.paidAt(null, asked)).isEqualTo(asked);
    }
}
