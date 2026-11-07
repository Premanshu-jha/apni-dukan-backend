package in.apnidukan.shops.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.shops.service.ShopOwnershipService;
import in.apnidukan.shops.service.ShopOwnershipService.IncomingHandover;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Hand-over requests made to the signed-in person's phone: see them, accept (they become the shop's owner) or
 * decline. See ShopOwnershipService.
 */
@RestController
@RequestMapping("/api/v1/me/handover")
public class HandoverController {

    private final ShopOwnershipService ownership;
    private final CurrentUserProvider currentUser;

    public HandoverController(ShopOwnershipService ownership, CurrentUserProvider currentUser) {
        this.ownership = ownership;
        this.currentUser = currentUser;
    }

    /** Requests waiting for my phone (usually none or one). */
    @GetMapping
    public List<IncomingHandover> incoming() {
        return ownership.incoming(currentUser.get().userId());
    }

    /** Take the shop over; then POST /auth/refresh so the token carries the shop as OWNER. */
    @PostMapping("/{id}/accept")
    public ShopResponse accept(@PathVariable UUID id) {
        return ownership.accept(currentUser.get().userId(), id);
    }

    /** Say no (204); the owner is told. */
    @PostMapping("/{id}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@PathVariable UUID id) {
        ownership.decline(currentUser.get().userId(), id);
    }
}
