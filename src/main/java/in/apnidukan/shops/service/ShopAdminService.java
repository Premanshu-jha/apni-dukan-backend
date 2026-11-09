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
    private final Clock clock;

    public ShopAdminService(ShopRepository shops, ShopMemberRepository members, UserDirectory users, FileRefs files,
                            Notifications notifications, List<ShopChangeListener> listeners, ShopReviewStore review,
                            SalonFacts salons, DashboardCounts dashboardCounts, ShopAssignments assignments,
                            Clock clock) {
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
        }
        AdminShopDetail.Salon salon = null;
        if (shop.getType() == ShopType.SALON) {
            SalonFacts.Size size = salons.size(shopId);
            salon = new AdminShopDetail.Salon(size.barbers(), size.services());
        }
        TimelinePage timeline = timelinePage(shopId, null);
        // The salon video only here (the field team's shop page), never in lists or anything customers see
        String video = shop.getShopVideoFileId() == null ? null : existingLink(shop.getShopVideoFileId());
        return new AdminShopDetail(views(List.of(shop)).get(0), salon, changes, moved, oldOwnerPhoto, oldShopPhoto,
                video, timeline.items(), timeline.next());
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
     * Approves a shop after the visit (PENDING_VISIT only). {@code version} is the one shown in the list the field
     * team checked: if the owner changed anything since (photos, address), 409 SHOP_CHANGED, so nothing unchecked
     * goes live. 409 WRONG_STATUS from any other status; 404 if there's no such shop.
     */
    @Transactional
    public AdminShopResponse approve(UUID adminId, UUID shopId, long version, String noteInput) {
        Shop shop = checked(adminId, shopId, version, "approved", false, ShopStatus.PENDING_VISIT);
        String note = optionalNote(noteInput);
        shop.approve(now());
        save(shop);
        review.addEvent(shopId, now(), adminId, true, ShopEventType.APPROVED, null, note, List.of());
        dashboardCounts.clearAfterCommit();
        tellOwner(shop, "SHOP_APPROVED", "Your shop is live",
                shop.details().name() + " is approved. Customers nearby can find it now.");
        return view(shop);
    }

    /**
     * The visit didn't pass but the shop can fix it: back to DRAFT with the reason; the owner fixes it and submits
     * again. Bookings already made (a salon re-checked after a move) stay; suspend instead to cancel them.
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
        Shop shop = shops.findById(shopId).orElseThrow(() -> ApiException.notFound("Shop"));
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
        members.findByShopIdOrderByCreatedAtAscIdAsc(shop.getId()).stream()
                .filter(m -> m.getRole() == ShopRole.OWNER)
                .forEach(m -> notifications.queue(new Notice(m.getUserId(), type, null, shop.getUpdatedAt(), title,
                        body, "/shops/me", Map.of())));
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
        return list.stream().map(shop -> AdminShopResponse.from(shop, link(links, shop.getOwnerPhotoFileId()),
                link(links, shop.getShopPhotoFileId()), approvedBefore.contains(shop.getId()), phones.get(shop.getId()),
                since.get(shop.getId()), assignee(assignees.get(shop.getId()), agents))).toList();
    }

    private static AdminShopResponse.Assignee assignee(UUID id, Map<UUID, UserDirectory.Person> agents) {
        if (id == null) {
            return null;
        }
        UserDirectory.Person p = agents.get(id);
        return new AdminShopResponse.Assignee(id, p == null ? null : p.name());
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
