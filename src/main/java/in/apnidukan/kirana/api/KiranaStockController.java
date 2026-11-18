package in.apnidukan.kirana.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.kirana.api.KiranaResponses.BillResult;
import in.apnidukan.kirana.api.StockRequests.AddPurchase;
import in.apnidukan.kirana.api.StockRequests.ConfirmPaper;
import in.apnidukan.kirana.api.StockRequests.LabelPhoto;
import in.apnidukan.kirana.api.StockRequests.LineDecision;
import in.apnidukan.kirana.api.StockRequests.CorrectLine;
import in.apnidukan.kirana.api.StockRequests.LineChange;
import in.apnidukan.kirana.api.StockRequests.LinesChange;
import in.apnidukan.kirana.api.StockRequests.NewLine;
import in.apnidukan.kirana.api.StockRequests.PurchaseHeader;
import in.apnidukan.kirana.api.StockRequests.LinkUnknown;
import in.apnidukan.kirana.api.StockRequests.NewRead;
import in.apnidukan.kirana.api.StockRequests.ReadKind;
import in.apnidukan.kirana.api.StockRequests.ScanBatch;
import in.apnidukan.kirana.api.StockResponses.CounterSuggestions;
import in.apnidukan.kirana.api.StockResponses.LinkResult;
import in.apnidukan.kirana.api.StockResponses.ListLinePage;
import in.apnidukan.kirana.api.StockResponses.PhotoNeededPage;
import in.apnidukan.kirana.api.StockResponses.ListLineView;
import in.apnidukan.kirana.api.StockResponses.PaperDraft;
import in.apnidukan.kirana.api.StockResponses.PurchasePage;
import in.apnidukan.kirana.api.StockResponses.PurchaseView;
import in.apnidukan.kirana.api.StockResponses.ReadPage;
import in.apnidukan.kirana.api.StockResponses.ReadView;
import in.apnidukan.kirana.api.StockResponses.ScanBatchResult;
import in.apnidukan.kirana.api.StockResponses.SetupView;
import in.apnidukan.kirana.api.StockResponses.UnknownPage;
import in.apnidukan.kirana.api.StockResponses.UnknownView;
import in.apnidukan.kirana.service.PaperBillService;
import in.apnidukan.kirana.service.PhotoReads;
import in.apnidukan.kirana.service.ProductSetupService;
import in.apnidukan.kirana.service.PurchaseService;
import in.apnidukan.kirana.service.ScanService;
import in.apnidukan.kirana.service.SuggestionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Stock from photos and scans for the partner app (the kirana's owner and helpers; round 3): distributors' bills and
 * paper bills read by AI, scanning while shelving, unknown barcodes ("Needs a tap"), and counter suggestions. See the
 * services for every check.
 */
@RestController
@RequestMapping("/api/v1/kirana")
public class KiranaStockController {

    private final PhotoReads photoReads;
    private final PurchaseService purchases;
    private final ScanService scans;
    private final PaperBillService paperBills;
    private final SuggestionService suggestions;
    private final ProductSetupService setup;
    private final CurrentUserProvider currentUser;

    public KiranaStockController(PhotoReads photoReads, PurchaseService purchases, ScanService scans,
                                 PaperBillService paperBills, SuggestionService suggestions,
                                 ProductSetupService setup, CurrentUserProvider currentUser) {
        this.setup = setup;
        this.photoReads = photoReads;
        this.purchases = purchases;
        this.scans = scans;
        this.paperBills = paperBills;
        this.suggestions = suggestions;
        this.currentUser = currentUser;
    }

    // ---------- photos to read ----------

    /** A bill's photo to read (uploaded first as BILL): 201, READING; poll GET /reads/{id}. */
    @PostMapping("/reads")
    @ResponseStatus(HttpStatus.CREATED)
    public ReadView upload(@Valid @RequestBody NewRead body) {
        return photoReads.upload(me(), body.kind().name(), body.clientRef(), body.fileIds());
    }

    /** The shop's photos of one kind, newest first, 50 at a time. */
    @GetMapping("/reads")
    public ReadPage reads(@RequestParam ReadKind kind, @RequestParam(required = false) String cursor) {
        return photoReads.list(me(), kind.name(), cursor);
    }

    @GetMapping("/reads/{id}")
    public ReadView read(@PathVariable UUID id) {
        return photoReads.get(me(), id);
    }

    /** Throws a photo away. 409 READ_USED once it became a bill or was added to stock. */
    @PostMapping("/reads/{id}/discard")
    public ReadView discardRead(@PathVariable UUID id) {
        return photoReads.discard(me(), id);
    }

    // ---------- product setup (after Submit; the field visit is booked when it is finished) ----------

    /** Where the shop's product setup stands. */
    @GetMapping("/setup")
    public SetupView setup() {
        return setup.get(me());
    }

    /** The shop's products still needing a picture, 20 at a time, each with what its photo must show. */
    @GetMapping("/setup/photos")
    public PhotoNeededPage setupPhotos(@RequestParam(required = false) String cursor) {
        return setup.photos(me(), cursor);
    }

    /** Lines of the shop's list: to check (status NEW), SKIPPED or ADDED, 20 at a time. */
    @GetMapping("/setup/lines")
    public ListLinePage setupLines(@RequestParam(required = false) String status,
                                   @RequestParam(required = false) String cursor) {
        return setup.lines(me(), status, cursor);
    }

    /** Adds a line as a product with its count, skips it, or brings a skipped one back. */
    @PostMapping("/setup/lines/{id}")
    public ListLineView decideLine(@PathVariable UUID id, @Valid @RequestBody LineDecision body) {
        return setup.decide(me(), id, body);
    }

    /** "I've added all my products" (owner): books the field visit. */
    @PostMapping("/setup/finish")
    public SetupView finishSetup() {
        return setup.finish(me());
    }

    // ---------- distributors' bills ----------

    @GetMapping("/purchases")
    public PurchasePage purchases(@RequestParam(required = false) String cursor) {
        return purchases.list(me(), cursor);
    }

    @GetMapping("/purchases/{id}")
    public PurchaseView purchase(@PathVariable UUID id) {
        return purchases.get(me(), id);
    }

    /** A line's product (or a new one), quantity, rate (owner) or skip. 409 PURCHASE_CHANGED / PURCHASE_DONE. */
    @PatchMapping("/purchases/{id}/lines/{lineNo}")
    public PurchaseView line(@PathVariable UUID id, @PathVariable int lineNo, @Valid @RequestBody LineChange body) {
        return purchases.setLines(me(), id, body.version(), List.of(new PurchaseService.LineEdit(lineNo,
                body.productId(), body.newProduct(), body.quantity(), body.ratePaise(), body.skip())));
    }

    /** Several lines at once (accepting suggestions down the bill), with one version. */
    @PatchMapping("/purchases/{id}/lines")
    public PurchaseView lines(@PathVariable UUID id, @Valid @RequestBody LinesChange body) {
        return purchases.setLines(me(), id, body.version(), body.lines().stream()
                .map(l -> new PurchaseService.LineEdit(l.lineNo(), l.productId(), l.newProduct(), l.quantity(),
                        l.ratePaise(), l.skip())).toList());
    }

    /** A line the reading missed. 409 TOO_MANY_LINES. */
    @PostMapping("/purchases/{id}/lines")
    public PurchaseView addLine(@PathVariable UUID id, @Valid @RequestBody NewLine body) {
        return purchases.addLine(me(), id, body.version(), body.wording(), body.quantity(), body.productId(),
                body.newProduct(), body.ratePaise());
    }

    /** The bill's distributor, number or date corrected (the draft). */
    @PatchMapping("/purchases/{id}")
    public PurchaseView header(@PathVariable UUID id, @Valid @RequestBody PurchaseHeader body) {
        return purchases.setHeader(me(), id, body.version(), body.distributor(), body.billNo(), body.billDate());
    }

    /** A line of an added bill was the wrong product (owner): its stock moves to the right one. */
    @PostMapping("/purchases/{id}/lines/{lineNo}/correct")
    public PurchaseView correct(@PathVariable UUID id, @PathVariable int lineNo,
                                @Valid @RequestBody CorrectLine body) {
        return purchases.correctLine(me(), id, lineNo, body.productId(), body.newProduct());
    }

    /** Adds the bill to stock. 409 LINES_TO_CHECK / BILL_ALREADY_ADDED / PURCHASE_DONE. */
    @PostMapping("/purchases/{id}/add")
    public PurchaseView add(@PathVariable UUID id, @Valid @RequestBody AddPurchase body) {
        return purchases.add(me(), id, body.version(), Boolean.TRUE.equals(body.addAgain()));
    }

    @PostMapping("/purchases/{id}/discard")
    public PurchaseView discardPurchase(@PathVariable UUID id) {
        return purchases.discard(me(), id);
    }

    // ---------- scanning while shelving ----------

    /** A batch of scans: each ADDED, TICKED, UNKNOWN or REJECTED. */
    @PostMapping("/scans")
    public ScanBatchResult scan(@Valid @RequestBody ScanBatch body) {
        return scans.scan(me(), body);
    }

    /**
     * "Needs a tap": barcodes no product has, newest first, 20 at a time, with likely products; dismissed=true: the ones
     * put away.
     */
    @GetMapping("/scans/unknown")
    public UnknownPage unknowns(@RequestParam(required = false) String cursor,
                                @RequestParam(defaultValue = "false") boolean dismissed) {
        return scans.unknowns(me(), dismissed, cursor);
    }

    /** A put-away barcode back to "Needs a tap". 409 UNKNOWN_DONE once linked. */
    @PostMapping("/scans/unknown/{id}/reopen")
    public UnknownView reopen(@PathVariable UUID id) {
        return scans.reopen(me(), id);
    }

    @GetMapping("/scans/unknown/{id}")
    public UnknownView unknown(@PathVariable UUID id) {
        return scans.unknown(me(), id);
    }

    /** A photo of the pack's front (uploaded first as PRODUCT_PHOTO), read when nobody knows the barcode. */
    @PostMapping("/scans/unknown/{id}/label")
    public UnknownView label(@PathVariable UUID id, @Valid @RequestBody LabelPhoto body) {
        return scans.label(me(), id, body);
    }

    /** Links it to a product (or a new one); its scans go into stock or tick the open bill. 409 UNKNOWN_DONE. */
    @PostMapping("/scans/unknown/{id}/link")
    public LinkResult link(@PathVariable UUID id, @Valid @RequestBody LinkUnknown body) {
        return scans.link(me(), id, body);
    }

    /** Not something the shop sells: off the list. */
    @PostMapping("/scans/unknown/{id}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismiss(@PathVariable UUID id) {
        scans.dismiss(me(), id);
    }

    // ---------- counter ----------

    /** What to offer at the counter: sells now, the khata customer's usual, often bought with what is on the bill. */
    @GetMapping("/suggestions")
    public CounterSuggestions suggestions(@RequestParam(required = false) UUID khataCustomerId,
                                          @RequestParam(required = false) List<UUID> with) {
        return suggestions.suggest(me(), khataCustomerId, with);
    }

    // ---------- paper bills ----------

    /** A paper bill's photo as read, its lines matched to products. */
    @GetMapping("/paper-bills/{readId}")
    public PaperDraft paperBill(@PathVariable UUID readId) {
        return paperBills.draft(me(), readId);
    }

    /** Confirms it as a bill (as POST /bills), with the day on the paper. */
    @PostMapping("/paper-bills/{readId}/confirm")
    public BillResult confirmPaperBill(@PathVariable UUID readId, @Valid @RequestBody ConfirmPaper body) {
        return paperBills.confirm(me(), readId, body);
    }

    private UUID me() {
        return currentUser.get().userId();
    }
}
