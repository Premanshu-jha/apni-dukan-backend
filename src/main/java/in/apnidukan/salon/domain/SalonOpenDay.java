package in.apnidukan.salon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * One day of a salon's opening hours, e.g. Monday 10:00 to 21:00. A weekday without one is the salon's weekly off.
 * Times are minutes after midnight in salon time (see {@link SalonTime}); stored as a {@link Shift} of the salon.
 * Rows are never changed: a new week deletes the days that changed and adds the new ones (as barbers' hours do).
 */
@Entity
@Table(name = "salon_hours")
@Audited
public class SalonOpenDay {

    /** Filled in by Hibernate when the row is first saved (a random UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private UUID shopId;

    /** ISO day of the week: 1 = Monday ... 7 = Sunday. */
    @Column(nullable = false, updatable = false)
    private int weekday;

    @Column(name = "open_min", nullable = false, updatable = false)
    private int openMin;

    @Column(name = "close_min", nullable = false, updatable = false)
    private int closeMin;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    protected SalonOpenDay() {
        // for JPA
    }

    public SalonOpenDay(UUID shopId, Shift day, Instant now) {
        this.shopId = shopId;
        this.weekday = day.weekday();
        this.openMin = day.startMin();
        this.closeMin = day.endMin();
        this.updatedAt = now;
    }

    /** The day as a block of time: start = opening, end = closing. */
    public Shift day() {
        return new Shift(weekday, openMin, closeMin);
    }

    public UUID getId() { return id; }
    public UUID getShopId() { return shopId; }
}
