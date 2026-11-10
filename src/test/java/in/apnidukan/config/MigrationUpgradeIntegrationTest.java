package in.apnidukan.config;

import in.apnidukan.support.TestContainers;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The upgrade production goes through: a database at V20 (what is live) holding rows of every kind, then every later
 * migration on top, exactly as Flyway runs them on deploy. The rows stay as they were, and the new columns and tables
 * are there.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
class MigrationUpgradeIntegrationTest {

    @Test
    void aDatabaseFullOfV20RowsUpgradesToTheLatestKeepingEveryRow() {
        String name = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(TestContainers.jdbcUrl(),
                TestContainers.DB_USER, TestContainers.DB_PASSWORD));
        admin.execute("CREATE DATABASE " + name);
        String url = TestContainers.jdbcUrl().replaceAll("/[^/]+$", "/" + name);
        DriverManagerDataSource ds = new DriverManagerDataSource(url, TestContainers.DB_USER,
                TestContainers.DB_PASSWORD);
        try {
            flyway(ds, MigrationVersion.fromVersion("20")).migrate();
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            Timestamp now = Timestamp.from(Instant.parse("2026-11-01T04:30:00Z"));
            UUID owner = UUID.randomUUID();
            UUID customer = UUID.randomUUID();
            for (UUID[] p : new UUID[][] {{owner}, {customer}}) {
                jdbc.update("INSERT INTO users (id, phone, name, admin, created_at, updated_at) VALUES (?, ?, 'P', "
                        + "false, ?, ?)", p[0], p[0].equals(owner) ? "9000000001" : "9000000101", now, now);
            }
            jdbc.update("INSERT INTO refresh_tokens (id, user_id, token_hash, created_at, expires_at, revoked_at, "
                    + "revoke_reason, replaced_by) VALUES (?, ?, 'h1', ?, ?, ?, 'ROTATED', ?)", UUID.randomUUID(), owner,
                    now, Timestamp.from(now.toInstant().plusSeconds(86_400 * 30L)), now, UUID.randomUUID());
            UUID shop = UUID.randomUUID();
            jdbc.update("INSERT INTO shops (id, name, owner_name, address_line, area, city, pincode, latitude, "
                    + "longitude, status, type, version, created_at, updated_at) VALUES (?, 'Sharma Hair', 'Owner', "
                    + "'Shop 4', 'Kondapur', 'Hyderabad', '500084', 17.465, 78.364, 'APPROVED', 'SALON', 0, ?, ?)",
                    shop, now, now);
            jdbc.update("INSERT INTO shop_members (id, shop_id, user_id, role, created_at) VALUES (?, ?, ?, 'OWNER', ?)",
                    UUID.randomUUID(), shop, owner, now);
            UUID barber = UUID.randomUUID();
            jdbc.update("INSERT INTO salon_staff (id, shop_id, name, active, version, created_at, updated_at) "
                    + "VALUES (?, ?, 'Raju', true, 0, ?, ?)", barber, shop, now, now);
            UUID booking = UUID.randomUUID();
            jdbc.update("INSERT INTO appointments (id, shop_id, staff_id, kind, customer_id, booking_no, start_code, "
                    + "starts_at, ends_at, status, created_by, created_at, updated_at) VALUES (?, ?, ?, 'BOOKING', ?, "
                    + "'AB12CDE', '4827', ?, ?, 'CONFIRMED', ?, ?, ?)", booking, shop, barber, customer,
                    Timestamp.from(now.toInstant().plusSeconds(7200)), Timestamp.from(now.toInstant().plusSeconds(9000)),
                    customer, now, now);
            jdbc.update("INSERT INTO notification_jobs (id, user_id, channel, type, title, body, send_at, status, "
                    + "attempts, next_try_at, created_at) VALUES (?, ?, 'PUSH', 'SALON_NEW_BOOKING', 't', 'b', ?, "
                    + "'SENT', 1, ?, ?)", UUID.randomUUID(), owner, now, now, now);

            flyway(ds, MigrationVersion.LATEST).migrate();

            Map<String, Object> after = jdbc.queryForMap("SELECT (SELECT count(*) FROM users) AS users, "
                    + "(SELECT count(*) FROM refresh_tokens) AS tokens, (SELECT status FROM shops) AS shop, "
                    + "(SELECT count(*) FROM shop_members) AS members, (SELECT status FROM appointments) AS booking, "
                    + "(SELECT change_xid FROM appointments) AS xid, (SELECT count(*) FROM notification_jobs) AS jobs, "
                    + "(SELECT count(*) FROM shop_invites) AS invites, (SELECT count(*) FROM salon_closings) AS closings, "
                    + "(SELECT count(*) FROM salon_message_purges) AS purges");
            assertThat(after).containsEntry("users", 2L).containsEntry("tokens", 1L).containsEntry("shop", "APPROVED")
                    .containsEntry("members", 1L).containsEntry("booking", "CONFIRMED").containsEntry("jobs", 1L)
                    .containsEntry("invites", 0L).containsEntry("closings", 0L).containsEntry("purges", 0L);
            assertThat(after.get("xid")).isNull();   // rows from before V21 carry no stamp
            // A write after the upgrade is stamped and moves the salon's counter at commit
            jdbc.update("UPDATE appointments SET updated_at = updated_at WHERE id = ?", booking);
            assertThat(jdbc.queryForObject("SELECT change_xid IS NOT NULL FROM appointments", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM salon_versions WHERE shop_id = ?", Long.class, shop))
                    .isEqualTo(1L);
        } finally {
            admin.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        }
    }

    @Test
    void existingAdminsBecomeFieldAgentsWithNoPincodesAndWaitingShopsStayUnassigned() {
        String name = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(TestContainers.jdbcUrl(),
                TestContainers.DB_USER, TestContainers.DB_PASSWORD));
        admin.execute("CREATE DATABASE " + name);
        String url = TestContainers.jdbcUrl().replaceAll("/[^/]+$", "/" + name);
        DriverManagerDataSource ds = new DriverManagerDataSource(url, TestContainers.DB_USER,
                TestContainers.DB_PASSWORD);
        try {
            flyway(ds, MigrationVersion.fromVersion("29")).migrate();
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            Timestamp now = Timestamp.from(Instant.parse("2026-11-08T04:30:00Z"));
            UUID boss = UUID.randomUUID();
            UUID fieldAdmin = UUID.randomUUID();
            UUID customer = UUID.randomUUID();
            jdbc.update("INSERT INTO users (id, phone, name, admin, admin_role, created_at, updated_at) VALUES "
                    + "(?, '9000000001', 'Prem', true, 'SUPER_ADMIN', ?, ?), (?, '9000000002', 'Ravi', true, 'ADMIN', "
                    + "?, ?), (?, '9000000003', 'Sita', false, 'NONE', ?, ?)", boss, now, now, fieldAdmin, now, now,
                    customer, now, now);
            UUID shop = UUID.randomUUID();
            jdbc.update("INSERT INTO shops (id, name, owner_name, address_line, area, city, pincode, latitude, "
                    + "longitude, status, type, version, created_at, updated_at) VALUES (?, 'Raju Hair', 'Owner', "
                    + "'Shop 4', 'Kondapur', 'Hyderabad', '500084', 17.465, 78.364, 'PENDING_VISIT', 'SALON', 0, ?, ?)",
                    shop, now, now);

            flyway(ds, MigrationVersion.LATEST).migrate();

            assertThat(jdbc.queryForList("SELECT admin_role || '/' || COALESCE(admin_designation, '-') FROM users "
                    + "ORDER BY phone", String.class)).containsExactly("SUPER_ADMIN/-", "ADMIN/FIELD_AGENT", "NONE/-");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_pincodes", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT assigned_admin_id IS NULL FROM shops", Boolean.class)).isTrue();
            // More than one super admin is allowed now
            jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN', admin_designation = NULL WHERE id = ?",
                    fieldAdmin);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE admin_role = 'SUPER_ADMIN'", Long.class))
                    .isEqualTo(2L);
        } finally {
            admin.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        }
    }

    @Test
    void salonsGetOpeningHoursFromTheirBarbersAndServicesATwoWeekComeBackInterval() {
        String name = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(TestContainers.jdbcUrl(),
                TestContainers.DB_USER, TestContainers.DB_PASSWORD));
        admin.execute("CREATE DATABASE " + name);
        String url = TestContainers.jdbcUrl().replaceAll("/[^/]+$", "/" + name);
        DriverManagerDataSource ds = new DriverManagerDataSource(url, TestContainers.DB_USER,
                TestContainers.DB_PASSWORD);
        try {
            flyway(ds, MigrationVersion.fromVersion("33")).migrate();
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            Timestamp now = Timestamp.from(Instant.parse("2026-11-09T04:30:00Z"));
            UUID salon = UUID.randomUUID();
            UUID empty = UUID.randomUUID();
            for (UUID s : new UUID[] {salon, empty}) {
                jdbc.update("INSERT INTO shops (id, name, owner_name, address_line, area, city, pincode, latitude, "
                        + "longitude, status, type, version, created_at, updated_at) VALUES (?, 'Hair', 'Owner', "
                        + "'Shop 4', 'Kondapur', 'Hyderabad', '500084', 17.465, 78.364, 'APPROVED', 'SALON', 0, ?, ?)",
                        s, now, now);
            }
            UUID raju = UUID.randomUUID();
            UUID left = UUID.randomUUID();   // switched off: not counted (his hours are cut to fit if he comes back)
            jdbc.update("INSERT INTO salon_staff (id, shop_id, name, active, version, created_at, updated_at) "
                    + "VALUES (?, ?, 'Raju', true, 0, ?, ?), (?, ?, 'Old', false, 0, ?, ?)", raju, salon, now, now,
                    left, salon, now, now);
            // Monday: Raju 10:00-14:00 and 15:00-20:00 → 10:00-20:00 (the one who left, 09:00-12:00, doesn't count)
            // Tuesday: Raju 11:00-19:00 → 11:00-19:00; the other days: nobody working → closed (the one who left
            // worked Sundays: customers mustn't see Sunday open with nobody to book)
            jdbc.update("INSERT INTO staff_hours (id, staff_id, weekday, start_min, end_min) VALUES "
                    + "(?, ?, 1, 600, 840), (?, ?, 1, 900, 1200), (?, ?, 1, 540, 720), (?, ?, 2, 660, 1140), "
                    + "(?, ?, 7, 480, 1320)",
                    UUID.randomUUID(), raju, UUID.randomUUID(), raju, UUID.randomUUID(), left, UUID.randomUUID(), raju,
                    UUID.randomUUID(), left);
            jdbc.update("INSERT INTO salon_services (id, shop_id, name, duration_min, price_paise, price_is_from, "
                    + "active, sort_order, version, created_at, updated_at) VALUES (?, ?, 'Haircut', 30, 15000, false, "
                    + "true, 0, 0, ?, ?)", UUID.randomUUID(), salon, now, now);

            // A closed day made before: Raju's CLOSED block for 12 Nov (India day)
            UUID closure = UUID.randomUUID();
            UUID ownerUser = UUID.randomUUID();
            jdbc.update("INSERT INTO users (id, phone, name, admin, admin_role, created_at, updated_at) VALUES (?, "
                    + "'9000000777', 'Owner', false, 'NONE', ?, ?)", ownerUser, now, now);
            jdbc.update("INSERT INTO appointments (id, shop_id, staff_id, kind, block_type, closure_id, starts_at, "
                    + "ends_at, status, created_by, created_at, updated_at) VALUES (?, ?, ?, 'BLOCK', 'CLOSED', ?, ?, ?, "
                    + "'BLOCKED', ?, ?, ?)", UUID.randomUUID(), salon, raju, closure,
                    Timestamp.from(Instant.parse("2026-11-11T18:30:00Z")),
                    Timestamp.from(Instant.parse("2026-11-12T18:30:00Z")), ownerUser, now, now);
            // A second closure id for that same day (shouldn't happen, but must not be left behind)
            jdbc.update("INSERT INTO appointments (id, shop_id, staff_id, kind, block_type, closure_id, starts_at, "
                    + "ends_at, status, created_by, created_at, updated_at) VALUES (?, ?, ?, 'BLOCK', 'CLOSED', ?, ?, ?, "
                    + "'BLOCKED', ?, ?, ?)", UUID.randomUUID(), salon, left, UUID.randomUUID(),
                    Timestamp.from(Instant.parse("2026-11-11T18:30:00Z")),
                    Timestamp.from(Instant.parse("2026-11-12T18:30:00Z")), ownerUser, now, now);

            flyway(ds, MigrationVersion.LATEST).migrate();

            // It is now the salon's one closed day, and every block of that day carries its id
            assertThat(jdbc.queryForObject("SELECT day::text FROM salon_closed_days WHERE shop_id = ?",
                    String.class, salon)).isEqualTo("2026-11-12");
            UUID day = jdbc.queryForObject("SELECT id FROM salon_closed_days WHERE shop_id = ?", UUID.class, salon);
            assertThat(jdbc.queryForList("SELECT DISTINCT closure_id FROM appointments WHERE shop_id = ? "
                    + "AND closure_id IS NOT NULL", UUID.class, salon)).containsExactly(day);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = "
                    + "'ix_appointments_shop_closures'", Long.class)).isZero();
            assertThat(jdbc.queryForList("SELECT weekday || ' ' || open_min || '-' || close_min FROM salon_hours "
                    + "WHERE shop_id = ? ORDER BY weekday", String.class, salon))
                    .containsExactly("1 600-1200", "2 660-1140");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM salon_hours WHERE shop_id = ?", Long.class, empty))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT return_after_days FROM salon_services", Integer.class))
                    .isEqualTo(14);
            assertThat(jdbc.queryForObject("SELECT registration_doc_type IS NULL FROM shops WHERE id = ?",
                    Boolean.class, salon)).isTrue();
        } finally {
            admin.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        }
    }

    /** As the app runs Flyway (application.properties): mixed files, no transactional lock, no statement limit. */
    private static Flyway flyway(DriverManagerDataSource ds, MigrationVersion target) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration").target(target).mixed(true)
                .initSql("SET statement_timeout = 0")
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .load();
    }
}
