package in.apnidukan.notifications.repo;

import in.apnidukan.notifications.domain.PushSubscription;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** The push_subscriptions table: one row per device that allowed notifications. */
@Repository
public class PushSubscriptionStore {

    private final JdbcTemplate jdbc;

    public PushSubscriptionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Saves this device for this person. A device address is unique: if someone else signed in on the same browser
     * before, the address now belongs to this person (the browser keeps one address per site).
     */
    public void save(UUID userId, String endpoint, String p256dh, String auth, Instant now) {
        jdbc.update("""
                        INSERT INTO push_subscriptions (id, user_id, endpoint, p256dh, auth, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (endpoint) DO UPDATE
                        SET user_id = EXCLUDED.user_id, p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth,
                            created_at = EXCLUDED.created_at""",
                UUID.randomUUID(), userId, endpoint, p256dh, auth, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    }

    /** Keeps only the person's newest {@code keep} devices. */
    public void trim(UUID userId, int keep) {
        jdbc.update("""
                DELETE FROM push_subscriptions WHERE user_id = ? AND id NOT IN (
                    SELECT id FROM push_subscriptions WHERE user_id = ? ORDER BY created_at DESC, id LIMIT ?)""",
                userId, userId, keep);
    }

    public List<PushSubscription> forUser(UUID userId) {
        return jdbc.query("SELECT id, user_id, endpoint, p256dh, auth FROM push_subscriptions WHERE user_id = ?",
                (rs, i) -> new PushSubscription(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("endpoint"), rs.getString("p256dh"), rs.getString("auth")),
                userId);
    }

    /** Forgets every device of this person (their account was deleted). */
    public void deleteAll(UUID userId) {
        jdbc.update("DELETE FROM push_subscriptions WHERE user_id = ?", userId);
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM push_subscriptions WHERE id = ?", id);
    }

    public void delete(UUID userId, String endpoint) {
        jdbc.update("DELETE FROM push_subscriptions WHERE user_id = ? AND endpoint = ?", userId, endpoint);
    }
}
