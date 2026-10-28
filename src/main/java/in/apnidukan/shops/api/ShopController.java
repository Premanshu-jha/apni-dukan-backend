package in.apnidukan.shops.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.shops.api.ShopRequests.CreateShop;
import in.apnidukan.shops.api.ShopRequests.UpdateShop;
import in.apnidukan.shops.service.ShopService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/shops")
public class ShopController {

    private final ShopService shops;
    private final CurrentUserProvider currentUser;

    public ShopController(ShopService shops, CurrentUserProvider currentUser) {
        this.shops = shops;
        this.currentUser = currentUser;
    }

    /**
     * Registration step 1: creates your shop (DRAFT) with you as owner. 201.
     * 409 ALREADY_HAS_SHOP if you have one. Then call POST /auth/refresh so your token carries the shop.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShopResponse create(@Valid @RequestBody CreateShop body) {
        return shops.create(currentUser.get().userId(), body);
    }

    /** My shop: details, status and your role. 404 NO_SHOP if you have none. */
    @GetMapping("/me")
    public ShopResponse mine() {
        return shops.mine(currentUser.get().userId());
    }

    /** Edit My shop (owner only). Send the version from GET; 409 SHOP_CHANGED if it's stale. */
    @PatchMapping("/me")
    public ShopResponse update(@Valid @RequestBody UpdateShop body) {
        return shops.update(currentUser.get().userId(), body);
    }
}
