package in.apnidukan.shops.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.shops.api.ShopRequests.CreateShop;
import in.apnidukan.shops.api.ShopRequests.SetPhotos;
import in.apnidukan.shops.api.ShopRequests.Submit;
import in.apnidukan.shops.api.ShopRequests.UpdateShop;
import in.apnidukan.shops.service.ShopMembersService;
import in.apnidukan.shops.service.ShopMembersService.MemberView;
import in.apnidukan.shops.service.ShopService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/shops")
public class ShopController {

    private final ShopService shops;
    private final CurrentUserProvider currentUser;
    private final ShopMembersService people;

    /** Everyone in my shop with name, phone and role (owner only). */
    @GetMapping("/me/members")
    public List<MemberView> members() {
        return people.members(currentUser.get().userId());
    }

    /** Add a helper by phone (owner only). 409 ALREADY_IN_A_SHOP if that person works in a shop. */
    @PostMapping("/me/helpers")
    public List<MemberView> addHelper(@Valid @RequestBody AddHelper body) {
        return people.addHelper(currentUser.get().userId(), body.phone());
    }

    /** Remove a helper (owner only); their sessions end at once. */
    @DeleteMapping("/me/helpers/{userId}")
    public List<MemberView> removeHelper(@PathVariable UUID userId) {
        return people.removeHelper(currentUser.get().userId(), userId);
    }

    /** Leave my shop (a helper or barber). 204. */
    @PostMapping("/me/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave() {
        people.leave(currentUser.get().userId());
    }

    /** @param phone the helper's 10-digit mobile number; they sign in with it as usual */
    public record AddHelper(@NotBlank @Size(max = 20) String phone) {
    }

    public ShopController(ShopService shops, CurrentUserProvider currentUser, ShopMembersService people) {
        this.shops = shops;
        this.currentUser = currentUser;
        this.people = people;
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

    /**
     * Set the owner's and/or the shop's photo (owner): ids of photos uploaded to POST /files with purpose
     * OWNER_PHOTO and SHOP_PHOTO. Send the version from GET /shops/me.
     */
    @PutMapping("/me/photos")
    public ShopResponse setPhotos(@Valid @RequestBody SetPhotos body) {
        return shops.setPhotos(currentUser.get().userId(), body);
    }

    /**
     * Registration step 2 (owner): ask for the field team's visit. DRAFT → PENDING_VISIT.
     * 409 PHOTOS_REQUIRED until both photos are set.
     */
    @PostMapping("/me/submit")
    public ShopResponse submit(@Valid @RequestBody Submit body) {
        return shops.submitForVisit(currentUser.get().userId(), body.version());
    }
}
