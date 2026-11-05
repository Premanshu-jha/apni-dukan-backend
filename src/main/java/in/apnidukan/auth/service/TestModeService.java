package in.apnidukan.auth.service;

import in.apnidukan.auth.domain.User;
import in.apnidukan.auth.repo.UserRepository;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.text.TextCleaner;
import in.apnidukan.common.text.TextCleaner.TextField;
import in.apnidukan.common.security.SessionRevocations;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * OTP test mode, run by the super admin from the admin website. While it is on (otp_test_mode.until in the
 * future), the numbers in test_phones sign in with any 6-digit code and no SMS is sent to them. Only for numbers with
 * no account yet, or whose account was made this way (users.test_account): a number that belongs to a real
 * customer or shop can't be added, and an admin's account never takes any code (not even if both somehow
 * happened), so test mode can't open someone's real account or the admin website. A test account stays signed in
 * only while test mode is on for its number (AuthService.refresh). Email codes, recovery and account deletion
 * always need the real code.
 * It switches itself off at `until`: nothing has to run for that, every check compares with the clock.
 *
 * Changes to test numbers and to admins take the same database lock (AdminTeamService.LOCK), so a number can't
 * become an admin and a test number at the same moment.
 */
@Service
public class TestModeService {

    static final int MIN_HOURS = 1;
    static final int MAX_HOURS = 8;
    static final TextField LABEL = new TextField("label", 2, 60);

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final AdminEvents events;
    private final SessionRevocations revocations;
    private final Clock clock;

    public TestModeService(JdbcTemplate jdbc, UserRepository users, AdminEvents events, SessionRevocations revocations,
                           Clock clock) {
        this.revocations = revocations;
        this.jdbc = jdbc;
        this.users = users;
        this.events = events;
        this.clock = clock;
    }

    /** Whether this (normalised) phone may sign in with any code right now. Read on every sign-in attempt. */
    @Transactional(readOnly = true)
    public boolean acceptsAnyCode(String phone) {
        Boolean yes = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM otp_test_mode m JOIN test_phones t "
                + "ON t.phone = ? WHERE m.id = 1 AND m.until > ? "
                + "AND NOT EXISTS (SELECT 1 FROM users u WHERE u.phone = t.phone "
                + "AND (u.admin_role <> 'NONE' OR NOT u.test_account)))",
                Boolean.class, phone, Timestamp.from(now()));
        return Boolean.TRUE.equals(yes);
    }

    /** Is test mode on now, and until when (for the banner on every admin screen). */
    @Transactional(readOnly = true)
    public Switch current() {
        Timestamp until = jdbc.queryForObject("SELECT until FROM otp_test_mode WHERE id = 1", Timestamp.class);
        Instant u = until == null ? null : until.toInstant();
        boolean on = u != null && u.isAfter(now());
        return new Switch(on, on ? u : null);
    }

    /** The full state for the super admin's page. */
    @Transactional(readOnly = true)
    public State state(UUID superAdminId) {
        requireSuperAdmin(superAdminId);
        return read();
    }

    /** On for 1 to 8 hours from now (again from now if it was already on). */
    @Transactional
    public State turnOn(UUID superAdminId, int hours) {
        requireSuperAdmin(superAdminId);
        if (hours < MIN_HOURS || hours > MAX_HOURS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                    java.util.Map.of("hours", "must be from " + MIN_HOURS + " to " + MAX_HOURS));
        }
        Instant now = now();
        Instant until = now.plus(Duration.ofHours(hours));
        jdbc.update("UPDATE otp_test_mode SET until = ?, turned_on_by = ?, turned_on_at = ? WHERE id = 1",
                Timestamp.from(until), superAdminId, Timestamp.from(now));
        events.record(superAdminId, "TEST_MODE_ON", null, "until " + until);
        return read();
    }

    @Transactional
    public State turnOff(UUID superAdminId) {
        requireSuperAdmin(superAdminId);
        int changed = jdbc.update("UPDATE otp_test_mode SET until = NULL WHERE id = 1 AND until IS NOT NULL");
        if (changed > 0) {
            events.record(superAdminId, "TEST_MODE_OFF", null, null);
            signOutTestAccounts(jdbc.queryForList("SELECT u.id FROM users u JOIN test_phones t ON t.phone = u.phone "
                    + "WHERE u.test_account", UUID.class));
        }
        return read();
    }

    /** 409 ADMIN_PHONE for an admin's number, 409 ALREADY_TEST_PHONE if it is listed already. */
    @Transactional
    public State addPhone(UUID superAdminId, String phoneInput, String labelInput) {
        requireSuperAdmin(superAdminId);
        String phone = PhoneNumbers.normalize(phoneInput);
        String label = TextCleaner.clean(LABEL, labelInput);
        AdminTeamService.lockTeam(jdbc);
        User existing = users.findByPhone(phone).orElse(null);
        if (existing != null && existing.isAdmin()) {
            throw new ApiException(HttpStatus.CONFLICT, "ADMIN_PHONE",
                    "That is an admin's number. An admin's number can't be a test number.");
        }
        if (existing != null && !existing.isTestAccount()) {
            throw new ApiException(HttpStatus.CONFLICT, "REAL_ACCOUNT",
                    "That number already has a real account (a customer or a shop). Use a number nobody has "
                            + "signed in with.");
        }
        int added = jdbc.update("INSERT INTO test_phones (phone, label, added_by, added_at) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (phone) DO NOTHING", phone, label, superAdminId, Timestamp.from(now()));
        if (added == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_TEST_PHONE", "That number is a test number already.");
        }
        events.record(superAdminId, "TEST_PHONE_ADDED", phone, label);
        return read();
    }

    @Transactional
    public State removePhone(UUID superAdminId, String phoneInput) {
        requireSuperAdmin(superAdminId);
        String phone = PhoneNumbers.normalize(phoneInput);
        AdminTeamService.lockTeam(jdbc);
        if (jdbc.update("DELETE FROM test_phones WHERE phone = ?", phone) > 0) {
            events.record(superAdminId, "TEST_PHONE_REMOVED", phone, null);
            signOutTestAccounts(jdbc.queryForList("SELECT id FROM users WHERE phone = ? AND test_account",
                    UUID.class, phone));
        }
        return read();
    }

    /** True if this (normalised) phone is a test number, whether or not test mode is on. */
    boolean isTestPhone(String phone) {
        Boolean yes = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM test_phones WHERE phone = ?)",
                Boolean.class, phone);
        return Boolean.TRUE.equals(yes);
    }

    /**
     * Their access tokens stop at once (after the change is saved); their refresh is refused from now on, because
     * test mode no longer covers their number (AuthService.refresh).
     */
    private void signOutTestAccounts(List<UUID> accounts) {
        if (accounts.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (UUID id : accounts) {
                    try {
                        revocations.revokeAll(id);
                    } catch (DataAccessException redisDown) {
                        return;   // their tokens expire within 15 minutes, and none can be refreshed
                    }
                }
            }
        });
    }

    private State read() {
        Switch s = current();
        String by = jdbc.query("SELECT u.name FROM otp_test_mode m LEFT JOIN users u ON u.id = m.turned_on_by "
                + "WHERE m.id = 1", rs -> rs.next() ? rs.getString(1) : null);
        List<TestPhone> phones = jdbc.query("SELECT t.phone, t.label, t.added_at, "
                + "EXISTS (SELECT 1 FROM users u WHERE u.phone = t.phone) AS has_account "
                + "FROM test_phones t ORDER BY t.added_at, t.phone",
                (rs, i) -> new TestPhone(rs.getString("phone"), rs.getString("label"),
                        rs.getTimestamp("added_at").toInstant(), rs.getBoolean("has_account")));
        return new State(s.on(), s.until(), s.on() ? by : null, phones);
    }

    private void requireSuperAdmin(UUID userId) {
        AdminTeamService.requireSuperAdmin(users, userId);
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /** @param until null when off */
    public record Switch(boolean on, Instant until) {
    }

    /**
     * @param turnedOnByName who turned it on (null when off)
     * @param phones         the test numbers, oldest first
     */
    public record State(boolean on, Instant until, String turnedOnByName, List<TestPhone> phones) {

        public State {
            phones = List.copyOf(phones);
        }
    }

    /** @param hasAccount whether someone has signed in with it yet */
    public record TestPhone(String phone, String label, Instant addedAt, boolean hasAccount) {
    }
}
