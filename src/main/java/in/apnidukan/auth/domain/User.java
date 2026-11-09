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

    /** Admin website access (V9). */
    @Enumerated(EnumType.STRING)
    @Column(name = "admin_role", nullable = false, length = 12)
    private AdminRole adminRole = AdminRole.NONE;

    /** The old column, kept in step with adminRole for a version still running during a deploy (V9). */
    @Column(nullable = false)
    private boolean admin;

    /** When they became an admin (null for the super admin set by hand, or a non-admin). */
    @Column(name = "admin_since")
    private Instant adminSince;

    /** An ADMIN's job title; null for everyone else (V30). */
    @Enumerated(EnumType.STRING)
    @Column(name = "admin_designation", length = 20)
    private AdminDesignation adminDesignation;

    /** Bumped by every Team-page change to this member (V30); an edit made on an older one is refused. */
    @Column(name = "admin_version", nullable = false)
    private long adminVersion;

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
        detailsChanged();
        this.email = email;
        this.emailVerifiedAt = now;
        this.updatedAt = now;
    }

    /** A test account gives up its email to the real person who proved the inbox is theirs (AuthService). */
    public void releaseEmail(Instant now) {
        detailsChanged();
        this.email = null;
        this.emailVerifiedAt = null;
        this.updatedAt = now;
    }

    public void rename(String name, Instant now) {
        detailsChanged();
        this.name = name;
        this.updatedAt = now;
    }

    public void changePhone(String phone, Instant now) {
        detailsChanged();
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
        this.adminDesignation = null;
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

    /**
     * A super admin made this person an admin (only from NONE; the caller checks). An ADMIN needs a designation, a
     * SUPER_ADMIN has none (the caller checks; the database refuses anything else).
     */
    public void makeAdmin(AdminRole role, AdminDesignation designation, Instant now) {
        setAdminRole(role, designation, now);
        this.adminSince = now;
    }

    /** A super admin changed this admin's role or designation on the Team page. */
    public void setAdminRole(AdminRole role, AdminDesignation designation, Instant now) {
        if (!role.isAdmin() || (role == AdminRole.ADMIN) != (designation != null)) {
            throw new IllegalStateException("An ADMIN needs a designation and a SUPER_ADMIN has none: " + role + "/"
                    + designation);
        }
        this.adminRole = role;
        this.adminDesignation = designation;
        this.admin = true;
        this.updatedAt = now;
    }

    /** A super admin removed this person from the team. */
    public void removeAdmin(Instant now) {
        this.adminRole = AdminRole.NONE;
        this.admin = false;
        this.adminSince = null;
        this.adminDesignation = null;
        this.updatedAt = now;
    }

    /**
     * A super admin typed this email on the Team page: stored unverified, so the person confirms it with a code at
     * their next sign-in (the admin website asks for it before anything else). Never someone's recovery address
     * until then.
     */
    public void setUnverifiedEmail(String email, Instant now) {
        detailsChanged();
        this.email = email;
        this.emailVerifiedAt = null;
        this.updatedAt = now;
    }

    /** Every Team-page change to this member bumps it (see adminVersion). */
    public void bumpAdminVersion() {
        this.adminVersion++;
    }

    /**
     * Their name, phone or email changed (by themselves too: a rename, a new email, account recovery): a Team-page
     * edit made on what was shown before is refused (409 MEMBER_CHANGED), so it never writes the old details back.
     */
    private void detailsChanged() {
        if (adminRole.isAdmin()) {
            this.adminVersion++;
        }
    }

    public AdminDesignation getAdminDesignation() { return adminDesignation; }
    public long getAdminVersion() { return adminVersion; }

    /** This account was first made by a sign-in in OTP test mode. */
    public void markTestAccount() {
        this.testAccount = true;
    }

    /**
     * Someone proved they hold this number with a real code (a sign-in with an SMS code, or account recovery): from
     * now on it is a normal account, never covered by test mode again.
     */
    public void becomeRealAccount(Instant now) {
        if (testAccount) {
            this.testAccount = false;
            this.updatedAt = now;
        }
    }

    /**
     * The number's real holder signed in with an SMS code to an account testers made: it is theirs now, normal for
     * good, and what the testers put in goes. Their email above all: it is the recovery address, so a tester could
     * otherwise move the account back to a phone of theirs ("lost phone"). The name goes too; the holder adds their
     * own. (Account recovery keeps the email: whoever recovers has just proved they read that inbox.)
     */
    public void claimByRealHolder(Instant now) {
        detailsChanged();
        this.testAccount = false;
        this.email = null;
        this.emailVerifiedAt = null;
        this.name = null;
        this.updatedAt = now;
    }

    public boolean isTestAccount() { return testAccount; }
    public Instant getAdminSince() { return adminSince; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
