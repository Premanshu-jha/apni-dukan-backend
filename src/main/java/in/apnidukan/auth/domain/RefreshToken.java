package in.apnidukan.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One device's long-lived sign-in. Only the SHA-256 of the token is stored. */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    /** The table's key is (id, created_at) since the weekly parts (V88); the id alone is still unique (random). */
    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Not a unique constraint since the weekly parts (V88), but unique in practice: a hash of 32 random bytes. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(length = 100)
    private String device;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason", length = 30)
    private RevokeReason revokeReason;

    /** The token this one was swapped for (rotation), if it was. */
    @Column(name = "replaced_by")
    private UUID replacedBy;

    protected RefreshToken() {
        // for JPA
    }

    public RefreshToken(UUID id, UUID userId, String tokenHash, String device, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.device = device;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public String getDevice() { return device; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public RevokeReason getRevokeReason() { return revokeReason; }
    public UUID getReplacedBy() { return replacedBy; }
}
