package in.apnidukan.auth.repo;

import in.apnidukan.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Revokes one token only if it is still active. Returns 1 if this call revoked it, 0 if it already was. */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.id = :id AND t.revokedAt IS NULL")
    int revokeIfActive(UUID id, Instant now);

    /** Signs a person out of every device. */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.userId = :userId AND t.revokedAt IS NULL")
    int revokeAllForUser(UUID userId, Instant now);
}
