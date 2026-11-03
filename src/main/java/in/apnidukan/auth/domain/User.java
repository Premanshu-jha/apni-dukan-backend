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

    @Column(nullable = false, length = 10, unique = true)
    private String phone;

    @Column(length = 100)
    private String name;

    @Column(length = 254)
    private String email;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(nullable = false)
    private boolean admin;

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
