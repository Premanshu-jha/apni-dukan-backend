package in.apnidukan.shops.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.shops.api.AdminShopDetail.TimelineEntry;
import in.apnidukan.shops.api.AdminShopDetail.TimelinePage;
import in.apnidukan.shops.domain.ReviewGroup;
import in.apnidukan.shops.domain.ShopType;
import in.apnidukan.shops.domain.TimelineCursor;
import in.apnidukan.shops.domain.VisitIssue;
import in.apnidukan.shops.service.ShopAdminService;
import in.apnidukan.shops.service.ShopAssignments;
import in.apnidukan.shops.service.ShopAssignments.Moved;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The admin website's shop pages (field team: admins and the super admin; anyone else gets 403). Each decision
 * carries the version the admin saw (409 SHOP_CHANGED if the owner changed something since) and tells the owner.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class ShopAdminController {

    private final ShopAdminService admin;
    private final ShopAssignments assignments;
    private final CurrentUserProvider currentUser;

    public ShopAdminController(ShopAdminService admin, ShopAssignments assignments, CurrentUserProvider currentUser) {
        this.admin = admin;
        this.assignments = assignments;
        this.currentUser = currentUser;
    }

    /** How many kiranas and salons are in each group (waiting for a first visit, re-check, sent back, live, suspended). */
    @GetMapping("/dashboard")
    public Map<ShopType, Map<ReviewGroup, Long>> dashboard() {
        return admin.dashboard(caller());
    }

    /**
     * A group's shops, longest waiting first, 50 a page, with `type` and `group` (both required, else 400
     * VALIDATION_FAILED naming the missing one); `q` searches the shop number, name, owner's name and owner's phone
     * (at least 3 letters, unless it is a shop number: else 400 VALIDATION_FAILED). The next page: `afterSince` and
     * `afterId` = the last shop's statusSince and id (both or neither, else 400 VALIDATION_FAILED); fewer than 50
     * means it was the last page. The older form `?status=` is gone (no app used it). A field agent gets only the
     * businesses assigned to them. A super admin may add `assignedTo` (one agent's) or `unassigned=true` (waiting
     * with no agent), not both (400 VALIDATION_FAILED).
     */
    @GetMapping("/shops")
    public List<AdminShopResponse> list(@RequestParam(required = false) ShopType type,
                                        @RequestParam(required = false) ReviewGroup group,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(required = false) Instant afterSince,
                                        @RequestParam(required = false) UUID afterId,
                                        @RequestParam(required = false) UUID assignedTo,
                                        @RequestParam(defaultValue = "false") boolean unassigned) {
        Map<String, Object> missing = new LinkedHashMap<>();
        if (type == null) {
            missing.put("type", "is required");
        }
        if (group == null) {
            missing.put("group", "is required");
        }
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid", missing);
        }
        if ((afterSince == null) != (afterId == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                    Map.of("afterId", "send afterSince and afterId together"));
        }
        if (assignedTo != null && unassigned) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                    Map.of("unassigned", "send assignedTo or unassigned, not both"));
        }
        if (unassigned && group != ReviewGroup.FIRST_VISIT && group != ReviewGroup.RECHECK) {
            // "Unassigned" means waiting for a visit with no agent; decided ones never need one
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                    Map.of("unassigned", "only for FIRST_VISIT and RECHECK"));
        }
        return admin.list(caller(), type, group, q, afterSince, afterId, assignedTo, unassigned);
    }

    /** One shop: what changed since it was last approved, a salon's size, photos and the first 50 timeline entries. */
    @GetMapping("/shops/{id}")
    public AdminShopDetail detail(@PathVariable UUID id) {
        return admin.detail(caller(), id);
    }

    /**
     * A shop's timeline, 50 entries a page, newest first: without values the newest page (the same as the shop
     * page's), with `afterAt` and `afterSeq` (from `timelineNext`, or this answer's `next`; both or neither, else 400
     * VALIDATION_FAILED) the page after. `next` is null on the last page.
     */
    @GetMapping("/shops/{id}/timeline")
    public TimelinePage timeline(@PathVariable UUID id, @RequestParam(required = false) Instant afterAt,
                                 @RequestParam(required = false) Long afterSeq) {
        if ((afterAt == null) != (afterSeq == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                    Map.of("afterSeq", "send afterAt and afterSeq together"));
        }
        return admin.timeline(caller(), id, afterAt == null ? null : new TimelineCursor(afterAt, afterSeq));
    }

    /**
     * Approve a shop after its visit (PENDING_VISIT: customers can find it from now on), or a live shop's re-check (it
     * stays live; its changes count as checked). Send {"version": n} from the page you checked; 409 SHOP_CHANGED if
     * the owner changed something since, 409 WRONG_STATUS when nothing waits for a check.
     */
    @PostMapping("/shops/{id}/approve")
    public AdminShopResponse approve(@PathVariable UUID id, @Valid @RequestBody Approve body) {
        return admin.approve(caller(), id, body.version(), body.note());
    }

    /** The visit didn't pass (PENDING_VISIT): back to DRAFT with the reason the owner sees. 409 WRONG_STATUS / SHOP_CHANGED. */
    @PostMapping("/shops/{id}/reject")
    public AdminShopResponse reject(@PathVariable UUID id, @Valid @RequestBody Decision body) {
        return admin.reject(caller(), id, body.version(), body.reason(), body.note(), body.checklist());
    }

    /**
     * A live shop's re-check needs a fix: it stays as it is and waits, the owner sees the reason. Same body as reject.
     * 409 WRONG_STATUS (not a live shop waiting for its re-check) / SHOP_CHANGED.
     */
    @PostMapping("/shops/{id}/ask-to-fix")
    public AdminShopResponse askToFix(@PathVariable UUID id, @Valid @RequestBody Decision body) {
        return admin.askToFix(caller(), id, body.version(), body.reason(), body.note(), body.checklist());
    }

    /**
     * Take a shop off the app (APPROVED or PENDING_VISIT), with the reason the owner sees. A salon's upcoming online
     * bookings are cancelled and each customer gets a message.
     */
    @PostMapping("/shops/{id}/suspend")
    public AdminShopResponse suspend(@PathVariable UUID id, @Valid @RequestBody Decision body) {
        return admin.suspend(caller(), id, body.version(), body.reason(), body.note(), body.checklist());
    }

    /**
     * The field team changes the address or pin (once verified the owner can't): `{version, addressLine, area, city,
     * pincode, latitude, longitude}`, all needed, the same limits as the owner's edit. No re-check; a note on the
     * timeline; the owner is told. Any status but CLOSED; a field agent only for a business assigned to them.
     */
    @PatchMapping("/shops/{id}/address")
    public AdminShopResponse changeAddress(@PathVariable UUID id, @Valid @RequestBody AddressChange body) {
        return admin.changeAddress(caller(), id, body.version(), body.addressLine(), body.area(), body.city(),
                body.pincode(), body.latitude(), body.longitude());
    }

    public record AddressChange(@NotNull Long version,
                                @NotBlank @Size(min = 5, max = 200) String addressLine,
                                @NotBlank @Size(min = 2, max = 100) String area,
                                @NotBlank @Size(min = 2, max = 60) String city,
                                @NotNull @jakarta.validation.constraints.Pattern(regexp = "[1-9][0-9]{5}",
                                        message = "must be a 6-digit pincode") String pincode,
                                @NotNull @jakarta.validation.constraints.DecimalMin("6.0")
                                @jakarta.validation.constraints.DecimalMax("37.6") java.math.BigDecimal latitude,
                                @NotNull @jakarta.validation.constraints.DecimalMin("68.0")
                                @jakarta.validation.constraints.DecimalMax("97.5") java.math.BigDecimal longitude) {
    }

    /**
     * Decline the owner's waiting address change request: `{version, reason}` (the owner is told the reason; the shop
     * keeps its address). 409 NO_ADDRESS_REQUEST when none waits; SHOP_CHANGED / WRONG_STATUS as for changeAddress.
     * To accept it, approve the shop (the request is applied with it).
     */
    @PostMapping("/shops/{id}/address-request/decline")
    public AdminShopResponse declineAddressRequest(@PathVariable UUID id, @Valid @RequestBody DeclineAddress body) {
        return admin.declineAddressRequest(caller(), id, body.version(), body.reason());
    }

    public record DeclineAddress(@NotNull Long version, @NotBlank @Size(max = 200) String reason) {
    }

    /** End a suspension: back to PENDING_VISIT, live again after the field team approves it. */
    @PostMapping("/shops/{id}/reinstate")
    public AdminShopResponse reinstate(@PathVariable UUID id, @Valid @RequestBody Approve body) {
        return admin.reinstate(caller(), id, body.version(), body.note());
    }

    /**
     * Send the salon's trade licence for the automatic check again. {"version"}. 409 NO_DOCUMENT, NOT_TRADE_LICENCE,
     * SHOP_CHANGED, WRONG_STATUS (closed).
     */
    @PostMapping("/shops/{id}/registration-document/recheck")
    public AdminShopResponse recheckLicence(@PathVariable UUID id, @Valid @RequestBody Approve body) {
        return admin.recheckLicence(caller(), id, body.version());
    }

    /** Super admins: licences not accepted, or waiting over half an hour, longest first (at most 100). */
    @GetMapping("/shops/licences-to-follow-up")
    public List<AdminShopResponse> licencesToFollowUp() {
        return admin.licencesToFollowUp(caller());
    }

    /**
     * Take a wrong or expired registration document off the salon, with the reason the owner is told. {"version",
     * "reason"}. The salon's status doesn't change. 409 NO_DOCUMENT, SHOP_CHANGED, WRONG_STATUS.
     */
    @PostMapping("/shops/{id}/registration-document/remove")
    public AdminShopResponse removeDocument(@PathVariable UUID id, @Valid @RequestBody RemoveDocument body) {
        return admin.removeDocument(caller(), id, body.version(), body.reason());
    }

    /** A note for admins only on the shop's timeline. */
    @PostMapping("/shops/{id}/notes")
    public ResponseEntity<TimelineEntry> note(@PathVariable UUID id, @Valid @RequestBody Note body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(admin.addNote(caller(), id, body.note()));
    }

    private UUID caller() {
        return currentUser.get().userId();
    }

    /**
     * @param version the shop's version on the page the admin checked
     * @param note    optional, for admins only (up to 500 characters)
     */
    /**
     * Super admins: give a business waiting for a visit to this field agent (any pincode). 204. 409 WRONG_STATUS
     * (not waiting), 409 OWN_SHOP (the agent works in it), 400 NOT_A_FIELD_AGENT, 404 if there's no such business.
     */
    @PostMapping("/shops/{id}/assign")
    public ResponseEntity<Void> assign(@PathVariable UUID id, @Valid @RequestBody Assign body) {
        assignments.assign(caller(), id, body.adminId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Super admins: move every business waiting for one admin's visit to a field agent (leave). Answers how many
     * moved, and how many stayed because the new agent works in them. 400 NOT_A_FIELD_AGENT / VALIDATION_FAILED.
     */
    @PostMapping("/assignments/move")
    public Moved moveAll(@Valid @RequestBody MoveAll body) {
        return assignments.moveAll(caller(), body.fromAdminId(), body.toAdminId());
    }

    public record Assign(@NotNull UUID adminId) {
    }

    public record MoveAll(@NotNull UUID fromAdminId, @NotNull UUID toAdminId) {
    }

    public record Approve(@NotNull Long version, @Size(max = 500) String note) {
    }

    /**
     * @param reason    what the owner should know, e.g. "The shop photo doesn't show the shop's name board."
     * @param note      optional, for admins only
     * @param checklist what was wrong (optional)
     */
    public record Decision(@NotNull Long version, @NotBlank @Size(max = 200) String reason,
                           @Size(max = 500) String note, @Size(max = 7) List<VisitIssue> checklist) {
    }

    public record Note(@NotBlank @Size(max = 500) String note) {
    }

    /** @param reason what the owner is told, e.g. "The licence expired in March." */
    public record RemoveDocument(@NotNull Long version, @NotBlank @Size(max = 200) String reason) {
    }
}
