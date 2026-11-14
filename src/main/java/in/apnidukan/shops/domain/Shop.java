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
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A shop: a kirana or a salon. Every version is kept in shops_aud (Envers). Saved with @DynamicUpdate (only the
 * columns that changed): the licence check's answer is written by LicenceCheckStore meanwhile, and an owner saving
 * something else must not write the old answer back.
 */
@Entity
@Table(name = "shops")
@Audited
@DynamicUpdate
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

    /** Which registration document the salon added (optional); null if none (kiranas never have one). */
    @Enumerated(EnumType.STRING)
    @Column(name = "registration_doc_type", length = 20)
    private RegistrationDocType registrationDocType;

    /** The registration document (a REG_DOC upload: a photo or a PDF), for the field team only; null if none. */
    @Column(name = "registration_doc_file_id")
    private UUID registrationDocFileId;

    /** When the document passed the automatic licence check (LicenceCheckStore); null until then or if it failed. */
    @Column(name = "registration_doc_checked_at")
    private Instant registrationDocCheckedAt;

    /** The automatic check of the document (V55): null without one, PENDING until LicenceCheckJob answers. */
    @Enumerated(EnumType.STRING)
    @Column(name = "licence_check_status", length = 10)
    @NotAudited
    private LicenceCheckStatus licenceCheckStatus;

    /** Why it FAILED; null otherwise. */
    @Enumerated(EnumType.STRING)
    @Column(name = "licence_check_reason", length = 30)
    @NotAudited
    private LicenceCheckReason licenceCheckReason;

    /** The licence number read from it, once PASSED (one salon each). */
    @Column(name = "licence_number", length = 40)
    @NotAudited
    private String licenceNumber;

    /** Its last valid day, once PASSED: customers see "Licensed" until then. */
    @Column(name = "licence_valid_until")
    @NotAudited
    private LocalDate licenceValidUntil;

    /** What the AI read from it (JSON, for the admin page); null until read. */
    @Column(name = "licence_extract")
    @JdbcTypeCode(SqlTypes.JSON)
    @NotAudited
    private String licenceExtract;

    @Column(name = "licence_check_attempts", nullable = false)
    @NotAudited
    private int licenceCheckAttempts;

    @Column(name = "licence_check_unusable", nullable = false)
    @NotAudited
    private int licenceCheckUnusable;

    /** When this check started (how long it has been waiting). */
    @Column(name = "licence_check_since")
    @NotAudited
    private Instant licenceCheckSince;

    /** Messages sent about its last day: 1 (a month before), 2 (expired). */
    @Column(name = "licence_expiry_notice", nullable = false)
    @NotAudited
    private short licenceExpiryNotice;

    /** When the job reads it next (PENDING only). */
    @Column(name = "licence_check_due_at")
    @NotAudited
    private Instant licenceCheckDueAt;

    /**
     * When a live shop started waiting for the field team's re-check (its owner changed what they checked); null when
     * nothing waits. Only ever set while APPROVED: the shop stays live meanwhile (see {@link #requestRecheck}).
     */
    @Column(name = "recheck_since")
    private Instant recheckSince;

    /** How many times the field team checked it (approve); its history row marks the version checked (lastApproved). */
    @Column(name = "checks", nullable = false)
    private int checks;

    /**
     * Ever approved by the field team (kept by a trigger from the timeline, V16; read only here, and not in the
     * history). Once it is, the owner can't change the address or pin any more: the field team does (addressLocked).
     */
    @Column(name = "ever_approved", insertable = false, updatable = false)
    @NotAudited
    private boolean everApproved;

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

    /**
     * Sets the registration document (both set, or both null to remove it). A new one waits for the automatic licence
     * check (PENDING, due now, any earlier answer gone); removing it ends the check. A change of its kind alone counts
     * as new too.
     */
    public void setRegistrationDoc(RegistrationDocType type, UUID fileId, Instant now) {
        if ((type == null) != (fileId == null)) {
            throw new IllegalArgumentException("A registration document needs both its type and its file");
        }
        this.registrationDocType = type;
        this.registrationDocFileId = fileId;
        this.registrationDocCheckedAt = null;
        this.licenceCheckStatus = fileId == null ? null : LicenceCheckStatus.PENDING;
        this.licenceCheckReason = null;
        this.licenceNumber = null;
        this.licenceValidUntil = null;
        this.licenceExtract = null;
        this.licenceCheckAttempts = 0;
        this.licenceCheckUnusable = 0;
        this.licenceCheckDueAt = fileId == null ? null : now;
        this.licenceCheckSince = fileId == null ? null : now;
        this.licenceExpiryNotice = 0;
        this.updatedAt = now;
    }

    /**
     * Checks the trade licence there again (PENDING, due now, the earlier answer gone): the salon's name, owner or
     * address changed (the answer was about the old ones), or the field team asked. False (nothing changed) without
     * one, or for a document of another kind (a Udyam or Shops and Establishments certificate added before only a
     * trade licence counted: it stays FAILED NOT_TRADE_LICENCE and is never sent to the AI).
     */
    public boolean restartLicenceCheck(Instant now) {
        if (registrationDocFileId == null || registrationDocType != RegistrationDocType.TRADE_LICENCE) {
            return false;
        }
        setRegistrationDoc(registrationDocType, registrationDocFileId, now);
        return true;
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
     * An approved shop changed what the field team checked (a photo, the video, the GSTIN or Udyam number; the address
     * can't change, addressLocked): it stays APPROVED (bookable, its team works as usual) and waits for the field
     * team's re-check. A change the owner makes after the field team asked for a fix answers that ask (the reason
     * goes). Returns true when it starts waiting now; false when it already waits (the first change keeps its place in
     * the queue) or isn't approved (a draft or a shop waiting for its visit is checked at that visit anyway).
     */
    public boolean requestRecheck(Instant now) {
        if (status != ShopStatus.APPROVED) {
            return false;
        }
        this.updatedAt = now;
        this.statusReason = null;   // a fix asked for: this change answers it
        if (recheckSince != null) {
            return false;
        }
        this.recheckSince = now;
        return true;
    }

    /**
     * Something about the shop the field team decides on changed without changing the shop's own columns (its owner's
     * address change request was made, cancelled or declined): a new version, so a decision made on what was shown
     * before answers 409 SHOP_CHANGED.
     */
    public void touch(Instant now) {
        this.updatedAt = now;
    }

    /**
     * A live shop's re-check has nothing left to check (the address request that started it was taken back, declined
     * or answered by the field team, and nothing else changed): it stops waiting, and a fix asked for goes with it.
     * True when it was waiting.
     */
    public boolean endRecheck(Instant now) {
        if (status != ShopStatus.APPROVED || recheckSince == null) {
            return false;
        }
        this.recheckSince = null;
        this.statusReason = null;
        this.updatedAt = now;
        return true;
    }

    /**
     * The field team asked the owner to fix something on a live shop's re-check: it stays as it is (live, waiting) and
     * the owner sees the reason until he changes something or it is approved.
     */
    public void askToFix(String reason, Instant now) {
        if (status != ShopStatus.APPROVED || recheckSince == null) {
            throw new IllegalStateException("Only a live shop waiting for its re-check can be asked for a fix");
        }
        this.statusReason = reason;
        this.updatedAt = now;
    }

    /**
     * The address and pin were verified by the field team: from the first approval on the owner can't change them (a
     * request to the field team does), whatever the status now. A draft (also one sent back) and a first visit can.
     */
    public boolean addressLocked() {
        return everApproved || status == ShopStatus.APPROVED;
    }

    /** Waiting for the field team: its first visit, a reinstated shop's visit, or a live shop's re-check. */
    public boolean waitingForVisit() {
        return status == ShopStatus.PENDING_VISIT || recheckSince != null;
    }

    /**
     * The field team visited and approves the shop: customers can find it from now on. From PENDING_VISIT, or a live
     * shop waiting for its re-check (it stays live; its changes count as checked).
     */
    public void approve(Instant now) {
        if (!waitingForVisit()) {
            throw new IllegalStateException("Only a shop waiting for a visit or a re-check can be approved, not "
                    + status);
        }
        this.recheckSince = null;   // a live shop's re-check: its changes are checked now
        this.checks++;
        this.status = ShopStatus.APPROVED;
        this.statusReason = null;
        this.updatedAt = now;
        // The registration document isn't part of the visit: the automatic licence check decides on it
    }

    /**
     * The visit didn't pass: PENDING_VISIT → DRAFT with the reason, so the owner can fix it and submit again. A live
     * shop's re-check is never sent back (askToFix).
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
        this.recheckSince = null;
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
        this.recheckSince = null;
        this.status = ShopStatus.CLOSED;
        this.statusReason = reason;
        // Never opened again: its licence goes, so its number never blocks a new registration of the same business
        setRegistrationDoc(null, null, now);
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
    public RegistrationDocType getRegistrationDocType() { return registrationDocType; }
    public UUID getRegistrationDocFileId() { return registrationDocFileId; }
    public Instant getRegistrationDocCheckedAt() { return registrationDocCheckedAt; }
    public Instant getRecheckSince() { return recheckSince; }
    public LicenceCheckStatus getLicenceCheckStatus() { return licenceCheckStatus; }
    public LicenceCheckReason getLicenceCheckReason() { return licenceCheckReason; }
    public String getLicenceNumber() { return licenceNumber; }
    public LocalDate getLicenceValidUntil() { return licenceValidUntil; }
    public String getLicenceExtract() { return licenceExtract; }
    public Instant getLicenceCheckSince() { return licenceCheckSince; }
    public boolean isEverApproved() { return everApproved; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
