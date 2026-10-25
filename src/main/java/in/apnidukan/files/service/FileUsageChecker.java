package in.apnidukan.files.service;

import in.apnidukan.files.domain.StoredFile;

/**
 * Tells the files module whether a file is attached to something (a bill, a payment, a shop photo).
 * Each later module that stores a fileId adds one of these, e.g. the purchase module checks bills.
 * A file that any checker says is in use cannot be deleted.
 */
public interface FileUsageChecker {

    boolean isInUse(StoredFile file);
}
