package in.apnidukan.salon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * A salon's online booking settings. The row is created the first time the owner saves them; until then the
 * defaults apply.
 */
@Entity
@Table(name = "salon_settings")
@Audited
public class SalonSettings {

    /** Earliest booking: 30 minutes from now. */
    public static final int DEFAULT_MIN_LEAD_MIN = 30;
    /** Latest booking: 7 days from today. */
    public static final int DEFAULT_MAX_DAYS_AHEAD = 7;

    /** The salon's shop id: one settings row per salon. */
    @Id
    @Column(name = "shop_id")
    private UUID shopId;

    @Column(name = "min_lead_min", nullable = false)
    private int minLeadMin;

    @Column(name = "max_days_ahead", nullable = false)
    private int maxDaysAhead;

    /** A wrapper, not long: null tells Spring Data the row is new (the id is ours, not generated). */
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SalonSettings() {
        // for JPA
    }

    public SalonSettings(UUID shopId, Instant now) {
        this.shopId = shopId;
        this.minLeadMin = DEFAULT_MIN_LEAD_MIN;
        this.maxDaysAhead = DEFAULT_MAX_DAYS_AHEAD;
        this.updatedAt = now;
    }

    public void apply(int minLeadMin, int maxDaysAhead, Instant now) {
        this.minLeadMin = minLeadMin;
        this.maxDaysAhead = maxDaysAhead;
        this.updatedAt = now;
    }

    public UUID getShopId() { return shopId; }
    public int getMinLeadMin() { return minLeadMin; }
    public int getMaxDaysAhead() { return maxDaysAhead; }
    /** null until the row is saved for the first time. */
    public Long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
}
