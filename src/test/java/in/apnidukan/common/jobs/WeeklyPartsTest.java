package in.apnidukan.common.jobs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** WeeklyParts around the database's two functions (which AppDatabaseRoleIntegrationTest runs for real). */
class WeeklyPartsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LongStatements statements = mock(LongStatements.class);
    private final WeeklyParts parts = new WeeklyParts(jdbc, statements,
            Clock.fixed(Instant.parse("2026-11-22T00:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void statementsRunTheWork() {
        when(statements.run(any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(0)).get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void makesTheComingWeeksThenCountsTheWeeksDropped() {
        when(jdbc.queryForObject("SELECT partitions_prepare(?, ?)", Integer.class, "refresh_tokens", 4)).thenReturn(1);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("refresh_tokens"), eq(30))).thenReturn(List.of(
                new WeeklyParts.Old("refresh_tokens_w20260907", LocalDate.parse("2026-09-07"), "DROPPED"),
                new WeeklyParts.Old("refresh_tokens_w20260914", LocalDate.parse("2026-09-14"), "BUSY"),
                new WeeklyParts.Old("refresh_tokens_w20260921", LocalDate.parse("2026-09-21"), "IN_USE")));
        assertThat(parts.maintain(WeeklyParts.Table.REFRESH_TOKENS, 30)).isEqualTo(1);
        verify(jdbc).queryForObject("SELECT partitions_prepare(?, ?)", Integer.class, "refresh_tokens", 4);
    }

    @Test
    @SuppressWarnings("unchecked")
    void neitherStepFailingStopsTheNightsRowCleanup() {
        when(jdbc.queryForObject("SELECT partitions_prepare(?, ?)", Integer.class, "notification_jobs", 4))
                .thenThrow(new QueryTimeoutException("busy"));
        when(jdbc.query(anyString(), any(RowMapper.class), eq("notification_jobs"), eq(90)))
                .thenReturn(List.of(new WeeklyParts.Old("notification_jobs_w20260105", LocalDate.parse("2026-01-05"),
                        "DROPPED")));
        assertThat(parts.maintain(WeeklyParts.Table.NOTIFICATION_JOBS, 90)).isEqualTo(1);   // the drop still ran

        when(jdbc.query(anyString(), any(RowMapper.class), eq("notification_jobs"), eq(90)))
                .thenThrow(new QueryTimeoutException("busy"));
        assertThat(parts.maintain(WeeklyParts.Table.NOTIFICATION_JOBS, 90)).isZero();   // and nothing is thrown
    }
}
