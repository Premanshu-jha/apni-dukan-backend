package in.apnidukan.shops.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.security.RateLimiter;
import in.apnidukan.shops.domain.ShopMember;
import in.apnidukan.shops.domain.ShopRole;
import in.apnidukan.shops.repo.ShopMemberRepository;
import in.apnidukan.shops.service.AddressLocator.Asker;
import in.apnidukan.shops.service.AddressLocator.Located;
import in.apnidukan.shops.service.GoogleGeocoder.Found;
import in.apnidukan.shops.service.GoogleGeocoder.Precision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The guards around Google: feature off, who may search, the per-person limits, the cache and the day's cap. */
class AddressLocatorTest {

    private static final UUID OWNER = UUID.randomUUID();
    // 23:30 UTC on 7 Nov: already 8 Nov in India, still 7 Nov in California, where Google's quota day runs
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T23:30:00Z"), ZoneOffset.UTC);

    private GoogleGeocoder google;
    private RateLimiter limits;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ShopMemberRepository members;
    private AddressLocator locator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        google = mock(GoogleGeocoder.class);
        limits = mock(RateLimiter.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        members = mock(ShopMemberRepository.class);
        when(redis.opsForValue()).thenReturn(values);
        when(limits.tryHit(anyString(), anyInt(), any())).thenReturn(true);
        when(limits.hit(anyString(), any())).thenReturn(1L);
        when(members.findByUserId(any())).thenReturn(Optional.empty());
        locator = new AddressLocator(google, limits, redis, members, CLOCK);
    }

    private Located locate() {
        return locator.locate(Asker.OWNER, OWNER, "  12,  MG   Road ", "Ameerpet", "Hyderabad", "500016");
    }

    private void googleFinds(Precision precision, boolean partial) {
        when(google.find(anyString(), anyString())).thenReturn(Optional.of(
                new Found(17.4374, 78.4482, precision, "12, MG Road, Hyderabad", partial)));
    }

    private static ShopMember memberAs(ShopRole role) {
        ShopMember m = mock(ShopMember.class);
        when(m.getRole()).thenReturn(role);
        return m;
    }

    @Test
    void withoutAKeyItIsOffAndNothingIsCounted() {
        AddressLocator off = new AddressLocator(null, limits, redis, members, CLOCK);
        assertThat(off.enabled()).isFalse();
        assertThatThrownBy(() -> off.locate(Asker.OWNER, OWNER, "12, MG Road", "Ameerpet", "Hyderabad", "500016"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("GEOCODING_OFF");
                    assertThat(e.getStatus().value()).isEqualTo(503);
                });
        verify(limits, never()).tryHit(anyString(), anyInt(), any());
    }

    @Test
    void asksGoogleWithTheTidiedAddressAndKeepsTheAnswer30Days() {
        googleFinds(Precision.STREET, false);
        Located l = locate();
        assertThat(l).isEqualTo(new Located(17.4374, 78.4482, Precision.STREET, "12, MG Road, Hyderabad"));
        verify(google).find("12, MG Road, Ameerpet, Hyderabad 500016", "500016");
        verify(values).set(startsWith("geocode:"), eq("17.4374|78.4482|STREET|12, MG Road, Hyderabad"),
                eq(Duration.ofDays(30)));
        verify(limits).tryHit("geocode-user-day:" + OWNER, 10, Duration.ofHours(24));
    }

    @Test
    void theDaysCapFollowsGooglesPacificDayNotIndias() {
        googleFinds(Precision.STREET, false);
        locate();
        verify(limits).hit("geocode-day:2026-11-07", Duration.ofHours(25));   // 05:00 IST on 8 Nov

        // Google's day turns at midnight Pacific (PST: 08:00 UTC, 13:30 IST)
        Clock before = Clock.fixed(Instant.parse("2026-11-08T07:59:00Z"), ZoneOffset.UTC);
        Clock after = Clock.fixed(Instant.parse("2026-11-08T08:00:00Z"), ZoneOffset.UTC);
        assertThat(new AddressLocator(google, limits, redis, members, before).googleDay()).isEqualTo("2026-11-07");
        assertThat(new AddressLocator(google, limits, redis, members, after).googleDay()).isEqualTo("2026-11-08");
        // In summer (PDT) it turns an hour earlier: 07:00 UTC, 12:30 IST
        Clock summer = Clock.fixed(Instant.parse("2026-07-01T07:00:00Z"), ZoneOffset.UTC);
        assertThat(new AddressLocator(google, limits, redis, members, summer).googleDay()).isEqualTo("2026-07-01");
    }

    @Test
    void aPartialMatchIsKeptOnlyADay() {
        googleFinds(Precision.AREA, true);
        assertThat(locate().precision()).isEqualTo(Precision.AREA);
        verify(values).set(startsWith("geocode:"), anyString(), eq(Duration.ofDays(1)));
    }

    @Test
    void aCachedAddressDoesntAskGoogleOrUseTheDaysLimits() {
        when(values.get(startsWith("geocode:"))).thenReturn("17.4374|78.4482|EXACT|12, MG Road | Ameerpet");
        Located l = locate();
        assertThat(l).isEqualTo(new Located(17.4374, 78.4482, Precision.EXACT, "12, MG Road | Ameerpet"));
        verify(google, never()).find(anyString(), anyString());
        verify(limits, never()).hit(startsWith("geocode-day:"), any());
        verify(limits, never()).tryHit(startsWith("geocode-user-day:"), anyInt(), any());
        verify(limits).tryHit("geocode-user:" + OWNER, 20, Duration.ofHours(1));   // still counted for the hour
    }

    @Test
    void theSameAddressTypedDifferentlyHitsTheSameCacheEntry() {
        String a = AddressLocator.sha256("12, MG Road, Ameerpet, Hyderabad 500016".toLowerCase(java.util.Locale.ROOT));
        when(values.get("geocode:" + a)).thenReturn("17.0|78.0|AREA|x");
        assertThat(locator.locate(Asker.OWNER, OWNER, "12, mg road", " AMEERPET ", "hyderabad", "500016").precision())
                .isEqualTo(Precision.AREA);
        verify(google, never()).find(anyString(), anyString());
    }

    @Test
    void anAddressGoogleDoesntKnowIsKeptSixHoursSoRetriesDontUseTheCap() {
        when(google.find(anyString(), anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(this::locate).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getCode()).isEqualTo("ADDRESS_NOT_FOUND");
            assertThat(e.getStatus().value()).isEqualTo(404);
        });
        verify(values).set(startsWith("geocode:"), eq("NONE"), eq(Duration.ofHours(6)));

        // Asked again: answered from the cache
        when(values.get(startsWith("geocode:"))).thenReturn("NONE");
        assertThatThrownBy(this::locate).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("ADDRESS_NOT_FOUND"));
        verify(google).find(anyString(), anyString());   // still only the first time
        verify(limits).hit(startsWith("geocode-day:"), any());
    }

    @Test
    void past20AnHourThePersonIsToldToWait() {
        when(limits.tryHit(eq("geocode-user:" + OWNER), eq(20), any())).thenReturn(false);
        when(limits.secondsLeft("geocode-user:" + OWNER)).thenReturn(1200L);
        assertThatThrownBy(this::locate).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getCode()).isEqualTo("TRY_LATER");
            assertThat(e.getStatus().value()).isEqualTo(429);
            assertThat(e.getRetryAfterSeconds()).isEqualTo(1200L);
        });
        verify(google, never()).find(anyString(), anyString());
    }

    @Test
    void past10GoogleLookupsADayThePersonIsToldToWaitAndTheDaysCapIsntTouched() {
        when(limits.tryHit(eq("geocode-user-day:" + OWNER), eq(10), any())).thenReturn(false);
        when(limits.secondsLeft("geocode-user-day:" + OWNER)).thenReturn(30_000L);
        assertThatThrownBy(this::locate).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getCode()).isEqualTo("TRY_LATER");
            assertThat(e.getRetryAfterSeconds()).isEqualTo(30_000L);
        });
        verify(limits, never()).hit(startsWith("geocode-day:"), any());
        verify(google, never()).find(anyString(), anyString());
    }

    @Test
    void pastTheDaysCapGoogleIsNotAsked() {
        when(limits.hit(startsWith("geocode-day:"), any())).thenReturn(281L);
        assertThatThrownBy(this::locate).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("GEOCODING_UNAVAILABLE"));
        verify(google, never()).find(anyString(), anyString());
    }

    @Test
    void whenTheDaysCapIsAlreadyUsedThePersonsAllowanceIsntTouched() {
        when(limits.current(startsWith("geocode-day:"))).thenReturn(280L);
        assertThatThrownBy(this::locate).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("GEOCODING_UNAVAILABLE"));
        assertThatThrownBy(() -> describe("28.6215", "77.3652")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("GEOCODING_UNAVAILABLE"));
        verify(limits, never()).tryHit(startsWith("geocode-user-day:"), anyInt(), any());
        verify(limits, never()).hit(startsWith("geocode-day:"), any());
        verify(google, never()).find(anyString(), anyString());
    }

    @Test
    void theCapsLastLookupStillGoesThrough() {
        when(limits.hit(startsWith("geocode-day:"), any())).thenReturn(280L);
        googleFinds(Precision.AREA, false);
        assertThat(locate().latitude()).isEqualTo(17.4374);
    }

    @Test
    void redisDownAtTheDaysCounterStopsBeforeGoogle() {
        doThrow(new RedisConnectionFailureException("down")).when(limits).hit(startsWith("geocode-day:"), any());
        // The global handler answers this one 503 SERVICE_BUSY
        assertThatThrownBy(this::locate).isInstanceOf(DataAccessResourceFailureException.class);
        verify(google, never()).find(anyString(), anyString());
    }

    @Test
    void ifRedisDoesntKeepTheAnswerTheOwnerStillGetsThePin() {
        googleFinds(Precision.EXACT, false);
        doThrow(new RedisConnectionFailureException("down")).when(values)
                .set(anyString(), anyString(), any(Duration.class));
        assertThat(locate().precision()).isEqualTo(Precision.EXACT);
    }

    @Test
    void helpersAndBarbersMayNotSearchButOwnersMay() {
        for (ShopRole role : new ShopRole[] {ShopRole.HELPER, ShopRole.BARBER}) {
            ShopMember member = memberAs(role);
            when(members.findByUserId(OWNER)).thenReturn(Optional.of(member));
            assertThatThrownBy(this::locate).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getCode()).isEqualTo("OWNER_ONLY");
                assertThat(e.getStatus().value()).isEqualTo(403);
            });
        }
        verify(limits, never()).tryHit(anyString(), anyInt(), any());

        ShopMember owner = memberAs(ShopRole.OWNER);
        when(members.findByUserId(OWNER)).thenReturn(Optional.of(owner));
        googleFinds(Precision.EXACT, false);
        assertThat(locate().precision()).isEqualTo(Precision.EXACT);
    }

    @Test
    void aCustomerMaySearchEvenAsABarberAndCountsInCustomersShare() {
        ShopMember barber = memberAs(ShopRole.BARBER);
        when(members.findByUserId(OWNER)).thenReturn(Optional.of(barber));
        googleFinds(Precision.STREET, false);
        assertThat(locator.locate(Asker.CUSTOMER, OWNER, "12, MG Road", "Ameerpet", "Hyderabad", "500016").precision())
                .isEqualTo(Precision.STREET);
        verify(limits).tryHit("geocode-user:" + OWNER, 20, Duration.ofHours(1));
        verify(limits).tryHit("geocode-user-day:" + OWNER, 10, Duration.ofHours(24));
        verify(limits).hit("geocode-day-customers:2026-11-07", Duration.ofHours(25));
        verify(limits).hit("geocode-day:2026-11-07", Duration.ofHours(25));
    }

    @Test
    void customersShareOfTheDayLeavesTheRestToOwners() {
        // Customers have used their 100: a customer is refused before anything is counted, an owner still searches
        when(limits.current("geocode-day-customers:2026-11-07")).thenReturn(100L);
        assertThatThrownBy(() -> locator.describe(Asker.CUSTOMER, OWNER, new java.math.BigDecimal("28.6215"),
                new java.math.BigDecimal("77.3652"))).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("GEOCODING_UNAVAILABLE"));
        verify(limits, never()).tryHit(startsWith("geocode-user-day:"), anyInt(), any());
        verify(google, never()).describe(anyString(), anyString());
        googleFinds(Precision.EXACT, false);
        assertThat(locate().precision()).isEqualTo(Precision.EXACT);
        verify(limits, never()).hit(startsWith("geocode-day-customers:"), any());

        // Two customers at once past the share: the one counted 101st is refused, its own allowance is given back,
        // and the day's count isn't touched (one hit: the owner's above)
        when(limits.current("geocode-day-customers:2026-11-07")).thenReturn(99L);
        when(limits.hit(startsWith("geocode-day-customers:"), any())).thenReturn(101L);
        assertThatThrownBy(() -> locator.locate(Asker.CUSTOMER, OWNER, "12, MG Road", "Ameerpet", "Hyderabad",
                "500017")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("GEOCODING_UNAVAILABLE"));
        verify(limits).giveBack("geocode-user-day:" + OWNER);
        verify(limits, org.mockito.Mockito.times(1)).hit(eq("geocode-day:2026-11-07"), any());
        verify(google).find(anyString(), anyString());   // only the owner's
    }

    @Test
    void pastTheDaysCapARefusedSearchGivesBackWhatItCounted() {
        when(limits.hit(startsWith("geocode-day:"), any())).thenReturn(281L);
        assertThatThrownBy(() -> locator.locate(Asker.CUSTOMER, OWNER, "12, MG Road", "Ameerpet", "Hyderabad",
                "500016")).isInstanceOf(ApiException.class);
        verify(limits).giveBack("geocode-user-day:" + OWNER);
        verify(limits).giveBack("geocode-day-customers:2026-11-07");
        verify(google, never()).find(anyString(), anyString());
    }

    private static final AddressLocator.Described NOIDA =
            new AddressLocator.Described("Ithum Tower, Sector 62 Road", "Sector 62", "Noida", "201309", "Ithum Tower, Noida");

    private AddressLocator.Described describe(String lat, String lng) {
        return locator.describe(Asker.OWNER, OWNER, new java.math.BigDecimal(lat), new java.math.BigDecimal(lng));
    }

    @Test
    void theAddressAtAPointIsAskedRoundedToFourDecimalsAndKept30Days() {
        when(google.describe(anyString(), anyString())).thenReturn(Optional.of(new GoogleGeocoder.Place(
                "Ithum Tower, Sector 62 Road", "Sector 62", "Noida", "201309", "Ithum Tower, Noida")));
        assertThat(describe("28.621549", "77.365249")).isEqualTo(NOIDA);
        verify(google).describe("28.6215", "77.3652");
        verify(values).set(eq("geocode-at:28.6215,77.3652"), anyString(), eq(Duration.ofDays(30)));
        // The same budget as the forward search
        verify(limits).tryHit("geocode-user:" + OWNER, 20, Duration.ofHours(1));
        verify(limits).tryHit("geocode-user-day:" + OWNER, 10, Duration.ofHours(24));
        verify(limits).hit("geocode-day:2026-11-07", Duration.ofHours(25));
    }

    @Test
    void aKeptAddressAtAPointComesBackWithoutGoogle() {
        when(values.get("geocode-at:28.6215,77.3652")).thenReturn(AddressLocator.toCache(NOIDA));
        assertThat(describe("28.62151", "77.36521")).isEqualTo(NOIDA);
        verify(google, never()).describe(anyString(), anyString());
        verify(limits, never()).hit(startsWith("geocode-day:"), any());
        // Empty fields survive the cache
        AddressLocator.Described partial = new AddressLocator.Described("", "Sector 62", "Noida", "", "");
        assertThat(AddressLocator.describedFromCache(AddressLocator.toCache(partial))).contains(partial);
        assertThat(AddressLocator.describedFromCache("garbage")).isEmpty();
    }

    @Test
    void nothingAtAPointIsKeptSixHours() {
        when(google.describe(anyString(), anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> describe("28.6215", "77.3652")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("ADDRESS_NOT_FOUND"));
        verify(values).set("geocode-at:28.6215,77.3652", "NONE", Duration.ofHours(6));
        when(values.get("geocode-at:28.6215,77.3652")).thenReturn("NONE");
        assertThatThrownBy(() -> describe("28.6215", "77.3652")).isInstanceOf(ApiException.class);
        verify(google).describe(anyString(), anyString());
    }

    @Test
    void theAddressAtAPointHasTheSameGuards() {
        AddressLocator off = new AddressLocator(null, limits, redis, members, CLOCK);
        assertThatThrownBy(() -> off.describe(Asker.OWNER, OWNER, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("GEOCODING_OFF"));

        when(limits.tryHit(eq("geocode-user-day:" + OWNER), eq(10), any())).thenReturn(false);
        assertThatThrownBy(() -> describe("28.6215", "77.3652")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("TRY_LATER"));

        ShopMember barber = memberAs(ShopRole.BARBER);
        when(members.findByUserId(OWNER)).thenReturn(Optional.of(barber));
        assertThatThrownBy(() -> describe("28.6215", "77.3652")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("OWNER_ONLY"));
        verify(google, never()).describe(anyString(), anyString());
    }

    @Test
    void ifRedisDoesntKeepTheAddressAtAPointItStillComesBack() {
        when(google.describe(anyString(), anyString())).thenReturn(Optional.of(new GoogleGeocoder.Place(
                "Ithum Tower, Sector 62 Road", "Sector 62", "Noida", "201309", "Ithum Tower, Noida")));
        doThrow(new RedisConnectionFailureException("down")).when(values)
                .set(anyString(), anyString(), any(Duration.class));
        assertThat(describe("28.6215", "77.3652")).isEqualTo(NOIDA);
    }

    @Test
    void anUnreadableCacheEntryIsAskedAgain() {
        assertThat(AddressLocator.fromCache("garbage")).isEmpty();
        assertThat(AddressLocator.fromCache("x|y|EXACT|a")).isEmpty();
        assertThat(AddressLocator.fromCache("1|2|NOPE|a")).isEmpty();
        assertThat(AddressLocator.fromCache(null)).isEmpty();
    }
}
