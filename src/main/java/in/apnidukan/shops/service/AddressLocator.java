package in.apnidukan.shops.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.security.RateLimiter;
import in.apnidukan.shops.domain.ShopRole;
import in.apnidukan.shops.repo.ShopMemberRepository;
import in.apnidukan.shops.service.GoogleGeocoder.Found;
import in.apnidukan.shops.service.GoogleGeocoder.Place;
import in.apnidukan.shops.service.GoogleGeocoder.Precision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * "Find my salon on the map": turns the address the owner typed at registration into a pin (POST /shops/geocode),
 * which the owner then drags onto the door. The phone's GPS stays the other way; the field team checks the pin on
 * its visit either way.
 *
 * Asks Google ({@link GoogleGeocoder}) only when GOOGLE_GEOCODING_KEY is set; without it every call answers
 * 503 GEOCODING_OFF and the app hides the button. Only someone without a shop (registering) or a shop's owner may
 * search; a helper or barber gets 403 OWNER_ONLY. Guards, all in Redis (shared by every copy of the backend), in
 * this order:
 * <ul>
 *   <li>per person: {@value #PER_USER_PER_HOUR} searches an hour, cache hits included, then 429 TRY_LATER;</li>
 *   <li>the cache, by a hash of the tidied address: a found address is kept {@value #CACHE_DAYS} days (a partial
 *       match 1 day), an address Google doesn't know {@value #NOT_FOUND_HOURS} hours. A hit doesn't call Google and
 *       doesn't count below;</li>
 *   <li>per person: {@value #PER_USER_GOOGLE_PER_DAY} calls to Google a day, then 429 TRY_LATER, so one person (or one
 *       made-up account) can't use up everyone's day;</li>
 *   <li>for everyone: {@value #DAILY_CAP} calls to Google a day, under the 300 a day set as Google's own quota, so the
 *       bill can't grow; past it 503 GEOCODING_UNAVAILABLE. Counted by the Pacific-time date, because Google's quota
 *       day starts at midnight Pacific time (12:30 IST in summer, 13:30 IST in winter).</li>
 * </ul>
 * The guards run before Google is called: if Redis is down they fail (503 SERVICE_BUSY from the global handler), so
 * Google is never called unguarded. Only keeping an answer may fail quietly: the owner still gets the pin.
 *
 * The other way round, {@link #describe} (POST /shops/reverse-geocode) gives the address at the phone's GPS point, to
 * fill the form's empty fields. Same people, same limits and the same counters (one budget for both), its answers
 * kept by the point rounded to 4 decimals (about 11 m): found 30 days, nothing there {@value #NOT_FOUND_HOURS} hours.
 *
 * Customers set their saved places the same way (POST /me/places/geocode and /reverse-geocode, {@link Asker#CUSTOMER}):
 * anyone signed in, with the same per-person limits and cache, but at most {@value #CUSTOMER_DAILY_CAP} of the day's
 * calls to Google between all of them, so owners registering always have the rest of the day's
 * {@value #DAILY_CAP}.
 */
@Service
public class AddressLocator {

    private static final Logger log = LoggerFactory.getLogger(AddressLocator.class);
    static final int PER_USER_PER_HOUR = 20;
    static final int PER_USER_GOOGLE_PER_DAY = 10;
    static final int DAILY_CAP = 280;
    /** Of {@link #DAILY_CAP}, what customers may use between them (their saved places). */
    static final int CUSTOMER_DAILY_CAP = 100;
    static final int CACHE_DAYS = 30;
    static final int NOT_FOUND_HOURS = 6;
    /** Google's quota day. */
    static final ZoneId GOOGLE_ZONE = ZoneId.of("America/Los_Angeles");
    /** Kept in the cache for an address Google doesn't know. */
    static final String NOT_FOUND = "NONE";

    /** Who is searching: an owner registering (or someone about to), or a customer saving a place. */
    public enum Asker {
        OWNER, CUSTOMER
    }

    /** Where the typed address is: the pin to show, how exact it is, and Google's tidy form of the address. */
    public record Located(double latitude, double longitude, Precision precision, String formattedAddress) {
    }

    /** The address at a GPS point, in the form's fields ("" where Google gave nothing). */
    public record Described(String addressLine, String area, String city, String pincode, String formattedAddress) {
    }

    /** Between the fields of a kept reverse answer (can't appear in an address). */
    private static final String SEP = String.valueOf((char) 0x1F);

    private final GoogleGeocoder google;
    private final RateLimiter limits;
    private final StringRedisTemplate redis;
    private final ShopMemberRepository members;
    private final Clock clock;

    @Autowired
    public AddressLocator(@Value("${app.geocoding.google-key:}") String googleKey,
                          @Value("${app.geocoding.google-url:}") String googleUrl,
                          RateLimiter limits, StringRedisTemplate redis, ShopMemberRepository members, Clock clock) {
        this(googleKey.isBlank() ? null : new GoogleGeocoder(googleKey.strip(),
                        googleUrl.isBlank() ? GoogleGeocoder.GOOGLE : URI.create(googleUrl.strip())),
                limits, redis, members, clock);
    }

    /** @param google null when the feature is off (no key) */
    AddressLocator(GoogleGeocoder google, RateLimiter limits, StringRedisTemplate redis, ShopMemberRepository members,
                   Clock clock) {
        this.google = google;
        this.limits = limits;
        this.redis = redis;
        this.members = members;
        this.clock = clock;
    }

    /** True when GOOGLE_GEOCODING_KEY is set. */
    public boolean enabled() {
        return google != null;
    }

    /**
     * Where this address is. 503 GEOCODING_OFF (no key), 403 OWNER_ONLY (a helper or barber), 429 TRY_LATER (this
     * person's limits), 404 ADDRESS_NOT_FOUND, 503 GEOCODING_UNAVAILABLE (Google failed, or the day's cap is used).
     */
    public Located locate(Asker asker, UUID userId, String addressLine, String area, String city, String pincode) {
        if (google == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GEOCODING_OFF",
                    "Address search isn't available. Use your location.");
        }
        guard(asker, userId);

        String address = tidy(addressLine) + ", " + tidy(area) + ", " + tidy(city) + " " + pincode.strip();
        String cacheKey = "geocode:" + sha256(address.toLowerCase(Locale.ROOT));
        String cached = redis.opsForValue().get(cacheKey);
        if (NOT_FOUND.equals(cached)) {
            throw notFound();
        }
        Optional<Located> kept = fromCache(cached);
        if (kept.isPresent()) {
            return kept.get();
        }

        beforeGoogle(asker, userId);
        Optional<Found> answer = google.find(address, pincode.strip());
        if (answer.isEmpty()) {
            keep(cacheKey, NOT_FOUND, Duration.ofHours(NOT_FOUND_HOURS));
            throw notFound();
        }
        Found found = answer.get();
        Located located = new Located(found.latitude(), found.longitude(), found.precision(), found.formattedAddress());
        keep(cacheKey, toCache(located), found.partial() ? Duration.ofDays(1) : Duration.ofDays(CACHE_DAYS));
        return located;
    }

    /**
     * The address at this point (inside India: the request is checked), to fill the registration form. 503
     * GEOCODING_OFF, 403 OWNER_ONLY, 429 TRY_LATER, 404 ADDRESS_NOT_FOUND, 503 GEOCODING_UNAVAILABLE as for
     * {@link #locate}.
     */
    public Described describe(Asker asker, UUID userId, BigDecimal latitude, BigDecimal longitude) {
        if (google == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GEOCODING_OFF",
                    "Address search isn't available. Use your location.");
        }
        guard(asker, userId);
        String lat = latitude.setScale(4, RoundingMode.HALF_UP).toPlainString();
        String lng = longitude.setScale(4, RoundingMode.HALF_UP).toPlainString();
        String cacheKey = "geocode-at:" + lat + "," + lng;
        String cached = redis.opsForValue().get(cacheKey);
        if (NOT_FOUND.equals(cached)) {
            throw notFound();
        }
        Optional<Described> kept = describedFromCache(cached);
        if (kept.isPresent()) {
            return kept.get();
        }

        beforeGoogle(asker, userId);
        Optional<Place> answer = google.describe(lat, lng);
        if (answer.isEmpty()) {
            keep(cacheKey, NOT_FOUND, Duration.ofHours(NOT_FOUND_HOURS));
            throw notFound();
        }
        Place p = answer.get();
        Described described = new Described(p.addressLine(), p.area(), p.city(), p.pincode(), p.formattedAddress());
        keep(cacheKey, toCache(described), Duration.ofDays(CACHE_DAYS));
        return described;
    }

    /**
     * An owner's search: only someone without a shop or its owner (a customer's: anyone signed in); then this person's
     * searches this hour (cache hits included), one count whichever app asks.
     */
    private void guard(Asker asker, UUID userId) {
        if (asker == Asker.OWNER && members.findByUserId(userId).filter(m -> m.getRole() != ShopRole.OWNER).isPresent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the shop owner can set its location.");
        }
        requireUnder("geocode-user:" + userId, PER_USER_PER_HOUR, Duration.ofHours(1));
    }

    /**
     * Before each call to Google: if everyone's lookups for Google's day are already used up (or, for a customer,
     * customers' share of them), 503 without touching this person's allowance; then this person's lookups today; then
     * counts customers' (a customer) and everyone's.
     */
    private void beforeGoogle(Asker asker, UUID userId) {
        String dayKey = "geocode-day:" + googleDay();
        String customersKey = "geocode-day-customers:" + googleDay();
        if (limits.current(dayKey) >= DAILY_CAP
                || asker == Asker.CUSTOMER && limits.current(customersKey) >= CUSTOMER_DAILY_CAP) {
            throw GoogleGeocoder.unavailable();
        }
        String personKey = "geocode-user-day:" + userId;
        requireUnder(personKey, PER_USER_GOOGLE_PER_DAY, Duration.ofHours(24));
        // Two at once past the pre-check: the one counted over a cap is refused, and what it counted on the way is
        // given back (its person's allowance, customers' share), so a refusal never uses anything up
        if (asker == Asker.CUSTOMER && limits.hit(customersKey, Duration.ofHours(25)) > CUSTOMER_DAILY_CAP) {
            limits.giveBack(personKey);
            log.warn("Address search: customers' {} Google lookups for today are used up; customers get 503 until "
                    + "midnight Pacific time", CUSTOMER_DAILY_CAP);
            throw GoogleGeocoder.unavailable();
        }
        if (limits.hit(dayKey, Duration.ofHours(25)) > DAILY_CAP) {
            limits.giveBack(personKey);
            if (asker == Asker.CUSTOMER) {
                limits.giveBack(customersKey);
            }
            log.warn("Address search: today's {} Google lookups are used up; answering 503 until midnight Pacific "
                    + "time", DAILY_CAP);
            throw GoogleGeocoder.unavailable();
        }
    }

    /** Google's quota day: today's date in Pacific time (Google resets the daily quota at midnight there). */
    String googleDay() {
        return LocalDate.now(clock.withZone(GOOGLE_ZONE)).toString();
    }

    private void requireUnder(String key, int max, Duration window) {
        if (!limits.tryHit(key, max, window)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TRY_LATER",
                    "Many address searches in a short time. Wait a little, or use your location.")
                    .retryAfter(limits.secondsLeft(key));
        }
    }

    /** Keeps an answer; if Redis doesn't take it, the owner still gets theirs (it is only asked again next time). */
    private void keep(String cacheKey, String value, Duration ttl) {
        try {
            redis.opsForValue().set(cacheKey, value, ttl);
        } catch (DataAccessException redisDown) {
            log.warn("Redis didn't keep the address search answer ({})", redisDown.getClass().getSimpleName());
        }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ADDRESS_NOT_FOUND",
                "Couldn't find that address. Check the pincode and area, or use your location.");
    }

    /** Spaces collapsed, ends trimmed: "  12,  MG   Road " and "12, MG Road" are the same address. */
    static String tidy(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ");
    }

    /** "lat|lng|PRECISION|formatted address" (the address last: it may itself contain "|"). */
    static String toCache(Located l) {
        return l.latitude() + "|" + l.longitude() + "|" + l.precision().name() + "|" + l.formattedAddress();
    }

    /** The cached answer, or empty for none (or an unreadable one, which is then simply asked again). */
    static Optional<Located> fromCache(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split("\\|", 4);
        if (parts.length != 4) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Located(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                    Precision.valueOf(parts[2]), parts[3]));
        } catch (IllegalArgumentException e) {   // NumberFormatException is one too
            return Optional.empty();
        }
    }

    /** The five fields joined by an invisible separator. */
    static String toCache(Described d) {
        return String.join(SEP, d.addressLine(), d.area(), d.city(), d.pincode(), d.formattedAddress());
    }

    static Optional<Described> describedFromCache(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split(java.util.regex.Pattern.quote(SEP), -1);
        return parts.length == 5
                ? Optional.of(new Described(parts[0], parts[1], parts[2], parts[3], parts[4]))
                : Optional.empty();
    }

    static String sha256(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
