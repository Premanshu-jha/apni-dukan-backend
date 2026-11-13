package in.apnidukan.files.repo;

import in.apnidukan.files.domain.FilePurpose;
import in.apnidukan.files.domain.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {

    /** Always look files up together with the shop, so one shop can never reach another shop's file. */
    Optional<StoredFile> findByIdAndShopId(UUID id, UUID shopId);

    Optional<StoredFile> findByShopIdAndPurposeAndSha256(UUID shopId, FilePurpose purpose, String sha256);

    /** Uploads of this shop since a moment (today's count, for the daily limit). */
    long countByShopIdAndCreatedAtGreaterThanEqual(UUID shopId, java.time.Instant since);

    /** A person's files of one purpose since a moment (ticket files: ix_files_ticket_uploader). */
    long countByUploadedByAndPurposeAndCreatedAtGreaterThanEqual(UUID uploadedBy, FilePurpose purpose,
                                                                 java.time.Instant since);

    /**
     * Deletes the row only if it still exists and belongs to the shop, in one statement.
     * Returns 1 if it was deleted, 0 if someone else deleted it first (e.g. a double tap).
     */
    @Modifying
    @Query("DELETE FROM StoredFile f WHERE f.id = :id AND f.shopId = :shopId")
    int deleteByIdAndShopId(UUID id, UUID shopId);
}
