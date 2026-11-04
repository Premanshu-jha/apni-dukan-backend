package in.apnidukan.common.files;

import java.util.UUID;

/**
 * Implemented by every module that points at uploaded files (shop and owner photos, barber photos). A file that any
 * of them says is attached can't be deleted (409 FILE_IN_USE), so nothing ever points at a missing file.
 */
public interface AttachedFiles {

    boolean isAttached(UUID fileId);
}
