package in.apnidukan.shops.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.shops.api.AdminShopDetail.TimelineEntry;
import in.apnidukan.shops.domain.ReviewGroup;
import in.apnidukan.shops.domain.ShopStatus;
import in.apnidukan.shops.domain.ShopType;
import in.apnidukan.shops.domain.VisitIssue;
import in.apnidukan.shops.service.ShopAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    private final CurrentUserProvider currentUser;

    public ShopAdminController(ShopAdminService admin, CurrentUserProvider currentUser) {
        this.admin = admin;
        this.currentUser = currentUser;
    }

    /** How many kiranas and salons are in each group (waiting for a first visit, re-check, sent back, live, suspended). */
    @GetMapping("/dashboard")
    public Map<ShopType, Map<ReviewGroup, Long>> dashboard() {
        return admin.dashboard(caller());
    }

    /**
     * A group's shops, longest waiting first (at most 100), with `type` and `group`; `q` searches the shop number,
     * name, owner's name and owner's phone. The older form `?status=` (default PENDING_VISIT) still answers too.
     */
    @GetMapping("/shops")
    public List<AdminShopResponse> list(@RequestParam(required = false) ShopType type,
                                        @RequestParam(required = false) ReviewGroup group,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(defaultValue = "PENDING_VISIT") ShopStatus status) {
        if (type != null && group != null) {
            return admin.list(caller(), type, group, q);
        }
        return admin.list(caller(), status);
    }

    /** One shop: what changed since it was last approved, a salon's size, photos and the timeline. */
    @GetMapping("/shops/{id}")
    public AdminShopDetail detail(@PathVariable UUID id) {
        return admin.detail(caller(), id);
    }

    /**
     * Approve a shop after its visit (PENDING_VISIT): customers can find it from now on. Send {"version": n} from
     * the page you checked; 409 SHOP_CHANGED if the owner changed something since.
     */
    @PostMapping("/shops/{id}/approve")
    public AdminShopResponse approve(@PathVariable UUID id, @Valid @RequestBody Approve body) {
        return admin.approve(caller(), id, body.version(), body.note());
    }

    /** The visit didn't pass: back to DRAFT with the reason the owner sees. 409 WRONG_STATUS / SHOP_CHANGED. */
    @PostMapping("/shops/{id}/reject")
    public AdminShopResponse reject(@PathVariable UUID id, @Valid @RequestBody Decision body) {
        return admin.reject(caller(), id, body.version(), body.reason(), body.note(), body.checklist());
    }

    /**
     * Take a shop off the app (APPROVED or PENDING_VISIT), with the reason the owner sees. A salon's upcoming online
     * bookings are cancelled and each customer gets a message.
     */
    @PostMapping("/shops/{id}/suspend")
    public AdminShopResponse suspend(@PathVariable UUID id, @Valid @RequestBody Decision body) {
        return admin.suspend(caller(), id, body.version(), body.reason(), body.note(), body.checklist());
    }

    /** End a suspension: back to PENDING_VISIT, live again after the field team approves it. */
    @PostMapping("/shops/{id}/reinstate")
    public AdminShopResponse reinstate(@PathVariable UUID id, @Valid @RequestBody Approve body) {
        return admin.reinstate(caller(), id, body.version(), body.note());
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
}
