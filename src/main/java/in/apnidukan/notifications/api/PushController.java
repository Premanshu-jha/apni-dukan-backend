package in.apnidukan.notifications.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.notifications.service.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Notifications on this device (Web Push). */
@RestController
public class PushController {

    private final NotificationService notifications;
    private final CurrentUserProvider currentUser;

    public PushController(NotificationService notifications, CurrentUserProvider currentUser) {
        this.notifications = notifications;
        this.currentUser = currentUser;
    }

    /** The key to pass to the browser's pushManager.subscribe (applicationServerKey). Empty if not set up. */
    @GetMapping("/api/v1/push/public-key")
    public PublicKey publicKey() {
        return new PublicKey(notifications.publicKey());
    }

    /** Save this device after the person allowed notifications: the browser's PushSubscription as JSON. 204. */
    @PostMapping("/api/v1/me/push-subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void subscribe(@Valid @RequestBody Subscription body) {
        notifications.subscribe(currentUser.get().userId(), body.endpoint(), body.keys().p256dh(),
                body.keys().auth());
    }

    /** Stop notifications on this device (e.g. on sign-out). 204, also if it wasn't saved. */
    @DeleteMapping("/api/v1/me/push-subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@Valid @RequestBody Unsubscribe body) {
        notifications.unsubscribe(currentUser.get().userId(), body.endpoint());
    }

    public record PublicKey(String publicKey) {
    }

    /**
     * The browser's PushSubscription.toJSON(). expirationTime, if the browser sends it, is ignored.
     */
    public record Subscription(@NotBlank @Size(max = 1000) String endpoint, Long expirationTime,
                               @NotNull @Valid Keys keys) {
    }

    public record Keys(@NotBlank @Size(max = 100) String p256dh, @NotBlank @Size(max = 50) String auth) {
    }

    public record Unsubscribe(@NotBlank @Size(max = 1000) String endpoint) {
    }
}
