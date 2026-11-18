package in.apnidukan.kirana.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.kirana.api.OrderRequests.CatalogueEdit;
import in.apnidukan.kirana.api.OrderRequests.FssaiDecision;
import in.apnidukan.kirana.api.OrderRequests.RejectPart;
import in.apnidukan.kirana.api.OrderResponses.AdminPayoutView;
import in.apnidukan.kirana.api.OrderResponses.CataloguePage;
import in.apnidukan.kirana.api.OrderResponses.CatalogueView;
import in.apnidukan.kirana.api.OrderResponses.FailedRefundView;
import in.apnidukan.kirana.api.OrderResponses.FssaiCheckView;
import in.apnidukan.kirana.api.OrderResponses.FssaiView;
import in.apnidukan.kirana.api.OrderResponses.OpenOrderView;
import in.apnidukan.kirana.api.OrderResponses.UpiRecheckView;
import in.apnidukan.kirana.api.StockRequests.ReviewDecision;
import in.apnidukan.kirana.api.StockResponses.ImportResult;
import in.apnidukan.kirana.api.StockResponses.ReviewView;
import in.apnidukan.kirana.api.StockResponses.ScanStatsView;
import in.apnidukan.kirana.service.CatalogueAdminService;
import in.apnidukan.kirana.service.CatalogueService;
import in.apnidukan.kirana.service.ScanService;
import in.apnidukan.kirana.service.OnlineService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * The admin website's kirana pages: the shared catalogue (super admins), FSSAI decisions (super admins), UPI IDs to
 * re-check (the field team), failed refunds and payouts (super admins). See CatalogueService and OnlineService.
 */
@RestController
@RequestMapping("/api/v1/admin/kirana")
public class KiranaAdminController {

    private final CatalogueService catalogue;
    private final OnlineService online;
    private final CatalogueAdminService catalogueAdmin;
    private final ScanService scans;
    private final CurrentUserProvider currentUser;

    public KiranaAdminController(CatalogueService catalogue, OnlineService online, CatalogueAdminService catalogueAdmin,
                                 ScanService scans, CurrentUserProvider currentUser) {
        this.catalogue = catalogue;
        this.online = online;
        this.catalogueAdmin = catalogueAdmin;
        this.scans = scans;
        this.currentUser = currentUser;
    }

    /** Barcodes for the team to decide (shops disagree, a trusted link corrected, an import clash), oldest first. */
    @GetMapping("/catalogue/reviews")
    public List<ReviewView> reviews() {
        return catalogueAdmin.reviews(me());
    }

    /** Which item the barcode is: trusted for every shop from now on. 409 REVIEW_DONE. */
    @PostMapping("/catalogue/reviews/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decideReview(@PathVariable UUID id, @Valid @RequestBody ReviewDecision body) {
        catalogueAdmin.decide(me(), id, body.itemId());
    }

    /**
     * Imports a distributor's product master: a CSV file (multipart field "file", at most 2 MB), a header row naming at
     * least barcode and name. Bad rows are listed, the rest imported.
     */
    @PostMapping(value = "/catalogue/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult importMaster(@RequestPart("file") MultipartFile file) {
        return catalogueAdmin.importMaster(me(), file);
    }

    /** Scanning per day over every shop (unknown-barcode rate, one-tap links), the last {@code days} (1–90). */
    @GetMapping("/scans/stats")
    public List<ScanStatsView> scanStats(@RequestParam(defaultValue = "30") int days) {
        return scans.stats(me(), days);
    }

    @GetMapping("/catalogue")
    public CataloguePage catalogue(@RequestParam(required = false) String q,
                                   @RequestParam(required = false) String cursor) {
        return catalogue.list(me(), q, cursor);
    }

    @PatchMapping("/catalogue/{id}")
    public CatalogueView editCatalogue(@PathVariable UUID id, @Valid @RequestBody CatalogueEdit body) {
        return catalogue.edit(me(), id, body);
    }

    @PostMapping("/shops/{shopId}/fssai")
    public FssaiView decideFssai(@PathVariable UUID shopId, @Valid @RequestBody FssaiDecision body) {
        return online.decideFssai(me(), shopId, body);
    }

    /** Certificates for a person: status CHECKING (default: the AI couldn't decide, or asked again) or REJECTED. */
    @GetMapping("/fssai")
    public List<FssaiCheckView> fssaiChecks(@RequestParam(required = false) String status) {
        return online.fssaiChecks(me(), status);
    }

    /** Paid orders still open 2 hours after they were placed. */
    @GetMapping("/orders/open")
    public List<OpenOrderView> openOrders() {
        return online.openOrders(me());
    }

    /** Super admins: cancels a paid order no one can finish, refunded in full ({reason}). */
    @PostMapping("/orders/{orderId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelOrder(@PathVariable UUID orderId, @Valid @RequestBody RejectPart body) {
        online.cancelOrder(me(), orderId, body.reason());
    }

    @GetMapping("/upi-checks")
    public List<UpiRecheckView> upiChecks() {
        return online.upiRechecks(me());
    }

    @PostMapping("/shops/{shopId}/upi-check")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void upiChecked(@PathVariable UUID shopId) {
        online.upiChecked(me(), shopId);
    }

    @GetMapping("/refunds/failed")
    public List<FailedRefundView> failedRefunds() {
        return online.failedRefunds(me());
    }

    @GetMapping("/payouts")
    public List<AdminPayoutView> payouts(@RequestParam(required = false) String status) {
        return online.payouts(me(), status);
    }

    @PostMapping("/payouts/{id}/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retryPayout(@PathVariable UUID id) {
        online.retryPayout(me(), id);
    }

    private UUID me() {
        return currentUser.get().userId();
    }
}
