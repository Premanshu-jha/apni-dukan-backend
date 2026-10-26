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
}
