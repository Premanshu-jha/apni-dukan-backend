package in.apnidukan.auth.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** The super admin's log (admin_events): admins, test numbers and test emails added or removed, test mode on and off. */
@Component
class AdminEvents {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    AdminEvents(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** In the caller's transaction, so the log and the change are saved together. */
    void record(UUID by, String event, String phone, String detail) {
        jdbc.update("INSERT INTO admin_events (id, at, by_user, event, phone, detail) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), Timestamp.from(Instant.now(clock)), by, event, phone,
                detail == null || detail.length() <= 200 ? detail : detail.substring(0, 200));
    }
}
