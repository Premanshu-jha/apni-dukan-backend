package in.apnidukan.auth.repo;

import in.apnidukan.auth.domain.RefreshToken;
import in.apnidukan.auth.domain.RevokeReason;
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
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now, t.revokeReason = :reason "
            + "WHERE t.id = :id AND t.revokedAt IS NULL")
    int revokeIfActive(UUID id, Instant now, RevokeReason reason);

    /**
     * Swaps a token for its successor (rotation), only if it is still active: marks it ROTATED and remembers which
     * token replaced it, so the cleanup knows when it can no longer raise the "copied token" alarm. Returns 1 if this
     * call rotated it, 0 if it had already ended.
     */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now, "
            + "t.revokeReason = in.apnidukan.auth.domain.RevokeReason.ROTATED, t.replacedBy = :successor "
            + "WHERE t.id = :id AND t.revokedAt IS NULL")
    int rotateIfActive(UUID id, UUID successor, Instant now);

    /** A rotated token came back: marks it so that it can trigger "signed out everywhere" only this once. */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokeReason = in.apnidukan.auth.domain.RevokeReason.REUSE_DETECTED "
            + "WHERE t.id = :id AND t.revokeReason = in.apnidukan.auth.domain.RevokeReason.ROTATED")
    int markReuseDetected(UUID id);

    /** Forgets the device labels of a person's sign-ins (their account was deleted). */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.device = NULL WHERE t.userId = :userId")
    int forgetDevices(UUID userId);

    /** Signs a person out of every device. */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now, "
            + "t.revokeReason = in.apnidukan.auth.domain.RevokeReason.SIGNED_OUT_EVERYWHERE "
            + "WHERE t.userId = :userId AND t.revokedAt IS NULL")
    int revokeAllForUser(UUID userId, Instant now);
}
