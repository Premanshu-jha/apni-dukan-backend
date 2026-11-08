package in.apnidukan.shops.domain;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A shop: a kirana or a salon. Every version is kept in shops_aud (Envers). */
@Entity
@Table(name = "shops")
@Audited
public class Shop {

    /** Filled in by Hibernate when the row is first saved (a random UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "shop_code", nullable = false, unique = true, updatable = false)
    private long shopCode;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "owner_name", nullable = false, length = 100)
    private String ownerName;

    @Column(name = "address_line", nullable = false, length = 200)
    private String addressLine;

    @Column(nullable = false, length = 100)
    private String area;

    @Column(nullable = false, length = 60)
    private String city;

    @Column(nullable = false, length = 6)
    private String pincode;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(length = 15)
    private String gstin;

    @Column(name = "udyam_number", length = 19)
    private String udyamNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShopStatus status;

    /** Kirana or salon; set once at registration. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private ShopType type;

    /** The owner's photo (an OWNER_PHOTO upload of this shop); required before Submit for visit. */
    @Column(name = "owner_photo_file_id")
    private UUID ownerPhotoFileId;

    /** The shop from outside (a SHOP_PHOTO upload); required before Submit for visit; customers see it. */
    @Column(name = "shop_photo_file_id")
    private UUID shopPhotoFileId;

    /** A short video of the salon (a SHOP_VIDEO upload) for the field team; required before Submit for visit. */
    @Column(name = "shop_video_file_id")
    private UUID shopVideoFileId;

    /** Why the field team sent it back to DRAFT or suspended it (shown to the owner); null otherwise. */
    @Column(name = "status_reason", length = 200)
    private String statusReason;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Shop() {
        // for JPA
    }

    public Shop(long shopCode, ShopType type, ShopDetails details, Instant now) {
        this.shopCode = shopCode;
        this.type = type;
        this.status = ShopStatus.DRAFT;
        this.createdAt = now;
        apply(details, now);
    }

    /** Replaces the editable details (name, owner, address, pin, GSTIN/Udyam). */
    public void apply(ShopDetails details, Instant now) {
        this.name = details.name();
        this.ownerName = details.ownerName();
        this.addressLine = details.addressLine();
        this.area = details.area();
        this.city = details.city();
        this.pincode = details.pincode();
        this.latitude = details.latitude();
        this.longitude = details.longitude();
        this.gstin = details.gstin();
        this.udyamNumber = details.udyamNumber();
        this.updatedAt = now;
    }

    /** Sets the owner's and the shop's photo (either may stay as it is: pass the current one). */
    public void setPhotos(UUID ownerPhotoFileId, UUID shopPhotoFileId, Instant now) {
        this.ownerPhotoFileId = ownerPhotoFileId;
        this.shopPhotoFileId = shopPhotoFileId;
        this.updatedAt = now;
    }

    public boolean hasBothPhotos() {
        return ownerPhotoFileId != null && shopPhotoFileId != null;
    }

    /** Sets the salon video (null: none). */
    public void setVideo(UUID shopVideoFileId, Instant now) {
        this.shopVideoFileId = shopVideoFileId;
        this.updatedAt = now;
    }

    /** What the field team needs before a visit: both photos, and for a salon its video too. */
    public boolean readyForVisit() {
        return hasBothPhotos() && (type != ShopType.SALON || shopVideoFileId != null);
    }

    /** Registration done: DRAFT → PENDING_VISIT, waiting for the field team. Needs both photos and the video. */
    public void submitForVisit(Instant now) {
        if (status != ShopStatus.DRAFT || !readyForVisit()) {
            throw new IllegalStateException("Only a DRAFT shop with both photos (and a salon's video) can be submitted, not "
                    + status);
        }
        this.status = ShopStatus.PENDING_VISIT;
        this.statusReason = null;
        this.updatedAt = now;
    }

    /**
     * An approved shop changed what the field team checked (its photos or its address and pin): it goes back to
     * PENDING_VISIT and customers can't find it until the field team approves it again. Bookings already made stay.
     * Does nothing in any other status.
     */
    public void recheckIfApproved(Instant now) {
        if (status == ShopStatus.APPROVED) {
            this.status = ShopStatus.PENDING_VISIT;
            this.updatedAt = now;
        }
    }

    /** The field team visited and approves the shop: customers can find it from now on. Only from PENDING_VISIT. */
    public void approve(Instant now) {
        if (status != ShopStatus.PENDING_VISIT) {
            throw new IllegalStateException("Only a PENDING_VISIT shop can be approved, not " + status);
        }
        this.status = ShopStatus.APPROVED;
        this.statusReason = null;
        this.updatedAt = now;
    }

    /**
     * The visit didn't pass: PENDING_VISIT → DRAFT with the reason, so the owner can fix it and submit again.
     */
    public void reject(String reason, Instant now) {
        if (status != ShopStatus.PENDING_VISIT) {
            throw new IllegalStateException("Only a PENDING_VISIT shop can be sent back, not " + status);
        }
        this.status = ShopStatus.DRAFT;
        this.statusReason = reason;
        this.updatedAt = now;
    }

    /**
     * Taken off the app by Apni Dukan (APPROVED, or PENDING_VISIT: e.g. a shop found shut at its re-check visit).
     * Customers can't find it; only the field team brings it back, through a new visit (reinstate).
     */
    public void suspend(String reason, Instant now) {
        if (status != ShopStatus.APPROVED && status != ShopStatus.PENDING_VISIT) {
            throw new IllegalStateException("Only an APPROVED or PENDING_VISIT shop can be suspended, not " + status);
        }
        this.status = ShopStatus.SUSPENDED;
        this.statusReason = reason;
        this.updatedAt = now;
    }

    /**
     * SUSPENDED → PENDING_VISIT: back in the field team's visit list, live again only after they approve it. So a
     * shop that was never visited, or changed its address or photos while suspended, never goes live unchecked.
     */
    public void reinstate(Instant now) {
        if (status != ShopStatus.SUSPENDED) {
            throw new IllegalStateException("Only a SUSPENDED shop can be reinstated, not " + status);
        }
        this.status = ShopStatus.PENDING_VISIT;
        this.statusReason = null;
        this.updatedAt = now;
    }

    /**
     * Its owner closes it for good, from any status: customers can't find it, nobody works in it any more, and it is
     * never opened again (the owner registers a new shop). The reason, if they gave one, is kept for the field team.
     */
    public void close(String reason, Instant now) {
        if (status == ShopStatus.CLOSED) {
            throw new IllegalStateException("The shop is closed already");
        }
        this.status = ShopStatus.CLOSED;
        this.statusReason = reason;
        this.updatedAt = now;
    }

    public ShopDetails details() {
        return new ShopDetails(name, ownerName, addressLine, area, city, pincode, latitude, longitude, gstin, udyamNumber);
    }

    public UUID getId() { return id; }
    public long getShopCode() { return shopCode; }
    public ShopStatus getStatus() { return status; }
    public String getStatusReason() { return statusReason; }
    public ShopType getType() { return type; }
    public UUID getOwnerPhotoFileId() { return ownerPhotoFileId; }
    public UUID getShopPhotoFileId() { return shopPhotoFileId; }
    public UUID getShopVideoFileId() { return shopVideoFileId; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
