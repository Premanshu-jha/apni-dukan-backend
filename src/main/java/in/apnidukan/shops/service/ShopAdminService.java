package in.apnidukan.shops.service;

import in.apnidukan.common.context.SalonFacts;
import in.apnidukan.common.context.ShopChangeListener;
import in.apnidukan.common.context.TeamRole;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.notify.Notifications;
import in.apnidukan.common.notify.Notifications.Notice;
import in.apnidukan.common.text.TextCleaner;
import in.apnidukan.common.text.TextCleaner.TextField;
import in.apnidukan.common.files.FileRefs;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.shops.api.AdminShopDetail;
import in.apnidukan.shops.api.AdminShopDetail.Change;
import in.apnidukan.shops.api.AdminShopDetail.TimelineEntry;
import in.apnidukan.shops.api.AdminShopDetail.TimelineNext;
import in.apnidukan.shops.api.AdminShopDetail.TimelinePage;
import in.apnidukan.shops.api.AdminShopResponse;
import in.apnidukan.shops.domain.RegistrationDocType;
import in.apnidukan.shops.domain.ReviewGroup;
import in.apnidukan.shops.domain.Shop;
import in.apnidukan.shops.domain.ShopDetails;
import in.apnidukan.shops.domain.ShopEventType;
import in.apnidukan.shops.domain.ShopRole;
import in.apnidukan.shops.domain.ShopStatus;
import in.apnidukan.shops.domain.ShopType;
import in.apnidukan.shops.domain.TimelineCursor;
import in.apnidukan.shops.domain.VisitIssue;
import in.apnidukan.shops.repo.ShopMemberRepository;
import in.apnidukan.shops.api.AddressRequestView;
import in.apnidukan.shops.repo.AddressRequestStore;
import in.apnidukan.shops.repo.ShopRepository;
import in.apnidukan.shops.repo.ShopReviewStore;
import in.apnidukan.shops.repo.ShopReviewStore.Snapshot;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Field-team pages: the shops waiting for a visit, with the owner's and the shop's photo; approving one after the
 * visit or sending it back with a reason; suspending a shop (off the app; a salon's upcoming online bookings are
 * cancelled) and reinstating it. Each decision carries the version the field team saw (409 SHOP_CHANGED if the
 * owner changed something since) and tells the owner. Only for the team: the security config refuses everyone else
 * (403), and the role is read again from the database here, because the one in a sign-in token can be up to 15
 * minutes old. A field agent sees and decides only the businesses assigned to them (any other: 404, as if it didn't
 * exist); suspending and reinstating are for super admins (ShopAssignments has the rules).
 */
@Service
public class ShopAdminService {

    static final TextField REASON = new TextField("reason", 2, 200);
    static final TextField NOTE = new TextField("note", 2, 500);
    /** Reads the stored licence extract (JSON written by LicenceCheckJob) for the shop page. */
    private static final tools.jackson.databind.json.JsonMapper READ_JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    private final ShopRepository shops;
    private final ShopMemberRepository members;
    private final UserDirectory users;
    private final FileRefs files;
    private final Notifications notifications;
    private final List<ShopChangeListener> listeners;
    private final ShopReviewStore review;
    private final SalonFacts salons;
    private final DashboardCounts dashboardCounts;
    private final ShopAssignments assignments;
    private final ShopService shopService;
    private final AddressRequestStore addressRequests;
    private final in.apnidukan.shops.repo.LicenceCheckStore licences;
    private final Clock clock;

    public ShopAdminService(ShopRepository shops, ShopMemberRepository members, UserDirectory users, FileRefs files,
                            Notifications notifications, List<ShopChangeListener> listeners, ShopReviewStore review,
                            SalonFacts salons, DashboardCounts dashboardCounts, ShopAssignments assignments,
                            ShopService shopService, AddressRequestStore addressRequests, Clock clock,
                            in.apnidukan.shops.repo.LicenceCheckStore licences) {
        this.licences = licences;
        this.shopService = shopService;
        this.addressRequests = addressRequests;
        this.dashboardCounts = dashboardCounts;
        this.assignments = assignments;
        this.review = review;
        this.salons = salons;
        this.shops = shops;
        this.members = members;
        this.users = users;
        this.files = files;
        this.notifications = notifications;
        this.listeners = listeners;
        this.clock = clock;
    }

    /**
     * The dashboard: how many shops of each type are in each group. Kept 30 seconds in Redis (one count over every
     * shop for each admin page open is too much at tens of thousands of shops); a field-team decision clears it, so
     * the admin who decided sees the new counts at once. Owners' own steps (submitting, a pin moved) show within
     * 30 seconds. If Redis doesn't answer, the counts come straight from the database.
     */
    @Transactional(readOnly = true)
    public Map<ShopType, Map<ReviewGroup, Long>> dashboard(UUID adminId) {
        TeamRole role = requireAdmin(adminId);
        // A field agent's own businesses: a small index range, counted fresh (never shared through Redis)
        return role == TeamRole.FIELD_AGENT ? review.counts(adminId) : dashboardCounts.get();
    }

    /**
     * One page (50) of a group's shops, longest waiting first, after the cursor (null: the first page); `q` searches
     * shop number, names and owner phone. A field agent gets only their own (whatever {@code assignedTo} says); a
     * super admin may narrow it to one agent's ({@code assignedTo}) or to those nobody is assigned
     * ({@code unassigned}).
     */
    @Transactional(readOnly = true)
    public List<AdminShopResponse> list(UUID adminId, ShopType type, ReviewGroup group, String q,
                                        Instant afterSince, UUID afterId, UUID assignedTo, boolean unassigned) {
        TeamRole role = requireAdmin(adminId);
        requireSearchable(q);
        if (role == TeamRole.FIELD_AGENT) {
            assignedTo = adminId;
            unassigned = false;
        }
        List<UUID> ids = review.search(type, group, q,
                afterId == null ? null : new ShopReviewStore.Cursor(afterSince, afterId), assignedTo, unassigned);
        Map<UUID, Shop> byId = shops.findAllById(ids).stream().collect(Collectors.toMap(Shop::getId,
                Function.identity()));
        return views(ids.stream().map(byId::get).filter(Objects::nonNull).toList());
    }

    /** One shop with what changed since it was last approved and its timeline. 404 if there's no such shop. */
    @Transactional(readOnly = true)
    public AdminShopDetail detail(UUID adminId, UUID shopId) {
        assignments.requireCanSee(adminId, shopId);
        Shop shop = shops.findById(shopId).orElseThrow(() -> ApiException.notFound("Shop"));
        Snapshot approved = review.lastApproved(shopId).orElse(null);
        List<Change> changes = new ArrayList<>();
        Long moved = null;
        String oldOwnerPhoto = null;
        String oldShopPhoto = null;
        if (approved != null) {
            ShopDetails now = shop.details();
            change(changes, "name", "Shop name", approved.name(), now.name());
            change(changes, "ownerName", "Owner's name", approved.ownerName(), now.ownerName());
            change(changes, "addressLine", "Address", approved.addressLine(), now.addressLine());
            change(changes, "area", "Area", approved.area(), now.area());
            change(changes, "city", "City", approved.city(), now.city());
            change(changes, "pincode", "Pincode", approved.pincode(), now.pincode());
            if (approved.latitude().compareTo(now.latitude()) != 0
                    || approved.longitude().compareTo(now.longitude()) != 0) {
                moved = Math.round(metresBetween(approved.latitude().doubleValue(), approved.longitude().doubleValue(),
                        now.latitude().doubleValue(), now.longitude().doubleValue()));
                changes.add(new Change("location", "Pin on the map", approved.latitude() + ", " + approved.longitude(),
                        now.latitude() + ", " + now.longitude()));
            }
            change(changes, "gstin", "GSTIN", approved.gstin(), now.gstin());
            change(changes, "udyamNumber", "Udyam number", approved.udyamNumber(), now.udyamNumber());
            if (!Objects.equals(approved.ownerPhotoFileId(), shop.getOwnerPhotoFileId())) {
                changes.add(new Change("ownerPhoto", "Owner's photo", null, null));
                oldOwnerPhoto = existingLink(approved.ownerPhotoFileId());
            }
            if (!Objects.equals(approved.shopPhotoFileId(), shop.getShopPhotoFileId())) {
                changes.add(new Change("shopPhoto", "Shop's photo", null, null));
                oldShopPhoto = existingLink(approved.shopPhotoFileId());
            }
            if (!Objects.equals(approved.shopVideoFileId(), shop.getShopVideoFileId())) {
                changes.add(new Change("shopVideo", "Salon video", null, null));
            }
            if (!Objects.equals(approved.registrationDocFileId(), shop.getRegistrationDocFileId())
                    || !Objects.equals(approved.registrationDocType(),
                            shop.getRegistrationDocType() == null ? null : shop.getRegistrationDocType().name())) {
                changes.add(new Change("registrationDoc", "Registration document",
                        docLabel(approved.registrationDocType()), docLabel(shop.getRegistrationDocType())));
            }
        }
        AdminShopDetail.Salon salon = null;
        if (shop.getType() == ShopType.SALON) {
            SalonFacts.Size size = salons.size(shopId);
            SalonFacts.Profile profile = salons.profile(shopId);
            salon = new AdminShopDetail.Salon(size.barbers(), size.services(), profile.services(), profile.hours(),
                    profile.ratings(), profile.ratingAverage());
        }
        AdminShopDetail.RegistrationDoc doc = shop.getRegistrationDocType() == null ? null
                : new AdminShopDetail.RegistrationDoc(shop.getRegistrationDocType(),
                        shop.getRegistrationDocType().label(), existingLink(shop.getRegistrationDocFileId()),
                        shop.getRegistrationDocCheckedAt(), shop.getLicenceCheckStatus(),
                        shop.getLicenceCheckReason(), shop.getLicenceNumber(), shop.getLicenceValidUntil(),
                        shop.getLicenceExtract() == null ? null : READ_JSON.readTree(shop.getLicenceExtract()),
                        shop.getLicenceCheckSince());
        TimelinePage timeline = timelinePage(shopId, null);
        // The salon video only here (the field team's shop page), never in lists or anything customers see
        String video = shop.getShopVideoFileId() == null ? null : existingLink(shop.getShopVideoFileId());
        AddressRequestView addressRequest = addressRequests.pending(shopId).map(AddressRequestService::view)
                .orElse(null);
        return new AdminShopDetail(views(List.of(shop)).get(0), salon, changes, moved, oldOwnerPhoto, oldShopPhoto,
                video, doc, addressRequest, timeline.items(), timeline.next());
    }

    /**
     * A shop's timeline, 50 entries a page, newest first, after the cursor (null: the newest, the same as the shop
     * page's). 404 if there's no such shop.
     */
    @Transactional(readOnly = true)
    public TimelinePage timeline(UUID adminId, UUID shopId, TimelineCursor after) {
        assignments.requireCanSee(adminId, shopId);
        if (!shops.existsById(shopId)) {
            throw ApiException.notFound("Shop");
        }
        return timelinePage(shopId, after);
    }

    private TimelinePage timelinePage(UUID shopId, TimelineCursor after) {
        int page = ShopReviewStore.TIMELINE_PAGE;
        List<ShopReviewStore.Event> events = review.timeline(shopId, after, page + 1);
        List<ShopReviewStore.Event> shown = events.size() > page ? events.subList(0, page) : events;
        ShopReviewStore.Event last = shown.isEmpty() ? null : shown.get(shown.size() - 1);
        return new TimelinePage(shown.stream()
                .map(e -> new TimelineEntry(e.at(), e.event(), e.byName(), e.byAdmin(), e.reason(), e.note(),
                        e.checklist()))
                .toList(), events.size() > page ? new TimelineNext(last.at(), last.seq()) : null);
    }

    /**
     * 400 VALIDATION_FAILED for a search of 1 or 2 letters: it would match most shops by name and read the trigram
     * index for nothing useful. A shop number ("7", "#42") of any length still works; phone digits need 3 anyway.
     */
    static void requireSearchable(String q) {
        String text = q == null ? "" : q.strip();
        if (!text.isEmpty() && text.codePointCount(0, text.length()) < 3 && !text.matches("#?\\d+")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                    Map.of("q", "type at least 3 letters"));
        }
    }

    /** A note on the shop's timeline for admins only (no decision). 404 if there's no such shop. */
    @Transactional
    public TimelineEntry addNote(UUID adminId, UUID shopId, String noteInput) {
        assignments.requireCanSee(adminId, shopId);
        if (!shops.existsById(shopId)) {
            throw ApiException.notFound("Shop");
        }
        requireNotOwnShop(adminId, shopId);
        String note = TextCleaner.clean(NOTE, noteInput);
        Instant now = now();
        review.addEvent(shopId, now, adminId, true, ShopEventType.NOTE, null, note, List.of());
        String by = users.find(Set.of(adminId)).values().stream().map(UserDirectory.Person::name).findFirst()
                .orElse(null);
        return new TimelineEntry(now, ShopEventType.NOTE, by, true, null, note, List.of());
    }

    /**
     * Sends a salon's trade licence for the automatic check again (a field agent for a business assigned to them, or a
     * super admin): the owner's details were fixed by the field team, or the AI got it wrong once. Counts towards the
     * salon's checks a day (past it, read tomorrow). Any status but CLOSED. 409 NO_DOCUMENT without one, 409
     * NOT_TRADE_LICENCE for a document of another kind (it is never sent to the AI), 409 SHOP_CHANGED.
     */
    @Transactional
    public AdminShopResponse recheckLicence(UUID adminId, UUID shopId, long version) {
        Shop shop = checked(adminId, shopId, version, "changed", false, ShopStatus.DRAFT, ShopStatus.PENDING_VISIT,
                ShopStatus.APPROVED, ShopStatus.SUSPENDED);
        if (shop.getRegistrationDocFileId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_DOCUMENT", "This salon has no registration document.");
        }
        if (!shop.restartLicenceCheck(now())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_TRADE_LICENCE", "Only a trade licence is checked; this is "
                    + "a " + shop.getRegistrationDocType().label().toLowerCase(java.util.Locale.ROOT) + ".");
        }
        save(shop);
        shopService.startCheck(shopId);
        review.addEvent(shopId, now(), adminId, true, ShopEventType.NOTE, null, "Trade licence sent for checking again",
                List.of());
        return view(shop);
    }

    /**
     * Salons whose trade licence wasn't accepted, or has waited more than half an hour for its check (no AI key, the
     * AI down), longest first, at most 100 (super admins): to follow up with the owner or look at the AI.
     */
    @Transactional(readOnly = true)
    public List<AdminShopResponse> licencesToFollowUp(UUID adminId) {
        assignments.requireSuperAdmin(adminId);
        List<UUID> ids = licences.followUp(now().minus(java.time.Duration.ofMinutes(30)), 100);
        Map<UUID, Shop> byId = shops.findAllById(ids).stream().collect(Collectors.toMap(Shop::getId,
                Function.identity()));
        return views(ids.stream().map(byId::get).filter(Objects::nonNull).toList());
    }

    /**
     * Takes a wrong, someone else's or expired registration document off the salon, with the reason the owner is told:
     * no "Licensed" badge from now on. Any status but CLOSED (a field agent only for a business assigned to them). 409
     * NO_DOCUMENT without one, 409 SHOP_CHANGED if the owner changed the shop since the page was loaded. The salon's
     * status doesn't change.
     */
    @Transactional
    public AdminShopResponse removeDocument(UUID adminId, UUID shopId, long version, String reasonInput) {
        Shop shop = checked(adminId, shopId, version, "changed", false, ShopStatus.DRAFT, ShopStatus.PENDING_VISIT,
                ShopStatus.APPROVED, ShopStatus.SUSPENDED);
        if (shop.getRegistrationDocFileId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_DOCUMENT", "This salon has no registration document.");
        }
        String reason = TextCleaner.clean(REASON, reasonInput);
        String label = shop.getRegistrationDocType().label();
        shop.setRegistrationDoc(null, null, now());
        save(shop);
        review.addEvent(shopId, now(), adminId, true, ShopEventType.NOTE, null,
                "Registration document removed (" + label + "): " + reason, List.of());
        tellOwner(shop, "SHOP_DOCUMENT_REMOVED", "Trade licence removed",
                "We removed the " + label.toLowerCase(java.util.Locale.ROOT) + " from " + shop.details().name() + ": "
                        + reason + " " + afterRemoval(shop.getStatus()),
                shop.getStatus() == ShopStatus.DRAFT ? "/register/photos" : "/setup/document");
        return view(shop);
    }

    /** What a removed licence means for the salon now, and where to add the right one. */
    static String afterRemoval(ShopStatus status) {
        return switch (status) {
            case APPROVED -> "Your salon stays open for bookings. Add the right one in Salon → Trade licence.";
            case DRAFT -> "You can add the right one in your registration, under Photos.";
            default -> "Nothing else changes. Add the right one in Salon → Trade licence.";
        };
    }

    /**
     * Approves a shop after the visit: PENDING_VISIT, or a live shop waiting for its re-check (it stays live; its
     * changes count as checked). The owner's waiting address change request is approved with it: the new address and
     * pin are the shop's from now on (as changeAddress; the owner is told).
     * Decline the request first (declineAddressRequest) to approve the rest without it. {@code version} is the one shown in the list the field team checked: if the owner
     * changed anything since (photos, address), 409 SHOP_CHANGED, so nothing unchecked is approved. 409 WRONG_STATUS
     * from any other status (a live shop with nothing to re-check too); 404 if there's no such shop.
     */
    @Transactional
    public AdminShopResponse approve(UUID adminId, UUID shopId, long version, String noteInput) {
        assignments.lockForAssignment();   // first: an approved new address may change the pincode
        Shop shop = checkedWaiting(adminId, shopId, version, "approved");
        boolean wasLive = shop.getStatus() == ShopStatus.APPROVED;
        String note = optionalNote(noteInput);
        shop.approve(now());
        save(shop);   // the row is locked from here: the owner's request can't change under us
        review.addEvent(shopId, now(), adminId, true, ShopEventType.APPROVED, null, note, List.of());
        // After approving: no longer waiting, so a new pincode doesn't send it to another agent's visit list
        addressRequests.pending(shopId).ifPresent(r -> applyAddressRequest(adminId, shop, r));
        dashboardCounts.clearAfterCommit();
        if (wasLive) {
            tellOwner(shop, "SHOP_RECHECKED", "Your changes are checked",
                    "Our field team checked the changes to " + shop.details().name() + ". Nothing more to do.");
        } else {
            tellOwner(shop, "SHOP_APPROVED", "Your shop is live",
                    shop.details().name() + " is approved. Customers nearby can find it now.");
        }
        return view(shop);
    }

    /**
     * Like checked, for approve and send back: the shop must be waiting for a visit (PENDING_VISIT, or a live shop's
     * re-check). Checked before the version, as the status is in checked: a live shop another admin approved first
     * answers 409 WRONG_STATUS (nothing waits any more), not SHOP_CHANGED.
     */
    private Shop checkedWaiting(UUID adminId, UUID shopId, long version, String what) {
        assignments.requireCanDecide(adminId, shopId);
        // Its row locked and read fresh: the licence check's answer (plain SQL, no new version) written a moment ago
        // is in what this saves, never undone or broken by a stale copy
        Shop shop = shops.lockById(shopId).orElseThrow(() -> ApiException.notFound("Shop"));
        requireNotOwnShop(adminId, shopId);
        if (!shop.waitingForVisit()) {
            throw new ApiException(HttpStatus.CONFLICT, "WRONG_STATUS",
                    "A shop that is " + shop.getStatus() + (shop.getStatus() == ShopStatus.APPROVED
                            ? " with nothing waiting for a check" : "") + " can't be " + what
                            + ". Someone may have decided already; reload.", Map.of("status", shop.getStatus().name()));
        }
        if (shop.getVersion() != version) {
            throw new ApiException(HttpStatus.CONFLICT, "SHOP_CHANGED",
                    "The shop changed after you loaded it. Reload and check the new details first.");
        }
        return shop;
    }

    /**
     * The visit didn't pass but the shop can fix it: PENDING_VISIT → DRAFT with the reason; the owner fixes it and
     * submits again. Bookings already made (a reinstated salon) stay; suspend instead to cancel them. A live shop's
     * re-check is never sent back (that would strand its team and its bookings): askToFix.
     */
    @Transactional
    public AdminShopResponse reject(UUID adminId, UUID shopId, long version, String reasonInput, String noteInput,
                                    Collection<VisitIssue> checklist) {
        Shop shop = checked(adminId, shopId, version, "sent back", false, ShopStatus.PENDING_VISIT);
        String reason = TextCleaner.clean(REASON, reasonInput);
        String note = optionalNote(noteInput);
        shop.reject(reason, now());
        save(shop);
        review.addEvent(shopId, now(), adminId, true, ShopEventType.SENT_BACK, reason, note, issues(checklist));
        dashboardCounts.clearAfterCommit();
        tellOwner(shop, "SHOP_NOT_APPROVED", "Your shop needs a change",
                shop.details().name() + " wasn't approved yet: " + reason + " Fix it in the app and submit again.");
        return view(shop);
    }

    /**
     * A live shop's re-check needs a fix from the owner: it stays as it is (live) and keeps its place in the queue, the owner is told the reason (statusReason, until he changes
     * something or the field team approves). Same rules as approve; 409 WRONG_STATUS for anything but a live shop
     * waiting for its re-check (a first visit is sent back instead).
     */
    @Transactional
    public AdminShopResponse askToFix(UUID adminId, UUID shopId, long version, String reasonInput, String noteInput,
                                      Collection<VisitIssue> checklist) {
        Shop shop = checkedWaiting(adminId, shopId, version, "asked for a fix");
        if (shop.getStatus() != ShopStatus.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "WRONG_STATUS", "A shop that is " + shop.getStatus()
                    + " is sent back, not asked for a fix.", Map.of("status", shop.getStatus().name()));
        }
        String reason = TextCleaner.clean(REASON, reasonInput);
        String note = optionalNote(noteInput);
        shop.askToFix(reason, now());
        save(shop);
        review.addEvent(shopId, now(), adminId, true, ShopEventType.FIX_ASKED, reason, note, issues(checklist));
        tellOwner(shop, "SHOP_FIX_ASKED", "Please fix your change", "Our field team checked the changes to "
                + shop.details().name() + ": " + reason + " Fix it in the Salon tab → Salon details (or Photos and "
                + "video). Your salon stays live.", "/setup");
        return view(shop);
    }

    /**
     * The field team changes a shop's address or pin (the owner can't once it was verified, Shop.addressLocked): any
     * status but CLOSED, a field agent only for a business assigned to them, never their own. No re-check (they
     * checked it); a note on the timeline with the old and new address; the owner is told. 409 SHOP_CHANGED /
     * WRONG_STATUS as for a decision; 400 VALIDATION_FAILED for a field the owner's edit would refuse.
     */
    @Transactional
    public AdminShopResponse changeAddress(UUID adminId, UUID shopId, long version, String addressLine, String area,
                                          String city, String pincode, java.math.BigDecimal latitude,
                                          java.math.BigDecimal longitude) {
        assignments.lockForAssignment();   // first: a new pincode may assign it to another field agent
        Shop shop = checked(adminId, shopId, version, "changed", false, ShopStatus.DRAFT, ShopStatus.PENDING_VISIT,
                ShopStatus.APPROVED, ShopStatus.SUSPENDED);
        ShopDetails old = shopService.moveByFieldTeam(shop, addressLine, area, city, pincode, latitude, longitude);
        // The field team set it themselves (the row is locked by the save above): the owner's waiting request is
        // answered, not applied later over this; a re-check started only by it ends. Nothing changed: it stays.
        if (old != null && addressRequests.decide(shopId, "CANCELLED", null, adminId, now())) {
            shopService.endRecheckIfNothingLeft(shop);
            save(shop);
        }
        if (old != null) {
            ShopDetails now = shop.details();
            review.addEvent(shopId, now(), adminId, true, ShopEventType.NOTE, null, "Address changed by the field "
                    + "team: " + addressText(old) + " → " + addressText(now), List.of());
            dashboardCounts.clearAfterCommit();
            tellOwner(shop, "SHOP_ADDRESS_CHANGED", "Your address is updated", "Our field team changed the address "
                    + "of " + now.name() + " to " + addressText(now) + ". Customers see the new one now.");
        }
        return view(shop);
    }

    /** The owner's request becomes the shop's address (approve); the timeline and the owner say so. */
    private void applyAddressRequest(UUID adminId, Shop shop, AddressRequestStore.Request r) {
        addressRequests.decide(shop.getId(), "APPROVED", null, adminId, now());
        ShopDetails old = shopService.moveByFieldTeam(shop, r.addressLine(), r.area(), r.city(), r.pincode(),
                r.latitude(), r.longitude());
        if (old != null) {
            ShopDetails now = shop.details();
            review.addEvent(shop.getId(), now(), adminId, true, ShopEventType.NOTE, null, "Address change approved: "
                    + addressText(old) + " → " + addressText(now), List.of());
            tellOwner(shop, "SHOP_ADDRESS_CHANGED", "Your new address is approved", "Our field team approved the new "
                    + "address of " + now.name() + ": " + addressText(now) + ". Customers see it now.");
        }
    }

    /**
     * The field team declines the owner's waiting address change request, with the reason he is told: the shop keeps
     * its address. A live shop still waits for its re-check if something else changed too (approve it for those);
     * otherwise it stops waiting. Any status
     * but CLOSED, as changeAddress; 409 NO_ADDRESS_REQUEST when none waits (the owner took it back, or someone decided
     * already).
     */
    @Transactional
    public AdminShopResponse declineAddressRequest(UUID adminId, UUID shopId, long version, String reasonInput) {
        Shop shop = checked(adminId, shopId, version, "changed", false, ShopStatus.DRAFT, ShopStatus.PENDING_VISIT,
                ShopStatus.APPROVED, ShopStatus.SUSPENDED);
        String reason = TextCleaner.clean(REASON, reasonInput);
        shop.touch(now());
        save(shop);   // the row is locked from here
        if (!addressRequests.decide(shopId, "DECLINED", reason, adminId, now())) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_ADDRESS_REQUEST",
                    "No address change waits for this shop any more. Reload.");
        }
        shopService.endRecheckIfNothingLeft(shop);   // the address was all there was to check: done
        save(shop);
        dashboardCounts.clearAfterCommit();
        review.addEvent(shopId, now(), adminId, true, ShopEventType.NOTE, null, "Address change declined: " + reason,
                List.of());
        tellOwner(shop, "SHOP_ADDRESS_DECLINED", "Your new address wasn't approved", "Our field team didn't approve "
                + "the new address of " + shop.details().name() + ": " + reason + " Your address stays as it was; "
                + "you can ask again in the Salon tab → Salon details.", "/setup/address");
        return view(shop);
    }

    private static String addressText(ShopDetails d) {
        return d.addressLine() + ", " + d.area() + ", " + d.city() + " " + d.pincode();
    }

    /**
     * Off the app (APPROVED, or PENDING_VISIT found shut or fake at its visit): customers can't find it, and a
     * salon's upcoming online bookings are cancelled with a message to each customer (ShopChangeListener.suspended).
     */
    @Transactional
    public AdminShopResponse suspend(UUID adminId, UUID shopId, long version, String reasonInput, String noteInput,
                                     Collection<VisitIssue> checklist) {
        Shop shop = checked(adminId, shopId, version, "suspended", true, ShopStatus.APPROVED,
                ShopStatus.PENDING_VISIT);
        String reason = TextCleaner.clean(REASON, reasonInput);
        String note = optionalNote(noteInput);
        shop.suspend(reason, now());
        save(shop);   // the row is now locked: bookings in progress have finished, new ones see SUSPENDED
        // An address change waiting is dropped: after a suspension the shop is visited again from scratch
        addressRequests.decide(shopId, "CANCELLED", null, adminId, now());
        review.addEvent(shopId, now(), adminId, true, ShopEventType.SUSPENDED, reason, note, issues(checklist));
        dashboardCounts.clearAfterCommit();
        listeners.forEach(l -> l.suspended(shop.getId()));
        tellOwner(shop, "SHOP_SUSPENDED", "Your shop is off the app",
                shop.details().name() + " is hidden from customers: " + reason + " Contact Clozest to fix this.");
        return view(shop);
    }

    /**
     * SUSPENDED → PENDING_VISIT: back in the visit list; it goes live when the field team approves it after
     * checking it again. Cancelled bookings stay cancelled.
     */
    @Transactional
    public AdminShopResponse reinstate(UUID adminId, UUID shopId, long version, String noteInput) {
        assignments.lockForAssignment();   // first: it is assigned below, after its row is locked
        Shop shop = checked(adminId, shopId, version, "reinstated", true, ShopStatus.SUSPENDED);
        String note = optionalNote(noteInput);
        shop.reinstate(now());
        save(shop);
        review.addEvent(shopId, now(), adminId, true, ShopEventType.REINSTATED, null, note, List.of());
        assignments.startedWaiting(shopId, "REINSTATED");
        dashboardCounts.clearAfterCommit();
        tellOwner(shop, "SHOP_REINSTATED", "Your shop will be checked again",
                shop.details().name() + " is no longer suspended. Our team will visit; it goes live once approved.");
        return view(shop);
    }

    /**
     * The shop, if it is what the field team saw (same version) and in a status this decision applies to, and this
     * admin may decide on it: a field agent only on one assigned to them, a super admin on any;
     * {@code superAdminOnly} for suspending and reinstating. The shop's row is locked first, so a reassignment can't
     * slip in between this check and the decision.
     */
    private Shop checked(UUID adminId, UUID shopId, long version, String what, boolean superAdminOnly,
                         ShopStatus... allowed) {
        if (superAdminOnly) {
            assignments.requireSuperAdmin(adminId);
        }
        assignments.requireCanDecide(adminId, shopId);
        // Its row locked and read fresh: the licence check's answer (plain SQL, no new version) written a moment ago
        // is in what this saves, never undone or broken by a stale copy
        Shop shop = shops.lockById(shopId).orElseThrow(() -> ApiException.notFound("Shop"));
        requireNotOwnShop(adminId, shopId);
        // Status first: when another admin decided meanwhile, say so (not "the owner changed it")
        if (!java.util.Arrays.asList(allowed).contains(shop.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "WRONG_STATUS",
                    "A shop that is " + shop.getStatus() + " can't be " + what + ". Someone may have decided "
                            + "already; reload.", Map.of("status", shop.getStatus().name()));
        }
        if (shop.getVersion() != version) {
            throw new ApiException(HttpStatus.CONFLICT, "SHOP_CHANGED",
                    "The shop changed after you loaded it. Reload and check the new details first.");
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
        tellOwner(shop, type, title, body, "/shops/me");
    }

    /** @param url the partner app's page the message opens */
    private void tellOwner(Shop shop, String type, String title, String body, String url) {
        members.findByShopIdOrderByCreatedAtAscIdAsc(shop.getId()).stream()
                .filter(m -> m.getRole() == ShopRole.OWNER)
                .forEach(m -> notifications.queue(new Notice(m.getUserId(), type, null, shop.getUpdatedAt(), title,
                        body, url, Map.of())));
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private AdminShopResponse view(Shop shop) {
        return views(List.of(shop)).get(0);
    }

    /** With each shop's group and owner phone, read for all of them at once. */
    private List<AdminShopResponse> views(List<Shop> list) {
        List<UUID> ids = list.stream().map(Shop::getId).toList();
        Set<UUID> approvedBefore = review.everApproved(ids);
        Map<UUID, String> phones = review.ownerPhones(ids);
        Map<UUID, Instant> since = review.statusSince(ids);
        Map<UUID, UUID> assignees = assignments.assignees(ids);
        Map<UUID, UserDirectory.Person> agents = users.find(new java.util.HashSet<>(assignees.values()));
        // Every photo link of the page with one read of the files table
        Map<UUID, String> links = files.links(list.stream()
                .flatMap(shop -> java.util.stream.Stream.of(shop.getOwnerPhotoFileId(), shop.getShopPhotoFileId()))
                .filter(Objects::nonNull).toList());
        Set<UUID> addressAsked = addressRequests.withPending(ids);
        return list.stream().map(shop -> AdminShopResponse.from(shop, link(links, shop.getOwnerPhotoFileId()),
                link(links, shop.getShopPhotoFileId()), approvedBefore.contains(shop.getId()), phones.get(shop.getId()),
                since.get(shop.getId()), assignee(assignees.get(shop.getId()), agents),
                addressAsked.contains(shop.getId()))).toList();
    }

    private static AdminShopResponse.Assignee assignee(UUID id, Map<UUID, UserDirectory.Person> agents) {
        if (id == null) {
            return null;
        }
        UserDirectory.Person p = agents.get(id);
        return new AdminShopResponse.Assignee(id, p == null ? null : p.name());
    }

    /** A stored document type ("TRADE_LICENCE") as the field team reads it; null for none or an unknown one. */
    private static String docLabel(String type) {
        if (type == null) {
            return null;
        }
        try {
            return RegistrationDocType.valueOf(type).label();
        } catch (IllegalArgumentException unknown) {
            return type;
        }
    }

    private static String docLabel(RegistrationDocType type) {
        return type == null ? null : type.label();
    }

    private static void change(List<Change> out, String field, String label, String before, String after) {
        if (!Objects.equals(before, after)) {
            out.add(new Change(field, label, before, after));
        }
    }

    /** A link to a photo still stored (an old photo may have been deleted since); null otherwise. */
    private String existingLink(UUID fileId) {
        return fileId == null || files.find(fileId).isEmpty() ? null : files.link(fileId);
    }

    private static String optionalNote(String note) {
        return note == null || note.isBlank() ? null : TextCleaner.clean(NOTE, note);
    }

    private static List<VisitIssue> issues(Collection<VisitIssue> checklist) {
        return checklist == null ? List.of() : checklist.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    /** Distance between two points on the earth (haversine), in metres. */
    static double metresBetween(double lat1, double lng1, double lat2, double lng2) {
        double r = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    private static String link(Map<UUID, String> links, UUID fileId) {
        return fileId == null ? null : links.get(fileId);
    }

    /**
     * 403 OWN_SHOP when the admin works in this shop (owner, helper or barber): nobody decides on, or writes the
     * field team's notes about, their own shop. The super admin too: another admin decides.
     */
    private void requireNotOwnShop(UUID adminId, UUID shopId) {
        if (members.findByShopIdAndUserId(shopId, adminId).isPresent()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWN_SHOP",
                    "You can't decide on a shop you belong to. Ask another admin.");
        }
    }

    /**
     * The security config already refused anyone whose token isn't an admin's (403). This catches a token issued
     * before the admin flag was removed: 404, so the pages don't reveal that they exist. Returns their role.
     */
    private TeamRole requireAdmin(UUID userId) {
        TeamRole role = users.teamRole(userId);
        if (!role.onTeam()) {
            throw ApiException.notFound("Page");
        }
        return role;
    }
}
