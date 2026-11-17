package in.apnidukan.common.context;

/**
 * Razorpay webhook events that aren't about the salons' subscriptions (payments, orders, refunds), handed on by the
 * subscriptions' webhook (one URL for every Razorpay event) after it checked their signature. Implemented by the kirana
 * module (online orders), so the shops module doesn't depend on it.
 */
public interface RazorpayEvents {

    /**
     * Handles one signed event (its raw JSON body); returns what happened (for the log and the tests), or null when it
     * isn't one this handler knows.
     */
    String handle(String event, byte[] body, String eventId);
}
