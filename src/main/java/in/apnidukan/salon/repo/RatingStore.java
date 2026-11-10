package in.apnidukan.salon.repo;

import in.apnidukan.salon.domain.RatingTag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The salon_ratings and salon_rating_totals tables (V34): one rating per completed online booking, and each salon's
 * running totals, kept in step in the same transaction (save()).
 */
@Repository
public class RatingStore {

    /** A rating as stored. comment is null when none was given (or its account was deleted). */
    public record Rating(UUID appointmentId, UUID shopId, UUID customerId, int stars, int tags, String comment,
                         Instant createdAt, Instant updatedAt) {
    }

    /**
     * A salon's totals.
     * @param tagCounts how many ratings ticked each tag
     */
    public record Totals(int ratings, long starsSum, Map<RatingTag, Integer> tagCounts) {

        public static final Totals NONE = new Totals(0, 0, Map.of());

        public Totals {
            tagCounts = Map.copyOf(tagCounts);
        }

        /** Average stars to one decimal (4.65 → 4.7); null with no ratings. */
        public Double average() {
            return ratings == 0 ? null : Math.round(starsSum * 10.0 / ratings) / 10.0;
        }
    }

    private static final String TOTALS_COLUMNS = "shop_id, ratings, stars_sum, on_time, clean, friendly, skilled, "
            + "good_value";

    private static final RowMapper<Rating> ROW = (rs, i) -> new Rating(rs.getObject("appointment_id", UUID.class),
            rs.getObject("shop_id", UUID.class), rs.getObject("customer_id", UUID.class), rs.getInt("stars"),
            rs.getInt("tags"), rs.getString("comment"),
            rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public RatingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Rating> find(UUID appointmentId) {
        return jdbc.query("SELECT * FROM salon_ratings WHERE appointment_id = ?", ROW, appointmentId).stream()
                .findFirst();
    }

    /** The ratings of these bookings, in one query (a page of the customer's bookings). */
    public Map<UUID, Rating> forAppointments(Collection<UUID> appointmentIds) {
        Map<UUID, Rating> out = new HashMap<>();
        if (appointmentIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT * FROM salon_ratings WHERE appointment_id = ANY(?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", appointmentIds.toArray())),
                rs -> {
                    Rating r = ROW.mapRow(rs, 0);
                    out.put(r.appointmentId(), r);
                });
        return out;
    }

    /**
     * Saves a new rating or replaces this booking's earlier one, and moves the salon's totals by the difference, in
     * the caller's transaction. The caller holds the customer's lock (AppointmentStore.lockCustomer), so two saves of
     * the same booking run one after the other and `before` is what is stored now.
     * @param before the booking's rating as stored until now, or null for a first rating
     */
    public void save(Rating rating, Rating before) {
        if (before == null) {
            jdbc.update("INSERT INTO salon_ratings (appointment_id, shop_id, customer_id, stars, tags, comment, "
                            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    rating.appointmentId(), rating.shopId(), rating.customerId(), rating.stars(), rating.tags(),
                    rating.comment(), AppointmentStore.at(rating.createdAt()), AppointmentStore.at(rating.updatedAt()));
        } else {
            jdbc.update("UPDATE salon_ratings SET stars = ?, tags = ?, comment = ?, updated_at = ? "
                            + "WHERE appointment_id = ?",
                    rating.stars(), rating.tags(), rating.comment(), AppointmentStore.at(rating.updatedAt()),
                    rating.appointmentId());
        }
        int count = before == null ? 1 : 0;
        int stars = rating.stars() - (before == null ? 0 : before.stars());
        int oldTags = before == null ? 0 : before.tags();
        Map<RatingTag, Integer> tags = new EnumMap<>(RatingTag.class);
        for (RatingTag t : RatingTag.values()) {
            tags.put(t, t.in(rating.tags()) - t.in(oldTags));
        }
        Object[] values = {count, stars, tags.get(RatingTag.ON_TIME), tags.get(RatingTag.CLEAN),
                tags.get(RatingTag.FRIENDLY), tags.get(RatingTag.SKILLED), tags.get(RatingTag.GOOD_VALUE),
                rating.shopId()};
        if (before == null) {
            // A first rating adds only (no value below 0): the salon's first creates the row, later ones add to it
            // under its row lock. (A changed rating can take away, and the row the INSERT proposes is checked
            // against the totals' "not below 0" rules before the conflict is found, so that one is an UPDATE.)
            jdbc.update("INSERT INTO salon_rating_totals AS t (ratings, stars_sum, on_time, clean, friendly, skilled, "
                    + "good_value, shop_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (shop_id) DO UPDATE SET "
                    + "ratings = t.ratings + EXCLUDED.ratings, stars_sum = t.stars_sum + EXCLUDED.stars_sum, "
                    + "on_time = t.on_time + EXCLUDED.on_time, clean = t.clean + EXCLUDED.clean, "
                    + "friendly = t.friendly + EXCLUDED.friendly, skilled = t.skilled + EXCLUDED.skilled, "
                    + "good_value = t.good_value + EXCLUDED.good_value", values);
        } else {
            // The salon's row exists: it was made with this rating's first save
            jdbc.update("UPDATE salon_rating_totals SET ratings = ratings + ?, stars_sum = stars_sum + ?, "
                    + "on_time = on_time + ?, clean = clean + ?, friendly = friendly + ?, skilled = skilled + ?, "
                    + "good_value = good_value + ? WHERE shop_id = ?", values);
        }
    }

    /** One salon's totals (none yet: zeros). */
    public Totals totals(UUID shopId) {
        return totals(List.of(shopId)).getOrDefault(shopId, Totals.NONE);
    }

    /** Many salons' totals in one query (a page of the salon list); salons with none are left out. */
    public Map<UUID, Totals> totals(Collection<UUID> shopIds) {
        Map<UUID, Totals> out = new HashMap<>();
        if (shopIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT " + TOTALS_COLUMNS + " FROM salon_rating_totals WHERE shop_id = ANY(?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", shopIds.toArray())),
                rs -> {
                    Map<RatingTag, Integer> tags = new EnumMap<>(RatingTag.class);
                    tags.put(RatingTag.ON_TIME, rs.getInt("on_time"));
                    tags.put(RatingTag.CLEAN, rs.getInt("clean"));
                    tags.put(RatingTag.FRIENDLY, rs.getInt("friendly"));
                    tags.put(RatingTag.SKILLED, rs.getInt("skilled"));
                    tags.put(RatingTag.GOOD_VALUE, rs.getInt("good_value"));
                    out.put(rs.getObject("shop_id", UUID.class),
                            new Totals(rs.getInt("ratings"), rs.getLong("stars_sum"), tags));
                });
        return out;
    }

    /** A rating on the salon's list, with its booking's number and start. */
    public record Listed(Rating rating, String bookingNo, Instant visitAt) {
    }

    private static final String PAGE = "SELECT r.*, a.booking_no, a.starts_at AS visit_at FROM salon_ratings r "
            + "JOIN appointments a ON a.id = r.appointment_id WHERE r.shop_id = ? ";
    private static final String NEWEST_FIRST = " ORDER BY r.created_at DESC, r.appointment_id DESC LIMIT ?";

    /**
     * One page of a salon's ratings, newest first (by when first given), after the cursor (the last one of the
     * previous page; null for the first page).
     */
    public List<Listed> page(UUID shopId, Instant afterAt, UUID afterId, int limit) {
        RowMapper<Listed> listed = (rs, i) -> new Listed(ROW.mapRow(rs, i), rs.getString("booking_no"),
                rs.getObject("visit_at", java.time.OffsetDateTime.class).toInstant());
        if (afterAt == null) {
            return jdbc.query(PAGE + NEWEST_FIRST, listed, shopId, limit);
        }
        return jdbc.query(PAGE + "AND (r.created_at, r.appointment_id) < (?, ?)" + NEWEST_FIRST, listed, shopId,
                AppointmentStore.at(afterAt), afterId, limit);
    }

    /** A deleted account's comments go (its stars and tags stay in the salon's totals, without anything personal). */
    public void forgetComments(UUID customerId) {
        jdbc.update("UPDATE salon_ratings SET comment = NULL WHERE customer_id = ? AND comment IS NOT NULL",
                customerId);
    }
}
