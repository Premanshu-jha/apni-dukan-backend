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

    /** Only the id, so the row locked next (lockById) is read fresh, not from this lookup's copy. */
    @Query("SELECT u.id FROM User u WHERE lower(u.email) = lower(:email) AND u.emailVerifiedAt IS NOT NULL")
    Optional<UUID> findIdByVerifiedEmail(String email);
}
