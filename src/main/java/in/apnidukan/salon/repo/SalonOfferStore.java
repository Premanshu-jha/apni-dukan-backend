package in.apnidukan.salon.repo;

import in.apnidukan.salon.domain.SalonOffer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The salon_offers, salon_offer_services and salon_offer_mutes tables, and the offer switch in
 * salon_customer_prefs (V37), in plain SQL.
 */
@Repository
public class SalonOfferStore {

    /** An offer's columns with its services (from salon_offer_services). */
    private static final String COLUMNS = """
            SELECT o.id, o.shop_id, o.percent_off, o.all_services, o.starts_on, o.ends_on, o.from_min, o.to_min,
                o.ended_at, o.notified, o.created_at,
                ARRAY(SELECT x.service_id FROM salon_offer_services x WHERE x.offer_id = o.id) AS service_ids""";

    private static final String OFFER = COLUMNS + " FROM salon_offers o";

    /** The owner's list: also how many bookings (not cancelled) each brought. */
    private static final String LISTED = COLUMNS + """
            , (SELECT count(DISTINCT s.appointment_id) FROM appointment_services s
               JOIN appointments a ON a.id = s.appointment_id
               WHERE s.offer_id = o.id AND a.status NOT IN ('CANCELLED_BY_CUSTOMER', 'CANCELLED_BY_SALON')) AS bookings
            FROM salon_offers o WHERE o.shop_id = ?
            ORDER BY (o.ended_at IS NULL AND o.ends_on >= ?) DESC, o.created_at DESC, o.id LIMIT ?""";

    /**
     * The salon's past customers an offer message goes to: anyone who completed an online booking there, whose
     * account isn't deleted, who doesn't work in it (owner, helper or barber), and who hasn't switched offer messages
     * off (from every salon, or from this one). One parameter: the salon, five times.
     */
    private static final String RECIPIENTS = """
            SELECT DISTINCT a.customer_id FROM appointments a
            JOIN users u ON u.id = a.customer_id AND u.deleted_at IS NULL
            WHERE a.shop_id = ? AND a.kind = 'BOOKING' AND a.status = 'COMPLETED' AND a.customer_id IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM shop_members m WHERE m.user_id = a.customer_id AND m.shop_id = ?)
              AND NOT EXISTS (SELECT 1 FROM salon_customer_prefs p WHERE p.user_id = a.customer_id
                              AND NOT p.salon_offers)
              AND NOT EXISTS (SELECT 1 FROM salon_offer_mutes x WHERE x.user_id = a.customer_id AND x.shop_id = ?)""";

    private final JdbcTemplate jdbc;

    public SalonOfferStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** An offer and how many bookings (not cancelled) were made with it. */
    public record Listed(SalonOffer offer, int bookings) {
    }

    public void insert(SalonOffer o, UUID by) {
        jdbc.update("INSERT INTO salon_offers (id, shop_id, percent_off, all_services, starts_on, ends_on, from_min, "
                        + "to_min, notified, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                o.id(), o.shopId(), o.percentOff(), o.allServices(), o.startsOn(), o.endsOn(), o.fromMin(), o.toMin(),
                o.notified(), by, AppointmentStore.at(o.createdAt()));
        for (UUID s : o.serviceIds()) {
            jdbc.update("INSERT INTO salon_offer_services (offer_id, service_id) VALUES (?, ?)", o.id(), s);
        }
    }

    /** The salon's offers not ended early that last until {@code from} or later, soonest first. */
    public List<SalonOffer> current(UUID shopId, LocalDate from) {
        return jdbc.query(OFFER + " WHERE o.shop_id = ? AND o.ended_at IS NULL AND o.ends_on >= ? "
                + "ORDER BY o.starts_on, o.percent_off DESC, o.id", SalonOfferStore::offer, shopId, from);
    }

    /**
     * The salon's offers, each with its bookings (the owner's list): those running or to come on {@code today} first
     * (so each can be ended), then the rest; newest first within each.
     */
    public List<Listed> list(UUID shopId, LocalDate today, int limit) {
        return jdbc.query(LISTED, (rs, i) -> new Listed(offer(rs, i), rs.getInt("bookings")), shopId, today, limit);
    }

    /** How many bookings (not cancelled) were made with this offer. */
    public int bookings(UUID offerId) {
        Integer n = jdbc.queryForObject("SELECT count(DISTINCT s.appointment_id) FROM appointment_services s "
                + "JOIN appointments a ON a.id = s.appointment_id WHERE s.offer_id = ? AND a.status NOT IN "
                + "('CANCELLED_BY_CUSTOMER', 'CANCELLED_BY_SALON')", Integer.class, offerId);
        return n == null ? 0 : n;
    }

    /** One of the salon's offers, its row locked until the transaction ends. */
    public Optional<SalonOffer> lock(UUID shopId, UUID offerId) {
        // NO KEY UPDATE: bookings checking their key to it (FOR KEY SHARE) aren't held up
        return jdbc.query(OFFER + " WHERE o.id = ? AND o.shop_id = ? FOR NO KEY UPDATE OF o", SalonOfferStore::offer,
                offerId, shopId).stream().findFirst();
    }

    public void end(UUID offerId, Instant now) {
        jdbc.update("UPDATE salon_offers SET ended_at = ? WHERE id = ? AND ended_at IS NULL",
                AppointmentStore.at(now), offerId);
    }

    /** Who an offer message from this salon goes to (see RECIPIENTS). */
    public List<UUID> recipients(UUID shopId) {
        return jdbc.queryForList(RECIPIENTS + " ORDER BY 1", UUID.class, shopId, shopId, shopId);
    }

    /** How many people an offer message from this salon would go to now. */
    public int countRecipients(UUID shopId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM (" + RECIPIENTS + ") r", Integer.class,
                shopId, shopId, shopId);
        return n == null ? 0 : n;
    }

    // ---------- the customer's switches ----------

    /** Offer messages from salons they visited: on unless switched off. */
    public boolean wantsOffers(UUID customerId) {
        List<Boolean> on = jdbc.queryForList("SELECT salon_offers FROM salon_customer_prefs WHERE user_id = ?",
                Boolean.class, customerId);
        return on.isEmpty() || on.get(0);
    }

    public void setWantsOffers(UUID customerId, boolean on, Instant now) {
        jdbc.update("INSERT INTO salon_customer_prefs (user_id, come_back_reminders, salon_offers, updated_at) "
                        + "VALUES (?, true, ?, ?) ON CONFLICT (user_id) DO UPDATE SET salon_offers = "
                        + "EXCLUDED.salon_offers, updated_at = EXCLUDED.updated_at",
                customerId, on, AppointmentStore.at(now));
    }

    /** They completed an online booking at this salon (only then can they get its offer messages). */
    public boolean visited(UUID customerId, UUID shopId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM appointments WHERE "
                + "customer_id = ? AND shop_id = ? AND kind = 'BOOKING' AND status = 'COMPLETED')", Boolean.class,
                customerId, shopId));
    }

    public boolean muted(UUID customerId, UUID shopId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM salon_offer_mutes WHERE "
                + "user_id = ? AND shop_id = ?)", Boolean.class, customerId, shopId));
    }

    public void setMuted(UUID customerId, UUID shopId, boolean muted, Instant now) {
        if (muted) {
            jdbc.update("INSERT INTO salon_offer_mutes (user_id, shop_id, created_at) VALUES (?, ?, ?) "
                    + "ON CONFLICT DO NOTHING", customerId, shopId, AppointmentStore.at(now));
        } else {
            jdbc.update("DELETE FROM salon_offer_mutes WHERE user_id = ? AND shop_id = ?", customerId, shopId);
        }
    }

    /** A deleted account: its choices about salons' offers go (the general switch goes with the prefs row). */
    public void forget(UUID customerId) {
        jdbc.update("DELETE FROM salon_offer_mutes WHERE user_id = ?", customerId);
    }

    private static SalonOffer offer(ResultSet rs, int i) throws SQLException {
        OffsetDateTime ended = rs.getObject("ended_at", OffsetDateTime.class);
        return new SalonOffer(rs.getObject("id", UUID.class), rs.getObject("shop_id", UUID.class),
                rs.getInt("percent_off"), rs.getBoolean("all_services"), ids(rs.getArray("service_ids")),
                rs.getObject("starts_on", LocalDate.class), rs.getObject("ends_on", LocalDate.class),
                (Integer) rs.getObject("from_min"), (Integer) rs.getObject("to_min"),
                ended == null ? null : ended.toInstant(), rs.getInt("notified"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static Set<UUID> ids(Array a) throws SQLException {
        return a == null ? Set.of() : new HashSet<>(Arrays.asList((UUID[]) a.getArray()));
    }
}
