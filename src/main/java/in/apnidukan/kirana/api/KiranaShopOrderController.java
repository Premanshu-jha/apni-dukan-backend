package in.apnidukan.kirana.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.kirana.api.OrderRequests.DeliveryCode;
import in.apnidukan.kirana.api.OrderRequests.MissingLines;
import in.apnidukan.kirana.api.OrderRequests.RejectPart;
import in.apnidukan.kirana.api.OrderResponses.ShopPartPage;
import in.apnidukan.kirana.api.OrderResponses.ShopPartView;
import in.apnidukan.kirana.service.ShopOrderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Kirana online orders, the shop's order screens (owner and helpers): accept within 3 minutes or reject, mark missing
 * items, pack, hand over (another shop's items), send out, deliver against the customer's code. See ShopOrderService.
 */
@RestController
@RequestMapping("/api/v1/kirana/shop-orders")
public class KiranaShopOrderController {

    private final ShopOrderService orders;
    private final CurrentUserProvider currentUser;

    public KiranaShopOrderController(ShopOrderService orders, CurrentUserProvider currentUser) {
        this.orders = orders;
        this.currentUser = currentUser;
    }

    /** The shop's order parts, newest first: open=true (to act on) or done. */
    @GetMapping
    public ShopPartPage list(@RequestParam(defaultValue = "true") boolean open,
                             @RequestParam(required = false) String cursor) {
        return orders.list(me(), open, cursor);
    }

    @GetMapping("/{partId}")
    public ShopPartView get(@PathVariable UUID partId) {
        return orders.get(me(), partId);
    }

    @PostMapping("/{partId}/accept")
    public ShopPartView accept(@PathVariable UUID partId) {
        return orders.accept(me(), partId);
    }

    @PostMapping("/{partId}/reject")
    public ShopPartView reject(@PathVariable UUID partId, @Valid @RequestBody RejectPart body) {
        return orders.reject(me(), partId, body.reason());
    }

    @PostMapping("/{partId}/missing")
    public ShopPartView missing(@PathVariable UUID partId, @Valid @RequestBody MissingLines body) {
        return orders.missing(me(), partId, body.lineNos());
    }

    /**
     * The shop can't finish an order it accepted (customer unreachable, wrong address, accepted by mistake):
     * {reason}. The delivering shop cancels the whole order (refunded in full); a supplier only its items.
     */
    @PostMapping("/{partId}/cancel")
    public ShopPartView cancel(@PathVariable UUID partId, @Valid @RequestBody RejectPart body) {
        return orders.cancel(me(), partId, body.reason());
    }

    @PostMapping("/{partId}/packed")
    public ShopPartView packed(@PathVariable UUID partId) {
        return orders.packed(me(), partId);
    }

    /** A supplier hands its items to the delivering shop. */
    @PostMapping("/{partId}/handed")
    public ShopPartView handed(@PathVariable UUID partId) {
        return orders.handed(me(), partId);
    }

    /** The delivering shop sends the order out. 409 WAITING_FOR_SHOP. */
    @PostMapping("/{partId}/out")
    public ShopPartView out(@PathVariable UUID partId) {
        return orders.out(me(), partId);
    }

    /** Delivered against the customer's code. 400 WRONG_CODE; 429 CODE_LOCKED. */
    @PostMapping("/{partId}/deliver")
    public ShopPartView deliver(@PathVariable UUID partId, @Valid @RequestBody DeliveryCode body) {
        return orders.deliver(me(), partId, body.otp());
    }

    private UUID me() {
        return currentUser.get().userId();
    }
}
