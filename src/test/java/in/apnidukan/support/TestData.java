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
        // Photos point at files and files point at shops: let go of the photos first
        jdbc.update("UPDATE shops SET owner_photo_file_id = NULL, shop_photo_file_id = NULL, shop_video_file_id = NULL");
        jdbc.update("UPDATE salon_staff SET photo_file_id = NULL");
        jdbc.update("UPDATE otp_test_mode SET until = NULL, turned_on_by = NULL, turned_on_at = NULL");
        for (String table : new String[] {"earlier_slot_alerts", "shop_assignments", "admin_pincodes", "video_uploads", "shop_invites", "salon_closings", "salon_message_purges", "shop_handovers", "job_leases", "account_wipes", "file_deletions", "shop_events", "admin_events", "test_emails", "test_phones", "files", "appointment_events", "customer_strikes", "appointment_services",
                "appointments", "walk_in_access", "salon_pauses", "notification_jobs", "push_subscriptions",
                "staff_hours_aud", "staff_services_aud", "salon_staff_aud",
                "salon_services_aud", "salon_settings_aud", "shop_members_aud", "shops_aud", "revinfo", "staff_hours",
                "staff_services", "salon_staff", "salon_services", "salon_settings", "shop_members", "shops",
                "refresh_tokens", "users", "salon_versions"}) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    /** A person who finished sign-up: a verified email (phone@test.in) and the name "Test person". */
    public static void user(JdbcTemplate jdbc, UUID id, String phone) {
        bareUser(jdbc, id, phone);
        completeProfile(jdbc, phone);
    }

    /** A person who only signed in with the phone: no verified email, no name yet. */
    public static void bareUser(JdbcTemplate jdbc, UUID id, String phone) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO users (id, phone, admin, created_at, updated_at) VALUES (?, ?, false, ?, ?)",
                id, phone, now, now);
    }

    /** Gives the account with this phone a verified email and a name, as the sign-up screens would. */
    public static void completeProfile(JdbcTemplate jdbc, String phone) {
        jdbc.update("UPDATE users SET name = 'Test person', email = phone || '@test.in', email_verified_at = now() "
                + "WHERE phone = ?", phone);
    }

    /**
     * A DRAFT kirana in Hyderabad with the given person as its owner. The shop code comes from the column's default.
     */
    public static void shop(JdbcTemplate jdbc, UUID shopId, UUID ownerId) {
        shop(jdbc, shopId, ownerId, "KIRANA");
    }

    /** Like {@link #shop}, but a salon. */
    public static void salon(JdbcTemplate jdbc, UUID shopId, UUID ownerId) {
        shop(jdbc, shopId, ownerId, "SALON");
    }

    private static void shop(JdbcTemplate jdbc, UUID shopId, UUID ownerId, String type) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO shops (id, name, owner_name, address_line, area, city, pincode, latitude, "
                        + "longitude, status, type, version, created_at, updated_at) VALUES (?, 'Test Shop', "
                        + "'Test Owner', 'Shop 4, Main Road', 'Kondapur', 'Hyderabad', '500084', 17.465000, 78.364000, "
                        + "'DRAFT', ?, 0, ?, ?)",
                shopId, type, now, now);
        member(jdbc, shopId, ownerId, "OWNER");
    }

    public static void member(JdbcTemplate jdbc, UUID shopId, UUID userId, String role) {
        jdbc.update("INSERT INTO shop_members (id, shop_id, user_id, role, created_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), shopId, userId, role, Timestamp.from(Instant.now()));
    }
}
