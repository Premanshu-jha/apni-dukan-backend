package in.apnidukan.common.audit.domain;

import in.apnidukan.common.audit.RevisionUserListener;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.RevisionEntity;
import org.hibernate.envers.RevisionNumber;
import org.hibernate.envers.RevisionTimestamp;

import java.util.UUID;

/**
 * One saved change set in the history (Hibernate Envers). Every *_aud row points to one of these, which says when
 * the change happened and who made it.
 */
@Entity
@Table(name = "revinfo")
@RevisionEntity(RevisionUserListener.class)
public class Revision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @RevisionNumber
    @Column(name = "rev")
    private Long id;

    @RevisionTimestamp
    @Column(name = "revtstmp", nullable = false)
    private long timestamp;

    /** The signed-in person who made the change; null for system jobs. */
    @Column(name = "user_id")
    private UUID userId;

    public Long getId() { return id; }
    public long getTimestamp() { return timestamp; }
    public UUID getUserId() { return userId; }

    public void setUserId(UUID userId) { this.userId = userId; }
}
