package in.apnidukan.shops.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.shops.api.AddressRequestView;
import in.apnidukan.shops.api.ShopRequests.AddressRequest;
import in.apnidukan.shops.domain.Shop;
import in.apnidukan.shops.domain.ShopDetails;
import in.apnidukan.shops.domain.ShopMember;
import in.apnidukan.shops.domain.ShopRole;
import in.apnidukan.shops.domain.ShopStatus;
import in.apnidukan.shops.repo.AddressRequestStore;
import in.apnidukan.shops.repo.ShopRepository;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static in.apnidukan.common.text.TextCleaner.clean;

/**
 * The owner of a shop whose address the field team verified (Shop.addressLocked) can't change it himself: he asks
 * them here. The request waits (AddressRequestStore) and the shop keeps its old address, live and bookable, until the
 * field team approves it (ShopAdminService.approve applies it) or declines it with a reason.
 *
 * <p>Every change takes the team lock shared, then the shop's row (FOR UPDATE): requests and decisions on one shop
 * take turns, and each gives the shop a new version, so the field team never approves a request they didn't see.
 */
@Service
public class AddressRequestService {

    private final ShopService shopService;
    private final ShopRepository shops;
    private final AddressRequestStore requests;
    private final ShopAssignments assignments;
    private final DashboardCounts dashboardCounts;
    private final Clock clock;

    public AddressRequestService(ShopService shopService, ShopRepository shops, AddressRequestStore requests,
                                 ShopAssignments assignments, DashboardCounts dashboardCounts, Clock clock) {
        this.dashboardCounts = dashboardCounts;
        this.shopService = shopService;
        this.shops = shops;
        this.requests = requests;
        this.assignments = assignments;
        this.clock = clock;
    }

    /**
     * Asks the field team for a new address and pin (owner only; editing unlocked with a code, 403 EDIT_LOCKED). A
     * request still waiting is replaced. A live shop waits for its re-check (it stays live); the field team sees the
     * old and the new address. 409 ADDRESS_NOT_LOCKED when the owner can still change it himself (Salon details);
     * 409 WRONG_STATUS for a suspended or closed shop; 400 VALIDATION_FAILED when it is the address it has now.
     */
    @Transactional
    public AddressRequestView request(UUID userId, AddressRequest in) {
        Shop shop = lockedOwnShop(userId, in.version());
        if (!shop.addressLocked()) {
            throw new ApiException(HttpStatus.CONFLICT, "ADDRESS_NOT_LOCKED",
                    "Your address isn't verified yet: change it yourself in Salon details.");
        }
        if (shop.getStatus() == ShopStatus.SUSPENDED || shop.getStatus() == ShopStatus.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT, "WRONG_STATUS", (shop.getStatus() == ShopStatus.SUSPENDED
                    ? "Your shop is suspended" : "Your shop is closed") + ", so it can't ask for a new address. Write "
                    + "to us from More → Need help.", Map.of("status", shop.getStatus().name()));
        }
        shopService.requireUnlocked(shop, userId);
        ShopDetails old = shop.details();
        ShopDetails asked = new ShopDetails(old.name(), old.ownerName(), clean(ShopService.ADDRESS_LINE,
                in.addressLine()), clean(ShopService.AREA, in.area()), clean(ShopService.CITY, in.city()), in.pincode(),
                ShopService.coordinate(in.latitude()), ShopService.coordinate(in.longitude()), old.gstin(),
                old.udyamNumber());
        if (ShopService.sameLocation(old, asked)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                    Map.of("addressLine", "is the address you have now; change the address or move the pin"));
        }
        Instant now = now();
        requests.decide(shop.getId(), "CANCELLED", null, userId, now);   // the one still waiting, if any
        AddressRequestStore.Request made = requests.add(shop.getId(), userId, asked.addressLine(), asked.area(),
                asked.city(), asked.pincode(), asked.latitude(), asked.longitude(), now);
        boolean waitsNow = shopService.recheck(shop, userId);   // a live shop: in the field team's re-check list
        shop.touch(now);
        save(shop);
        if (waitsNow) {
            assignments.startedWaiting(shop.getId(), "RECHECK");
            dashboardCounts.clearAfterCommit();
        }
        return view(made);
    }

    /** The owner's latest request, whatever became of it (a declined one shows why); empty if he never asked. */
    @Transactional(readOnly = true)
    public Optional<AddressRequestView> latest(UUID userId) {
        ShopMember member = owner(userId);
        return requests.latest(member.getShopId()).map(AddressRequestService::view);
    }

    /**
     * The owner takes back his waiting request (no code needed: nothing changes). The shop keeps its address; a live
     * shop waiting for its re-check only because of the request stops waiting. Nothing waiting: nothing happens.
     */
    @Transactional
    public void cancel(UUID userId, long version) {
        Shop shop = lockedOwnShop(userId, version);
        Instant now = now();
        if (requests.decide(shop.getId(), "CANCELLED", null, userId, now)) {
            shop.touch(now);
            shopService.endRecheckIfNothingLeft(shop);
            save(shop);
            dashboardCounts.clearAfterCommit();
        }
    }

    static AddressRequestView view(AddressRequestStore.Request r) {
        return new AddressRequestView(r.id(), r.addressLine(), r.area(), r.city(), r.pincode(), r.latitude(),
                r.longitude(), r.status(), r.reason(), r.createdAt(), r.decidedAt());
    }

    private Shop lockedOwnShop(UUID userId, long version) {
        ShopMember member = owner(userId);
        assignments.lockForAssignment();   // before the shop's row: the request may assign it to a field agent
        Shop shop = shops.lockById(member.getShopId()).orElseThrow();
        if (shop.getVersion() != version) {
            throw ShopService.shopChanged();
        }
        return shop;
    }

    private ShopMember owner(UUID userId) {
        ShopMember member = shopService.membership(userId);
        if (member.getRole() != ShopRole.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the shop owner can change the address.");
        }
        return member;
    }

    private void save(Shop shop) {
        try {
            shops.saveAndFlush(shop);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw ShopService.shopChanged();
        }
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
