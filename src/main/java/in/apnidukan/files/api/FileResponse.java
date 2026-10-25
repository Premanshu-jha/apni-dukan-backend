package in.apnidukan.files.api;

import in.apnidukan.files.domain.FilePurpose;
import in.apnidukan.files.service.FileUploadResult;

import java.time.Instant;
import java.util.UUID;

/** What the app gets back after an upload. It keeps only {@code id}; bills and payments store that id. */
public record FileResponse(UUID id, FilePurpose purpose, String contentType, long sizeBytes,
                           String originalName, Instant createdAt, boolean duplicate) {

    static FileResponse from(FileUploadResult result) {
        var f = result.file();
        return new FileResponse(f.getId(), f.getPurpose(), f.getContentType(), f.getSizeBytes(),
                f.getOriginalName(), f.getCreatedAt(), result.duplicate());
    }
}
