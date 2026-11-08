package in.apnidukan.shops.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.security.RateLimiter;
import in.apnidukan.shops.domain.ShopMember;
import in.apnidukan.shops.domain.ShopRole;
import in.apnidukan.shops.repo.ShopMemberRepository;
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
        return locator.locate(OWNER, "  12,  MG   Road ", "Ameerpet", "Hyderabad", "500016");
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
        assertThatThrownBy(() -> off.locate(OWNER, "12, MG Road", "Ameerpet", "Hyderabad", "500016"))
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
        assertThat(locator.locate(OWNER, "12, mg road", " AMEERPET ", "hyderabad", "500016").precision())
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
    void anUnreadableCacheEntryIsAskedAgain() {
        assertThat(AddressLocator.fromCache("garbage")).isEmpty();
        assertThat(AddressLocator.fromCache("x|y|EXACT|a")).isEmpty();
        assertThat(AddressLocator.fromCache("1|2|NOPE|a")).isEmpty();
        assertThat(AddressLocator.fromCache(null)).isEmpty();
    }
}
