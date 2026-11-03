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
 * One block of a barber's weekly working hours, e.g. Monday 10:00 to 14:00. Two on one day make a split shift.
 * Times are minutes after midnight in salon time (see {@link SalonTime}).
 */
@Entity
@Table(name = "staff_hours")
@Audited
public class StaffShift {

    /** Filled in by Hibernate when the row is first saved (a random UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "staff_id", nullable = false, updatable = false)
    private UUID staffId;

    /** ISO day of the week: 1 = Monday ... 7 = Sunday. */
    @Column(nullable = false, updatable = false)
    private int weekday;

    @Column(name = "start_min", nullable = false, updatable = false)
    private int startMin;

    @Column(name = "end_min", nullable = false, updatable = false)
    private int endMin;

    protected StaffShift() {
        // for JPA
    }

    public StaffShift(UUID staffId, Shift shift) {
        this.staffId = staffId;
        this.weekday = shift.weekday();
        this.startMin = shift.startMin();
        this.endMin = shift.endMin();
    }

    public Shift shift() {
        return new Shift(weekday, startMin, endMin);
    }

    public UUID getId() { return id; }
    public UUID getStaffId() { return staffId; }
}
