package in.apnidukan.files.repo;

import in.apnidukan.files.domain.FilePurpose;
import in.apnidukan.files.domain.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {

    /** Always look files up together with the shop, so one shop can never reach another shop's file. */
    Optional<StoredFile> findByIdAndShopId(UUID id, UUID shopId);

    Optional<StoredFile> findByShopIdAndPurposeAndSha256(UUID shopId, FilePurpose purpose, String sha256);
}
