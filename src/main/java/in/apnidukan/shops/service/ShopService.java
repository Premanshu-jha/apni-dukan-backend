package in.apnidukan.shops.service;

import in.apnidukan.common.context.ShopChangeListener;
import in.apnidukan.common.context.ShopMemberships;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.text.TextCleaner.TextField;
import in.apnidukan.common.files.FileRefs;
import in.apnidukan.common.files.FileRefs.FileRef;
import in.apnidukan.shops.api.ShopRequests.CreateShop;
import in.apnidukan.shops.api.ShopRequests.SetPhotos;
import in.apnidukan.shops.api.ShopRequests.UpdateShop;
import in.apnidukan.shops.api.ShopResponse;
import in.apnidukan.shops.domain.Shop;
import in.apnidukan.shops.domain.ShopDetails;
import in.apnidukan.shops.domain.ShopMember;
import in.apnidukan.shops.domain.ShopRole;
import in.apnidukan.shops.domain.ShopStatus;
import in.apnidukan.shops.domain.ShopType;
import in.apnidukan.shops.repo.ShopMemberRepository;
import in.apnidukan.shops.repo.ShopRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static in.apnidukan.common.error.DatabaseErrors.violated;
import static in.apnidukan.common.text.TextCleaner.clean;

/**
 * Shop registration (step 1) and My shop. The person always comes from the sign-in token; the shop is looked up
 * from their membership in the database, never taken from the request.
 */
@Service
public class ShopService implements ShopMemberships {

    /** 2-digit state code, PAN (5 letters, 4 digits, 1 letter), entity number, 'Z', check character. */
    private static final Pattern GSTIN = Pattern.compile("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]");
    private static final String GSTIN_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    /** Unique key that allows one shop per person (named in V3). */
    static final String ONE_SHOP_PER_PERSON = "ux_shop_members_user";
    private static final Pattern UDYAM = Pattern.compile("UDYAM-[A-Z]{2}-[0-9]{2}-[0-9]{7}");

    /** Each text field's allowed length after cleaning; max is the column size in V3. */
    static final TextField NAME = new TextField("name", 2, 100);
    static final TextField OWNER_NAME = new TextField("ownerName", 2, 100);
    static final TextField ADDRESS_LINE = new TextField("addressLine", 5, 200);
    static final TextField AREA = new TextField("area", 2, 100);
    static final TextField CITY = new TextField("city", 2, 60);

    private final ShopRepository shops;
    private final ShopMemberRepository members;
    private final FileRefs files;
    private final List<ShopChangeListener> listeners;
    private final Clock clock;

    public ShopService(ShopRepository shops, ShopMemberRepository members, FileRefs files,
                       List<ShopChangeListener> listeners, Clock clock) {
        this.shops = shops;
        this.members = members;
        this.files = files;
        this.listeners = listeners;
        this.clock = clock;
    }

    /**
     * Registration step 1: creates the shop as DRAFT with the caller as its OWNER. A kirana unless the app asks for a
     * salon; the type can't be changed afterwards.
     * The caller's current access token doesn't carry the shop yet: the app calls POST /auth/refresh next.
     */
    @Transactional
    public ShopResponse create(UUID userId, CreateShop in) {
        if (members.existsByUserId(userId)) {
            throw alreadyHasShop();
        }
        Instant now = now();
        ShopDetails details = new ShopDetails(clean(NAME, in.name()), clean(OWNER_NAME, in.ownerName()),
                clean(ADDRESS_LINE, in.addressLine()), clean(AREA, in.area()), clean(CITY, in.city()),
                in.pincode(), coordinate(in.latitude()), coordinate(in.longitude()),
                gstin(in.gstin()), udyam(in.udyamNumber()));
        Shop shop = new Shop(shops.nextShopCode(), in.type() == null ? ShopType.KIRANA : in.type(), details, now);
        try {
            shops.saveAndFlush(shop);   // new entity (id still null) → INSERT; Hibernate fills shop.getId()
            members.saveAndFlush(new ShopMember(shop.getId(), userId, ShopRole.OWNER, now));
        } catch (DataIntegrityViolationException e) {
            // The same person registering twice at the same moment: the unique user_id lets only one through.
            // Any other database refusal is a real error, not "you already have a shop".
            if (violated(e, ONE_SHOP_PER_PERSON)) {
                throw alreadyHasShop();
            }
            throw e;
        }
        return ShopResponse.from(shop, ShopRole.OWNER);
    }

    @Transactional(readOnly = true)
    public ShopResponse mine(UUID userId) {
        ShopMember member = membership(userId);
        ShopResponse shop = ShopResponse.from(shops.findById(member.getShopId()).orElseThrow(), member.getRole());
        // A barber sees where he works, not the owner's business papers or photo
        return member.getRole() == ShopRole.BARBER ? shop.forBarber() : shop;
    }

    /** My shop edits. Owner only. A stale version (someone saved in between) is refused rather than overwritten. */
    @Transactional
    public ShopResponse update(UUID userId, UpdateShop in) {
        ShopMember member = membership(userId);
        if (member.getRole() != ShopRole.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the shop owner can change these details.");
        }
        Shop shop = shops.findById(member.getShopId()).orElseThrow();
        if (in.version() != shop.getVersion()) {
            throw shopChanged();
        }
        ShopDetails old = shop.details();
        ShopDetails updated = new ShopDetails(
                in.name() == null ? old.name() : clean(NAME, in.name()),
                in.ownerName() == null ? old.ownerName() : clean(OWNER_NAME, in.ownerName()),
                in.addressLine() == null ? old.addressLine() : clean(ADDRESS_LINE, in.addressLine()),
                in.area() == null ? old.area() : clean(AREA, in.area()),
                in.city() == null ? old.city() : clean(CITY, in.city()),
                in.pincode() == null ? old.pincode() : in.pincode(),
                in.latitude() == null ? old.latitude() : coordinate(in.latitude()),
                in.longitude() == null ? old.longitude() : coordinate(in.longitude()),
                in.gstin() == null ? old.gstin() : gstin(in.gstin()),
                in.udyamNumber() == null ? old.udyamNumber() : udyam(in.udyamNumber()));
        if (updated.equals(old)) {
            return ShopResponse.from(shop, member.getRole());
        }
        shop.apply(updated, now());
        boolean moved = !sameLocation(old, updated);
        if (moved) {
            shop.recheckIfApproved(now());   // the field team checked the old address: check the new one
        }
        try {
            shops.saveAndFlush(shop);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw shopChanged();
        }
        if (moved) {
            listeners.forEach(l -> l.locationChanged(shop.getId()));   // e.g. salon reminders with the new address
        }
        return ShopResponse.from(shop, member.getRole());
    }

    /**
     * Sets the owner's photo and/or the shop's photo (owner only). Each must be a photo uploaded by this shop for
     * that purpose (POST /files with purpose OWNER_PHOTO or SHOP_PHOTO); otherwise 400 naming the field.
     */
    @Transactional
    public ShopResponse setPhotos(UUID userId, SetPhotos in) {
        Shop shop = ownedShop(userId, in.version());
        UUID owner = in.ownerPhotoFileId() == null ? shop.getOwnerPhotoFileId()
                : photo("ownerPhotoFileId", in.ownerPhotoFileId(), shop, "OWNER_PHOTO");
        UUID front = in.shopPhotoFileId() == null ? shop.getShopPhotoFileId()
                : photo("shopPhotoFileId", in.shopPhotoFileId(), shop, "SHOP_PHOTO");
        if (!java.util.Objects.equals(owner, shop.getOwnerPhotoFileId())
                || !java.util.Objects.equals(front, shop.getShopPhotoFileId())) {
            shop.setPhotos(owner, front, now());
            shop.recheckIfApproved(now());   // the field team checked the old photos: check the new ones
            try {
                saveShop(shop);
            } catch (DataIntegrityViolationException deletedMeanwhile) {
                // A chosen file was deleted at the same moment: the database's foreign key refused the link
                Map<String, Object> fields = new java.util.LinkedHashMap<>();
                if (in.ownerPhotoFileId() != null) {
                    fields.put("ownerPhotoFileId", "may have just been deleted; upload it again");
                }
                if (in.shopPhotoFileId() != null) {
                    fields.put("shopPhotoFileId", "may have just been deleted; upload it again");
                }
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                        fields);
            }
        }
        return ShopResponse.from(shop, ShopRole.OWNER);
    }

    /**
     * Registration step 2: the owner asks for the field team's visit. DRAFT → PENDING_VISIT. Needs the owner's and
     * the shop's photo (409 PHOTOS_REQUIRED); from any other status, 409 WRONG_STATUS.
     */
    @Transactional
    public ShopResponse submitForVisit(UUID userId, long version) {
        Shop shop = ownedShop(userId, version);
        if (shop.getStatus() != ShopStatus.DRAFT) {
            throw new ApiException(HttpStatus.CONFLICT, "WRONG_STATUS",
                    "Only a shop still in DRAFT can be submitted; this one is " + shop.getStatus() + ".");
        }
        if (!shop.hasBothPhotos()) {
            Map<String, Object> missing = new java.util.LinkedHashMap<>();
            if (shop.getOwnerPhotoFileId() == null) {
                missing.put("ownerPhotoFileId", "add the owner's photo");
            }
            if (shop.getShopPhotoFileId() == null) {
                missing.put("shopPhotoFileId", "add a photo of the shop");
            }
            throw new ApiException(HttpStatus.CONFLICT, "PHOTOS_REQUIRED",
                    "Add the owner's photo and a photo of the shop first.", missing);
        }
        shop.submitForVisit(now());
        saveShop(shop);
        return ShopResponse.from(shop, ShopRole.OWNER);
    }

    /** True if this file is the owner's or the shop's photo of some shop (then it can't be deleted). */
    @Transactional(readOnly = true)
    public boolean usesFile(UUID fileId) {
        return shops.existsByOwnerPhotoFileIdOrShopPhotoFileId(fileId, fileId);
    }

    private Shop ownedShop(UUID userId, long version) {
        ShopMember member = membership(userId);
        if (member.getRole() != ShopRole.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the shop owner can change these details.");
        }
        Shop shop = shops.findById(member.getShopId()).orElseThrow();
        if (version != shop.getVersion()) {
            throw shopChanged();
        }
        return shop;
    }

    /** The file, if it is a photo this shop uploaded for this purpose; 400 naming the field otherwise. */
    private UUID photo(String field, UUID fileId, Shop shop, String purpose) {
        FileRef f = files.find(fileId)
                .filter(x -> x.shopId().equals(shop.getId()) && purpose.equals(x.purpose()) && x.isPhoto())
                .orElseThrow(() -> invalid(field, "must be a photo uploaded with purpose " + purpose));
        return f.id();
    }

    private void saveShop(Shop shop) {
        try {
            shops.saveAndFlush(shop);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw shopChanged();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Membership> find(UUID userId) {
        return members.findByUserId(userId).map(m -> new Membership(m.getShopId(), m.getRole().name(),
                shops.findById(m.getShopId()).orElseThrow().getType().name()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Member> members(UUID shopId) {
        return members.findByShopIdOrderByCreatedAtAscIdAsc(shopId).stream()
                .sorted(Comparator.comparing((ShopMember m) -> m.getRole() != ShopRole.OWNER))   // owner first
                .map(m -> new Member(m.getUserId(), m.getRole().name()))
                .toList();
    }

    @Override
    @Transactional
    public void addMember(UUID shopId, UUID userId, String role) {
        ShopRole r = ShopRole.valueOf(role);
        if (r == ShopRole.OWNER) {
            throw new IllegalArgumentException("A shop has exactly one owner; it can't be added as a member");
        }
        if (members.existsByUserId(userId)) {
            throw alreadyInAShop();
        }
        try {
            members.saveAndFlush(new ShopMember(shopId, userId, r, now()));
        } catch (DataIntegrityViolationException e) {
            if (violated(e, ONE_SHOP_PER_PERSON)) {   // added to two shops at the same moment
                throw alreadyInAShop();
            }
            throw e;
        }
    }

    @Override
    @Transactional
    public void removeMember(UUID shopId, UUID userId) {
        members.findByShopIdAndUserId(shopId, userId).ifPresent(m -> {
            if (m.getRole() == ShopRole.OWNER) {
                throw new IllegalStateException("The owner can't be removed from the shop");
            }
            members.delete(m);
            members.flush();
        });
    }

    /** Address, area, city, pincode and the map pin: what the field team visits and checks. */
    private static boolean sameLocation(ShopDetails a, ShopDetails b) {
        return a.addressLine().equals(b.addressLine()) && a.area().equals(b.area()) && a.city().equals(b.city())
                && a.pincode().equals(b.pincode()) && a.latitude().compareTo(b.latitude()) == 0
                && a.longitude().compareTo(b.longitude()) == 0;
    }

    /** Cut to microseconds, what the database stores, so the response right after saving matches a later GET. */
    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private ShopMember membership(UUID userId) {
        return members.findByUserId(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_SHOP",
                "You haven't registered or joined a shop yet."));
    }

    /** Stored to 6 decimals (about 10 cm), the column's precision. */
    static BigDecimal coordinate(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    /** Optional. Blank → none. Otherwise must be a valid-looking GSTIN; stored upper-case without spaces. */
    static String gstin(String value) {
        String v = compact(value);
        if (v == null) {
            return null;
        }
        if (!GSTIN.matcher(v).matches() || !validGstinState(v) || gstinCheckChar(v) != v.charAt(14)) {
            throw invalid("gstin", "is not a valid GSTIN (15 characters, e.g. 27AAPFU0939F1ZV); check for a typo");
        }
        return v;
    }

    /** Optional. Blank → none. Otherwise UDYAM-XX-00-0000000; stored upper-case. */
    static String udyam(String value) {
        String v = compact(value);
        if (v == null) {
            return null;
        }
        if (!UDYAM.matcher(v).matches()) {
            throw invalid("udyamNumber", "is not a valid Udyam number (e.g. UDYAM-TS-02-0012345)");
        }
        return v;
    }

    /** State codes 01-38, plus 97 (other territory) and 99 (centre jurisdiction). */
    private static boolean validGstinState(String gstin) {
        int state = Integer.parseInt(gstin.substring(0, 2));
        return (state >= 1 && state <= 38) || state == 97 || state == 99;
    }

    /**
     * The 15th character of a GSTIN is calculated from the first 14 (the government's "Luhn mod 36" rule).
     * A typo in any one character gives a different answer, so a mistyped GSTIN is caught here.
     * Example: for 27AAPFU0939F1ZV the first 14 characters give 'V', which matches the 15th, so it is valid.
     */
    static char gstinCheckChar(String gstin) {
        int sum = 0;
        for (int position = 0; position < 14; position++) {
            int value = GSTIN_CHARS.indexOf(gstin.charAt(position));  // '0'-'9' are 0-9, 'A'-'Z' are 10-35
            int weight = (position % 2 == 0) ? 1 : 2;                   // 1st x1, 2nd x2, 3rd x1, 4th x2, ...
            sum += addDigitsInBase36(value * weight);
        }
        int checkValue = (36 - sum % 36) % 36;   // what must be added to sum to reach a multiple of 36
        return GSTIN_CHARS.charAt(checkValue);
    }

    /** Adds the two "digits" of a number written in base 36, e.g. 70 = 1 x 36 + 34, so it becomes 1 + 34 = 35. */
    private static int addDigitsInBase36(int number) {
        return number / 36 + number % 36;
    }

    private static String compact(String value) {
        if (value == null) {
            return null;
        }
        String v = value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }

    private static ApiException invalid(String field, String problem) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                Map.of(field, problem));
    }

    private static ApiException alreadyInAShop() {
        return new ApiException(HttpStatus.CONFLICT, "ALREADY_IN_A_SHOP",
                "This person already works in a shop. One person can work in one shop only.");
    }

    private static ApiException alreadyHasShop() {
        return new ApiException(HttpStatus.CONFLICT, "ALREADY_HAS_SHOP", "You already have a shop.");
    }

    private static ApiException shopChanged() {
        return new ApiException(HttpStatus.CONFLICT, "SHOP_CHANGED",
                "Someone else saved changes to this shop. Reload and try again.");
    }
}
