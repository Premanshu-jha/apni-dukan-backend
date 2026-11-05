package in.apnidukan.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    /** Admin website access (V9). The old boolean column users.admin is no longer read. */
    @Enumerated(EnumType.STRING)
    @Column(name = "admin_role", nullable = false, length = 12)
    private AdminRole adminRole = AdminRole.NONE;

    /** The old column, kept in step with adminRole for a version still running during a deploy (V9). */
    @Column(nullable = false)
    private boolean admin;

    /** When they became an admin (null for the super admin set by hand, or a non-admin). */
    @Column(name = "admin_since")
    private Instant adminSince;

    /** Made by a sign-in in OTP test mode: only such an account takes any code while test mode is on (V10). */
    @Column(name = "test_account", nullable = false)
    private boolean testAccount;

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
        this.adminRole = AdminRole.NONE;
        this.admin = false;
        this.adminSince = null;
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
    public boolean isAdmin() { return adminRole.isAdmin(); }
    public AdminRole getAdminRole() { return adminRole; }

    /** The super admin made this person an admin (only from NONE; the caller checks). */
    public void makeAdmin(Instant now) {
        this.adminRole = AdminRole.ADMIN;
        this.admin = true;
        this.adminSince = now;
        this.updatedAt = now;
    }

    /** The super admin removed this admin (never the super admin: the caller checks). */
    public void removeAdmin(Instant now) {
        this.adminRole = AdminRole.NONE;
        this.admin = false;
        this.adminSince = null;
        this.updatedAt = now;
    }

    /** This account was first made by a sign-in in OTP test mode. */
    public void markTestAccount() {
        this.testAccount = true;
    }

    public boolean isTestAccount() { return testAccount; }
    public Instant getAdminSince() { return adminSince; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
