package in.apnidukan.salon.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.salon.service.SalonSupportService;
import in.apnidukan.salon.service.SalonSupportService.CustomerStrikes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Apni Dukan support only (users.admin); anyone else gets 403. */
@RestController
@RequestMapping("/api/v1/admin/salon")
public class SalonSupportController {

    private final SalonSupportService support;
    private final CurrentUserProvider currentUser;

    public SalonSupportController(SalonSupportService support, CurrentUserProvider currentUser) {
        this.support = support;
        this.currentUser = currentUser;
    }

    /**
     * A customer's strikes (latest first) and until when online booking is blocked, by their phone. A POST so the
     * phone number stays out of URLs (and so out of proxy and access logs).
     */
    @PostMapping("/strikes/lookup")
    public CustomerStrikes strikes(@Valid @RequestBody Lookup body) {
        return support.strikes(currentUser.get().userId(), body.phone());
    }

    public record Lookup(@NotBlank @Size(max = 20) String phone) {
    }

    /** Take back the strike from this booking, with the reason (kept in the booking's history). 204. */
    @PostMapping("/strikes/{appointmentId}/remove")
    public ResponseEntity<Void> removeStrike(@PathVariable UUID appointmentId, @Valid @RequestBody RemoveStrike body) {
        support.removeStrike(currentUser.get().userId(), appointmentId, body.reason());
        return ResponseEntity.noContent().build();
    }

    /** @param reason why, e.g. "Customer was in the chair; barber pressed No-show by mistake" */
    public record RemoveStrike(@NotBlank @Size(max = 200) String reason) {
    }
}
