package in.apnidukan.shops.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.google.GoogleServiceAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The Google Play Developer API, for the Play Store partner app's subscription (user choice billing, India): in the
 * app the owner picks Razorpay or Google Play on Google's own choice screen.
 * <ul>
 *   <li>{@link #subscription}: a Google Play purchase (its purchaseToken) as Google has it now
 *       (purchases.subscriptionsv2.get): state, product, until when it is paid, auto-renewing, the salon it was bought
 *       for (obfuscatedExternalAccountId, set by the app) and whether it was acknowledged;</li>
 *   <li>{@link #acknowledge}: a new purchase must be acknowledged within 3 days or Google refunds it;</li>
 *   <li>{@link #cancel}: stops a Google Play subscription renewing (the salon closed or changed hands);</li>
 *   <li>{@link #reportExternal}: a payment made with Razorpay after the owner picked it on Google's choice screen,
 *       reported to Google within 24 hours (externaltransactions.createexternaltransaction), the first with the
 *       externalTransactionToken the app got, the next ones (each month's charge) naming the first.</li>
 * </ul>
 * Signed in as the Play Console's service account (GOOGLE_PLAY_SERVICE_ACCOUNT, {@link GoogleServiceAccount}). Off
 * without it: the app then offers no in-app payment (the subscription's view says so), and nothing else changes.
 * The tokens are secrets and never logged.
 */
@Component
public final class GooglePlay {

    private static final Logger log = LoggerFactory.getLogger(GooglePlay.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final String SCOPE = "https://www.googleapis.com/auth/androidpublisher";
    /** What a purchase or transaction token looks like (Google's are long base64-ish strings). */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._:-]{10,1000}");
    /** Google documents no alphabet for externalTransactionToken: anything printable, no spaces (it goes in JSON). */
    private static final Pattern EXTERNAL_TOKEN = Pattern.compile("[\\x21-\\x7E]{10,1000}");

    private final GoogleServiceAccount account;
    private final String packageName;
    private final String productId;
    private final URI api;
    private final HttpClient http;

    /** A Google Play subscription as Google has it. */
    public record PlaySubscription(String state, String productId, Instant expiresAt, boolean autoRenewing,
                                   String accountId, boolean acknowledged, boolean test, boolean freeTrial,
                                   String orderId, Long pricePaise) {

        /** Without the order and price (tests, and answers read before they were kept). */
        public PlaySubscription(String state, String productId, Instant expiresAt, boolean autoRenewing,
                                String accountId, boolean acknowledged, boolean test, boolean freeTrial) {
            this(state, productId, expiresAt, autoRenewing, accountId, acknowledged, test, freeTrial, null, null);
        }

        /** Paid for now (active, or in Google's grace period while it retries the payment). */
        public boolean entitled() {
            return "SUBSCRIPTION_STATE_ACTIVE".equals(state) || "SUBSCRIPTION_STATE_IN_GRACE_PERIOD".equals(state)
                    || "SUBSCRIPTION_STATE_CANCELED".equals(state);   // cancelled: still paid until expiresAt
        }

        /**
         * Google has the money for the time up to expiresAt: active or cancelled (paid to the end), and not a free
         * trial. Not in Google's grace period (the payment failed; Google is retrying it) nor pending.
         */
        public boolean paid() {
            return ("SUBSCRIPTION_STATE_ACTIVE".equals(state) || "SUBSCRIPTION_STATE_CANCELED".equals(state))
                    && !freeTrial && expiresAt != null;
        }

        /** Google took it back: refunded, revoked, or never acknowledged in time (expired before its end). */
        public boolean over() {
            return "SUBSCRIPTION_STATE_EXPIRED".equals(state) || "SUBSCRIPTION_STATE_ON_HOLD".equals(state)
                    || "SUBSCRIPTION_STATE_PAUSED".equals(state);
        }
    }

    /** One Razorpay payment (or the $0 sign-up during a trial) to report to Google. */
    public record ExternalTransaction(String id, long amountPaise, Instant time, String token, String initialId,
                                      String state) {
    }

    /** What Google said to a report: done, or try again later, or never (a refusal that won't change). */
    public enum Reported { DONE, RETRY, REFUSED }

    public GooglePlay(@Value("${app.google-play.service-account:}") String keyFile,
                      @Value("${app.google-play.package-name:com.clozest.partner}") String packageName,
                      @Value("${app.google-play.product-id:}") String productId,
                      @Value("${app.google-play.endpoint:https://androidpublisher.googleapis.com}") URI api,
                      Clock clock) throws GeneralSecurityException {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.account = keyFile.isBlank() ? null : new GoogleServiceAccount(
                GoogleServiceAccount.readKeyFile(keyFile, "GOOGLE_PLAY_SERVICE_ACCOUNT"), SCOPE, clock, http);
        this.packageName = packageName.strip();
        this.productId = productId.strip();
        this.api = api;
    }

    /** In-app payment can be offered: the service account and the subscription's product id are set. */
    public boolean configured() {
        return account != null && !productId.isBlank();
    }

    /** The subscription product in Play Console (Monetize › Subscriptions), the one the app buys. */
    public String productId() {
        return productId;
    }

    /** A purchase token, as Google gives it, that is safe to put in a URL. */
    public static boolean token(String value) {
        return value != null && TOKEN.matcher(value).matches();
    }

    /** An externalTransactionToken as the app sends it (printable, no spaces, at most 1000 characters). */
    public static boolean externalToken(String value) {
        return value != null && EXTERNAL_TOKEN.matcher(value).matches();
    }

    /** purchases.subscriptionsv2.get. 503 PAYMENTS_UNAVAILABLE when Google doesn't answer; empty for an unknown token. */
    public java.util.Optional<PlaySubscription> subscription(String purchaseToken) {
        requireConfigured();
        if (!token(purchaseToken)) {
            return java.util.Optional.empty();
        }
        HttpResponse<String> answer = call("GET", "/androidpublisher/v3/applications/" + enc(packageName)
                + "/purchases/subscriptionsv2/tokens/" + enc(purchaseToken), null);
        if (answer.statusCode() == 404 || answer.statusCode() == 410 || answer.statusCode() == 400) {
            return java.util.Optional.empty();
        }
        if (answer.statusCode() / 100 != 2) {
            log.warn("Google Play didn't give a subscription: HTTP {}", answer.statusCode());
            throw unavailable();
        }
        try {
            JsonNode s = JSON.readTree(answer.body());
            JsonNode item = s.path("lineItems").path(0);
            String expiry = item.path("expiryTime").asString("");
            return java.util.Optional.of(new PlaySubscription(
                    s.path("subscriptionState").asString(""),
                    item.path("productId").asString(""),
                    expiry.isEmpty() ? null : Instant.parse(expiry),
                    item.path("autoRenewingPlan").path("autoRenewEnabled").asBoolean(false),
                    s.path("externalAccountIdentifiers").path("obfuscatedExternalAccountId").asString(""),
                    "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED".equals(s.path("acknowledgementState").asString("")),
                    s.has("testPurchase"),
                    item.path("offerPhase").has("freeTrial"),
                    orderId(s, item),
                    pricePaise(item.path("autoRenewingPlan").path("recurringPrice"))));
        } catch (JacksonException | java.time.format.DateTimeParseException e) {
            log.warn("Google Play's subscription wasn't readable");
            throw unavailable();
        }
    }

    /**
     * The order of the period paid now (each renewal is a new order: GPA.1234-…..0, ..1, …), for the payments ledger;
     * null when Google gives none.
     */
    static String orderId(JsonNode subscription, JsonNode item) {
        String id = item.path("latestSuccessfulOrderId").asString("");
        if (id.isEmpty()) {
            id = subscription.path("latestOrderId").asString("");
        }
        return id.matches("[A-Za-z0-9.\\-]{1,90}") ? id : null;
    }

    /** The plan's price in paise (Google's Money: units and nanos, rupees); null if not in rupees or not given. */
    static Long pricePaise(JsonNode money) {
        if (!"INR".equals(money.path("currencyCode").asString("")) || !money.has("units")) {
            return null;
        }
        try {
            long units = Long.parseLong(money.path("units").asString("0"));
            long nanos = money.path("nanos").asLong(0);
            return units * 100 + nanos / 10_000_000;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** purchases.subscriptions.acknowledge (needed within 3 days of a new purchase). */
    public void acknowledge(String purchaseToken) {
        requireConfigured();
        HttpResponse<String> answer = call("POST", "/androidpublisher/v3/applications/" + enc(packageName)
                + "/purchases/subscriptions/" + enc(productId) + "/tokens/" + enc(purchaseToken) + ":acknowledge",
                "{}");
        // A 400 saying it is acknowledged already is fine too; any other 400 is a failure (tried again: Google refunds
        // a purchase not acknowledged within 3 days)
        boolean already = answer.statusCode() == 400
                && errorMessage(answer.body()).toLowerCase(java.util.Locale.ROOT).contains("acknowledged");
        if (answer.statusCode() / 100 != 2 && !already) {
            log.warn("Google Play didn't acknowledge a purchase: HTTP {}", answer.statusCode());
            throw unavailable();
        }
    }

    /** purchases.subscriptions.cancel: no more renewals (paid time stays). True when done or already over. */
    public boolean cancel(String purchaseToken) {
        if (!configured() || !token(purchaseToken)) {
            return false;
        }
        try {
            HttpResponse<String> answer = call("POST", "/androidpublisher/v3/applications/" + enc(packageName)
                    + "/purchases/subscriptions/" + enc(productId) + "/tokens/" + enc(purchaseToken) + ":cancel",
                    "{}");
            return answer.statusCode() / 100 == 2 || answer.statusCode() == 400 || answer.statusCode() == 410;
        } catch (ApiException e) {
            return false;
        }
    }

    /**
     * externaltransactions.createexternaltransaction for one Razorpay payment from the app. DONE also when Google
     * already has it (409: a retry after an answer that got lost); REFUSED for a 4xx that a retry won't fix (logged
     * with Google's words, never the token); RETRY otherwise.
     */
    public Reported reportExternal(ExternalTransaction t) {
        if (!configured()) {
            return Reported.RETRY;
        }
        Map<String, Object> recurring = new LinkedHashMap<>();
        if (t.initialId() == null) {
            recurring.put("externalTransactionToken", t.token());
            recurring.put("externalSubscription", Map.of("subscriptionType", "RECURRING"));
        } else {
            recurring.put("initialExternalTransactionId", t.initialId());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("originalPreTaxAmount", price(t.amountPaise()));
        // ₹500 a month has no GST in it yet (the subscription's price, see SubscriptionService.PRICE_RUPEES)
        body.put("originalTaxAmount", price(0));
        body.put("transactionTime", DateTimeFormatter.ISO_INSTANT.format(t.time()));
        body.put("recurringTransaction", recurring);
        body.put("userTaxAddress", Map.of("regionCode", "IN", "administrativeArea", t.state()));
        try {
            HttpResponse<String> answer = call("POST", "/androidpublisher/v3/applications/" + enc(packageName)
                    + "/externalTransactions?externalTransactionId=" + enc(t.id()), JSON.writeValueAsString(body));
            int status = answer.statusCode();
            if (status / 100 == 2 || status == 409) {
                return Reported.DONE;
            }
            if (status == 401 || status == 429 || status >= 500) {
                return Reported.RETRY;
            }
            log.warn("Google refused external transaction {}: HTTP {} {}", t.id(), status, errorMessage(answer.body()));
            return Reported.REFUSED;
        } catch (ApiException e) {
            return Reported.RETRY;
        }
    }

    private static Map<String, String> price(long paise) {
        // Micros of a rupee: 1 paisa = 10,000 micros
        return Map.of("priceMicros", Long.toString(paise * 10_000L), "currency", "INR");
    }

    private HttpResponse<String> call(String method, String path, String json) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(api.resolve(path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + account.accessToken());
            if (json == null) {
                b.GET();
            } else {
                b.header("Content-Type", "application/json; charset=UTF-8")
                        .method(method, HttpRequest.BodyPublishers.ofString(json));
            }
            HttpResponse<String> answer = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (answer.statusCode() == 401) {
                account.forgetToken();
            }
            return answer;
        } catch (GoogleServiceAccount.SignInFailed e) {
            log.warn("Google refused the Play service account's sign-in ({}): check GOOGLE_PLAY_SERVICE_ACCOUNT",
                    e.getMessage());
            throw unavailable();
        } catch (IOException e) {
            log.warn("Google Play didn't answer: {}", e.getClass().getSimpleName());
            throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    private static String errorMessage(String body) {
        try {
            String message = JSON.readTree(body).path("error").path("message").asString("");
            return message.length() > 200 ? message.substring(0, 200) : message;
        } catch (JacksonException e) {
            return "";
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "IN_APP_PAYMENTS_OFF",
                    "Paying in the app isn't available yet.");
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENTS_UNAVAILABLE",
                "Couldn't reach the payment service. Please try again in a minute.").retryAfter(30);
    }
}
