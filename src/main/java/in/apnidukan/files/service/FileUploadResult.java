package in.apnidukan.files.service;

import in.apnidukan.files.domain.StoredFile;

/** @param duplicate true when this shop had already uploaded exactly the same file for the same purpose */
public record FileUploadResult(StoredFile file, boolean duplicate) {
}
