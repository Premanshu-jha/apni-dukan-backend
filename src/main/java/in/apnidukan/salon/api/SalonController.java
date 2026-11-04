package in.apnidukan.salon.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.salon.api.BookingRequests.LinkLogin;
import in.apnidukan.salon.api.SalonRequests.CreateService;
import in.apnidukan.salon.api.SalonRequests.CreateStaff;
import in.apnidukan.salon.api.SalonRequests.SetHours;
import in.apnidukan.salon.api.SalonRequests.SetServices;
import in.apnidukan.salon.api.SalonRequests.StaffPhoto;
import in.apnidukan.salon.api.SalonRequests.UpdateService;
import in.apnidukan.salon.api.SalonRequests.UpdateSettings;
import in.apnidukan.salon.api.SalonRequests.UpdateStaff;
import in.apnidukan.salon.api.SalonResponses.ServiceResponse;
import in.apnidukan.salon.api.SalonResponses.SettingsResponse;
import in.apnidukan.salon.api.SalonResponses.StaffResponse;
import in.apnidukan.salon.service.SalonSetupService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Salon setup for the shop app. Reads: anyone in the salon. Changes: owner only (403 OWNER_ONLY).
 * Every call needs a salon: 404 NO_SHOP without a shop, 409 NOT_A_SALON for a kirana.
 */
@RestController
@RequestMapping("/api/v1/salon")
public class SalonController {

    private final SalonSetupService setup;
    private final CurrentUserProvider currentUser;

    public SalonController(SalonSetupService setup, CurrentUserProvider currentUser) {
        this.setup = setup;
        this.currentUser = currentUser;
    }

    /** The salon's services, by the owner's order then name, switched-off ones included (active: false). */
    @GetMapping("/services")
    public List<ServiceResponse> services() {
        return setup.services(me());
    }

    /** Add a service. 201. 409 SERVICE_NAME_TAKEN if an active one has the same name. */
    @PostMapping("/services")
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceResponse addService(@Valid @RequestBody CreateService body) {
        return setup.addService(me(), body);
    }

    /** Edit a service or switch it off/on. Send the version you read; 409 SERVICE_CHANGED if it's stale. */
    @PatchMapping("/services/{id}")
    public ServiceResponse updateService(@PathVariable UUID id, @Valid @RequestBody UpdateService body) {
        return setup.updateService(me(), id, body);
    }

    /** Every barber with the services he does and his week. */
    @GetMapping("/staff")
    public List<StaffResponse> staff() {
        return setup.staff(me());
    }

    /** Add a barber: a name, and optionally his phone for his own sign-in. 201. */
    @PostMapping("/staff")
    @ResponseStatus(HttpStatus.CREATED)
    public StaffResponse addStaff(@Valid @RequestBody CreateStaff body) {
        return setup.addStaff(me(), body);
    }

    /** Rename a barber or switch him off/on. Send the version you read; 409 STAFF_CHANGED if it's stale. */
    @PatchMapping("/staff/{id}")
    public StaffResponse updateStaff(@PathVariable UUID id, @Valid @RequestBody UpdateStaff body) {
        return setup.updateStaff(me(), id, body);
    }

    /** Give the barber his own sign-in with his phone (he then sees his own day). */
    @PutMapping("/staff/{id}/login")
    public StaffResponse linkLogin(@PathVariable UUID id, @Valid @RequestBody LinkLogin body) {
        return setup.linkLogin(me(), id, body.phone());
    }

    /** Set the barber's photo (optional): {"fileId": id of a STAFF_PHOTO upload}, or {"fileId": null} to remove. */
    @PutMapping("/staff/{id}/photo")
    public StaffResponse setPhoto(@PathVariable UUID id, @RequestBody StaffPhoto body) {
        return setup.setPhoto(me(), id, body.fileId());
    }

    /** Take the barber's sign-in away; his sessions end at once. */
    @DeleteMapping("/staff/{id}/login")
    public StaffResponse unlinkLogin(@PathVariable UUID id) {
        return setup.unlinkLogin(me(), id);
    }

    /** Replace the services this barber does. */
    @PutMapping("/staff/{id}/services")
    public StaffResponse setServices(@PathVariable UUID id, @Valid @RequestBody SetServices body) {
        return setup.setServices(me(), id, body);
    }

    /** Replace this barber's week of working hours ("HH:mm", 15-minute steps, salon time). */
    @PutMapping("/staff/{id}/hours")
    public StaffResponse setHours(@PathVariable UUID id, @Valid @RequestBody SetHours body) {
        return setup.setHours(me(), id, body);
    }

    /** Online booking settings, or the defaults (version null) until the owner saves. */
    @GetMapping("/settings")
    public SettingsResponse settings() {
        return setup.settings(me());
    }

    /** Save booking settings. Send the version from GET (null on defaults); 409 SETTINGS_CHANGED if it's stale. */
    @PatchMapping("/settings")
    public SettingsResponse updateSettings(@Valid @RequestBody UpdateSettings body) {
        return setup.updateSettings(me(), body);
    }

    private UUID me() {
        return currentUser.get().userId();
    }
}
