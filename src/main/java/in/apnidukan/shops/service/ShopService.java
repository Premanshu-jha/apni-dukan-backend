package in.apnidukan.shops.service;

import in.apnidukan.common.context.ShopMemberships;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.shops.api.ShopRequests.CreateShop;
import in.apnidukan.shops.api.ShopRequests.UpdateShop;
import in.apnidukan.shops.api.ShopResponse;
import in.apnidukan.shops.domain.Shop;
import in.apnidukan.shops.domain.ShopDetails;
import in.apnidukan.shops.domain.ShopMember;
import in.apnidukan.shops.domain.ShopRole;
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
import java.util.Locale;
import java.text.Normalizer;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Shop registration (step 1) and My shop. The person always comes from the sign-in token; the shop is looked up
 * from their membership in the database, never taken from the request.
 */
@Service
public class ShopService implements ShopMemberships {

    /** 2-digit state code, PAN (5 letters, 4 digits, 1 letter), entity number, 'Z', check character. */
    private static final Pattern GSTIN = Pattern.compile("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]");
    private static final String GSTIN_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    /** Any kind of space, in any script (Java's \\s alone only knows ASCII spaces). */
    private static final Pattern SPACES = Pattern.compile("[\\s\\p{Z}]+");
    /** Unique key that allows one shop per person (named in V3). */
    static final String ONE_SHOP_PER_PERSON = "ux_shop_members_user";
    private static final Pattern UDYAM = Pattern.compile("UDYAM-[A-Z]{2}-[0-9]{2}-[0-9]{7}");

    /** A text field's name (for error messages) and its allowed length after cleaning; max is the column size in V3. */
    record TextField(String name, int minLength, int maxLength) {
    }

    static final TextField NAME = new TextField("name", 2, 100);
    static final TextField OWNER_NAME = new TextField("ownerName", 2, 100);
    static final TextField ADDRESS_LINE = new TextField("addressLine", 5, 200);
    static final TextField AREA = new TextField("area", 2, 100);
    static final TextField CITY = new TextField("city", 2, 60);

    private final ShopRepository shops;
    private final ShopMemberRepository members;
    private final Clock clock;

    public ShopService(ShopRepository shops, ShopMemberRepository members, Clock clock) {
        this.shops = shops;
        this.members = members;
        this.clock = clock;
    }

    /**
     * Registration step 1: creates the shop as DRAFT with the caller as its OWNER.
     * The caller's current access token doesn't carry the shop yet: the app calls POST /auth/refresh next.
     */
    @Transactional
    public ShopResponse create(UUID userId, CreateShop in) {
        if (members.existsByUserId(userId)) {
            throw alreadyHasShop();
        }
        Instant now = now();
        ShopDetails details = new ShopDetails(text(NAME, in.name()), text(OWNER_NAME, in.ownerName()),
                text(ADDRESS_LINE, in.addressLine()), text(AREA, in.area()), text(CITY, in.city()),
                in.pincode(), coordinate(in.latitude()), coordinate(in.longitude()),
                gstin(in.gstin()), udyam(in.udyamNumber()));
        Shop shop = new Shop(shops.nextShopCode(), details, now);
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
        return ShopResponse.from(shops.findById(member.getShopId()).orElseThrow(), member.getRole());
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
                in.name() == null ? old.name() : text(NAME, in.name()),
                in.ownerName() == null ? old.ownerName() : text(OWNER_NAME, in.ownerName()),
                in.addressLine() == null ? old.addressLine() : text(ADDRESS_LINE, in.addressLine()),
                in.area() == null ? old.area() : text(AREA, in.area()),
                in.city() == null ? old.city() : text(CITY, in.city()),
                in.pincode() == null ? old.pincode() : in.pincode(),
                in.latitude() == null ? old.latitude() : coordinate(in.latitude()),
                in.longitude() == null ? old.longitude() : coordinate(in.longitude()),
                in.gstin() == null ? old.gstin() : gstin(in.gstin()),
                in.udyamNumber() == null ? old.udyamNumber() : udyam(in.udyamNumber()));
        if (updated.equals(old)) {
            return ShopResponse.from(shop, member.getRole());
        }
        shop.apply(updated, now());
        try {
            shops.saveAndFlush(shop);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw shopChanged();
        }
        return ShopResponse.from(shop, member.getRole());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Membership> find(UUID userId) {
        return members.findByUserId(userId).map(m -> new Membership(m.getShopId(), m.getRole().name()));
    }

    /** Cut to microseconds, what the database stores, so the response right after saving matches a later GET. */
    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private ShopMember membership(UUID userId) {
        return members.findByUserId(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_SHOP",
                "You haven't registered or joined a shop yet."));
    }

    /**
     * Cleans a name or address before saving, or refuses it with a 400.
     * Example: "  Sharma   Store " becomes "Sharma Store".
     */
    static String text(TextField field, String value) {
        String normal = toNormalLetters(value);
        refuseHiddenCharacters(field.name(), normal);   // must run before collapseSpaces (see refuseHiddenCharacters)
        String cleaned = collapseSpaces(normal);
        requireTwoLettersOrDigits(field.name(), cleaned);
        requireLength(field, cleaned);
        return cleaned;
    }

    /** Look-alike letters become normal ones: full-width "Sharma" from some keyboards becomes plain "Sharma". */
    private static String toNormalLetters(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC);
    }

    /**
     * Refuses invisible characters, such as the right-to-left override that can make a name display differently
     * from what was typed. Tab and newline are allowed: they come from pasted addresses and collapseSpaces tidies them.
     * Runs before collapseSpaces, because that would turn line separators into spaces and hide them from this check.
     */
    private static void refuseHiddenCharacters(String field, String value) {
        boolean found = value.codePoints().anyMatch(ShopService::isHidden);
        if (found) {
            throw invalid(field, "contains invisible or special characters");
        }
    }

    private static boolean isHidden(int c) {
        if (c == '\t' || c == '\n' || c == '\r') {
            return false;
        }
        int type = Character.getType(c);
        return type == Character.CONTROL                 // control codes, e.g. the null character
                || type == Character.FORMAT              // zero-width space, right-to-left override
                || type == Character.PRIVATE_USE         // characters with no agreed meaning
                || type == Character.UNASSIGNED          // not a real character
                || type == Character.SURROGATE           // half of a broken emoji
                || type == Character.LINE_SEPARATOR      // Unicode line separator
                || type == Character.PARAGRAPH_SEPARATOR; // Unicode paragraph separator
    }

    /** Any run of spaces, tabs or newlines becomes one space, and the ends are trimmed. */
    private static String collapseSpaces(String value) {
        return SPACES.matcher(value).replaceAll(" ").strip();
    }

    /**
     * Checks the length of the cleaned value, which is what gets saved. @Size in the request only sees what was sent:
     * collapsing spaces can make it shorter, and toNormalLetters can make it longer (one character can become 3 or
     * more), which would otherwise reach the database too long and fail with a 500. Counted like Postgres counts.
     */
    private static void requireLength(TextField field, String value) {
        int length = value.codePointCount(0, value.length());
        if (length < field.minLength() || length > field.maxLength()) {
            throw invalid(field.name(), "must be " + field.minLength() + " to " + field.maxLength() + " characters");
        }
    }

    /** Refuses values like "!!" or "--". Letters in any script count, so Hindi names pass. */
    private static void requireTwoLettersOrDigits(String field, String value) {
        long count = value.codePoints().filter(Character::isLetterOrDigit).count();
        if (count < 2) {
            throw invalid(field, "must have at least 2 letters or digits");
        }
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

    /** Whether the database refused the write because of this named constraint. */
    private static boolean violated(DataIntegrityViolationException e, String constraint) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException c
                    && constraint.equalsIgnoreCase(c.getConstraintName())) {
                return true;
            }
        }
        return false;
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

    private static ApiException alreadyHasShop() {
        return new ApiException(HttpStatus.CONFLICT, "ALREADY_HAS_SHOP", "You already have a shop.");
    }

    private static ApiException shopChanged() {
        return new ApiException(HttpStatus.CONFLICT, "SHOP_CHANGED",
                "Someone else saved changes to this shop. Reload and try again.");
    }
}
