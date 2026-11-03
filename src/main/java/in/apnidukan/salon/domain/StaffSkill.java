package in.apnidukan.salon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * "This barber does this service." The row carries the shop too, so the database's keys can refuse a barber linked
 * to another salon's service.
 */
@Entity
@Table(name = "staff_services")
@Audited
public class StaffSkill {

    /** Filled in by Hibernate when the row is first saved (a random UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "shop_id", nullable = false, updatable = false)
    private UUID shopId;

    @Column(name = "staff_id", nullable = false, updatable = false)
    private UUID staffId;

    @Column(name = "service_id", nullable = false, updatable = false)
    private UUID serviceId;

    protected StaffSkill() {
        // for JPA
    }

    public StaffSkill(UUID shopId, UUID staffId, UUID serviceId) {
        this.shopId = shopId;
        this.staffId = staffId;
        this.serviceId = serviceId;
    }

    public UUID getId() { return id; }
    public UUID getStaffId() { return staffId; }
    public UUID getServiceId() { return serviceId; }
}
