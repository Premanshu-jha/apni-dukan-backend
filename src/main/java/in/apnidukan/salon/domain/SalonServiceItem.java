package in.apnidukan.salon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * One service a salon offers, e.g. "Haircut, 30 min, ₹150". Called an item to keep it apart from Spring's
 * service classes. Switched off (active = false) instead of deleted, so old bookings can still point at it.
 */
@Entity
@Table(name = "salon_services")
@Audited
public class SalonServiceItem {

    /** Filled in by Hibernate when the row is first saved (a random UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private UUID shopId;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(name = "duration_min", nullable = false)
    private int durationMin;

    @Column(name = "price_paise", nullable = false)
    private long pricePaise;

    @Column(name = "price_is_from", nullable = false)
    private boolean priceIsFrom;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "return_after_days", nullable = false)
    private int returnAfterDays;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ServiceIcon icon;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SalonServiceItem() {
        // for JPA
    }

    public SalonServiceItem(UUID shopId, ServiceDetails details, Instant now) {
        this.shopId = shopId;
        this.createdAt = now;
        apply(details, now);
    }

    public void apply(ServiceDetails details, Instant now) {
        this.name = details.name();
        this.durationMin = details.durationMin();
        this.pricePaise = details.pricePaise();
        this.priceIsFrom = details.priceIsFrom();
        this.active = details.active();
        this.sortOrder = details.sortOrder();
        this.returnAfterDays = details.returnAfterDays();
        this.icon = details.icon();
        this.updatedAt = now;
    }

    public ServiceDetails details() {
        return new ServiceDetails(name, durationMin, pricePaise, priceIsFrom, active, sortOrder, returnAfterDays, icon);
    }

    public UUID getId() { return id; }
    public UUID getShopId() { return shopId; }
    public boolean isActive() { return active; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
