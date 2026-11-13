package in.apnidukan.files.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One row per uploaded file. The bytes are in R2 under {@code storageKey}; this row only describes them. */
@Entity
@Table(name = "files")
public class StoredFile {

    @Id
    private UUID id;

    @Column(name = "shop_id")   // null for a support ticket's file (a person's, V49)
    private UUID shopId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private FilePurpose purpose;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "original_name")
    private String originalName;

    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StoredFile() {
        // for JPA
    }

    public StoredFile(UUID id, UUID shopId, FilePurpose purpose, String storageKey, String contentType,
                      long sizeBytes, String sha256, String originalName, UUID uploadedBy, Instant createdAt) {
        this.id = id;
        this.shopId = shopId;
        this.purpose = purpose;
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.originalName = originalName;
        this.uploadedBy = uploadedBy;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getShopId() { return shopId; }
    public FilePurpose getPurpose() { return purpose; }
    public String getStorageKey() { return storageKey; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public String getOriginalName() { return originalName; }
    public UUID getUploadedBy() { return uploadedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
