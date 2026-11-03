package in.apnidukan.shops.service;

import in.apnidukan.common.files.AttachedFiles;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Owner and shop photos in use can't be deleted from the files module. */
@Component
class ShopFiles implements AttachedFiles {

    private final ShopService shops;

    ShopFiles(ShopService shops) {
        this.shops = shops;
    }

    @Override
    public boolean isAttached(UUID fileId) {
        return shops.usesFile(fileId);
    }
}
