package in.apnidukan.kirana.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.kirana.api.OrderRequests.CancelOrder;
import in.apnidukan.kirana.api.OrderRequests.ChangeAddress;
import in.apnidukan.kirana.api.OrderRequests.PaymentDone;
import in.apnidukan.kirana.api.OrderRequests.PlaceOrder;
import in.apnidukan.kirana.api.OrderRequests.Quote;
import in.apnidukan.kirana.api.OrderRequests.Rate;
import in.apnidukan.kirana.api.OrderResponses.ListedPage;
import in.apnidukan.kirana.api.OrderResponses.NearbyPage;
import in.apnidukan.kirana.api.OrderResponses.OrderPage;
import in.apnidukan.kirana.api.OrderResponses.OrderView;
import in.apnidukan.kirana.api.OrderResponses.PayView;
import in.apnidukan.kirana.api.OrderResponses.QuoteView;
import in.apnidukan.kirana.api.OrderResponses.ShopPage;
import in.apnidukan.kirana.service.CheckoutService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Kirana online orders, the customer app's kirana tab (any signed-in person): shops near them, a shop's products and
 * the items shops near it have, the quote, checkout and payment, their orders. See CheckoutService.
 */
@RestController
@RequestMapping("/api/v1/kirana")
public class KiranaCustomerController {

    private final CheckoutService checkouts;
    private final CurrentUserProvider currentUser;

    public KiranaCustomerController(CheckoutService checkouts, CurrentUserProvider currentUser) {
        this.checkouts = checkouts;
        this.currentUser = currentUser;
    }

    /** Kirana shops within 2 km of the customer that take orders now, nearest first. */
    @GetMapping("/nearby")
    public ShopPage nearby(@RequestParam BigDecimal lat, @RequestParam BigDecimal lng,
                           @RequestParam(required = false) String cursor) {
        return checkouts.shops(lat, lng, cursor);
    }

    @GetMapping("/shops/{shopId}/products")
    public ListedPage products(@PathVariable UUID shopId, @RequestParam(required = false) String q,
                               @RequestParam(required = false) String cursor) {
        return checkouts.products(shopId, q, cursor);
    }

    /** Items the shop doesn't list that shops within 1 km of it have. */
    @GetMapping("/shops/{shopId}/nearby-items")
    public NearbyPage nearbyItems(@PathVariable UUID shopId, @RequestParam(required = false) String q,
                                  @RequestParam(required = false) String cursor) {
        return checkouts.nearbyItems(shopId, q, cursor);
    }

    /** What the cart would cost, and who gives what (combined, separate). Nothing is made. */
    @PostMapping("/quote")
    public QuoteView quote(@Valid @RequestBody Quote body) {
        return checkouts.quote(body);
    }

    /** Places the order and opens its Razorpay payment. 409 PRICE_CHANGED with the new quote. */
    @PostMapping("/checkouts")
    public PayView place(@Valid @RequestBody PlaceOrder body) {
        return checkouts.place(me(), body);
    }

    @GetMapping("/checkouts/{id}")
    public PayView checkout(@PathVariable UUID id) {
        return checkouts.checkout(me(), id);
    }

    /** The checkout's signed answer after paying. 400 BAD_SIGNATURE. */
    @PostMapping("/checkouts/{id}/paid")
    public PayView paid(@PathVariable UUID id, @Valid @RequestBody PaymentDone body) {
        return checkouts.paid(me(), id, body);
    }

    @GetMapping("/orders")
    public OrderPage orders(@RequestParam(required = false) String cursor) {
        return checkouts.myOrders(me(), cursor);
    }

    @GetMapping("/orders/{id}")
    public OrderView order(@PathVariable UUID id) {
        return checkouts.order(me(), id);
    }

    /**
     * Corrects the address text until the order goes out (the shop is told once it accepted), at most 3 times. 409
     * CANT_CHANGE_ADDRESS after.
     */
    @PatchMapping("/orders/{id}/address")
    public OrderView changeAddress(@PathVariable UUID id, @Valid @RequestBody ChangeAddress body) {
        return checkouts.changeAddress(me(), id, body.addressLine());
    }

    /** Cancels an order the shop hasn't accepted. 409 CANT_CANCEL. */
    @PostMapping("/orders/{id}/cancel")
    public OrderView cancel(@PathVariable UUID id, @Valid @RequestBody CancelOrder body) {
        return checkouts.cancel(me(), id, body.reason());
    }

    /** Rates a delivered order once. 409 CANT_RATE / ALREADY_RATED. */
    @PostMapping("/orders/{id}/rating")
    public OrderView rate(@PathVariable UUID id, @Valid @RequestBody Rate body) {
        return checkouts.rate(me(), id, body);
    }

    private UUID me() {
        return currentUser.get().userId();
    }
}
