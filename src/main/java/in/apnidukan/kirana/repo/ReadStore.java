package in.apnidukan.kirana.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * kirana_reads and kirana_shop_ai_calls (V72): photos a shop uploaded to be read by AI (a distributor's bill, a paper
 * bill, a pack's label), in plain SQL. The reading job claims each one, so two backends never read it together.
 */
@Repository
public class ReadStore {

    private static final String READ = "SELECT id, shop_id, kind, status, file_ids, client_ref, tries, next_try_at, "
            + "failure, result::text AS result, bill_id, product_id, created_by, created_at, updated_at "
            + "FROM kirana_reads";

    private final JdbcTemplate jdbc;

    public ReadStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A photo (1 to 3 pictures) to read.
     * @param kind   PURCHASE (a distributor's bill), PAPER_BILL (a counter bill written on paper), LABEL (a pack)
     * @param status READING (queued), READ (the result is in), FAILED (couldn't be read: why), DONE (used: added to
     *               stock, billed, linked), DISCARDED (the shop threw it away)
     * @param result what the AI read, as JSON (READ and after)
     * @param productId a LABEL read of a product's photo: the product (its photo is shared once the label matches)
     */
    public record Read(UUID id, UUID shopId, String kind, String status, List<UUID> fileIds, UUID clientRef,
                       int tries, Instant nextTryAt, String failure, String result, UUID billId, UUID productId,
                       UUID createdBy,
                       Instant createdAt, Instant updatedAt) {

        public Read {
            fileIds = List.copyOf(fileIds);
        }
    }

    /** A new photo to read, queued now; false (nothing changed) when the app sent this clientRef before. */
    public boolean insert(Read r) {
        return jdbc.update("INSERT INTO kirana_reads (id, shop_id, kind, status, file_ids, client_ref, tries, "
                        + "next_try_at, created_by, created_at, updated_at, product_id) VALUES (?, ?, ?, 'READING', "
                        + "?::uuid[], ?, 0, ?, ?, ?, ?, ?) ON CONFLICT (shop_id, client_ref) DO NOTHING",
                r.id(), r.shopId(), r.kind(), uuids(r.fileIds()), r.clientRef(), at(r.createdAt()), r.createdBy(),
                at(r.createdAt()), at(r.createdAt()), r.productId()) > 0;
    }

    /** A uuid[] literal ("{a,b}") for these ids. */
    private static String uuids(List<UUID> ids) {
        StringBuilder s = new StringBuilder("{");
        for (UUID id : ids) {
            if (s.length() > 1) {
                s.append(',');
            }
            s.append(id);
        }
        return s.append('}').toString();
    }

    public Optional<Read> byClientRef(UUID shopId, UUID clientRef) {
        return jdbc.query(READ + " WHERE shop_id = ? AND client_ref = ?", ReadStore::read, shopId, clientRef).stream()
                .findFirst();
    }

    public Optional<Read> find(UUID shopId, UUID id) {
        return jdbc.query(READ + " WHERE id = ? AND shop_id = ?", ReadStore::read, id, shopId).stream().findFirst();
    }

    public Optional<Read> find(UUID id) {
        return jdbc.query(READ + " WHERE id = ?", ReadStore::read, id).stream().findFirst();
    }

    /** Reads by id, in one query (any shop's: a pack's label read once is shown to every shop that scans it). */
    public java.util.Map<UUID, Read> findAll(java.util.Collection<UUID> ids) {
        java.util.Map<UUID, Read> out = new java.util.HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.query(READ + " WHERE id = ANY (?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids.toArray())),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    Read r = read(rs, 0);
                    out.put(r.id(), r);
                });
        return out;
    }

    /** One read, its row locked until the transaction ends. */
    public Optional<Read> lock(UUID shopId, UUID id) {
        return jdbc.query(READ + " WHERE id = ? AND shop_id = ? FOR NO KEY UPDATE", ReadStore::read, id, shopId)
                .stream().findFirst();
    }

    /** Reads due now, oldest first (the job claims each before reading it). */
    public List<UUID> due(Instant now, int limit) {
        return jdbc.queryForList("SELECT id FROM kirana_reads WHERE status = 'READING' AND next_try_at <= ? "
                + "ORDER BY next_try_at LIMIT ?", UUID.class, at(now), limit);
    }

    /**
     * Claims a read for this run: not due again until {@code until}, so another backend's run skips it meanwhile;
     * empty when it is held by another run, done or not due. Returns it as it was. The caller holds a transaction.
     */
    public Optional<Read> claim(UUID id, Instant until, Instant now) {
        Optional<Read> r = jdbc.query(READ + " WHERE id = ? AND status = 'READING' AND next_try_at <= ? "
                + "FOR NO KEY UPDATE SKIP LOCKED", ReadStore::read, id, at(now)).stream().findFirst();
        r.ifPresent(x -> jdbc.update("UPDATE kirana_reads SET tries = tries + 1, next_try_at = ?, updated_at = ? "
                + "WHERE id = ?", at(until), at(now), id));
        return r;
    }

    /** Waits for a daily cap until {@code nextTry}: not a try (the claim's count is taken back). */
    public void defer(UUID id, Instant nextTry, Instant now) {
        jdbc.update("UPDATE kirana_reads SET tries = greatest(tries - 1, 0), next_try_at = ?, updated_at = ? "
                + "WHERE id = ? AND status = 'READING'", at(nextTry), at(now), id);
    }

    /** The AI's answer is in. Only for a read still being read (not discarded meanwhile). */
    public boolean read(UUID id, String resultJson, Instant now) {
        return jdbc.update("UPDATE kirana_reads SET status = 'READ', result = ?::jsonb, failure = NULL, updated_at = ? "
                + "WHERE id = ? AND status = 'READING'", resultJson, at(now), id) > 0;
    }

    /** Couldn't be read (the shop is told why and can send a clearer one). */
    public boolean failed(UUID id, String failure, Instant now) {
        return jdbc.update("UPDATE kirana_reads SET status = 'FAILED', failure = ?, updated_at = ? "
                + "WHERE id = ? AND status = 'READING'", failure, at(now), id) > 0;
    }

    /** The AI didn't answer: tried again at {@code nextTry}. */
    public void retry(UUID id, Instant nextTry, Instant now) {
        jdbc.update("UPDATE kirana_reads SET next_try_at = ?, updated_at = ? WHERE id = ? AND status = 'READING'",
                at(nextTry), at(now), id);
    }

    /** Used (DONE, with the bill made from it) or thrown away (DISCARDED). The caller holds its lock. */
    public void setStatus(UUID id, String status, UUID billId, Instant now) {
        jdbc.update("UPDATE kirana_reads SET status = ?, bill_id = coalesce(?, bill_id), updated_at = ? WHERE id = ?",
                status, billId, at(now), id);
    }

    /** A shop's reads of one kind, newest first, after the cursor; one more than limit. */
    public List<Read> page(UUID shopId, String kind, Instant beforeAt, UUID beforeId, int limit) {
        if (beforeAt == null) {
            return jdbc.query(READ + " WHERE shop_id = ? AND kind = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                    ReadStore::read, shopId, kind, limit + 1);
        }
        return jdbc.query(READ + " WHERE shop_id = ? AND kind = ? AND (created_at, id) < (?, ?) "
                + "ORDER BY created_at DESC, id DESC LIMIT ?", ReadStore::read, shopId, kind, at(beforeAt), beforeId,
                limit + 1);
    }

    /**
     * Holds the shop's list pages until the transaction ends (a transaction-level advisory lock), so pages sent at the
     * same moment are counted one after another and the limit holds.
     */
    public void lockListPages(UUID shopId) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                "kirana-list-pages:" + shopId);
    }

    /** List pages the shop keeps: sent, not thrown away and not failed (through ix_kirana_reads_shop). */
    public int listPages(UUID shopId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM kirana_reads WHERE shop_id = ? AND kind = 'STOCK_LIST' "
                + "AND status NOT IN ('DISCARDED', 'FAILED')", Integer.class, shopId);
        return n == null ? 0 : n;
    }

    /** List pages the shop sent since then, thrown away or failed too (each was an AI read; ix_kirana_reads_shop). */
    public int listPagesSince(UUID shopId, Instant since) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM kirana_reads WHERE shop_id = ? AND kind = 'STOCK_LIST' "
                + "AND created_at >= ?", Integer.class, shopId, at(since));
        return n == null ? 0 : n;
    }

    /** Counts one AI read for the shop today; false (not counted) once it had {@code cap} today. */
    public boolean takeShopCall(UUID shopId, LocalDate day, int cap) {
        return jdbc.update("INSERT INTO kirana_shop_ai_calls (shop_id, day, calls) VALUES (?, ?, 1) "
                + "ON CONFLICT (shop_id, day) DO UPDATE SET calls = kirana_shop_ai_calls.calls + 1 "
                + "WHERE kirana_shop_ai_calls.calls < ?", shopId, java.sql.Date.valueOf(day), cap) > 0;
    }

    private static Read read(ResultSet rs, int i) throws SQLException {
        Array files = rs.getArray("file_ids");
        List<UUID> ids = new ArrayList<>();
        if (files != null) {
            for (Object o : (Object[]) files.getArray()) {
                ids.add(o instanceof UUID u ? u : UUID.fromString(o.toString()));
            }
        }
        return new Read(rs.getObject("id", UUID.class), rs.getObject("shop_id", UUID.class), rs.getString("kind"),
                rs.getString("status"), ids, rs.getObject("client_ref", UUID.class), rs.getInt("tries"),
                rs.getObject("next_try_at", OffsetDateTime.class).toInstant(), rs.getString("failure"),
                rs.getString("result"), rs.getObject("bill_id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime at(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }
}
