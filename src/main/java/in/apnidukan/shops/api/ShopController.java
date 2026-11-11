package in.apnidukan.shops.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.shops.api.ShopRequests.Close;
import in.apnidukan.shops.api.ShopRequests.CreateShop;
import in.apnidukan.shops.api.ShopRequests.Geocode;
import in.apnidukan.shops.api.ShopRequests.Handover;
import in.apnidukan.shops.api.ShopRequests.ReverseGeocode;
import in.apnidukan.shops.api.ShopRequests.SetPhotos;
import in.apnidukan.shops.api.ShopRequests.Submit;
import in.apnidukan.shops.api.ShopRequests.UpdateShop;
import in.apnidukan.shops.service.AddressLocator;
import in.apnidukan.shops.service.AddressLocator.Asker;
import in.apnidukan.shops.service.AddressLocator.Described;
import in.apnidukan.shops.service.AddressLocator.Located;
import in.apnidukan.shops.service.ShopInvitesService;
import in.apnidukan.shops.service.ShopInvitesService.InviteResult;
import in.apnidukan.shops.service.ShopInvitesService.InviteView;
import in.apnidukan.shops.service.ShopMembersService;
import in.apnidukan.shops.service.ShopMembersService.MemberView;
import in.apnidukan.shops.service.ShopOwnershipService;
import in.apnidukan.shops.service.ShopOwnershipService.HandoverRequested;
import in.apnidukan.shops.service.ShopOwnershipService.HandoverView;
import in.apnidukan.shops.service.ShopService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/shops")
public class ShopController {

    private final ShopService shops;
    private final CurrentUserProvider currentUser;
    private final ShopMembersService people;
    private final ShopOwnershipService ownership;
    private final ShopInvitesService invites;
    private final AddressLocator addresses;

    public ShopController(ShopService shops, CurrentUserProvider currentUser, ShopMembersService people,
                          ShopOwnershipService ownership, ShopInvitesService invites, AddressLocator addresses) {
        this.invites = invites;
        this.addresses = addresses;
        this.shops = shops;
        this.currentUser = currentUser;
        this.people = people;
        this.ownership = ownership;
    }

    /**
     * Close my shop for good (owner): CLOSED at once, a salon's upcoming bookings cancelled in the background within
     * seconds (customers told), everyone's membership ended, mine too. Asking again after it worked answers the same. Then call POST /auth/refresh.
     */
    @PostMapping("/me/close")
    public ShopResponse close(@Valid @RequestBody Close body) {
        return ownership.close(currentUser.get().userId(), body.version(), body.reason());
    }

    /**
     * Ask the person with this phone to take my shop over (owner). 202 with the same answer whether or not the
     * number has an account. 409 HANDOVER_PENDING while one waits; 429 after 5 a day.
     */
    @PostMapping("/me/handover")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public HandoverRequested requestHandover(@Valid @RequestBody Handover body) {
        return ownership.requestHandover(currentUser.get().userId(), body.phone(), body.version());
    }

    /** My shop's waiting hand-over request (owner); 404 NO_HANDOVER if none. */
    @GetMapping("/me/handover")
    public HandoverView myHandover() {
        return ownership.myHandover(currentUser.get().userId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_HANDOVER",
                        "No hand-over request is waiting."));
    }

    /** Cancel my shop's waiting hand-over request (owner). 204, also when none was waiting. */
    @DeleteMapping("/me/handover")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelHandover() {
        ownership.cancelHandover(currentUser.get().userId());
    }

    /** Everyone in my shop with name, phone and role (owner only). */
    @GetMapping("/me/members")
    public List<MemberView> members() {
        return people.members(currentUser.get().userId());
    }

    /**
     * Invite a helper by phone (owner only). 202 with the same answer whether or not the number has an account; the
     * person joins when they accept (GET /me/invites). 409 TOO_MANY_HELPERS (helpers and waiting invitations),
     * 409 ALREADY_A_MEMBER (already in this shop), 429 after 20 invitations a day.
     */
    @PostMapping("/me/helpers")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public InviteResult addHelper(@Valid @RequestBody AddHelper body) {
        return people.addHelper(currentUser.get().userId(), body.phone());
    }

    /** My shop's invitations still waiting (helpers and barbers), by the phone typed (owner only). */
    @GetMapping("/me/invites")
    public List<InviteView> invites() {
        return invites.waiting(currentUser.get().userId());
    }

    /** Cancel one of my shop's waiting invitations (owner only). 204; 404 INVITE_NOT_FOUND for another shop's. */
    @DeleteMapping("/me/invites/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelInvite(@PathVariable UUID id) {
        invites.cancel(currentUser.get().userId(), id);
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

    /** @param phone the helper's 10-digit mobile number; they sign in with it as usual and accept */
    public record AddHelper(@NotBlank @Size(max = 20) String phone) {
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

    /**
     * Registration: where the typed address is on the map, for the pin the owner then drags onto the door.
     * 200 {latitude, longitude, precision: EXACT|STREET|AREA, formattedAddress}. 404 ADDRESS_NOT_FOUND,
     * 429 TRY_LATER (20 an hour each), 503 GEOCODING_OFF (not set up: the app hides the button),
     * 503 GEOCODING_UNAVAILABLE (Google failed, or today's lookups are used up).
     */
    @PostMapping("/geocode")
    public Located geocode(@Valid @RequestBody Geocode body) {
        return addresses.locate(Asker.OWNER, currentUser.get().userId(), body.addressLine(), body.area(), body.city(),
                body.pincode());
    }

    /**
     * Registration: the address at the phone's GPS point, to fill the form's empty fields. 200 {addressLine, area,
     * city, pincode, formattedAddress} ("" where Google gave nothing). 400 outside India; otherwise the same answers,
     * people and limits as POST /shops/geocode (one budget for both).
     */
    @PostMapping("/reverse-geocode")
    public Described reverseGeocode(@Valid @RequestBody ReverseGeocode body) {
        return addresses.describe(Asker.OWNER, currentUser.get().userId(), body.latitude(), body.longitude());
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
     * Set the salon video (owner): the file from POST /files/video-uploads/{id}/complete. Send the version from GET
     * /shops/me. A video it replaces is deleted.
     */
    @PutMapping("/me/video")
    public ShopResponse setVideo(@Valid @RequestBody ShopRequests.SetVideo body) {
        return shops.setVideo(currentUser.get().userId(), body);
    }

    /**
     * Set a salon's registration document (owner): its type and a REG_DOC upload (photo or PDF). Send the version
     * from GET /shops/me. Optional; an approved salon stays live, and customers see "Registered" once the field team
     * has checked the new document.
     */
    @PutMapping("/me/registration-document")
    public ShopResponse setRegistrationDoc(@Valid @RequestBody ShopRequests.SetRegistrationDoc body) {
        return shops.setRegistrationDoc(currentUser.get().userId(), body);
    }

    /**
     * Remove a salon's registration document (owner): `?version=` from GET /shops/me (409 SHOP_CHANGED if stale).
     * Customers see "Unregistered" from now on; the salon stays as it is. 409 NOT_A_SALON for a kirana.
     */
    @DeleteMapping("/me/registration-document")
    public ShopResponse removeRegistrationDoc(@RequestParam long version) {
        return shops.removeRegistrationDoc(currentUser.get().userId(), version);
    }

    /**
     * Registration step 2 (owner): ask for the field team's visit. DRAFT → PENDING_VISIT.
     * 409 PHOTOS_REQUIRED until both photos and the salon video are set; then, for a salon, 409
     * REGISTRATION_INCOMPLETE until at least one service and its opening hours are set (the registration document is
     * optional).
     */
    @PostMapping("/me/submit")
    public ShopResponse submit(@Valid @RequestBody Submit body) {
        return shops.submitForVisit(currentUser.get().userId(), body.version());
    }
}
