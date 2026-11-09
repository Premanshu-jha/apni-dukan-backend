package in.apnidukan.auth.repo;

import in.apnidukan.auth.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByPhone(String phone);

    /**
     * Reads the person and locks the row until the transaction ends (SELECT ... FOR UPDATE). Refresh and
     * "sign out everywhere" both take this lock, so a refresh can't slip a new token in while all are being ended.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> lockById(UUID id);

    @Query("SELECT u FROM User u WHERE lower(u.email) = lower(:email) AND u.emailVerifiedAt IS NOT NULL")
    Optional<User> findByVerifiedEmail(String email);

    /**
     * True while the account exists and isn't deleted, holding its row in shared mode until the transaction ends
     * (FOR SHARE conflicts with the lock deleting an account takes, unlike a foreign-key check).
     */
    @Query(value = "SELECT deleted_at IS NULL FROM users WHERE id = :id FOR SHARE", nativeQuery = true)
    Optional<Boolean> lockIfActive(UUID id);

    /** The name only (no entity is loaded, so the row locked next with lockById is read fresh). */
    @Query("SELECT u.name FROM User u WHERE u.id = :id")
    Optional<String> findNameById(UUID id);

    /** The phone only (no entity is loaded, so the row locked next with lockById is read fresh). */
    @Query("SELECT u.phone FROM User u WHERE u.id = :id")
    Optional<String> findPhoneById(UUID id);

    /**
     * Whether the account with this phone signed in within the last 30 days (a sign-in made then: its refresh token is
     * still kept, swapped or not). For the SMS budget (SmsBudget): near the cap, these people get codes first.
     */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM users u JOIN refresh_tokens r ON r.user_id = u.id "
            + "WHERE u.phone = :phone AND u.deleted_at IS NULL AND r.created_at > now() - interval '30 days')",
            nativeQuery = true)
    boolean signedInRecently(String phone);

    /** Only the id of the account with this phone (no entity is loaded, so lockById next reads the row fresh). */
    @Query("SELECT u.id FROM User u WHERE u.phone = :phone")
    Optional<UUID> findIdByPhone(String phone);

    /**
     * "ROLE" or "ROLE:DESIGNATION" of a live account, read fresh from the row (no entity is loaded or reused), so a
     * check made again after waiting for a lock sees a change committed meanwhile. Empty for a deleted account.
     */
    @Query(value = "SELECT admin_role || COALESCE(':' || admin_designation, '') FROM users "
            + "WHERE id = :id AND deleted_at IS NULL", nativeQuery = true)
    Optional<String> findTeamRoleById(UUID id);

    /** Only the id, so the row locked next (lockById) is read fresh, not from this lookup's copy. */
    @Query("SELECT u.id FROM User u WHERE lower(u.email) = lower(:email) AND u.emailVerifiedAt IS NOT NULL")
    Optional<UUID> findIdByVerifiedEmail(String email);
}
