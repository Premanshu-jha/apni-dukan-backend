package in.apnidukan.auth.service;

import in.apnidukan.auth.domain.AdminRole;
import in.apnidukan.auth.domain.User;
import in.apnidukan.auth.repo.RefreshTokenRepository;
import in.apnidukan.auth.repo.UserRepository;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.security.SessionRevocations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The admin website's team, run by the super admin: who the admins are, adding one by phone (the account is made if
 * that phone has none; they sign in with the normal OTP) and removing one (signed out at once). The super admin is
 * set by hand in the database and is never changed here.
 *
 * Who may do this is always read from the database, not from the sign-in token (which can be up to 15 minutes old).
 */
@Service
public class AdminTeamService {

    private static final Logger log = LoggerFactory.getLogger(AdminTeamService.class);

    /** Advisory lock for changes to admins and test numbers (so a number can't become both at the same moment). */
    static final int LOCK = 7201;

    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final TestModeService testMode;
    private final SessionRevocations revocations;
    private final RefreshTokenRepository refreshTokens;
    private final AdminEvents events;
    private final Clock clock;

    public AdminTeamService(UserRepository users, JdbcTemplate jdbc, TestModeService testMode,
                            SessionRevocations revocations, RefreshTokenRepository refreshTokens,
                            AdminEvents events, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.jdbc = jdbc;
        this.testMode = testMode;
        this.revocations = revocations;
        this.events = events;
        this.clock = clock;
    }

    /** Who is signed in to the admin website, and whether OTP test mode is on (for the banner). */
    @Transactional(readOnly = true)
    public Me me(UUID userId) {
        User u = users.findById(userId).filter(User::isAdmin).filter(x -> !x.isDeleted())
                .orElseThrow(() -> ApiException.notFound("Page"));
        return new Me(u.getId(), u.getName(), u.getPhone(), u.getAdminRole(), testMode.current());
    }

    /** The super admin first, then admins by when they were added. */
    @Transactional(readOnly = true)
    public List<Member> team(UUID superAdminId) {
        requireSuperAdmin(users, superAdminId);
        return jdbc.query("SELECT id, name, phone, admin_role, COALESCE(admin_since, created_at) AS since FROM users "
                        + "WHERE admin_role <> 'NONE' AND deleted_at IS NULL",
                        (rs, i) -> new Member(rs.getObject("id", UUID.class), rs.getString("name"),
                                rs.getString("phone"), AdminRole.valueOf(rs.getString("admin_role")),
                                rs.getTimestamp("since").toInstant()))
                .stream()
                .sorted(Comparator.comparing((Member m) -> m.adminRole() != AdminRole.SUPER_ADMIN)
                        .thenComparing(Member::since))
                .toList();
    }

    /** 409 ALREADY_ADMIN, or TEST_PHONE for a test number (it signs in with any code while test mode is on). */
    @Transactional
    public Member add(UUID superAdminId, String phoneInput) {
        requireSuperAdmin(users, superAdminId);
        String phone = PhoneNumbers.normalize(phoneInput);
        lockTeam(jdbc);
        if (testMode.isTestPhone(phone)) {
            throw new ApiException(HttpStatus.CONFLICT, "TEST_PHONE",
                    "That is a test number. Remove it from the test numbers first.");
        }
        Instant now = now();
        User user = users.findByPhone(phone).orElseGet(() -> {
            try {
                return users.saveAndFlush(new User(UUID.randomUUID(), phone, now));
            } catch (DataIntegrityViolationException e) {
                throw new ApiException(HttpStatus.CONFLICT, "TRY_AGAIN", "Please try again.");
            }
        });
        if (user.isAdmin()) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_ADMIN", "That person is an admin already.");
        }
        if (user.isTestAccount()) {
            throw new ApiException(HttpStatus.CONFLICT, "TEST_PHONE",
                    "That number's account was made in test mode, so others may be signed in to it. Use another "
                            + "number.");
        }
        user.makeAdmin(now);
        users.saveAndFlush(user);
        // Anyone already signed in to this account starts again: admin rights come only with a new sign-in
        refreshTokens.revokeAllForUser(user.getId(), now);
        signOutAfterCommit(user.getId());
        events.record(superAdminId, "ADMIN_ADDED", phone, null);
        return new Member(user.getId(), user.getName(), user.getPhone(), user.getAdminRole(), now);
    }

    /** Removes an admin; their sessions end at once (the app signs in again without admin rights). */
    @Transactional
    public void remove(UUID superAdminId, UUID adminId) {
        requireSuperAdmin(users, superAdminId);
        lockTeam(jdbc);
        User user = users.lockById(adminId).filter(User::isAdmin).orElseThrow(() -> ApiException.notFound("Admin"));
        if (user.getAdminRole() == AdminRole.SUPER_ADMIN) {
            throw new ApiException(HttpStatus.CONFLICT, "CANT_REMOVE_SUPER_ADMIN",
                    "The super admin can't be removed here.");
        }
        user.removeAdmin(now());
        users.saveAndFlush(user);
        events.record(superAdminId, "ADMIN_REMOVED", user.getPhone(), null);
        signOutAfterCommit(adminId);
    }

    /** After the change is saved: a token refreshed in between would otherwise still carry the old rights. */
    private void signOutAfterCommit(UUID userId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    revocations.revokeAll(userId);
                } catch (DataAccessException redisDown) {
                    // Every admin page reads the role from the database anyway; the token expires within 15 min
                    log.warn("Redis didn't answer; sessions of {} end when their tokens expire", userId);
                }
            }
        });
    }

    /** 403 SUPER_ADMIN_ONLY unless this person is the super admin (404 if not an admin at all). */
    static void requireSuperAdmin(UserRepository users, UUID userId) {
        AdminRole role = users.findById(userId).filter(u -> !u.isDeleted()).map(User::getAdminRole)
                .orElse(AdminRole.NONE);
        if (role == AdminRole.NONE) {
            throw ApiException.notFound("Page");
        }
        if (role != AdminRole.SUPER_ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SUPER_ADMIN_ONLY", "Only the super admin can do this.");
        }
    }

    static void lockTeam(JdbcTemplate jdbc) {
        jdbc.queryForObject("SELECT count(*) FROM (SELECT pg_advisory_xact_lock(?)) l", Long.class, LOCK);
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    public record Me(UUID userId, String name, String phone, AdminRole adminRole, TestModeService.Switch testMode) {
    }

    /** @param since when they became an admin (for the super admin, set by hand: when the account was made) */
    public record Member(UUID userId, String name, String phone, AdminRole adminRole, Instant since) {
    }
}
