package in.apnidukan.shops.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * play_external_transactions: Razorpay payments made from the Play Store app (Razorpay picked on Google's choice
 * screen), to report to Google within 24 hours. Written in the transaction that records the payment, so none is
 * missed; sent by PlayReports.
 */
@Repository
public class PlayReportStore {

    /** One report waiting to go (or tried and waiting for its next try). */
    public record Pending(String id, UUID shopId, String subscriptionId, String initialId, String token,
                          int amountPaise, Instant transactionTime, String taxState, int attempts) {
    }

    private final JdbcTemplate jdbc;

    public PlayReportStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Queues the report of Razorpay payment {@code paymentId}; the same payment (or id) again, from a replayed
     * webhook, adds nothing. Returns whether it was added.
     */
    public boolean queue(String id, String paymentId, UUID shopId, String subscriptionId, String initialId,
                         String token, int amountPaise, Instant transactionTime, String taxState, Instant now) {
        return jdbc.update("INSERT INTO play_external_transactions (id, payment_id, shop_id, razorpay_subscription_id, "
                        + "initial_id, token, amount_paise, transaction_time, tax_state, next_try_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                id, paymentId, shopId, subscriptionId, initialId, token, amountPaise, ts(transactionTime), taxState,
                ts(now), ts(now)) == 1;
    }

    /** The first report of this Razorpay subscription, if it has one. */
    public Optional<String> initialOf(String subscriptionId) {
        return jdbc.queryForList("SELECT id FROM play_external_transactions WHERE razorpay_subscription_id = ? "
                + "AND initial_id IS NULL", String.class, subscriptionId).stream().findFirst();
    }

    /**
     * Claims the reports due now for this round: their next try moves to {@code claimedUntil} in the same statement
     * (SKIP LOCKED), so another server's round never takes the same ones, and one this round doesn't finish (the
     * server stopped) comes back by itself. Firsts before later ones (Google needs the first before it accepts a
     * charge naming it), oldest first.
     */
    public List<Pending> claimDue(Instant now, Instant claimedUntil, int limit) {
        List<Pending> claimed = jdbc.query("UPDATE play_external_transactions SET next_try_at = ? WHERE id IN ("
                        + "SELECT id FROM play_external_transactions WHERE status = 'PENDING' AND next_try_at <= ? "
                        + "ORDER BY (initial_id IS NOT NULL), next_try_at LIMIT ? FOR UPDATE SKIP LOCKED) "
                        + "RETURNING id, shop_id, razorpay_subscription_id, initial_id, token, amount_paise, "
                        + "transaction_time, tax_state, attempts",
                (rs, i) -> new Pending(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6), rs.getTimestamp(7).toInstant(), rs.getString(8), rs.getInt(9)),
                ts(claimedUntil), ts(now), limit);
        // RETURNING keeps no order
        return claimed.stream().sorted(Comparator.comparing((Pending p) -> p.initialId() != null)
                .thenComparing(Pending::transactionTime)).toList();
    }

    /** The status (PENDING, DONE or REFUSED) of the first report another one names. */
    public Optional<String> initialStatus(String initialId) {
        return jdbc.queryForList("SELECT status FROM play_external_transactions WHERE id = ?", String.class, initialId)
                .stream().findFirst();
    }

    public void done(String id, Instant now) {
        jdbc.update("UPDATE play_external_transactions SET status = 'DONE', reported_at = ?, "
                + "attempts = attempts + 1 WHERE id = ?", ts(now), id);
    }

    /** Try again at {@code next}. */
    public void retry(String id, Instant next) {
        jdbc.update("UPDATE play_external_transactions SET attempts = attempts + 1, next_try_at = ? WHERE id = ?",
                ts(next), id);
    }

    /** Google refused it for good (logged): kept for a person to look at. */
    public void refused(String id, Instant now) {
        jdbc.update("UPDATE play_external_transactions SET status = 'REFUSED', attempts = attempts + 1, "
                + "reported_at = ? WHERE id = ?", ts(now), id);
    }

    /** How many have been waiting since before {@code before} (logged: Google wants each within 24 hours). */
    public int waitingOlderThan(Instant before) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM play_external_transactions WHERE status = 'PENDING' "
                + "AND created_at < ?", Integer.class, ts(before));
        return n == null ? 0 : n;
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
