package in.apnidukan.shops.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.shops.service.SubscriptionService;
import in.apnidukan.shops.service.SubscriptionService.Checkout;
import in.apnidukan.shops.service.SubscriptionService.View;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The salon's subscription in the partner app (see {@link SubscriptionService}): its state, the payment screen's
 * Razorpay checkout, and the checkout's signed answer.
 */
@RestController
@RequestMapping("/api/v1/shops/me/subscription")
public class SubscriptionController {

    /** The checkout's answer, as Razorpay gives it to the app. */
    public record Confirm(@NotBlank @Size(max = 40) String razorpayPaymentId,
                          @NotBlank @Size(max = 40) String razorpaySubscriptionId,
                          @NotBlank @Size(max = 128) String razorpaySignature) {
    }

    private final SubscriptionService subscriptions;
    private final CurrentUserProvider currentUser;

    public SubscriptionController(SubscriptionService subscriptions, CurrentUserProvider currentUser) {
        this.subscriptions = subscriptions;
        this.currentUser = currentUser;
    }

    /** Anyone in the salon: its trial, AutoPay and until when customers can find it. 404 NO_SHOP. */
    @GetMapping
    public View mine() {
        return subscriptions.mine(currentUser.get().userId());
    }

    /**
     * Owner: what to open Razorpay's checkout with. 403 OWNER_ONLY, 409 SUBSCRIPTION_NOT_STARTED (not approved yet),
     * 409 ALREADY_SUBSCRIBED, 409 PAYMENT_STARTING (another tap is opening it), 503 SUBSCRIPTIONS_OFF,
     * 503 PAYMENTS_UNAVAILABLE.
     */
    @PostMapping("/checkout")
    public Checkout checkout() {
        return subscriptions.checkout(currentUser.get().userId());
    }

    /** Owner: the checkout's answer; AutoPay shows as on at once. 400 PAYMENT_NOT_CONFIRMED if it doesn't check out. */
    @PostMapping("/confirm")
    public View confirm(@Valid @RequestBody Confirm body) {
        return subscriptions.confirm(currentUser.get().userId(), body.razorpayPaymentId(),
                body.razorpaySubscriptionId(), body.razorpaySignature());
    }
}
