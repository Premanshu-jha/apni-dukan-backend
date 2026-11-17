package in.apnidukan.kirana.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static in.apnidukan.kirana.repo.KiranaSettingsStore.at;

/**
 * kirana_refunds, kirana_earnings, kirana_payouts, kirana_payout_accounts and kirana_razorpay_events (V68): the money of
 * online orders after the payment, in plain SQL. Refunds and payouts are written here inside the request and sent to
 * Razorpay by jobs, never inside a transaction.
 */
@Repository
public class MoneyStore {

    private static final String REFUND = "SELECT r.id, r.checkout_id, r.order_id, r.amount_paise, r.reason, r.status, "
            + "r.razorpay_refund_id, r.tries, r.failure, r.created_at, r.updated_at, "
            + "coalesce(r.payment_id, c.razorpay_payment_id) "
            + "FROM kirana_refunds r JOIN kirana_checkouts c ON c.id = r.checkout_id";

    private static final String PAYOUT = "SELECT id, shop_id, amount_paise, upi_id, status, razorpay_payout_id, "
            + "failure, created_at, updated_at FROM kirana_payouts";

    private final JdbcTemplate jdbc;

    public MoneyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param reason why: REJECTED, TIMED_OUT, MISSING, CANCELLED (by the customer or with its order), NOT_PAID_IN_TIME
     * @param status PENDING, SENDING (asked, answer not known yet), SENT (Razorpay took it), PROCESSED, FAILED
     */
    public record Refund(UUID id, UUID checkoutId, UUID orderId, long amountPaise, String reason, String status,
                         String razorpayRefundId, int tries, String failure, Instant createdAt, Instant updatedAt,
                         String paymentId) {
    }

    /** @param status SENDING, SENT, PROCESSED, FAILED or REVERSED */
    public record Payout(UUID id, UUID shopId, long amountPaise, String upiId, String status, String razorpayPayoutId,
                         String failure, Instant createdAt, Instant updatedAt) {
    }

    /** What a shop is owed and when: one row per delivered order part. */
    public record Earning(UUID partId, UUID shopId, long amountPaise, LocalDate dueOn, UUID payoutId) {
    }

    /** RazorpayX's contact and fund account for a shop's UPI ID. */
    public record PayoutAccount(String contactId, String fundAccountId) {
    }

    /** A shop with earnings due, and their total. */
    public record Due(UUID shopId, long amountPaise, int parts) {
    }

    // ---------- refunds ----------

    public void addRefund(UUID id, UUID checkoutId, UUID orderId, long amountPaise, String reason, Instant now) {
        addRefund(id, checkoutId, orderId, null, amountPaise, reason, now);
    }

    /** @param paymentId the payment it goes back to; null for the checkout's own payment */
    public void addRefund(UUID id, UUID checkoutId, UUID orderId, String paymentId, long amountPaise, String reason,
                          Instant now) {
        jdbc.update("INSERT INTO kirana_refunds (id, checkout_id, order_id, payment_id, amount_paise, reason, status, "
                        + "tries, next_try_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)",
                id, checkoutId, orderId, paymentId, amountPaise, reason, at(now), at(now), at(now));
    }

    /** Whether a refund to this payment was recorded already (a second payment is refunded once). */
    public boolean refundedPayment(UUID checkoutId, String paymentId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM kirana_refunds WHERE checkout_id = ? AND payment_id = ?",
                Integer.class, checkoutId, paymentId);
        return n != null && n > 0;
    }

    /** Refunds to send (or whose sending is unknown) whose time has come, oldest first. */
    public List<UUID> refundsDue(Instant now, int limit) {
        return jdbc.queryForList("SELECT id FROM kirana_refunds WHERE status IN ('PENDING', 'SENDING') "
                + "AND next_try_at <= ? ORDER BY next_try_at LIMIT ?", UUID.class, at(now), limit);
    }

    public Optional<Refund> refundByRazorpay(String razorpayRefundId) {
        return jdbc.query(REFUND + " WHERE r.razorpay_refund_id = ?", MoneyStore::refund, razorpayRefundId).stream()
                .findFirst();
    }

    public Optional<Refund> refund(UUID id) {
        return jdbc.query(REFUND + " WHERE r.id = ?", MoneyStore::refund, id).stream().findFirst();
    }

    /** A checkout's refunds, oldest first. */
    public List<Refund> refundsOf(UUID checkoutId) {
        return jdbc.query(REFUND + " WHERE r.checkout_id = ? ORDER BY r.created_at, r.id", MoneyStore::refund,
                checkoutId);
    }

    /**
     * Claims a refund whose time has come for this run: it becomes SENDING (asked, or about to be: never PENDING again,
     * so a lost answer is always looked up before asking again) and isn't due again until {@code until}, so another
     * backend's run skips it meanwhile. Returns it as it was before (tries &gt; 0: Razorpay may have it already); empty
     * when it is held, done or not due. The caller holds a transaction.
     */
    public Optional<Refund> claimRefund(UUID id, Instant until, Instant now) {
        Optional<Refund> r = jdbc.query(REFUND + " WHERE r.id = ? AND r.status IN ('PENDING', 'SENDING') "
                + "AND r.next_try_at <= ? FOR NO KEY UPDATE OF r SKIP LOCKED", MoneyStore::refund, id, at(now)).stream()
                .findFirst();
        r.ifPresent(x -> jdbc.update("UPDATE kirana_refunds SET status = 'SENDING', tries = tries + 1, next_try_at = ?, "
                + "updated_at = ? WHERE id = ?", at(until), at(now), id));
        return r;
    }

    /** Razorpay took the refund; its webhook says when the money is back. */
    public void refundSent(UUID id, String razorpayRefundId, String status, Instant now) {
        jdbc.update("UPDATE kirana_refunds SET status = ?, razorpay_refund_id = ?, failure = NULL, updated_at = ? "
                + "WHERE id = ? AND status IN ('PENDING', 'SENDING', 'SENT')", status, razorpayRefundId, at(now), id);
    }

    /** Razorpay couldn't be asked or looked up: tried again (looked up first) at {@code nextTry}. */
    public void refundRetry(UUID id, Instant nextTry, String failure, Instant now) {
        jdbc.update("UPDATE kirana_refunds SET next_try_at = ?, failure = ?, updated_at = ? WHERE id = ? "
                + "AND status = 'SENDING'", at(nextTry), failure, at(now), id);
    }

    /** Razorpay refused or couldn't complete it (a refund whose money is back is never marked failed after). */
    public void refundFailed(UUID id, String failure, Instant now) {
        jdbc.update("UPDATE kirana_refunds SET status = 'FAILED', failure = ?, updated_at = ? WHERE id = ? "
                + "AND status <> 'PROCESSED'", failure, at(now), id);
    }

    /** Failed refunds for the admin website, newest first. */
    public List<Refund> failedRefunds(int limit) {
        return jdbc.query(REFUND + " WHERE r.status = 'FAILED' ORDER BY r.updated_at DESC LIMIT ?", MoneyStore::refund,
                limit);
    }

    // ---------- earnings and payouts ----------

    /** What the shop earned from a delivered order part; once per part. */
    public void addEarning(UUID partId, UUID shopId, long amountPaise, LocalDate dueOn, Instant now) {
        jdbc.update("INSERT INTO kirana_earnings (part_id, shop_id, amount_paise, due_on, created_at) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (part_id) DO NOTHING", partId, shopId, amountPaise,
                Date.valueOf(dueOn), at(now));
    }

    /** Shops with earnings due by this day and not paid yet, their totals; at most {@code limit} shops. */
    public List<Due> dueByShop(LocalDate day, int limit) {
        // Shops that can't be paid now (no UPI ID after a hand-over, a new UPI ID waiting for its check) are left out,
        // so they never hold the places of shops that can
        return jdbc.query("SELECT e.shop_id, sum(e.amount_paise), count(*) FROM kirana_earnings e "
                        + "JOIN kirana_settings s ON s.shop_id = e.shop_id AND s.upi_id IS NOT NULL "
                        + "WHERE e.payout_id IS NULL AND e.due_on <= ? AND NOT EXISTS (SELECT 1 FROM kirana_online o "
                        + "WHERE o.shop_id = e.shop_id AND o.upi_recheck) "
                        + "GROUP BY e.shop_id ORDER BY min(e.due_on), e.shop_id LIMIT ?",
                (rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getLong(2), rs.getInt(3)), Date.valueOf(day), limit);
    }

    /**
     * Gathers the shop's earnings due by this day into a new payout (locking them; any held by another job are left
     * for it). Returns the payout's amount, 0 when nothing was left to gather.
     */
    public long gather(UUID payoutId, UUID shopId, LocalDate day, String upiId, Instant now) {
        List<long[]> rows = jdbc.query("SELECT amount_paise FROM kirana_earnings WHERE shop_id = ? AND payout_id IS NULL "
                + "AND due_on <= ? FOR UPDATE SKIP LOCKED", (rs, i) -> new long[] {rs.getLong(1)}, shopId,
                Date.valueOf(day));
        long sum = rows.stream().mapToLong(r -> r[0]).sum();
        if (sum <= 0) {
            return 0;
        }
        jdbc.update("INSERT INTO kirana_payouts (id, shop_id, amount_paise, upi_id, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 'SENDING', ?, ?)", payoutId, shopId, sum, upiId, at(now), at(now));
        jdbc.update("UPDATE kirana_earnings SET payout_id = ? WHERE shop_id = ? AND payout_id IS NULL AND due_on <= ? "
                + "AND part_id IN (SELECT part_id FROM kirana_earnings WHERE shop_id = ? AND payout_id IS NULL "
                + "AND due_on <= ? FOR UPDATE SKIP LOCKED)", payoutId, shopId, Date.valueOf(day), shopId,
                Date.valueOf(day));
        Long linked = jdbc.queryForObject("SELECT coalesce(sum(amount_paise), 0) FROM kirana_earnings "
                + "WHERE payout_id = ?", Long.class, payoutId);
        long total = linked == null ? 0 : linked;
        if (total != sum) {
            jdbc.update("UPDATE kirana_payouts SET amount_paise = ? WHERE id = ?", total, payoutId);
        }
        return total;
    }

    public Optional<Payout> payout(UUID id) {
        return jdbc.query(PAYOUT + " WHERE id = ?", MoneyStore::payout, id).stream().findFirst();
    }

    public Optional<Payout> lockPayout(UUID id) {
        return jdbc.query(PAYOUT + " WHERE id = ? FOR NO KEY UPDATE SKIP LOCKED", MoneyStore::payout, id).stream()
                .findFirst();
    }

    public Optional<Payout> payoutByRazorpay(String razorpayPayoutId) {
        return jdbc.query(PAYOUT + " WHERE razorpay_payout_id = ?", MoneyStore::payout, razorpayPayoutId).stream()
                .findFirst();
    }

    /** Payouts asked of RazorpayX whose answer was lost, last touched before this time (sent again: idempotent). */
    public List<UUID> sendingBefore(Instant before, int limit) {
        return jdbc.queryForList("SELECT id FROM kirana_payouts WHERE status = 'SENDING' AND updated_at < ? "
                + "ORDER BY updated_at LIMIT ?", UUID.class, at(before), limit);
    }

    public void touchPayout(UUID id, Instant now) {
        jdbc.update("UPDATE kirana_payouts SET updated_at = ? WHERE id = ?", at(now), id);
    }

    /**
     * A payout's new status, only forward: SENDING and SENT may become anything, PROCESSED only REVERSED; FAILED and
     * REVERSED are final (a late or out-of-order answer never undoes them). Whether it changed.
     */
    public boolean payoutStatus(UUID id, String status, String razorpayPayoutId, String failure, Instant now) {
        return jdbc.update("UPDATE kirana_payouts SET status = ?, razorpay_payout_id = coalesce(?, razorpay_payout_id), "
                + "failure = ?, updated_at = ? WHERE id = ? AND status <> ? AND (status IN ('SENDING', 'SENT') "
                + "OR (status = 'PROCESSED' AND ? = 'REVERSED'))", status, razorpayPayoutId, failure, at(now), id,
                status, status) > 0;
    }

    /** A note on a failed payout (sent again), its status kept. */
    public void payoutNote(UUID id, String failure, Instant now) {
        jdbc.update("UPDATE kirana_payouts SET failure = ?, updated_at = ? WHERE id = ?", failure, at(now), id);
    }

    /** A failed or reversed payout's earnings are freed, to be paid again by the next run. */
    public int releaseEarnings(UUID payoutId) {
        return jdbc.update("UPDATE kirana_earnings SET payout_id = NULL WHERE payout_id = ?", payoutId);
    }

    /**
     * A shop's failed and reversed payouts that weren't sent again: their earnings are freed for the next run (once its
     * new UPI ID is checked), and each is marked as sent again. How many.
     */
    public int releaseFailedOf(UUID shopId, Instant now) {
        jdbc.update("UPDATE kirana_earnings SET payout_id = NULL WHERE payout_id IN (SELECT id FROM kirana_payouts "
                + "WHERE shop_id = ? AND status IN ('FAILED', 'REVERSED'))", shopId);
        return jdbc.update("UPDATE kirana_payouts SET failure = coalesce(failure || ' · ', '') || 'sent again', "
                + "updated_at = ? WHERE shop_id = ? AND status IN ('FAILED', 'REVERSED') "
                + "AND (failure IS NULL OR failure NOT LIKE '%sent again')", at(now), shopId);
    }

    /** A shop's payouts, newest first. */
    public List<Payout> payouts(UUID shopId, int limit) {
        return jdbc.query(PAYOUT + " WHERE shop_id = ? ORDER BY created_at DESC, id DESC LIMIT ?", MoneyStore::payout,
                shopId, limit);
    }

    /** Payouts in this status, newest first (the admin website). */
    public List<Payout> payoutsIn(String status, int limit) {
        return jdbc.query(PAYOUT + " WHERE status = ? ORDER BY updated_at DESC, id DESC LIMIT ?", MoneyStore::payout,
                status, limit);
    }

    /** What the shop is owed and not paid yet: {due by today, not due yet}. */
    public long[] owed(UUID shopId, LocalDate today) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount_paise) FILTER (WHERE due_on <= ?), 0), "
                        + "coalesce(sum(amount_paise) FILTER (WHERE due_on > ?), 0) FROM kirana_earnings "
                        + "WHERE shop_id = ? AND payout_id IS NULL",
                (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}, Date.valueOf(today), Date.valueOf(today), shopId);
    }

    public Optional<PayoutAccount> payoutAccount(UUID shopId, String upiId) {
        return jdbc.query("SELECT contact_id, fund_account_id FROM kirana_payout_accounts WHERE shop_id = ? "
                + "AND upi_id = ?", (rs, i) -> new PayoutAccount(rs.getString(1), rs.getString(2)), shopId, upiId)
                .stream().findFirst();
    }

    public void savePayoutAccount(UUID shopId, String upiId, PayoutAccount a, Instant now) {
        jdbc.update("INSERT INTO kirana_payout_accounts (shop_id, upi_id, contact_id, fund_account_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (shop_id, upi_id) DO NOTHING", shopId, upiId,
                a.contactId(), a.fundAccountId(), at(now));
    }

    // ---------- webhooks ----------

    /** Whether this Razorpay event id is new (a retried webhook is handled once). */
    public boolean firstTime(String eventId, Instant now) {
        return jdbc.update("INSERT INTO kirana_razorpay_events (event_id, at) VALUES (?, ?) ON CONFLICT DO NOTHING",
                eventId, at(now)) > 0;
    }

    private static Refund refund(ResultSet rs, int i) throws SQLException {
        return new Refund(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8), rs.getString(9),
                rs.getObject(10, OffsetDateTime.class).toInstant(), rs.getObject(11, OffsetDateTime.class).toInstant(),
                rs.getString(12));
    }

    private static Payout payout(ResultSet rs, int i) throws SQLException {
        return new Payout(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getObject(8, OffsetDateTime.class).toInstant(),
                rs.getObject(9, OffsetDateTime.class).toInstant());
    }
}
