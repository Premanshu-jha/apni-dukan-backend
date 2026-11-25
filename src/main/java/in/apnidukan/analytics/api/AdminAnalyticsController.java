package in.apnidukan.analytics.api;

import in.apnidukan.analytics.domain.FeeSettings;
import in.apnidukan.analytics.service.AnalyticsService;
import in.apnidukan.analytics.service.AnalyticsService.BookingsView;
import in.apnidukan.analytics.service.AnalyticsService.MoneyView;
import in.apnidukan.analytics.service.AnalyticsService.PeopleView;
import in.apnidukan.analytics.service.AnalyticsService.SalonsView;
import in.apnidukan.analytics.service.AnalyticsService.SubscriptionsView;
import in.apnidukan.analytics.service.AnalyticsService.VersionsView;
import in.apnidukan.common.context.CurrentUserProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The admin website's Analytics page (super admins only: 403 SUPER_ADMIN_ONLY for anyone else on the team). Days are
 * yyyy-MM-dd in India's calendar, both included; without them the last 30 days. 400 VALIDATION_FAILED names a bad
 * field (a range longer than 366 days, a pincode that isn't 6 digits).
 */
@RestController
@RequestMapping("/api/v1/admin/analytics")
public class AdminAnalyticsController {

    private final AnalyticsService analytics;
    private final CurrentUserProvider currentUser;

    public AdminAnalyticsController(AnalyticsService analytics, CurrentUserProvider currentUser) {
        this.analytics = analytics;
        this.currentUser = currentUser;
    }

    /** Money per month (the last {@code months}, 1 to 36) and source, the monthly money now, and Google's rates. */
    @GetMapping("/money")
    public MoneyView money(@RequestParam(defaultValue = "12") int months) {
        return analytics.money(caller(), months);
    }

    /** A month's payments as a CSV file: {@code /money/2026-11.csv}. */
    @GetMapping("/money/{month}.csv")
    public ResponseEntity<byte[]> moneyCsv(@PathVariable String month) {
        String csv = analytics.moneyCsv(caller(), month);
        // A byte order mark so Excel reads ₹ and Hindi or Telugu salon names right
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"clozest-payments-" + month + ".csv\"")
                .cacheControl(CacheControl.noStore())
                .body(out);
    }

    @GetMapping("/settings")
    public FeeSettings settings() {
        return analytics.settings(caller());
    }

    /** Google's fee rates in basis points (1500 = 15%), 0 to 3000. Send the {@code version} shown: 409 SETTINGS_CHANGED. */
    @PutMapping("/settings")
    public FeeSettings saveSettings(@RequestBody SettingsBody body) {
        return analytics.saveSettings(caller(), body.playFeeBps(), body.userChoiceFeeBps(), body.version());
    }

    /** Checked by the service, after the super admin check (so the rest of the team always gets 403). */
    public record SettingsBody(Integer playFeeBps, Integer userChoiceFeeBps, Integer version) {
    }

    @GetMapping("/subscriptions")
    public SubscriptionsView subscriptions() {
        return analytics.subscriptions(caller());
    }

    @GetMapping("/people")
    public PeopleView people(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return analytics.people(caller(), AnalyticsService.parseDay(from, "from"), AnalyticsService.parseDay(to, "to"));
    }

    /** {@code pincode}: one area's bookings only. */
    @GetMapping("/bookings")
    public BookingsView bookings(@RequestParam(required = false) String from,
                                 @RequestParam(required = false) String to,
                                 @RequestParam(required = false) String pincode) {
        return analytics.bookings(caller(), AnalyticsService.parseDay(from, "from"), AnalyticsService.parseDay(to, "to"),
                blank(pincode));
    }

    /** {@code sort}: quiet (least used first) or bookings (most first); 50 a page from {@code page} 0. */
    @GetMapping("/salons")
    public SalonsView salons(@RequestParam(defaultValue = "quiet") String sort,
                             @RequestParam(required = false) String pincode,
                             @RequestParam(defaultValue = "0") int page) {
        return analytics.salons(caller(), sort, blank(pincode), page);
    }

    @GetMapping("/app-versions")
    public VersionsView appVersions() {
        return analytics.appVersions(caller());
    }

    private UUID caller() {
        return currentUser.get().userId();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
