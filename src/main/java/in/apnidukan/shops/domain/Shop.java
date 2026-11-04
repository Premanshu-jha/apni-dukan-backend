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

    /** Registration done: DRAFT → PENDING_VISIT, waiting for the field team. Needs both photos. */
    public void submitForVisit(Instant now) {
        if (status != ShopStatus.DRAFT || !hasBothPhotos()) {
            throw new IllegalStateException("Only a DRAFT shop with both photos can be submitted, not " + status);
        }
        this.status = ShopStatus.PENDING_VISIT;
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
        this.updatedAt = now;
    }

    public ShopDetails details() {
        return new ShopDetails(name, ownerName, addressLine, area, city, pincode, latitude, longitude, gstin, udyamNumber);
    }

    public UUID getId() { return id; }
    public long getShopCode() { return shopCode; }
    public ShopStatus getStatus() { return status; }
    public ShopType getType() { return type; }
    public UUID getOwnerPhotoFileId() { return ownerPhotoFileId; }
    public UUID getShopPhotoFileId() { return shopPhotoFileId; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
