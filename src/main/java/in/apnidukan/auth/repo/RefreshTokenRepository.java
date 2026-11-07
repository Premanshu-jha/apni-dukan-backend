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

    /** Whose token it is, without loading the token (it is read fresh after the person's row is locked). */
    @Query("SELECT t.userId FROM RefreshToken t WHERE t.tokenHash = :tokenHash")
    Optional<UUID> findUserIdByTokenHash(String tokenHash);

    /** Records which token this one was swapped for. */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.replacedBy = :next WHERE t.id = :id")
    int replacedBy(UUID id, UUID next);

    /** Revokes one token only if it is still active. Returns 1 if this call revoked it, 0 if it already was. */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now, t.revokeReason = :reason "
            + "WHERE t.id = :id AND t.revokedAt IS NULL")
    int revokeIfActive(UUID id, Instant now, RevokeReason reason);

    /** A rotated token came back: marks it so that it can trigger "signed out everywhere" only this once. */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokeReason = in.apnidukan.auth.domain.RevokeReason.REUSE_DETECTED "
            + "WHERE t.id = :id AND t.revokeReason IN (in.apnidukan.auth.domain.RevokeReason.ROTATED, "
            + "in.apnidukan.auth.domain.RevokeReason.GRACE_REPLACED)")
    int markReuseDetected(UUID id);

    /**
     * The database's clock, in microseconds: refresh compares the swap times other copies of the backend wrote with it,
     * so servers whose own clocks differ can't disagree about the grace.
     */
    @Query(value = "SELECT (extract(epoch FROM clock_timestamp()) * 1000000)::bigint", nativeQuery = true)
    long databaseMicros();

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
