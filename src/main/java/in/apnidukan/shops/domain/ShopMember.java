package in.apnidukan.shops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/** A person working in a shop, as OWNER, HELPER or BARBER (salons). One shop per person for now (unique user_id). */
@Entity
@Table(name = "shop_members")
@Audited
public class ShopMember {

    /** Filled in by Hibernate when the row is first saved (a random UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private UUID shopId;

    @Column(name = "user_id", nullable = false, unique = true, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ShopRole role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ShopMember() {
        // for JPA
    }

    public ShopMember(UUID shopId, UUID userId, ShopRole role, Instant now) {
        this.shopId = shopId;
        this.userId = userId;
        this.role = role;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public UUID getShopId() { return shopId; }
    public UUID getUserId() { return userId; }
    public ShopRole getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
}
