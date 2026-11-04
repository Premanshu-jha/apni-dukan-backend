package in.apnidukan.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A person who signs in with their phone. Shop membership (owner/helper) is added in V3. */
@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    /** Null only once the account is deleted. */
    @Column(length = 10, unique = true)
    private String phone;

    @Column(length = 100)
    private String name;

    @Column(length = 254)
    private String email;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(nullable = false)
    private boolean admin;

    /** When the person deleted their account; null while it exists. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
        // for JPA
    }

    public User(UUID id, String phone, Instant now) {
        this.id = id;
        this.phone = phone;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void verifyEmail(String email, Instant now) {
        this.email = email;
        this.emailVerifiedAt = now;
        this.updatedAt = now;
    }

    public void rename(String name, Instant now) {
        this.name = name;
        this.updatedAt = now;
    }

    public void changePhone(String phone, Instant now) {
        this.phone = phone;
        this.updatedAt = now;
    }

    /**
     * The person deleted their account: everything that says who they were goes (phone, name, email, field-team
     * access). The row stays, so their past bookings and the shops' records stay whole and simply show no name.
     */
    public void anonymise(Instant now) {
        this.phone = null;
        this.name = null;
        this.email = null;
        this.emailVerifiedAt = null;
        this.admin = false;
        this.deletedAt = now;
        this.updatedAt = now;
    }

    public boolean isDeleted() { return deletedAt != null; }
    public Instant getDeletedAt() { return deletedAt; }
    public UUID getId() { return id; }
    public String getPhone() { return phone; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
    public boolean isEmailVerified() { return emailVerifiedAt != null; }
    public boolean isAdmin() { return admin; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
