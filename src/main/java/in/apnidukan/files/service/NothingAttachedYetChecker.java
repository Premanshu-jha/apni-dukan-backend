package in.apnidukan.files.service;

import in.apnidukan.files.domain.StoredFile;
import org.springframework.stereotype.Component;

/**
 * Placeholder until the modules that attach files (shop photo, bills, payments) exist.
 * It always answers "not in use"; real checkers are added next to it, and any one saying "in use" blocks a delete.
 * It also guarantees Spring always has at least one checker to inject.
 */
@Component
class NothingAttachedYetChecker implements FileUsageChecker {

    @Override
    public boolean isInUse(StoredFile file) {
        return false;
    }
}
