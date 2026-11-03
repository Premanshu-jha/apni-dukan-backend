package in.apnidukan.shops.service;

import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.files.FileRefs;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.shops.api.AdminShopResponse;
import in.apnidukan.shops.domain.Shop;
import in.apnidukan.shops.domain.ShopStatus;
import in.apnidukan.shops.repo.ShopRepository;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Field-team pages: the shops waiting for a visit, with the owner's and the shop's photo, and approving one after
 * the visit. Only for users.admin: the security config refuses everyone else (403), and the flag is checked again
 * in the database here, because the one in a sign-in token can be up to 15 minutes old.
 */
@Service
public class ShopAdminService {

    private final ShopRepository shops;
    private final UserDirectory users;
    private final FileRefs files;
    private final Clock clock;

    public ShopAdminService(ShopRepository shops, UserDirectory users, FileRefs files, Clock clock) {
        this.shops = shops;
        this.users = users;
        this.files = files;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AdminShopResponse> list(UUID adminId, ShopStatus status) {
        requireAdmin(adminId);
        return shops.findByStatusOrderByCreatedAtAsc(status).stream().map(this::view).toList();
    }

    /**
     * Approves a shop after the visit (PENDING_VISIT only). {@code version} is the one shown in the list the field
     * team checked: if the owner changed anything since (photos, address), 409 SHOP_CHANGED, so nothing unchecked
     * goes live. 409 WRONG_STATUS from any other status; 404 if there's no such shop.
     */
    @Transactional
    public AdminShopResponse approve(UUID adminId, UUID shopId, long version) {
        requireAdmin(adminId);
        Shop shop = shops.findById(shopId).orElseThrow(() -> ApiException.notFound("Shop"));
        if (shop.getVersion() != version) {
            throw new ApiException(HttpStatus.CONFLICT, "SHOP_CHANGED",
                    "The shop changed after you loaded it. Reload, check the new details, then approve.");
        }
        if (shop.getStatus() != ShopStatus.PENDING_VISIT) {
            throw new ApiException(HttpStatus.CONFLICT, "WRONG_STATUS",
                    "Only a shop waiting for its visit (PENDING_VISIT) can be approved; this one is "
                            + shop.getStatus() + ".");
        }
        shop.approve(Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
        try {
            shops.saveAndFlush(shop);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ApiException(HttpStatus.CONFLICT, "SHOP_CHANGED", "The shop changed in between. Try again.");
        }
        return view(shop);
    }

    private AdminShopResponse view(Shop shop) {
        return AdminShopResponse.from(shop, link(shop.getOwnerPhotoFileId()), link(shop.getShopPhotoFileId()));
    }

    private String link(java.util.UUID fileId) {
        return fileId == null ? null : files.link(fileId);
    }

    /**
     * The security config already refused anyone whose token isn't an admin's (403). This catches a token issued
     * before the admin flag was removed: 404, so the pages don't reveal that they exist.
     */
    private void requireAdmin(UUID userId) {
        if (!users.isAdmin(userId)) {
            throw ApiException.notFound("Page");
        }
    }
}
