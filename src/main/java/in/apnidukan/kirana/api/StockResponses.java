package in.apnidukan.kirana.api;

import in.apnidukan.kirana.api.KiranaResponses.ProductView;
import in.apnidukan.kirana.domain.Unit;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What the kirana stock endpoints return (round 3: photos of distributors' bills and paper bills, scanning while
 * shelving, unknown barcodes, owner corrections, counter suggestions, the shared catalogue's review list). Money in
 * paise; quantities as plain numbers; lists a page at a time ({@code next}: the following page's token, null on the
 * last).
 */
public final class StockResponses {

    private StockResponses() {
    }

    // ---------- photos read by AI ----------

    /**
     * A photo sent to be read.
     * @param kind       PURCHASE (a distributor's bill), PAPER_BILL (the shop's own paper bill) or LABEL (a pack)
     * @param status     READING, READ, FAILED (failure says why, in words for the shop), DONE (used) or DISCARDED
     * @param purchaseId for PURCHASE once read: the draft to check
     * @param billId     for PAPER_BILL once confirmed: the bill it became
     */
    public record ReadView(UUID id, String kind, String status, List<UUID> fileIds, String failure, UUID purchaseId,
                           UUID billId, Instant createdAt, Instant updatedAt, boolean recount, String failureCode) {

        public ReadView {
            fileIds = List.copyOf(fileIds);
        }
    }

    public record ReadPage(List<ReadView> items, String next) {

        public ReadPage {
            items = List.copyOf(items);
        }
    }

    /**
     * A product the text read may be: one of the shop's ({@code productId}), or a shared catalogue item the shop doesn't
     * have yet ({@code productId} null: adding it makes the product).
     * @param score 0 to 1, how alike the names are
     */
    public record SuggestionView(UUID productId, UUID itemId, String name, String brand, String size, Long pricePaise,
                                 double score) {
    }

    // ---------- distributors' bills ----------

    /**
     * A line of a distributor's bill.
     * @param quantity    pieces (or kg) to add, as read or corrected
     * @param ratePaise   paid per piece (or kg): the owner's only (null for a helper), as is amountPaise
     * @param pick        NONE (no product yet), SURE (one product clearly matched), LIKELY, REMEMBERED (this
     *                    distributor's wording was matched before), PICKED (chosen by the shop) or SKIPPED
     * @param scanned     pieces of its product scanned while shelving with this bill (a check: the bill adds the
     *                    stock); null when none
     * @param shortBy     scanned, but fewer than the bill says: how many fewer (a short delivery, or not all scanned)
     * @param suggestions for a line without a product: up to 3 likely products, best first
     * @param sure        the first suggestion is a clear match (one tap)
     * @param toCheck     still needs the shop: no product or no quantity, and not skipped
     */
    public record LineView(int lineNo, String wording, String brand, String name, String size, BigDecimal quantity,
                           Long ratePaise, Long mrpPaise, Long amountPaise, UUID productId, String productName,
                           Unit unit, String pick, BigDecimal scanned, BigDecimal shortBy,
                           List<SuggestionView> suggestions, boolean sure, boolean toCheck) {

        public LineView {
            suggestions = List.copyOf(suggestions);
        }
    }

    /** A product scanned while shelving the bill though on none of its lines: added with the bill. */
    public record HeldView(UUID productId, String name, Unit unit, BigDecimal quantity) {
    }

    /** @param status DRAFT, ADDED (to stock) or DISCARDED */
    public record PurchaseSummary(UUID id, String status, String distributor, String billNo, LocalDate billDate,
                                  Long totalPaise, int lines, int toCheck, Instant createdAt, Instant addedAt) {
    }

    public record PurchasePage(List<PurchaseSummary> items, String next) {

        public PurchasePage {
            items = List.copyOf(items);
        }
    }

    /**
     * @param version      send it back with each change (409 PURCHASE_CHANGED when someone changed it meanwhile)
     * @param totalPaise   the owner's only (null for a helper)
     * @param held         scanned with the bill, on none of its lines (added with it)
     * @param openUnknowns barcodes scanned with this bill still in "Needs a tap"
     * @param photoFileIds the bill's photo, page by page
     */
    public record PurchaseView(UUID id, UUID readId, String status, String distributor, String billNo,
                               LocalDate billDate, Long totalPaise, long version, List<LineView> lines,
                               List<HeldView> held, int openUnknowns, List<UUID> photoFileIds, String byName,
                               Instant createdAt, String addedByName, Instant addedAt) {

        public PurchaseView {
            lines = List.copyOf(lines);
            held = List.copyOf(held);
            photoFileIds = List.copyOf(photoFileIds);
        }
    }

    // ---------- scanning while shelving ----------

    /**
     * What one scanned code did.
     * @param outcome      ADDED (stock went up), TICKED (held with the bill being shelved, on its line {@code lineNo}),
     *                     HELD (held with the bill though on none of its lines: added with the bill), UNKNOWN (no
     *                     product has it: in "Needs a tap"), REJECTED (a misread or too many: scan again)
     * @param quantity     pieces (or kg) it meant: the pack times how often it was scanned
     * @param stockAfter   ADDED: the product's stock now
     * @param lineNo       TICKED: the bill's line
     * @param scannedTotal TICKED or HELD: all scanned of this product with this bill so far
     * @param lineQuantity TICKED: what the bill's line says came ("12 of 24")
     * @param unknownId    UNKNOWN: its entry in "Needs a tap"
     * @param catalogue    UNKNOWN: what the shared catalogue says it is, when it knows the barcode
     * @param needsLabel   UNKNOWN: nobody has read this pack yet: send a photo of its front (POST
     *                     /scans/unknown/{id}/label)
     * @param problem      REJECTED: why, in words for the shop
     */
    public record ScanResult(String code, String outcome, UUID productId, String name, Unit unit, BigDecimal quantity,
                             BigDecimal stockAfter, Integer lineNo, BigDecimal scannedTotal, BigDecimal lineQuantity,
                             UUID unknownId, SuggestionView catalogue, boolean needsLabel, String problem) {
    }

    /** @param repeated this batch was sent before (a retry): nothing was counted again */
    public record ScanBatchResult(List<ScanResult> results, boolean repeated) {

        public ScanBatchResult {
            results = List.copyOf(results);
        }
    }

    /**
     * What the pack's photo says (read by AI).
     * @param status READING, READ or FAILED (failure says why)
     */
    public record LabelView(String status, String name, String brand, String size, Long mrpPaise, String category,
                            String failure, String failureCode) {
    }

    /**
     * A barcode no product of the shop has ("Needs a tap").
     * @param scans       times scanned so far (linking adds them to stock, times the pack)
     * @param purchaseId  scanned while this bill was open (linking ticks its line instead of adding stock)
     * @param catalogue   what the shared catalogue says it is, if it knows the barcode
     * @param trusted     with catalogue: the link is trusted (a product master, the team, or two shops agree)
     * @param label       its pack's photo as read, if one was sent
     * @param needsLabel  nobody has read this pack yet: send a photo of its front
     * @param brandGuess  the brand other barcodes of the same company have, if known
     * @param suggestions up to 3 likely products (the shop's, else catalogue items), best first
     * @param sure        the first is a clear match: one tap links it
     * @param status      OPEN, or DISMISSED (put away: POST /scans/unknown/{id}/reopen brings it back)
     */
    public record UnknownView(UUID id, String code, BigDecimal scans, UUID purchaseId, SuggestionView catalogue,
                              boolean trusted, LabelView label, boolean needsLabel, String brandGuess,
                              List<SuggestionView> suggestions, boolean sure, String status, Instant createdAt,
                              Instant lastScannedAt) {

        public UnknownView {
            suggestions = List.copyOf(suggestions);
        }
    }

    public record UnknownPage(List<UnknownView> items, String next) {

        public UnknownPage {
            items = List.copyOf(items);
        }
    }

    /**
     * An unknown barcode linked to a product (every open entry of it settled).
     * @param quantity   pieces (or kg) its scans added to stock
     * @param held       pieces (or kg) held with the bill being shelved ({@code purchaseId}; its line {@code lineNo},
     *                   if the product is on one): the bill adds them
     * @param needsPhoto neither the product nor its catalogue item has a photo: ask for one of the front
     * @param note       anything the shop should know about the numbers (a loose label adds nothing; a pack rounded)
     */
    public record LinkResult(ProductView product, BigDecimal quantity, BigDecimal held, UUID purchaseId,
                             Integer lineNo, boolean needsPhoto, String note) {
    }

    // ---------- owner corrections ----------

    /**
     * A barcode moved to another product.
     * @param suggestedQuantity stock that came in by scanning this barcode into the first product (at most its stock):
     *                          offer to move it too (POST /products/{id}/move-stock); 0 when there is none
     */
    public record BarcodeMoved(ProductView from, ProductView to, BigDecimal suggestedQuantity) {
    }

    public record StockMoved(ProductView from, ProductView to) {
    }

    // ---------- counter suggestions ----------

    /**
     * A product to offer at the counter.
     * @param times how many bills it was on (in the period the list looks at)
     */
    public record SuggestedProduct(UUID id, String name, Unit unit, long pricePaise, BigDecimal stock,
                                   UUID photoFileId, String photoUrl, long times) {
    }

    /**
     * @param now      sold most around this hour in the last 14 days
     * @param usual    the khata customer's usual things (empty without one)
     * @param together often on the same bill as what is on the bill now (empty with nothing on it)
     */
    public record CounterSuggestions(List<SuggestedProduct> now, List<SuggestedProduct> usual,
                                     List<SuggestedProduct> together) {

        public CounterSuggestions {
            now = List.copyOf(now);
            usual = List.copyOf(usual);
            together = List.copyOf(together);
        }
    }

    // ---------- paper bills ----------

    /**
     * A line of a paper bill as read.
     * @param byWeight    the line is loose (kg) rather than pieces
     * @param pricePaise  the price written, each or per kg
     * @param productId   a clearly matching product (sure), else null: pick one from suggestions or bill it by name
     */
    public record PaperLineView(int lineNo, String wording, BigDecimal quantity, boolean byWeight, Long pricePaise,
                                Long amountPaise, UUID productId, String productName, Unit unit, boolean sure,
                                List<SuggestionView> suggestions) {

        public PaperLineView {
            suggestions = List.copyOf(suggestions);
        }
    }

    /**
     * A paper bill read into a draft (nothing is billed until it is confirmed).
     * @param failure     FAILED: why, in words for the shop
     * @param readDate    the day read on the paper, as read
     * @param writtenOn   that day when it can be used (today or up to 7 days back), else null with {@code dateProblem}
     * @param billId      once confirmed: the bill it became
     */
    public record PaperDraft(UUID readId, String status, String failure, LocalDate readDate, LocalDate writtenOn,
                             String dateProblem, Long totalPaise, List<PaperLineView> lines, List<UUID> photoFileIds,
                             UUID billId, String failureCode) {

        public PaperDraft {
            lines = List.copyOf(lines);
            photoFileIds = List.copyOf(photoFileIds);
        }
    }

    // ---------- the admin website ----------

    /** @param shops how many shops say the barcode is this item */
    public record ReviewItem(UUID id, String name, String brand, String size, Long mrpPaise, int shops) {
    }

    /**
     * A barcode for the team to decide.
     * @param kind  CONFLICT (shops disagree), CORRECTION (a trusted link corrected by a shop) or IMPORT (a product
     *              master's row clashes)
     * @param item  what the catalogue says now
     * @param other what the shop (or the product master) says
     */
    public record ReviewView(UUID id, String code, String kind, ReviewItem item, ReviewItem other, String shopName,
                             String note, Instant createdAt) {
    }

    public record ImportProblem(int line, String problem) {
    }

    /**
     * @param rows      rows read (the header left out)
     * @param added     barcodes new to the catalogue
     * @param confirmed barcodes it had (from shops), now trusted
     * @param reviews   rows that clash with what it had: on the review list
     * @param problems  rows left out and why (the first 200); problemCount: all of them
     */
    public record ImportResult(int rows, int added, int confirmed, int reviews, List<ImportProblem> problems,
                               int problemCount) {

        public ImportResult {
            problems = List.copyOf(problems);
        }
    }

    /**
     * A day of scanning over every shop.
     * @param unknownRate share of scans no product had (0 to 1)
     * @param sure        unknown barcodes linked with one tap (a clear match)
     * @param picked      linked by picking from a few
     * @param created     linked by adding a new product
     */
    public record ScanStatsView(LocalDate day, long shops, long scans, long unknown, double unknownRate, long sure,
                                long picked, long created) {
    }

    // ---------- product setup ----------

    /** A product of the shop, by id and name (to open it). */
    public record ProductRef(UUID id, String name) {
    }

    /**
     * A page of the shop's list, as the setup shows it.
     * @param fileId       its photo (or file)
     * @param status       READING, READ or FAILED (take it again: {@code failure} says why)
     * @param linesLeftOut products on the page past the 150 one read keeps: photograph them again
     * @param linesToCheck its lines still to add or skip; linesAdded, linesSkipped: done
     * @param recount      sent as a recount: the counts it clearly gave were taken without asking
     * @param linesAutoCounted of linesAdded, those counted automatically (each can be undone)
     */
    public record SetupPageView(UUID readId, int pageNo, UUID fileId, String status, String failure,
                                int linesLeftOut, long linesToCheck, long linesAdded, long linesSkipped,
                                Instant sentAt, boolean recount, long linesAutoCounted, String failureCode) {
    }

    /**
     * Where the shop's product setup stands (after Submit: its field visit is booked once it is finished).
     * @param readyAt        when the owner said all products are added; null until then
     * @param products       the shop's active products
     * @param uncounted      of them, never counted (added without a count): each needs a count before finishing
     * @param uncountedProducts up to 20 of them, by name
     * @param linesToCheck   lines of the list's pages still to add or skip
     * @param pagesReading   list pages still being read; pagesFailed: couldn't be read (take them again, or delete)
     * @param pagesLeft      list pages that can still be kept
     * @param pages          the list's pages kept, in the order sent
     * @param photosMissing  products with no picture (no checked photo of the shop's, none in the shared catalogue): each
     *                       needs a photo before finishing; of them photosChecking have one being checked,
     *                       photosRefused one to retake (GET /setup/photos lists them)
     * @param photosFinding  of photosMissing, those whose picture GS1 may still bring (nothing for the shop to do yet)
     * @param photosLeftToday photos the shop can still send to be checked today (300 a day)
     * @param nextStep       what the home screen offers next: LIST (pages reading, lines to check, a page to retake),
     *                       COUNT, PHOTOS (to take or retake), WAIT (photos being checked, or pictures GS1 is
     *                       looking for), FINISH, DONE; ADD when
     *                       there are no products yet
     * @param canFinish      every condition for "I've added all my products" holds
     * @param youCanFinish   canFinish, and the caller is the owner (only the owner says it); a helper sees false
     * @param beforeApproval finished, but products added since (or not counted) still need a count or a picture: the
     *                       field agent can't approve the shop until they have (nextStep COUNT, PHOTOS or WAIT)
     */
    public record SetupView(Instant readyAt, long products, long uncounted, List<ProductRef> uncountedProducts,
                            long linesToCheck, long linesAdded, long linesSkipped, long pagesReading, long pagesFailed,
                            int pagesLeft, List<SetupPageView> pages, long photosMissing, long photosChecking,
                            long photosRefused, long photosFinding, int photosLeftToday, String nextStep,
                            boolean canFinish,
                            boolean youCanFinish, boolean beforeApproval) {

        public SetupView {
            uncountedProducts = List.copyOf(uncountedProducts);
            pages = List.copyOf(pages);
        }
    }

    /**
     * A line of the shop's list, as read.
     * @param pageNo      its page's place among the pages kept (1 = first sent)
     * @param quantity    what the list says (a hint: counted again when added); null if not written
     * @param status      NEW (to check), ADDED, SKIPPED
     * @param productId   ADDED: the product it became or was counted on
     * @param counted     ADDED: what the shop counted
     * @param suggestions NEW: the shop's products or catalogue items it may be, best first
     * @param sure        the first suggestion is a clear match
     * @param autoCounted ADDED by a recount without asking: the product's count became the list's ("Undo" puts the
     *                    count back as it was, sales since kept, and the line comes back to check)
     * @param countBefore autoCounted: the product's count just before
     * @param productName ADDED: the product's name now (to show "Tata Salt 1 kg: 10 → 15")
     * @param stockNow    ADDED: the product's stock now
     * @param countPutBack only in the answer to UNDO on an auto-counted line: true when the product's count was put
     *                    back, false when it was left as it is (counted again since, its count withdrawn, removed, or
     *                    sold another way now); null otherwise
     */
    public record ListLineView(UUID id, UUID readId, Integer pageNo, int lineNo, String wording, String brand,
                               String name, String size, boolean byWeight, Long mrpPaise, BigDecimal quantity,
                               String status, UUID productId, BigDecimal counted, List<SuggestionView> suggestions,
                               boolean sure, boolean autoCounted, BigDecimal countBefore, String productName,
                               BigDecimal stockNow, Boolean countPutBack) {

        public ListLineView {
            suggestions = List.copyOf(suggestions);
        }

        /** As this, saying whether UNDO put the count back. */
        public ListLineView withCountPutBack(boolean putBack) {
            return new ListLineView(id, readId, pageNo, lineNo, wording, brand, name, size, byWeight, mrpPaise,
                    quantity, status, productId, counted, suggestions, sure, autoCounted, countBefore, productName,
                    stockNow, putBack);
        }
    }

    /**
     * A product still needing a picture, with what the camera screen tells the shopkeeper.
     * @param loose      sold by the kg: a photo of the goods in their sack or bin (no label)
     * @param sizeToShow the size the photo must show ("250 ml"), from the product's name; null when it has none
     * @param photoCheck CHECKING, REFUSED or NEED_SIZE (a photo of the side with the size: sent alone, the front
     *                   waiting is kept); null: no photo sent yet
     * @param code       why it was refused, as a code to word in the shop's language (PhotoRules); reason: in English;
     *                   seen: what the photo showed (another product, another size)
     * @param newPhotoUrl the photo being checked or refused
     * @param findingPicture GS1 may still bring its official picture (listed last): "Finding the company's picture…
     *                   you can photograph it anyway"
     */
    public record PhotoNeededView(UUID productId, String name, boolean loose, String sizeToShow, String photoCheck,
                                  String code, String reason, String seen, String newPhotoUrl,
                                  boolean findingPicture) {
    }

    public record PhotoNeededPage(List<PhotoNeededView> items, String next) {

        public PhotoNeededPage {
            items = List.copyOf(items);
        }
    }

    public record ListLinePage(List<ListLineView> items, String next) {

        public ListLinePage {
            items = List.copyOf(items);
        }
    }

    /**
     * A GS1 lookup for the admin website.
     * @param kind   CODE (a catalogue barcode's picture) or NAME (a product added without a barcode)
     * @param code   the barcode looked up (CODE) or found (NAME)
     * @param status WAITING, FOUND, NOT_FOUND (tried again weekly) or REFUSED (GS1 disagreed with the catalogue, the
     *               picture was refused, or the shop said "Not this product")
     * @param note   why, in English
     */
    public record Gs1LookupView(UUID id, String kind, String code, String status, String note, int tries, UUID shopId,
                                String shopName, UUID productId, String productName, String itemName,
                                Instant updatedAt) {
    }

    /**
     * A page of GS1 lookups at one status, newest change first.
     * @param counts       how many lookups stand at each status (all of them, not just this page)
     * @param switchedOn   GS1 DataKart's settings are filled in (else lookups only wait)
     */
    public record Gs1Page(java.util.Map<String, Long> counts, boolean switchedOn, List<Gs1LookupView> items,
                          String next) {

        public Gs1Page {
            counts = java.util.Map.copyOf(counts);
            items = List.copyOf(items);
        }
    }
}
