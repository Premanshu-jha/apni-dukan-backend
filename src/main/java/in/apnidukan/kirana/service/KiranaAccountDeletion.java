package in.apnidukan.kirana.service;

import in.apnidukan.common.context.AccountDeletionListener;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.kirana.repo.OrderStore;
import in.apnidukan.kirana.repo.OrderStore.Checkout;
import in.apnidukan.kirana.repo.OrderStore.Order;
import in.apnidukan.kirana.repo.OrderStore.Part;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Someone deleting their account with kirana orders. An order a shop has accepted (being packed, on its way) must be
 * delivered or cancelled first, so the shop isn't left with an order no one can take (409 KIRANA_ORDER_OPEN). Orders
 * no shop has accepted yet are cancelled and refunded with the account; delivered ones stay as the shops' records.
 */
@Component
@org.springframework.core.annotation.Order(30)
class KiranaAccountDeletion implements AccountDeletionListener {

    private final OrderStore orders;
    private final OrderFlow flow;

    KiranaAccountDeletion(OrderStore orders, OrderFlow flow) {
        this.orders = orders;
        this.flow = flow;
    }

    @Override
    @Transactional(readOnly = true)
    public void checkCanDelete(UUID userId, Instant now) {
        for (Order o : orders.openOrdersOf(userId)) {
            if (!"PLACED".equals(o.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "KIRANA_ORDER_OPEN", "Your order #" + o.orderNo()
                        + " is being packed or is on its way. Delete your account once it is delivered.",
                        Map.of("orderId", o.id().toString()));
            }
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void accountDeleted(UUID userId, Instant now) {
        checkCanDelete(userId, now);   // again, under the account's lock
        for (Order peek : orders.openOrdersOf(userId)) {
            Checkout c = orders.lockCheckout(peek.checkoutId()).orElseThrow();
            Order o = orders.lockOrder(peek.id()).orElseThrow();
            List<Part> parts = orders.lockPartsOf(o.id());
            if (!"PLACED".equals(o.status())) {
                // A shop accepted it meanwhile: the account stays until it is delivered
                throw new ApiException(HttpStatus.CONFLICT, "KIRANA_ORDER_OPEN", "Your order #" + o.orderNo()
                        + " is being packed or is on its way. Delete your account once it is delivered.",
                        Map.of("orderId", o.id().toString()));
            }
            flow.cancelOrder(c, o, parts, "The account was deleted", "CANCELLED", now);
        }
    }
}
