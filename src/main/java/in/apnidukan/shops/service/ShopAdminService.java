package in.apnidukan.shops.service;

import in.apnidukan.common.context.ShopChangeListener;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.notify.Notifications;
import in.apnidukan.common.notify.Notifications.Notice;
import in.apnidukan.common.text.TextCleaner;
import in.apnidukan.common.text.TextCleaner.TextField;
import in.apnidukan.common.files.FileRefs;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.shops.api.AdminShopResponse;
import in.apnidukan.shops.domain.Shop;
import in.apnidukan.shops.domain.ShopRole;
import in.apnidukan.shops.domain.ShopStatus;
import in.apnidukan.shops.repo.ShopMemberRepository;
import in.apnidukan.shops.repo.ShopRepository;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Field-team pages: the shops waiting for a visit, with the owner's and the shop's photo; approving one after the
 * visit or sending it back with a reason; suspending a shop (off the app; a salon's upcoming online bookings are
 * cancelled) and reinstating it. Each decision carries the version the field team saw (409 SHOP_CHANGED if the
 * owner changed something since) and tells the owner. Only for users.admin: the security config refuses everyone else (403), and the flag is checked again
 * in the database here, because the one in a sign-in token can be up to 15 minutes old.
 */
@Service
public class ShopAdminService {

    static final TextField REASON = new TextField("reason", 2, 200);

    private final ShopRepository shops;
    private final ShopMemberRepository members;
    private final UserDirectory users;
    private final FileRefs files;
    private final Notifications notifications;
    private final List<ShopChangeListener> listeners;
    private final Clock clock;

    public ShopAdminService(ShopRepository shops, ShopMemberRepository members, UserDirectory users, FileRefs files,
                            Notifications notifications, List<ShopChangeListener> listeners, Clock clock) {
        this.shops = shops;
        this.members = members;
        this.users = users;
        this.files = files;
        this.notifications = notifications;
        this.listeners = listeners;
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
        Shop shop = checked(adminId, shopId, version, "approved", ShopStatus.PENDING_VISIT);
        shop.approve(now());
        save(shop);
        tellOwner(shop, "SHOP_APPROVED", "Your shop is live",
                shop.details().name() + " is approved. Customers nearby can find it now.");
        return view(shop);
    }

    /**
     * The visit didn't pass but the shop can fix it: back to DRAFT with the reason; the owner fixes it and submits
     * again. Bookings already made (a salon re-checked after a move) stay; suspend instead to cancel them.
     */
    @Transactional
    public AdminShopResponse reject(UUID adminId, UUID shopId, long version, String reasonInput) {
        Shop shop = checked(adminId, shopId, version, "sent back", ShopStatus.PENDING_VISIT);
        String reason = TextCleaner.clean(REASON, reasonInput);
        shop.reject(reason, now());
        save(shop);
        tellOwner(shop, "SHOP_NOT_APPROVED", "Your shop needs a change",
                shop.details().name() + " wasn't approved yet: " + reason + " Fix it in the app and submit again.");
        return view(shop);
    }

    /**
     * Off the app (APPROVED, or PENDING_VISIT found shut or fake at its visit): customers can't find it, and a
     * salon's upcoming online bookings are cancelled with a message to each customer (ShopChangeListener.suspended).
     */
    @Transactional
    public AdminShopResponse suspend(UUID adminId, UUID shopId, long version, String reasonInput) {
        Shop shop = checked(adminId, shopId, version, "suspended", ShopStatus.APPROVED, ShopStatus.PENDING_VISIT);
        String reason = TextCleaner.clean(REASON, reasonInput);
        shop.suspend(reason, now());
        save(shop);   // the row is now locked: bookings in progress have finished, new ones see SUSPENDED
        listeners.forEach(l -> l.suspended(shop.getId()));
        tellOwner(shop, "SHOP_SUSPENDED", "Your shop is off the app",
                shop.details().name() + " is hidden from customers: " + reason + " Contact Apni Dukan to fix this.");
        return view(shop);
    }

    /**
     * SUSPENDED → PENDING_VISIT: back in the visit list; it goes live when the field team approves it after
     * checking it again. Cancelled bookings stay cancelled.
     */
    @Transactional
    public AdminShopResponse reinstate(UUID adminId, UUID shopId, long version) {
        Shop shop = checked(adminId, shopId, version, "reinstated", ShopStatus.SUSPENDED);
        shop.reinstate(now());
        save(shop);
        tellOwner(shop, "SHOP_REINSTATED", "Your shop will be checked again",
                shop.details().name() + " is no longer suspended. Our team will visit; after that it is live again.");
        return view(shop);
    }

    /** The shop, if it is what the field team saw (same version) and in a status this decision applies to. */
    private Shop checked(UUID adminId, UUID shopId, long version, String what, ShopStatus... allowed) {
        requireAdmin(adminId);
        Shop shop = shops.findById(shopId).orElseThrow(() -> ApiException.notFound("Shop"));
        if (shop.getVersion() != version) {
            throw new ApiException(HttpStatus.CONFLICT, "SHOP_CHANGED",
                    "The shop changed after you loaded it. Reload and check the new details first.");
        }
        if (!java.util.Arrays.asList(allowed).contains(shop.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "WRONG_STATUS",
                    "A shop that is " + shop.getStatus() + " can't be " + what + ".");
        }
        return shop;
    }

    private void save(Shop shop) {
        try {
            shops.saveAndFlush(shop);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ApiException(HttpStatus.CONFLICT, "SHOP_CHANGED", "The shop changed in between. Try again.");
        }
    }

    /** A message to the shop's owner, in the same transaction as the decision. */
    private void tellOwner(Shop shop, String type, String title, String body) {
        members.findByShopIdOrderByCreatedAtAscIdAsc(shop.getId()).stream()
                .filter(m -> m.getRole() == ShopRole.OWNER)
                .forEach(m -> notifications.queue(new Notice(m.getUserId(), type, null, shop.getUpdatedAt(), title,
                        body, "/shops/me", Map.of())));
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
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
