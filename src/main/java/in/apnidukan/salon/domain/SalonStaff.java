package in.apnidukan.salon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A barber in a salon. A barber doesn't need a sign-in (the owner works the app for him); when the owner links his
 * phone, userId is his sign-in and he is a BARBER member of the salon, so he can open his own day in the shop app.
 * Switched off (active = false) instead of deleted, so old bookings can still point at him.
 */
@Entity
@Table(name = "salon_staff")
@Audited
public class SalonStaff {

    /** Filled in by Hibernate when the row is first saved (a random UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private UUID shopId;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(name = "user_id", unique = true)
    private UUID userId;

    /** His photo (a STAFF_PHOTO upload of this salon); optional, only for the salon's own screens. */
    @Column(name = "photo_file_id")
    private UUID photoFileId;

    @Column(nullable = false)
    private boolean active;

    /** When the owner removed him for good (switched off, out of the salon's lists); null if not removed. */
    @Column(name = "removed_at")
    private Instant removedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SalonStaff() {
        // for JPA
    }

    public SalonStaff(UUID shopId, String name, Instant now) {
        this.shopId = shopId;
        this.name = name;
        this.active = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void apply(String name, boolean active, Instant now) {
        this.name = name;
        this.active = active;
        this.updatedAt = now;
    }

    /** Removed for good by the owner: switched off and out of the salon's lists (his past bookings keep his name). */
    public void remove(Instant now) {
        this.active = false;
        this.removedAt = now;
        this.updatedAt = now;
    }

    public boolean isRemoved() { return removedAt != null; }

    /** Sets or clears (null) his photo. */
    public void setPhoto(UUID photoFileId, Instant now) {
        this.photoFileId = photoFileId;
        this.updatedAt = now;
    }

    /** Links his sign-in (the owner invited him by phone). */
    public void linkLogin(UUID userId, Instant now) {
        this.userId = userId;
        this.updatedAt = now;
    }

    /** Removes his sign-in (he left, or the owner unlinked him); his name and history stay. */
    public void unlinkLogin(Instant now) {
        this.userId = null;
        this.updatedAt = now;
    }

    /**
     * Marks the barber as changed when his services or hours change (they live in their own tables). Saving then
     * raises his version, so another screen still showing the old list gets 409 instead of overwriting it.
     */
    public void touch(Instant now) {
        // Always a different value, even if the clock hasn't moved (a fixed test clock, the same microsecond):
        // an unchanged field wouldn't make Hibernate write the row, and the version wouldn't go up.
        this.updatedAt = now.isAfter(updatedAt) ? now : updatedAt.plus(1, ChronoUnit.MICROS);
    }

    public UUID getId() { return id; }
    public UUID getShopId() { return shopId; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
    /** His sign-in, or null if he has none. */
    public UUID getUserId() { return userId; }
    public UUID getPhotoFileId() { return photoFileId; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
