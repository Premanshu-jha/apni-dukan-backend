package in.apnidukan.analytics.service;

import in.apnidukan.analytics.domain.FeeSettings;
import in.apnidukan.analytics.repo.AnalyticsStore;
import in.apnidukan.analytics.repo.AnalyticsStore.Count;
import in.apnidukan.analytics.repo.AnalyticsStore.MonthFlow;
import in.apnidukan.analytics.repo.AnalyticsStore.MonthMoney;
import in.apnidukan.analytics.repo.AnalyticsStore.PaymentLine;
import in.apnidukan.analytics.repo.AnalyticsStore.Point;
import in.apnidukan.analytics.repo.AnalyticsStore.Recurring;
import in.apnidukan.analytics.repo.AnalyticsStore.SalonLine;
import in.apnidukan.analytics.repo.AnalyticsStore.SalonUse;
import in.apnidukan.analytics.repo.AnalyticsStore.VersionDay;
import in.apnidukan.analytics.repo.DailyTotals;
import in.apnidukan.common.context.TeamRole;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The admin website's Analytics page: money, subscriptions, people, bookings, salons' use and app versions. Super
 * admins only (404 for anyone not on the team, 403 SUPER_ADMIN_ONLY for the rest of the team). Only totals and
 * per-salon figures: never a list of customers or what one person did.
 *
 * Google doesn't tell us its fee per payment (only the Play Console shows it), so Google's part is an estimate at the
 * rates saved on the page (15% on Play billing, 11% on Razorpay picked in the app); Razorpay's fee is exact. Days and
 * months are India's.
 */
@Service
public class AnalyticsService {

    /** Longest range a page asks for at once (a year and a day). */
    static final int MAX_DAYS = 366;
    static final int MAX_MONTHS = 36;
    static final int MAX_CSV_ROWS = 50_000;
    static final int PAGE = 50;
    /** A rate above 30% would be a typing mistake: Google's highest is 30%. */
    static final int MAX_FEE_BPS = 3_000;
    private static final Pattern PINCODE = Pattern.compile("[1-9][0-9]{5}");
    private static final Pattern MONTH = Pattern.compile("20[0-9]{2}-(0[1-9]|1[0-2])");
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AnalyticsStore store;
    private final UserDirectory users;
    private final Clock clock;

    public AnalyticsService(AnalyticsStore store, UserDirectory users, Clock clock) {
        this.store = store;
        this.users = users;
        this.clock = clock;
    }

    // ---- Money ---------------------------------------------------------------------------------------------------

    /** One month's money for one source (or all: source null), in paise. Fees: Razorpay exact, Google estimated. */
    public record Money(String source, long payments, long salons, long grossPaise, long refundsPaise,
                        long refundCount, long razorpayFeePaise, long googleFeeEstimatePaise, long netPaise) {
    }

    public record MonthView(String month, Money total, List<Money> bySource) {
        public MonthView {
            bySource = List.copyOf(bySource);
        }
    }

    /** The money coming in each month at today's paid salons (what each last paid). */
    public record RecurringView(long salons, long monthlyPaise, List<Recurring> bySource) {
        public RecurringView {
            bySource = List.copyOf(bySource);
        }
    }

    public record MoneyView(List<MonthView> months, RecurringView recurring, FeeSettings fees) {
        public MoneyView {
            months = List.copyOf(months);
        }
    }

    public MoneyView money(UUID caller, int months) {
        requireSuperAdmin(caller);
        if (months < 1 || months > MAX_MONTHS) {
            throw invalid("months");
        }
        Instant now = now();
        YearMonth last = YearMonth.from(now.atZone(DailyTotals.INDIA));
        YearMonth first = last.minusMonths(months - 1L);
        FeeSettings fees = store.settings();
        Map<String, List<Money>> bySource = new LinkedHashMap<>();
        for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
            bySource.put(m.toString(), new ArrayList<>());
        }
        for (MonthMoney row : store.money(first.atDay(1).atStartOfDay(DailyTotals.INDIA).toInstant())) {
            List<Money> list = bySource.get(row.month());
            if (list != null) {
                list.add(money(row, fees));
            }
        }
        List<MonthView> out = new ArrayList<>();
        bySource.forEach((month, list) -> out.add(new MonthView(month, sum(list), List.copyOf(list))));
        List<Recurring> recurring = store.recurring(now);
        return new MoneyView(out, new RecurringView(recurring.stream().mapToLong(Recurring::salons).sum(),
                recurring.stream().mapToLong(Recurring::monthlyPaise).sum(), recurring), fees);
    }

    public static Money money(MonthMoney row, FeeSettings fees) {
        long kept = row.grossPaise() - row.refundsPaise();
        int bps = switch (row.source()) {
            case "GOOGLE_PLAY" -> fees.playFeeBps();
            case "APP_RAZORPAY" -> fees.userChoiceFeeBps();
            default -> 0;
        };
        long google = Math.max(0, Math.round(kept * (double) bps / 10_000));
        return new Money(row.source(), row.charges(), row.salons(), row.grossPaise(), row.refundsPaise(),
                row.refundCount(), row.razorpayFeePaise(), google, kept - row.razorpayFeePaise() - google);
    }

    private static Money sum(List<Money> list) {
        long payments = 0;
        long salons = 0;
        long gross = 0;
        long refunds = 0;
        long refundCount = 0;
        long razorpay = 0;
        long google = 0;
        long net = 0;
        for (Money m : list) {
            payments += m.payments();
            salons += m.salons();   // a salon paying from two sources in one month counts twice: rare, and said so
            gross += m.grossPaise();
            refunds += m.refundsPaise();
            refundCount += m.refundCount();
            razorpay += m.razorpayFeePaise();
            google += m.googleFeeEstimatePaise();
            net += m.netPaise();
        }
        return new Money(null, payments, salons, gross, refunds, refundCount, razorpay, google, net);
    }

    /** A month's payments as a CSV file (for the accountant), oldest first. 400 VALIDATION_FAILED for a bad month. */
    public String moneyCsv(UUID caller, String month) {
        requireSuperAdmin(caller);
        if (month == null || !MONTH.matcher(month).matches()) {
            throw invalid("month");
        }
        YearMonth m = YearMonth.parse(month);
        Instant from = m.atDay(1).atStartOfDay(DailyTotals.INDIA).toInstant();
        Instant to = m.plusMonths(1).atDay(1).atStartOfDay(DailyTotals.INDIA).toInstant();
        List<PaymentLine> lines = store.payments(from, to, MAX_CSV_ROWS + 1);
        boolean cut = lines.size() > MAX_CSV_ROWS;
        if (cut) {
            lines = lines.subList(0, MAX_CSV_ROWS);
        }
        StringBuilder csv = new StringBuilder(64 + lines.size() * 120);
        csv.append("Date (India),Salon,Area,Paid through,Type,Amount (₹),Razorpay fee (₹),Payment id,Imported\r\n");
        for (PaymentLine p : lines) {
            csv.append(CSV_TIME.format(p.paidAt().atZone(DailyTotals.INDIA))).append(',')
                    .append(cell(p.shopName())).append(',')
                    .append(cell(p.area())).append(',')
                    .append(sourceName(p.source())).append(',')
                    .append("REFUND".equals(p.kind()) ? "Refund" : "Payment").append(',')
                    .append(rupees(p.amountPaise())).append(',')
                    .append(p.feePaise() == null ? "" : rupees(p.feePaise())).append(',')
                    .append(cell(p.externalId())).append(',')
                    .append(p.imported() ? "yes" : "no").append("\r\n");
        }
        if (cut) {
            // Never a silently short file: the accountant sees it is cut
            csv.append("Only the first ").append(MAX_CSV_ROWS).append(" payments of the month are in this file\r\n");
        }
        return csv.toString();
    }

    /** A CSV cell: quoted when needed, and text a spreadsheet would run as a formula starts with ' instead. */
    public static String cell(String value) {
        if (value == null) {
            return "";
        }
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    public static String rupees(long paise) {
        long abs = Math.abs(paise);
        return (paise < 0 ? "-" : "") + abs / 100 + "." + String.format("%02d", abs % 100);
    }

    private static String sourceName(String source) {
        return switch (source) {
            case "GOOGLE_PLAY" -> "Google Play";
            case "APP_RAZORPAY" -> "Razorpay (in the app)";
            default -> "Razorpay (website)";
        };
    }

    // ---- Google's fee rates --------------------------------------------------------------------------------------

    public FeeSettings settings(UUID caller) {
        requireSuperAdmin(caller);
        return store.settings();
    }

    /** Send the {@code version} the page showed. 409 SETTINGS_CHANGED when someone saved since. */
    @Transactional
    public FeeSettings saveSettings(UUID caller, Integer playFeeBps, Integer userChoiceFeeBps, Integer version) {
        requireSuperAdmin(caller);
        if (playFeeBps == null || playFeeBps < 0 || playFeeBps > MAX_FEE_BPS) {
            throw invalid("playFeeBps");
        }
        if (userChoiceFeeBps == null || userChoiceFeeBps < 0 || userChoiceFeeBps > MAX_FEE_BPS) {
            throw invalid("userChoiceFeeBps");
        }
        if (version == null || version < 0) {
            throw invalid("version");
        }
        Instant now = now();
        if (!store.saveSettings(playFeeBps, userChoiceFeeBps, version, caller, now)) {
            throw new ApiException(HttpStatus.CONFLICT, "SETTINGS_CHANGED",
                    "Someone else changed these rates. Look again.");
        }
        store.logEvent(caller, "ANALYTICS_SETTINGS_CHANGED", "Google's fee: Play billing " + percent(playFeeBps)
                + ", Razorpay in the app " + percent(userChoiceFeeBps), now);
        return store.settings();
    }

    private static String percent(int bps) {
        return bps / 100 + (bps % 100 == 0 ? "" : "." + String.format("%02d", bps % 100)) + "%";
    }

    // ---- Subscriptions -------------------------------------------------------------------------------------------

    public record SourceCount(String source, long salons, long renewing) {
    }

    public record SubscriptionsView(long trial, long trialWithAutoPay, long paying, List<SourceCount> payingBySource,
                                    long grace, long lapsed, long paymentProblems, List<MonthFlow> months,
                                    List<SalonLine> trialsEnding, List<SalonLine> problems) {
        public SubscriptionsView {
            payingBySource = List.copyOf(payingBySource);
            months = List.copyOf(months);
            trialsEnding = List.copyOf(trialsEnding);
            problems = List.copyOf(problems);
        }
    }

    public SubscriptionsView subscriptions(UUID caller) {
        requireSuperAdmin(caller);
        Instant now = now();
        long trial = 0;
        long trialRenewing = 0;
        long grace = 0;
        long lapsed = 0;
        long problems = 0;
        Map<String, long[]> sources = new LinkedHashMap<>();
        for (String s : List.of("WEBSITE", "APP_RAZORPAY", "GOOGLE_PLAY")) {
            sources.put(s, new long[2]);
        }
        for (Count c : store.standing(now)) {
            if (c.problem()) {
                problems += c.salons();
            }
            switch (c.state()) {
                case "TRIAL" -> {
                    trial += c.salons();
                    if (c.renewing()) {
                        trialRenewing += c.salons();
                    }
                }
                case "PAYING" -> {
                    long[] n = sources.computeIfAbsent(c.source(), k -> new long[2]);
                    n[0] += c.salons();
                    if (c.renewing()) {
                        n[1] += c.salons();
                    }
                }
                case "GRACE" -> grace += c.salons();
                default -> lapsed += c.salons();
            }
        }
        List<SourceCount> paying = new ArrayList<>();
        sources.forEach((s, n) -> paying.add(new SourceCount(s, n[0], n[1])));
        YearMonth first = YearMonth.from(now.atZone(DailyTotals.INDIA)).minusMonths(11);
        Instant from = first.atDay(1).atStartOfDay(DailyTotals.INDIA).toInstant();
        return new SubscriptionsView(trial, trialRenewing, paying.stream().mapToLong(SourceCount::salons).sum(),
                paying, grace, lapsed, problems, store.flow(from, now),
                store.trialsEnding(now, Duration.ofDays(7), PAGE), store.paymentProblems(now, PAGE));
    }

    // ---- People --------------------------------------------------------------------------------------------------

    public record PeopleView(LocalDate from, LocalDate to, List<Point> accounts, List<Point> salons,
                             long salonsEverApproved, List<Point> signups, List<Point> salonsRegistered,
                             List<Point> salonsApproved, Double approvalHoursMedian, List<Point> activeDay,
                             List<Point> activeToday, List<Point> activeWeek, List<Point> activeMonth) {
        public PeopleView {
            accounts = List.copyOf(accounts);
            salons = List.copyOf(salons);
            signups = List.copyOf(signups);
            salonsRegistered = List.copyOf(salonsRegistered);
            salonsApproved = List.copyOf(salonsApproved);
            activeDay = List.copyOf(activeDay);
            activeToday = List.copyOf(activeToday);
            activeWeek = List.copyOf(activeWeek);
            activeMonth = List.copyOf(activeMonth);
        }
    }

    public PeopleView people(UUID caller, LocalDate from, LocalDate to) {
        requireSuperAdmin(caller);
        Range r = range(from, to);
        List<Point> ever = store.latest("salons_ever_approved");
        return new PeopleView(r.from, r.to, store.latest("users_now"), store.latest("salons_now"),
                ever.isEmpty() ? 0 : ever.get(0).value(), store.series("signups", r.from, r.to),
                store.series("salons_registered", r.from, r.to), store.series("salons_approved", r.from, r.to),
                store.approvalHours(start(r.from), start(r.to.plusDays(1))),
                store.series("active_day", r.from, r.to), store.latest("active_day"), store.latest("active_week"),
                store.latest("active_month"));
    }

    // ---- Bookings ------------------------------------------------------------------------------------------------

    /** For one area (pincode) only the bookings made and visits that happened per day; the rest is for all areas. */
    public record BookingsView(LocalDate from, LocalDate to, String pincode, List<Point> created, List<Point> visits,
                               List<Point> visitsByStatus, List<Point> busiestHours, List<Point> topAreas) {
        public BookingsView {
            created = List.copyOf(created);
            visits = List.copyOf(visits);
            visitsByStatus = List.copyOf(visitsByStatus);
            busiestHours = List.copyOf(busiestHours);
            topAreas = List.copyOf(topAreas);
        }
    }

    public BookingsView bookings(UUID caller, LocalDate from, LocalDate to, String pincode) {
        requireSuperAdmin(caller);
        Range r = range(from, to);
        if (pincode != null && !PINCODE.matcher(pincode).matches()) {
            throw invalid("pincode");
        }
        if (pincode != null) {
            return new BookingsView(r.from, r.to, pincode, store.series("bookings_by_pincode", pincode, r.from, r.to),
                    store.series("completed_by_pincode", pincode, r.from, r.to), List.of(), List.of(), List.of());
        }
        return new BookingsView(r.from, r.to, null, store.series("bookings_created", r.from, r.to), List.of(),
                store.totals("visits", r.from, r.to, 50), store.totals("visit_hours", r.from, r.to, 24),
                store.totals("bookings_by_pincode", r.from, r.to, 20));
    }

    // ---- Salons' use ---------------------------------------------------------------------------------------------

    public record SalonsView(List<SalonUse> salons, boolean more, long quietSalons) {
        public SalonsView {
            salons = List.copyOf(salons);
        }
    }

    /** {@code sort}: "quiet" (least used first) or "bookings" (most first); 50 a page. */
    public SalonsView salons(UUID caller, String sort, String pincode, int page) {
        requireSuperAdmin(caller);
        if (!"quiet".equals(sort) && !"bookings".equals(sort)) {
            throw invalid("sort");
        }
        if (pincode != null && !PINCODE.matcher(pincode).matches()) {
            throw invalid("pincode");
        }
        if (page < 0 || page > 1_000) {
            throw invalid("page");
        }
        List<SalonUse> rows = store.salons("quiet".equals(sort), pincode, page * PAGE, PAGE);
        boolean more = rows.size() > PAGE;
        LocalDate today = today();
        return new SalonsView(List.copyOf(more ? rows.subList(0, PAGE) : rows), more,
                store.quietSalons(today.minusDays(7)));
    }

    // ---- App versions --------------------------------------------------------------------------------------------

    public record VersionsView(LocalDate today, List<VersionDay> days) {
        public VersionsView {
            days = List.copyOf(days);
        }
    }

    /** The last 7 days (today so far included). */
    public VersionsView appVersions(UUID caller) {
        requireSuperAdmin(caller);
        LocalDate today = today();
        return new VersionsView(today, store.versions(today.minusDays(6)));
    }

    // ---- Helpers -------------------------------------------------------------------------------------------------

    private record Range(LocalDate from, LocalDate to) {
    }

    /** Both days included; default the last 30 days. 400 VALIDATION_FAILED when back to front or too long. */
    private Range range(LocalDate from, LocalDate to) {
        LocalDate today = today();
        LocalDate t = to == null ? today : to;
        LocalDate f = from == null ? t.minusDays(29) : from;
        if (t.isAfter(today.plusDays(1)) || t.getYear() < 2020) {
            throw invalid("to");
        }
        if (f.isAfter(t) || f.plusDays(MAX_DAYS).isBefore(t.plusDays(1))) {
            throw invalid("from");
        }
        return new Range(f, t);
    }

    /** A day from a query (yyyy-MM-dd), or null when not given; 400 VALIDATION_FAILED naming the field. */
    public static LocalDate parseDay(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid(field);
        }
    }

    private static Instant start(LocalDate day) {
        return day.atStartOfDay(DailyTotals.INDIA).toInstant();
    }

    private void requireSuperAdmin(UUID caller) {
        TeamRole role = users.teamRole(caller);
        if (role == TeamRole.NONE) {
            throw ApiException.notFound("Page");
        }
        if (role != TeamRole.SUPER_ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SUPER_ADMIN_ONLY", "Only a super admin can do this.");
        }
    }

    private static ApiException invalid(String field) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                Map.of(field, "not valid"));
    }

    private Instant now() {
        return clock.instant();
    }

    private LocalDate today() {
        return now().atZone(DailyTotals.INDIA).toLocalDate();
    }
}
