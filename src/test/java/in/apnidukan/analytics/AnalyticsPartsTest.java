package in.apnidukan.analytics;

import in.apnidukan.analytics.domain.FeeSettings;
import in.apnidukan.analytics.repo.AnalyticsStore.MonthMoney;
import in.apnidukan.analytics.service.ActivityMarks;
import in.apnidukan.analytics.service.ActivityMarks.Side;
import in.apnidukan.analytics.service.AnalyticsService;
import in.apnidukan.analytics.service.AnalyticsService.Money;
import in.apnidukan.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The analytics' small rules without a database: fees, the CSV's cells and which app a request came from. */
class AnalyticsPartsTest {

    private static final FeeSettings RATES = new FeeSettings(1500, 1100, 0, null, null);

    @Test
    void googlesFeeIsEstimatedOnWhatWasKeptRazorpaysIsTakenAsGiven() {
        Money play = AnalyticsService.money(new MonthMoney("2026-11", "GOOGLE_PLAY", 4, 200_000, 50_000, 1, 0, 4),
                RATES);
        assertThat(play.googleFeeEstimatePaise()).isEqualTo(22_500);   // 15% of ₹1,500 kept
        assertThat(play.netPaise()).isEqualTo(127_500);

        Money app = AnalyticsService.money(new MonthMoney("2026-11", "APP_RAZORPAY", 1, 50_000, 0, 0, 1_180, 1), RATES);
        assertThat(app.googleFeeEstimatePaise()).isEqualTo(5_500);
        assertThat(app.netPaise()).isEqualTo(50_000 - 1_180 - 5_500);

        Money web = AnalyticsService.money(new MonthMoney("2026-11", "WEBSITE", 1, 50_000, 0, 0, 1_180, 1), RATES);
        assertThat(web.googleFeeEstimatePaise()).isZero();
        assertThat(web.netPaise()).isEqualTo(48_820);

        // Everything refunded: never a negative fee
        Money back = AnalyticsService.money(new MonthMoney("2026-11", "GOOGLE_PLAY", 1, 50_000, 50_000, 1, 0, 1),
                RATES);
        assertThat(back.googleFeeEstimatePaise()).isZero();
        assertThat(back.netPaise()).isZero();
    }

    @Test
    void csvCellsAreQuotedAndNeverRunAsFormulas() {
        assertThat(AnalyticsService.cell("Kondapur")).isEqualTo("Kondapur");
        assertThat(AnalyticsService.cell("Cuts, Shaves")).isEqualTo("\"Cuts, Shaves\"");
        assertThat(AnalyticsService.cell("Raju \"the\" barber")).isEqualTo("\"Raju \"\"the\"\" barber\"");
        assertThat(AnalyticsService.cell("=1+1")).isEqualTo("'=1+1");
        assertThat(AnalyticsService.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(AnalyticsService.cell("-2")).isEqualTo("'-2");
        assertThat(AnalyticsService.cell("Line\nbreak")).isEqualTo("\"Line\nbreak\"");
        assertThat(AnalyticsService.cell(null)).isEmpty();
        assertThat(AnalyticsService.cell("సెలూన్")).isEqualTo("సెలూన్");
    }

    @Test
    void rupeesAreWrittenWithTwoPlaces() {
        assertThat(AnalyticsService.rupees(50_000)).isEqualTo("500.00");
        assertThat(AnalyticsService.rupees(1_180)).isEqualTo("11.80");
        assertThat(AnalyticsService.rupees(-10_005)).isEqualTo("-100.05");
        assertThat(AnalyticsService.rupees(7)).isEqualTo("0.07");
    }

    @Test
    void theAppSaysWhichSideItIsElseTheWebsiteItCameFrom() {
        assertThat(ActivityMarks.side("partner-app", null)).contains(new Side("PARTNER", "APP"));
        assertThat(ActivityMarks.side(" Customer-Web ", null)).contains(new Side("CUSTOMER", "WEB"));
        assertThat(ActivityMarks.side("admin-web", "https://clozest.in")).contains(new Side("ADMIN", "WEB"));
        assertThat(ActivityMarks.side(null, "https://partner.clozest.in")).contains(new Side("PARTNER", "WEB"));
        assertThat(ActivityMarks.side("unknown", "https://admin.clozest.in")).contains(new Side("ADMIN", "WEB"));
        assertThat(ActivityMarks.side(null, "https://clozest.in")).contains(new Side("CUSTOMER", "WEB"));
        assertThat(ActivityMarks.side(null, null)).isEmpty();
        assertThat(ActivityMarks.side("ci", " ")).isEmpty();
    }

    @Test
    void daysFromTheQueryMustBeRealDays() {
        assertThat(AnalyticsService.parseDay(null, "from")).isNull();
        assertThat(AnalyticsService.parseDay("2026-11-25", "from")).isEqualTo(LocalDate.of(2026, 11, 25));
        assertThatThrownBy(() -> AnalyticsService.parseDay("2026-02-30", "to"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("VALIDATION_FAILED"));
        assertThatThrownBy(() -> AnalyticsService.parseDay("yesterday", "from")).isInstanceOf(ApiException.class);
    }
}
