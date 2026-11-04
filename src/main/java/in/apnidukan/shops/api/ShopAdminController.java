package in.apnidukan.shops.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.shops.domain.ShopStatus;
import in.apnidukan.shops.service.ShopAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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

    /** @param version the shop's version in the list the field team checked */
    public record Approve(@NotNull Long version) {
    }
}
