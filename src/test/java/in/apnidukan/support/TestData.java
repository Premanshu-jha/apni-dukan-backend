package in.apnidukan.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Cleans the test database and seeds rows directly, in an order the foreign keys allow. */
public final class TestData {

    private TestData() {
    }

    /** Empties every table the tests write to (children first). */
    public static void clean(JdbcTemplate jdbc) {
        for (String table : new String[] {"files", "shop_members_aud", "shops_aud", "revinfo", "shop_members", "shops",
                "refresh_tokens", "users"}) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    public static void user(JdbcTemplate jdbc, UUID id, String phone) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO users (id, phone, admin, created_at, updated_at) VALUES (?, ?, false, ?, ?)",
                id, phone, now, now);
    }

    /** A DRAFT shop in Hyderabad with the given person as its owner. The shop code comes from the column's default. */
    public static void shop(JdbcTemplate jdbc, UUID shopId, UUID ownerId) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO shops (id, name, owner_name, address_line, area, city, pincode, latitude, "
                        + "longitude, status, version, created_at, updated_at) VALUES (?, 'Test Kirana', 'Test Owner', "
                        + "'Shop 4, Main Road', 'Kondapur', 'Hyderabad', '500084', 17.465000, 78.364000, 'DRAFT', 0, ?, ?)",
                shopId, now, now);
        member(jdbc, shopId, ownerId, "OWNER");
    }

    public static void member(JdbcTemplate jdbc, UUID shopId, UUID userId, String role) {
        jdbc.update("INSERT INTO shop_members (id, shop_id, user_id, role, created_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), shopId, userId, role, Timestamp.from(Instant.now()));
    }
}
