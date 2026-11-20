package in.apnidukan.kirana.repo;

import in.apnidukan.kirana.domain.KhataCustomer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static in.apnidukan.kirana.repo.KiranaSettingsStore.at;

/** kirana_khata_customers and kirana_khata_entries (V66): the shop's own khata book, in plain SQL. */
@Repository
public class KhataStore {

    private static final String COLUMNS = "SELECT id, shop_id, name, phone, note, credit_limit_paise, balance_paise, "
            + "active, version, updated_at FROM kirana_khata_customers";

    private static final String ENTRY = "SELECT e.id, e.kind, e.amount_paise, e.balance_after, e.bill_id, b.bill_no, "
            + "e.method, e.note, e.created_by, e.created_at, e.reverses_id, "
            + "EXISTS (SELECT 1 FROM kirana_khata_entries r WHERE r.reverses_id = e.id) AS reversed "
            + "FROM kirana_khata_entries e LEFT JOIN kirana_bills b ON b.id = e.bill_id";

    private final JdbcTemplate jdbc;

    public KhataStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * An entry as stored, for the customer's page and the answer to a payment.
     * @param reversesId for a REVERSAL: the payment it undoes
     * @param reversed   for a PAYMENT: whether it was undone since
     */
    public record Entry(UUID id, String kind, long amountPaise, long balanceAfter, UUID billId, Long billNo,
                        String method, String note, UUID by, Instant at, UUID reversesId, boolean reversed) {
    }

    public void insert(KhataCustomer c, UUID by, Instant now) {
        jdbc.update("INSERT INTO kirana_khata_customers (id, shop_id, name, phone, note, credit_limit_paise, "
                        + "balance_paise, active, version, created_by, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, true, 0, ?, ?, ?)",
                c.id(), c.shopId(), c.name(), c.phone(), c.note(), c.creditLimitPaise(), by, at(now), at(now));
    }

    public Optional<KhataCustomer> find(UUID shopId, UUID id) {
        return jdbc.query(COLUMNS + " WHERE id = ? AND shop_id = ?", KhataStore::customer, id, shopId).stream()
                .findFirst();
    }

    /** The shop's active customer with this phone. */
    public Optional<KhataCustomer> findByPhone(UUID shopId, String phone) {
        return jdbc.query(COLUMNS + " WHERE shop_id = ? AND phone = ? AND active", KhataStore::customer, shopId, phone)
                .stream().findFirst();
    }

    /** One of the shop's customers, the row locked until the transaction ends (their balance is about to change). */
    public Optional<KhataCustomer> lock(UUID shopId, UUID id) {
        return jdbc.query(COLUMNS + " WHERE id = ? AND shop_id = ? FOR NO KEY UPDATE", KhataStore::customer, id,
                shopId).stream().findFirst();
    }

    /**
     * One page of the shop's active customers in name order, after (afterName, afterId) when given; only those who
     * owe something when {@code owing}. With {@code words} (lower case, LIKE wildcards escaped): a name or note
     * containing it ("1204" finds "Tower B 1204"), or, for {@code digits}, a phone containing them. Reads one more
     * than {@code limit}.
     */
    public List<Row> page(UUID shopId, String words, String digits, boolean owing, String afterName, UUID afterId,
                          int limit) {
        StringBuilder sql = new StringBuilder(COLUMNS.replace(" FROM", ", lower(name) AS sort_key FROM"))
                .append(" WHERE shop_id = ? AND active");
        List<Object> args = new ArrayList<>();
        args.add(shopId);
        if (owing) {
            sql.append(" AND balance_paise > 0");
        }
        if (words != null) {
            sql.append(" AND ((lower(name) || ' ' || coalesce(lower(note), '')) LIKE ? ESCAPE '\\'");
            args.add("%" + words + "%");
            if (digits != null) {
                sql.append(" OR phone LIKE ?");
                args.add("%" + digits + "%");
            }
            sql.append(")");
        }
        if (afterName != null) {
            sql.append(" AND (lower(name) COLLATE \"C\", id) > (?, ?)");
            args.add(afterName);
            args.add(afterId);
        }
        sql.append(" ORDER BY lower(name) COLLATE \"C\", id LIMIT ?");
        args.add(limit + 1);
        return jdbc.query(sql.toString(), (rs, i) -> new Row(customer(rs, i), rs.getString("sort_key")),
                args.toArray());
    }

    /** A customer on a page, with the key it was sorted by (Postgres's lower(name), for the next page's token). */
    public record Row(KhataCustomer customer, String sortKey) {
    }

    /**
     * Waits until no other request of this transaction's lifetime handles the same phone in the shop (adding a customer,
     * changing a phone, putting a customer back in the book), so their checks for a free phone never race. A
     * transaction-level advisory lock: released at commit or rollback.
     */
    public void lockPhone(UUID shopId, String phone) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                "kirana-phone:" + shopId + ":" + phone);
    }

    /** What the shop holds as its customers' advances (balances below zero, active customers), as a positive sum. */
    public long advances(UUID shopId) {
        Long n = jdbc.queryForObject("SELECT coalesce(-sum(balance_paise), 0) FROM kirana_khata_customers "
                + "WHERE shop_id = ? AND active AND balance_paise < 0", Long.class, shopId);
        return n == null ? 0 : n;
    }

    /** What all the shop's active customers owe together (balances above zero). */
    public long owed(UUID shopId) {
        Long n = jdbc.queryForObject("SELECT coalesce(sum(balance_paise), 0) FROM kirana_khata_customers "
                + "WHERE shop_id = ? AND active AND balance_paise > 0", Long.class, shopId);
        return n == null ? 0 : n;
    }

    /**
     * Puts a customer taken out of the book back in (the caller holds their lock): a cancelled bill left the shop owing
     * them. Their phone is dropped if someone else in the book has it now (one phone per active customer).
     */
    public KhataCustomer reactivate(KhataCustomer c, Instant now) {
        if (c.phone() != null) {
            lockPhone(c.shopId(), c.phone());
        }
        return jdbc.queryForObject("UPDATE kirana_khata_customers k SET active = true, version = version + 1, "
                        + "updated_at = ?, phone = CASE WHEN EXISTS (SELECT 1 FROM kirana_khata_customers o WHERE "
                        + "o.shop_id = k.shop_id AND o.phone = k.phone AND o.active AND o.id <> k.id) THEN NULL "
                        + "ELSE k.phone END WHERE k.id = ? RETURNING k.id, k.shop_id, k.name, k.phone, k.note, "
                        + "k.credit_limit_paise, k.balance_paise, k.active, k.version, k.updated_at",
                KhataStore::customer, at(now), c.id());
    }

    /** Saves the customer's details (not the balance) and moves the version on. */
    public void update(KhataCustomer c, Instant now) {
        jdbc.update("UPDATE kirana_khata_customers SET name = ?, phone = ?, note = ?, credit_limit_paise = ?, "
                        + "active = ?, version = version + 1, updated_at = ? WHERE id = ? AND shop_id = ?",
                c.name(), c.phone(), c.note(), c.creditLimitPaise(), c.active(), at(now), c.id(), c.shopId());
    }

    /**
     * Records an entry and moves the customer's balance by its amount (the caller holds the customer's lock). Returns
     * the balance after it.
     */
    public long addEntry(UUID shopId, UUID customerId, long balanceBefore, String kind, long amountPaise,
                         UUID billId, String method, UUID clientRef, String note, UUID by, Instant now, UUID entryId) {
        long after = Math.addExact(balanceBefore, amountPaise);
        jdbc.update("INSERT INTO kirana_khata_entries (id, shop_id, customer_id, kind, amount_paise, balance_after, "
                        + "bill_id, method, client_ref, note, created_by, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                entryId, shopId, customerId, kind, amountPaise, after, billId, method, clientRef, note, by, at(now));
        jdbc.update("UPDATE kirana_khata_customers SET balance_paise = ?, updated_at = ? WHERE id = ?", after, at(now),
                customerId);
        return after;
    }

    /**
     * Records the owner undoing a payment (the caller holds the customer's lock and checked it wasn't undone): the
     * amount goes back on what they owe. Returns the balance after it.
     */
    public long addReversal(UUID shopId, UUID customerId, long balanceBefore, long amountPaise, String method,
                            UUID reversesId, String note, UUID by, Instant now, UUID entryId) {
        long after = Math.addExact(balanceBefore, amountPaise);
        jdbc.update("INSERT INTO kirana_khata_entries (id, shop_id, customer_id, kind, amount_paise, balance_after, "
                        + "method, reverses_id, note, created_by, created_at) "
                        + "VALUES (?, ?, ?, 'REVERSAL', ?, ?, ?, ?, ?, ?, ?)",
                entryId, shopId, customerId, amountPaise, after, method, reversesId, note, by, at(now));
        jdbc.update("UPDATE kirana_khata_customers SET balance_paise = ?, updated_at = ? WHERE id = ?", after, at(now),
                customerId);
        return after;
    }

    /** The customer's entry, if it is theirs. */
    public Optional<Entry> entryOf(UUID customerId, UUID entryId) {
        return jdbc.query(ENTRY + " WHERE e.id = ? AND e.customer_id = ?", KhataStore::entry, entryId, customerId)
                .stream().findFirst();
    }

    /** The payment recorded with this clientRef in the shop, if any (a repeat of it). */
    public Optional<Entry> paymentByClientRef(UUID shopId, UUID clientRef) {
        return jdbc.query(ENTRY + " WHERE e.shop_id = ? AND e.client_ref = ?", KhataStore::entry, shopId, clientRef)
                .stream().findFirst();
    }

    /** The customer that entry belongs to. */
    public Optional<UUID> customerOfEntry(UUID entryId) {
        return jdbc.queryForList("SELECT customer_id FROM kirana_khata_entries WHERE id = ?", UUID.class, entryId)
                .stream().findFirst();
    }

    public Optional<Entry> entry(UUID entryId) {
        return jdbc.query(ENTRY + " WHERE e.id = ?", KhataStore::entry, entryId).stream().findFirst();
    }

    /** The customer's entries, newest first, older than (beforeAt, beforeId) when given; one more than limit. */
    public List<Entry> entries(UUID customerId, Instant beforeAt, UUID beforeId, int limit) {
        if (beforeAt == null) {
            return jdbc.query(ENTRY + " WHERE e.customer_id = ? ORDER BY e.created_at DESC, e.id DESC LIMIT ?",
                    KhataStore::entry, customerId, limit + 1);
        }
        return jdbc.query(ENTRY + " WHERE e.customer_id = ? AND (e.created_at, e.id) < (?, ?) "
                        + "ORDER BY e.created_at DESC, e.id DESC LIMIT ?",
                KhataStore::entry, customerId, at(beforeAt), beforeId, limit + 1);
    }

    /**
     * Khata payments the shop received in [from, to), less payments undone in it (a payment put on the wrong customer
     * and recorded again on the right one counts once) and advances given back in it: {cash, UPI} in paise.
     */
    public long[] received(UUID shopId, Instant from, Instant to) {
        long[] out = {0, 0};
        jdbc.query("SELECT method, -sum(amount_paise) FROM kirana_khata_entries WHERE shop_id = ? "
                + "AND kind IN ('PAYMENT', 'REVERSAL', 'REFUND') AND created_at >= ? AND created_at < ? GROUP BY method",
                rs -> {
                    out["CASH".equals(rs.getString(1)) ? 0 : 1] = rs.getLong(2);
                }, shopId, at(from), at(to));
        return out;
    }

    static KhataCustomer customer(ResultSet rs, int i) throws SQLException {
        return new KhataCustomer(rs.getObject("id", UUID.class), rs.getObject("shop_id", UUID.class),
                rs.getString("name"), rs.getString("phone"), rs.getString("note"),
                (Long) rs.getObject("credit_limit_paise"), rs.getLong("balance_paise"), rs.getBoolean("active"),
                rs.getLong("version"), rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static Entry entry(ResultSet rs, int i) throws SQLException {
        return new Entry(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getLong(4),
                rs.getObject(5, UUID.class), (Long) rs.getObject(6), rs.getString(7), rs.getString(8),
                rs.getObject(9, UUID.class), rs.getObject(10, OffsetDateTime.class).toInstant(),
                rs.getObject(11, UUID.class), rs.getBoolean(12));
    }
}
