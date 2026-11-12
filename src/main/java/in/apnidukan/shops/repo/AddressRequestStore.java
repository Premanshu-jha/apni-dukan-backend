package in.apnidukan.shops.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * An owner's requests to change his verified address (address_change_requests, V44): at most one PENDING per shop.
 * Callers hold the shop's row lock (it is read for update first), so two requests or decisions on one shop take
 * turns; the unique index is the last guard.
 */
@Repository
public class AddressRequestStore {

    /** A request as stored. status: PENDING, APPROVED, DECLINED or CANCELLED; reason only when DECLINED. */
    public record Request(UUID id, UUID shopId, UUID requestedBy, String addressLine, String area, String city,
                          String pincode, BigDecimal latitude, BigDecimal longitude, String status, String reason,
                          Instant createdAt, Instant decidedAt) {

        public boolean pending() {
            return "PENDING".equals(status);
        }
    }

    private static final String COLUMNS = "id, shop_id, requested_by, address_line, area, city, pincode, latitude, "
            + "longitude, status, reason, created_at, decided_at";

    private static final RowMapper<Request> ROW = (rs, n) -> new Request(rs.getObject("id", UUID.class),
            rs.getObject("shop_id", UUID.class), rs.getObject("requested_by", UUID.class), rs.getString("address_line"),
            rs.getString("area"), rs.getString("city"), rs.getString("pincode"), rs.getBigDecimal("latitude"),
            rs.getBigDecimal("longitude"), rs.getString("status"), rs.getString("reason"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant());

    private final JdbcTemplate jdbc;

    public AddressRequestStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The shop's waiting request, if any. */
    public Optional<Request> pending(UUID shopId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM address_change_requests WHERE shop_id = ? AND status = 'PENDING'",
                ROW, shopId).stream().findFirst();
    }

    /** The shop's latest request, whatever happened to it (the owner sees a declined one with its reason). */
    public Optional<Request> latest(UUID shopId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM address_change_requests WHERE shop_id = ? "
                + "ORDER BY seq DESC LIMIT 1", ROW, shopId).stream().findFirst();
    }

    /** Which of these shops have a request waiting (the field team's list badge). One index lookup per shop. */
    public java.util.Set<UUID> withPending(List<UUID> shopIds) {
        if (shopIds.isEmpty()) {
            return java.util.Set.of();
        }
        java.util.Set<UUID> out = new java.util.HashSet<>();
        jdbc.query("SELECT shop_id FROM address_change_requests WHERE status = 'PENDING' AND shop_id = ANY (?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", shopIds.toArray())),
                rs -> {
                    out.add(rs.getObject("shop_id", UUID.class));
                });
        return out;
    }

    /** A new waiting request. The caller cancelled the shop's earlier one first (one PENDING per shop). */
    public Request add(UUID shopId, UUID by, String addressLine, String area, String city, String pincode,
                       BigDecimal latitude, BigDecimal longitude, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO address_change_requests (id, shop_id, requested_by, address_line, area, city, pincode, "
                        + "latitude, longitude, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, shopId, by, addressLine, area, city, pincode, latitude, longitude, Timestamp.from(now));
        return new Request(id, shopId, by, addressLine, area, city, pincode, latitude, longitude, "PENDING", null, now,
                null);
    }

    /**
     * Decides the shop's waiting request: APPROVED, DECLINED (with the reason) or CANCELLED. Returns false when none
     * was waiting.
     */
    public boolean decide(UUID shopId, String status, String reason, UUID by, Instant now) {
        return jdbc.update("UPDATE address_change_requests SET status = ?, reason = ?, decided_by = ?, decided_at = ? "
                + "WHERE shop_id = ? AND status = 'PENDING'", status, reason, by, Timestamp.from(now), shopId) > 0;
    }
}
