package in.apnidukan.auth.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The pincodes each field agent covers (admin_pincodes, V30). Written only by the Team page, under the team lock
 * (TeamLock); read by the Team page and, in the shops module, by assignment (ShopAssignmentStore reads the table
 * itself).
 */
@Repository
public class AdminPincodeStore {

    private final JdbcTemplate jdbc;

    public AdminPincodeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** This admin's pincodes, in order. */
    public Set<String> of(UUID userId) {
        return new TreeSet<>(jdbc.queryForList("SELECT pincode FROM admin_pincodes WHERE user_id = ?", String.class,
                userId));
    }

    /** Pincodes of each of these admins, in order (an admin with none is left out). */
    public Map<UUID, List<String>> of(Collection<UUID> userIds) {
        Map<UUID, List<String>> out = new HashMap<>();
        if (userIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT user_id, pincode FROM admin_pincodes WHERE user_id = ANY (?) ORDER BY pincode",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", userIds.toArray())),
                rs -> {
                    out.computeIfAbsent(rs.getObject("user_id", UUID.class), k -> new ArrayList<>())
                            .add(rs.getString("pincode"));
                });
        return out;
    }

    /** Makes the admin's pincodes exactly these: the ones gone are deleted, new ones added. Returns those removed. */
    public Set<String> replace(UUID userId, Set<String> pincodes, Instant now) {
        Set<String> before = of(userId);
        Set<String> removed = new TreeSet<>(before);
        removed.removeAll(pincodes);
        if (!removed.isEmpty()) {
            jdbc.update("DELETE FROM admin_pincodes WHERE user_id = ? AND pincode = ANY (?)", ps -> {
                ps.setObject(1, userId);
                ps.setArray(2, ps.getConnection().createArrayOf("varchar", removed.toArray()));
            });
        }
        List<Object[]> added = new ArrayList<>();
        for (String p : pincodes) {
            if (!before.contains(p)) {
                added.add(new Object[] {userId, p, Timestamp.from(now)});
            }
        }
        if (!added.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO admin_pincodes (user_id, pincode, added_at) VALUES (?, ?, ?)", added);
        }
        return removed;
    }

    /** Every covered pincode with its agents (id and name), by pincode: the Team page's Coverage tab. */
    public Map<String, List<Agent>> coverage() {
        Map<String, List<Agent>> out = new LinkedHashMap<>();
        jdbc.query("SELECT p.pincode, u.id, u.name, u.phone FROM admin_pincodes p JOIN users u ON u.id = p.user_id "
                + "ORDER BY p.pincode, u.name NULLS LAST, u.id", rs -> {
                    out.computeIfAbsent(rs.getString("pincode"), k -> new ArrayList<>())
                            .add(new Agent(rs.getObject("id", UUID.class), rs.getString("name"),
                                    rs.getString("phone")));
                });
        return out;
    }

    public record Agent(UUID userId, String name, String phone) {
    }
}
