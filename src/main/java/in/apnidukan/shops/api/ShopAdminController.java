package in.apnidukan.shops.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.shops.domain.ShopStatus;
import in.apnidukan.shops.service.ShopAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Field team only (users.admin); anyone else gets 403. */
@RestController
@RequestMapping("/api/v1/admin/shops")
public class ShopAdminController {

    private final ShopAdminService admin;
    private final CurrentUserProvider currentUser;

    public ShopAdminController(ShopAdminService admin, CurrentUserProvider currentUser) {
        this.admin = admin;
        this.currentUser = currentUser;
    }

    /** Shops in a status, oldest first, with photo links. Default: PENDING_VISIT (waiting for the field team). */
    @GetMapping
    public List<AdminShopResponse> list(@RequestParam(defaultValue = "PENDING_VISIT") ShopStatus status) {
        return admin.list(currentUser.get().userId(), status);
    }

    /**
     * Approve a shop after its visit (PENDING_VISIT): customers can find it from now on. Send {"version": n} from
     * the list you checked; 409 SHOP_CHANGED if the owner changed something since.
     */
    @PostMapping("/{id}/approve")
    public AdminShopResponse approve(@PathVariable UUID id, @Valid @RequestBody Approve body) {
        return admin.approve(currentUser.get().userId(), id, body.version());
    }

    /** The visit didn't pass: back to DRAFT with the reason the owner sees. 409 WRONG_STATUS / SHOP_CHANGED. */
    @PostMapping("/{id}/reject")
    public AdminShopResponse reject(@PathVariable UUID id, @Valid @RequestBody Decision body) {
        return admin.reject(currentUser.get().userId(), id, body.version(), body.reason());
    }

    /**
     * Take a shop off the app (APPROVED or PENDING_VISIT), with the reason the owner sees. A salon's upcoming online
     * bookings are cancelled and each customer gets a message.
     */
    @PostMapping("/{id}/suspend")
    public AdminShopResponse suspend(@PathVariable UUID id, @Valid @RequestBody Decision body) {
        return admin.suspend(currentUser.get().userId(), id, body.version(), body.reason());
    }

    /** End a suspension: back to PENDING_VISIT, live again after the field team approves it (11f). */
    @PostMapping("/{id}/reinstate")
    public AdminShopResponse reinstate(@PathVariable UUID id, @Valid @RequestBody Approve body) {
        return admin.reinstate(currentUser.get().userId(), id, body.version());
    }

    /** @param version the shop's version in the list the field team checked */
    public record Approve(@NotNull Long version) {
    }

    /** @param reason what the owner should know, e.g. "The shop photo doesn't show the shop's name board." */
    public record Decision(@NotNull Long version, @NotBlank @Size(max = 200) String reason) {
    }
}
