package in.apnidukan.leads.service;

import in.apnidukan.common.context.TeamRole;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.security.RateLimiter;
import in.apnidukan.leads.repo.LeadStore;
import in.apnidukan.leads.repo.PincodeDirectory;
import in.apnidukan.leads.service.LeadService.AreaSince;
import in.apnidukan.leads.service.LeadService.ChangesRequest;
import in.apnidukan.leads.service.LeadService.RefreshRequest;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Checking a searched area for changes reads only our own database, so when Redis (the per-person count) doesn't
 * answer, the check and the refresh still go ahead, and never touch the map.
 */
class LeadChecksRedisDownTest {

    private static final UUID AGENT = UUID.fromString("c0000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-11-26T06:00:00Z");
    private static final PincodeDirectory.Pin PIN = new PincodeDirectory.Pin("500072", "Medchal Malkajgiri",
            "Telangana", "Kukatpally", 17.49, 78.40, 3000, false);

    private final PincodeDirectory pincodes = mock(PincodeDirectory.class);
    private final LeadStore store = mock(LeadStore.class);
    private final LeadScanner scanner = mock(LeadScanner.class);
    private final UserDirectory users = mock(UserDirectory.class);
    private final RateLimiter limits = mock(RateLimiter.class);
    private final LeadService service = new LeadService(pincodes, store, scanner, users, limits,
            new LeadMatchKeys("test-secret"), Clock.fixed(NOW, ZoneOffset.UTC), 60, 30, 6, 60);

    LeadChecksRedisDownTest() {
        when(users.teamRole(AGENT)).thenReturn(TeamRole.FIELD_AGENT);
        when(pincodes.pin("500072")).thenReturn(Optional.of(PIN));
        when(limits.tryHit(any(), anyInt(), any())).thenThrow(new RedisConnectionFailureException("down"));
    }

    @Test
    void aCheckGoesAheadWhenRedisIsDown() {
        Instant since = NOW.minusSeconds(120);
        when(store.changedSince(Map.of("500072", since))).thenReturn(Map.of("500072", NOW.minusSeconds(30)));
        LeadService.ChangesView v = service.changes(AGENT, new ChangesRequest(List.of(new AreaSince("500072", since))));
        assertThat(v.areas()).singleElement().satisfies(c -> assertThat(c.pincode()).isEqualTo("500072"));
        assertThat(v.asOf()).isEqualTo(NOW.minus(LeadService.SETTLE));
        verifyNoInteractions(scanner);
    }

    @Test
    void aRefreshGoesAheadWhenRedisIsDown() {
        when(store.leads(any())).thenReturn(Map.of());
        when(store.contactedIn(any())).thenReturn(List.of());
        when(store.salonsIn(any())).thenReturn(List.of());
        LeadService.AreaStatusView v = service.refresh(AGENT, "500072", new RefreshRequest(List.of(
                new LeadService.SeenShop("mb-a", "A", 17.49, 78.40, null, null))));
        assertThat(v.shops()).singleElement().satisfies(s -> {
            assertThat(s.mapboxId()).isEqualTo("mb-a");
            assertThat(s.clozest()).isNull();
        });
        verifyNoInteractions(scanner);
    }

    @Test
    void anEmptyCheckNeitherCountsNorReads() {
        LeadService.ChangesView v = service.changes(AGENT, new ChangesRequest(List.of()));
        assertThat(v.areas()).isEmpty();
        verifyNoInteractions(limits, store, scanner);
    }
}
