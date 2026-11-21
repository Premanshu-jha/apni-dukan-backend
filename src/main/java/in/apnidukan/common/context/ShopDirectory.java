package in.apnidukan.common.context;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Public facts about shops, for modules that show shops to customers (the salon booking pages). Implemented by the
 * shops module. Never includes private details such as GSTIN or the owner's phone.
 */
public interface ShopDirectory {

    Optional<ShopCard> find(UUID shopId);

    /**
     * Whether the shop is verified (approved now or before, and not a draft): its owner then confirms changes to its
     * checked details with a code to his phone first (EditUnlocks), so a phone left signed in can't change them.
     */
    boolean needsEditCode(UUID shopId);

    /**
     * Whether the shop is approved right now (live, not suspended, sent back or closed), its row held in shared mode to
     * the end of the transaction: an approval being saved is waited for. The caller holds a transaction.
     */
    boolean approvedNow(UUID shopId);

    /** find() for many shops in one query, by id (unknown ids are left out). */
    java.util.Map<UUID, ShopCard> findAll(java.util.Collection<UUID> shopIds);

    /**
     * Like find, and holds the shop's row in shared mode until the transaction ends (SELECT ... FOR SHARE). A
     * booking takes this: a change to the shop (a new address sending it back for a check) then waits for bookings
     * in progress to finish, and bookings after it see the new status and address.
     */
    Optional<ShopCard> findForBooking(UUID shopId);

    /**
     * Locks the shop's row against bookings until the transaction ends (SELECT ... FOR NO KEY UPDATE: it waits for
     * bookings in progress, which hold it FOR SHARE, and bookings after it wait for this one, but foreign-key checks
     * are not held up). A salon pausing online bookings takes it, so a booking either finished before the pause or
     * sees it.
     */
    void holdAgainstBookings(UUID shopId);

    /**
     * APPROVED shops of this type within radiusMetres of the point, nearest first, at most limit, after `after`
     * (the last card of the previous page; null for the first page). Found by the database's map index (PostGIS),
     * so it reads only shops near the point, however many there are.
     * @param type                KIRANA or SALON
     * @param skipPausedSalons    leave out salons that paused online bookings (salon_pauses) right now
     * @param search              only salons whose name or a service matches (see Search); null for all of them.
     *                            The same radius, order and pages either way: it only leaves shops out
     */
    List<ShopCard> nearby(String type, BigDecimal latitude, BigDecimal longitude, int radiusMetres, int limit,
                          Cursor after, boolean skipPausedSalons, Search search);

    /** Where a page of nearby shops ended: the last card's exact distance and id. */
    record Cursor(double distanceMetres, UUID id) {
    }

    /**
     * A customer's search in the salon list (made by the salon module from what they typed). A salon matches when
     * its name, or the name of an active service of it that a working barber does, matches the text (see
     * textMatches), or that service shows one of `serviceIcons`.
     * @param likePattern  "%text%" in lower case, with the text's own \, % and _ escaped by a backslash
     * @param typoWords    the text in lower case, its words split by single spaces, when small spelling slips are
     *                     forgiven (letters and spaces only, at least 4 of them), else null
     * @param serviceIcons the service pictures (ServiceIcon names) the text names; may be empty
     */
    record Search(String likePattern, String typoWords, List<String> serviceIcons) {

        /**
         * How close a word must be to a word of the name to count as a slip of it: pg_trgm's similarity of the two
         * words (shared three-letter pieces of all of theirs). "facal"/"facial" 0.44, "threding"/"threading" 0.58,
         * "mehndi"/"mehendi" 0.5, "rajoo"/"raju" 0.375; "shave"/"shape" 0.33, "raju"/"ravi" 0.25 stay apart.
         */
        public static final double TYPO_SIMILARITY = 0.35;

        /** The lower-cased name columns textMatches may be given: a shop's, and a service's in the two statements. */
        private static final Set<String> COLUMNS = Set.of("lower(s.name)", "lower(v.name)", "lower(o.name)");

        public Search {
            serviceIcons = List.copyOf(serviceIcons);
        }

        /**
         * SQL that is true when this lower-case text (a column expression, such as "lower(s.name)") matches: it
         * contains the whole text (LIKE likePattern), or, when slips are forgiven, every word of the text matches on
         * its own, in any order. A word matches when the name contains it ("aju"), or, from 4 letters, when it is a
         * slip of one of the name's words (the name split at spaces and punctuation: "Raju's", "Threading/Shaping";
         * Indian vowel signs stay in their word): about as long (1 letter more or fewer, 2 from 7 letters) and similar from
         * TYPO_SIMILARITY ("facal" finds "Facial", "rajoo" "Raju"). Word by word, so one common word shared is never
         * enough: "haircut" doesn't find "Raju Hair Salon" ("hair" is 3 letters shorter), nor "lakme salon" "Style
         * Salon", nor "head massage" "Body Massage". Its parameters are textArgs(), in that order. All in the same
         * statement: the words come as one text, split by the database.
         */
        public String textMatches(String lowerText) {
            // Only the columns written in this code, never anything a person sent: the statement is built from
            // constants and every value is a parameter (see spotbugs-exclude.xml)
            if (!COLUMNS.contains(lowerText)) {
                throw new IllegalArgumentException("not a known column: " + lowerText);
            }
            String like = lowerText + " LIKE ? ESCAPE '\\'";
            if (typoWords == null) {
                return "(" + like + ")";
            }
            return "(" + like + " OR NOT EXISTS (SELECT 1 FROM unnest(string_to_array(?, ' ')) AS qw(word)"
                    + " WHERE strpos(" + lowerText + ", qw.word) = 0 AND NOT (char_length(qw.word) >= 4"
                    + " AND EXISTS (SELECT 1 FROM unnest(regexp_split_to_array(" + lowerText + ", '[[:space:][:punct:]]+'))"
                    + " AS nw(word)"
                    + " WHERE abs(char_length(nw.word) - char_length(qw.word))"
                    + " <= CASE WHEN char_length(qw.word) >= 7 THEN 2 ELSE 1 END"
                    + " AND similarity(qw.word, nw.word) >= " + TYPO_SIMILARITY + "))))";
        }

        /** The parameters of textMatches, in order. */
        public List<Object> textArgs() {
            return typoWords == null ? List.of(likePattern) : List.of(likePattern, typoWords);
        }
    }

    /**
     * @param status     DRAFT, PENDING_VISIT, APPROVED or SUSPENDED
     * @param type       KIRANA or SALON
     * @param shopPhotoFileId the shop's photo, or null
     * @param distanceKm     from the point asked about in nearby(), rounded to 0.1 km; null from find()
     * @param distanceMetres the exact distance, for the next page's Cursor; null from find()
     */
    record ShopCard(UUID id, String name, String addressLine, String area, String city, String pincode,
                    BigDecimal latitude, BigDecimal longitude, String status, String type, UUID shopPhotoFileId,
                    Double distanceKm, Double distanceMetres) {
    }

    /**
     * Whether a shop still points at this file (a photo, the video, its registration document): the kirana module
     * then keeps it when its FSSAI certificate lets go of it (uploads of the same bytes share one file).
     */
    boolean usesFile(UUID fileId);
}
