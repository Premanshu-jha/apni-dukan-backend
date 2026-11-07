package in.apnidukan.shops.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.shops.service.ShopInvitesService;
import in.apnidukan.shops.service.ShopInvitesService.IncomingInvite;
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
 * Invitations made to the signed-in person's phone (to join a shop as a helper, or sign in as a salon's barber): see
 * them, accept (they join the shop) or decline. See ShopInvitesService.
 */
@RestController
@RequestMapping("/api/v1/me/invites")
public class InviteController {

    private final ShopInvitesService invites;
    private final CurrentUserProvider currentUser;

    public InviteController(ShopInvitesService invites, CurrentUserProvider currentUser) {
        this.invites = invites;
        this.currentUser = currentUser;
    }

    /** Invitations waiting for my phone (usually none or one). */
    @GetMapping
    public List<IncomingInvite> incoming() {
        return invites.incoming(currentUser.get().userId());
    }

    /** Join the shop; then POST /auth/refresh so the token carries the shop. */
    @PostMapping("/{id}/accept")
    public ShopResponse accept(@PathVariable UUID id) {
        return invites.accept(currentUser.get().userId(), id);
    }

    /** Say no (204); the owner is told. */
    @PostMapping("/{id}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@PathVariable UUID id) {
        invites.decline(currentUser.get().userId(), id);
    }
}
