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

/** A kirana shop. Every version is kept in shops_aud (Envers). */
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

    public Shop(long shopCode, ShopDetails details, Instant now) {
        this.shopCode = shopCode;
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

    public ShopDetails details() {
        return new ShopDetails(name, ownerName, addressLine, area, city, pincode, latitude, longitude, gstin, udyamNumber);
    }

    public UUID getId() { return id; }
    public long getShopCode() { return shopCode; }
    public ShopStatus getStatus() { return status; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
