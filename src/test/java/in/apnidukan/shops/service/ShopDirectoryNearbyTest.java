package in.apnidukan.shops.service;

import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.shops.repo.ShopRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The statement nearby() sends for the salon search, without a database (what it finds is checked against real
 * Postgres in SalonSearchIntegrationTest): one statement, a value for every placeholder, and the search placed
 * before the page cursor and the order, so pages and order stay those of the list without a search.
 */
class ShopDirectoryNearbyTest {

    /** Records the statement and its values instead of running it. */
    static final class Recording extends JdbcTemplate {
        final List<String> sql = new ArrayList<>();
        final List<List<Object>> args = new ArrayList<>();

        @Override
        public <T> List<T> query(String statement, RowMapper<T> rowMapper, Object... values) {
            sql.add(statement);
            args.add(Arrays.asList(values));
            return List.of();
        }
    }

    private final Recording jdbc = new Recording();
    private final ShopDirectoryService directory = new ShopDirectoryService(mock(ShopRepository.class), jdbc,
            Clock.fixed(Instant.parse("2026-11-10T04:30:00Z"), ZoneOffset.UTC), false);

    private String nearby(ShopDirectory.Search search, ShopDirectory.Cursor after) {
        directory.nearby("SALON", new BigDecimal("17.466"), new BigDecimal("78.365"), 2000, 30, after, true, search);
        assertThat(jdbc.sql).hasSize(1);
        String sql = jdbc.sql.get(0);
        assertThat(sql.chars().filter(c -> c == '?').count()).as(sql).isEqualTo(jdbc.args.get(0).size());
        return sql;
    }

    @Test
    void withoutASearchTheStatementIsTheListAsBefore() {
        String sql = nearby(null, null);
        assertThat(sql).doesNotContain("salon_services", "LIKE", "similarity");
        assertThat(jdbc.args.get(0)).hasSize(6);   // the point (2), type, radius, the pause's now, limit
    }

    @Test
    void aSearchAddsTheNameAndServicesBeforeTheCursorAndTheOrder() {
        UUID last = UUID.randomUUID();
        String sql = nearby(new ShopDirectory.Search("%facial%", "facial", List.of("FACIAL", "CLEANUP")),
                new ShopDirectory.Cursor(120.5, last));
        assertThat(sql).contains("lower(s.name) LIKE ? ESCAPE '\\'", "lower(v.name) LIKE ? ESCAPE '\\'",
                "strpos(lower(s.name), qw.word) = 0", "strpos(lower(v.name), qw.word) = 0",
                "similarity(qw.word, nw.word) >= 0.35", "v.active", "st.active",
                "EXISTS (SELECT 1 FROM staff_hours sh WHERE sh.staff_id = st.id)");
        // Word by word, never the whole phrase against the whole name (one shared word found other salons)
        assertThat(sql).doesNotContain("word_similarity");
        assertThat(sql.indexOf("lower(s.name)")).isLessThan(sql.indexOf(") > (?, ?)"));
        assertThat(sql.indexOf(") > (?, ?)")).isLessThan(sql.indexOf("ORDER BY metres, s.id"));
        assertThat(sql.chars().filter(c -> c == '(').count()).isEqualTo(sql.chars().filter(c -> c == ')').count());
        assertThat(jdbc.args.get(0)).containsSubsequence("%facial%", "facial", "%facial%", "facial",
                "FACIAL,CLEANUP", 120.5, last, 30);
    }

    @Test
    void anExactSearchHasNoSimilarityAndNoPicturesIsAnEmptyList() {
        String sql = nearby(new ShopDirectory.Search("%50\\%%", null, List.of()), null);
        assertThat(sql).doesNotContain("similarity", "strpos");
        assertThat(sql).contains("EXISTS (SELECT 1 FROM staff_hours sh WHERE sh.staff_id = st.id)");
        assertThat(jdbc.args.get(0)).containsSubsequence("%50\\%%", "%50\\%%", "", 30);
    }
}
