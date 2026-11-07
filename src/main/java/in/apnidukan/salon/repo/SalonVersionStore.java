package in.apnidukan.salon.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * salon_versions (V17): each salon's change counters, kept by database triggers on every table its day board,
 * upcoming list and setup are read from, moved once per transaction at its commit (V21). Read with one primary-key
 * lookup; never written by the app.
 */
@Repository
public class SalonVersionStore {

    /**
     * @param version      moves on any change the salon's screens could show (appointments, barbers, hours, services,
     *                     members, walk-in access, pauses, settings, a customer's name)
     * @param setupVersion moves only when the setup changes (barbers, their hours and services, services, settings)
     */
    public record Versions(long version, long setupVersion) {
    }

    /**
     * The counters and the pause, read together.
     * @param pausedUntil null if online bookings were never paused (or resumed); may be in the past
     */
    public record Now(Versions versions, java.time.Instant pausedUntil) {
    }

    private final JdbcTemplate jdbc;

    public SalonVersionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The salon's counters; 0 and 0 if nothing of it changed since V17 (no row yet). */
    public Versions find(UUID shopId) {
        return jdbc.query("SELECT version, setup_version FROM salon_versions WHERE shop_id = ?",
                        (rs, i) -> new Versions(rs.getLong(1), rs.getLong(2)), shopId)
                .stream().findFirst().orElse(new Versions(0, 0));
    }

    /**
     * find() and the salon's pause (salon_pauses) in one query: a customer's free-time check needs both, and the setup
     * kept in memory under setup_version (SalonSetups) needs nothing else.
     */
    public Now findWithPause(UUID shopId) {
        return jdbc.queryForObject("SELECT v.version, v.setup_version, p.paused_until FROM (SELECT ?::uuid AS id) s "
                        + "LEFT JOIN salon_versions v ON v.shop_id = s.id LEFT JOIN salon_pauses p ON p.shop_id = s.id",
                (rs, i) -> {
                    java.sql.Timestamp paused = rs.getTimestamp(3);
                    return new Now(new Versions(rs.getLong(1), rs.getLong(2)),
                            paused == null ? null : paused.toInstant());
                }, shopId);
    }

    /**
     * The counters as a day board answer needs them, and the oldest transaction still running (its id: the day
     * board's delta cursor, see V21), in one query. Read before the board's rows, so nothing written after it can be
     * missed by the next delta.
     */
    public record Board(long version, long setupVersion, long oldestRunningXid) {
    }

    public Board board(UUID shopId) {
        return jdbc.queryForObject("SELECT v.version, v.setup_version, "
                        + "pg_snapshot_xmin(pg_current_snapshot())::text::bigint FROM (SELECT ?::uuid AS id) s "
                        + "LEFT JOIN salon_versions v ON v.shop_id = s.id",
                (rs, i) -> new Board(rs.getLong(1), rs.getLong(2), rs.getLong(3)), shopId);
    }

    /**
     * A customer's name or phone changed (or the account went): every appointment of theirs is written again
     * (nothing in it changes), so the salons' counters move at commit (V21) and their rows show in the day boards'
     * deltas with the new name. The rows are locked in id order (ix_appointments_customer finds them), the order an
     * account deletion locks a customer's open bookings in, so the two can only wait for each other.
     */
    public void bumpForCustomer(UUID customerId) {
        jdbc.update("WITH theirs AS MATERIALIZED (SELECT id FROM appointments WHERE customer_id = ? ORDER BY id "
                + "FOR UPDATE) UPDATE appointments SET change_seq = change_seq WHERE id IN (SELECT id FROM theirs)",
                customerId);
    }
}
