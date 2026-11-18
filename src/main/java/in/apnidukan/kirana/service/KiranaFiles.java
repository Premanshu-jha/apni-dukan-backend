package in.apnidukan.kirana.service;

import in.apnidukan.common.files.AttachedFiles;
import in.apnidukan.common.files.FileRefs;
import in.apnidukan.kirana.repo.ProductStore;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.UUID;

/**
 * The kirana's files: a file a product, the shared catalogue, a paper bill, an FSSAI certificate or a read still points
 * at can't be deleted from the files module (409 FILE_IN_USE), and a file the kirana stops using is deleted only when
 * nothing else points at it (uploads of the same bytes share one file).
 */
@Component
class KiranaFiles implements AttachedFiles {

    private final ProductStore products;
    private final FileRefs files;

    KiranaFiles(ProductStore products, FileRefs files) {
        this.products = products;
        this.files = files;
    }

    @Override
    public boolean isAttached(UUID fileId) {
        return products.fileInUse(fileId, null);
    }

    /** Deletes these files unless something else uses them ({@code exceptRead}: the read letting go of them). */
    void releaseIfUnused(Collection<UUID> fileIds, UUID exceptRead) {
        for (UUID f : fileIds) {
            if (!products.fileInUse(f, exceptRead)) {
                files.release(f);
            }
        }
    }
}
