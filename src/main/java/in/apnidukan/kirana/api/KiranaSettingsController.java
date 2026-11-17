package in.apnidukan.kirana.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.kirana.api.KiranaRequests.UpiId;
import in.apnidukan.kirana.api.KiranaResponses.Settings;
import in.apnidukan.kirana.service.KiranaSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The kirana's counter settings: the shop's UPI ID (its walk-in QR). See KiranaSettingsService. */
@RestController
@RequestMapping("/api/v1/kirana/settings")
public class KiranaSettingsController {

    private final KiranaSettingsService settings;
    private final CurrentUserProvider currentUser;

    public KiranaSettingsController(KiranaSettingsService settings, CurrentUserProvider currentUser) {
        this.settings = settings;
        this.currentUser = currentUser;
    }

    /** The shop's name and UPI ID (owner and helpers: the helper shows the QR). */
    @GetMapping
    public Settings get() {
        return settings.get(me());
    }

    /** Sets the UPI ID (owner only). */
    @PutMapping("/upi-id")
    public Settings setUpiId(@Valid @RequestBody UpiId body) {
        return settings.setUpiId(me(), body.upiId());
    }

    private UUID me() {
        return currentUser.get().userId();
    }
}
