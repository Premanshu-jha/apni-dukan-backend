package in.apnidukan.notifications.service;

import in.apnidukan.common.google.GoogleServiceAccount;
import in.apnidukan.notifications.domain.NotificationJob;
import in.apnidukan.notifications.domain.PushSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sends notifications to the Android apps through Firebase Cloud Messaging's HTTP v1 API
 * (POST https://fcm.googleapis.com/v1/projects/{project}/messages:send), signed in as the Firebase project's service
 * account ({@link GoogleServiceAccount}: one access token serves every message for most of an hour).
 *
 * <p>One HTTP/2 client for every message (a single bean), so the sends share a few kept-alive connections to
 * fcm.googleapis.com; NotificationSendJob limits how many run at once (its per-host limit, host "fcm-app").
 *
 * <p>The message carries a notification (title and text, shown by Android itself while the app is in the
 * background, on the "clozest" channel the app creates) and data (type and url: the screen a tap opens).
 */
public final class FcmSender implements AppPushSender {

    private static final Logger log = LoggerFactory.getLogger(FcmSender.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    /** How long FCM keeps a message for a phone that is offline (as Web Push's TTL). */
    private static final String TTL = "21600s";
    /** The Android notification channel the apps create (src/api/appPush.ts); Android shows it as "Clozest". */
    static final String CHANNEL = "clozest";

    private final GoogleServiceAccount account;
    private final URI sendUri;
    private final HttpClient http;

    /**
     * @param serviceAccount the Firebase project's service-account key file (JSON)
     * @param fcmBase        https://fcm.googleapis.com (another address only in tests)
     */
    public FcmSender(JsonNode serviceAccount, URI fcmBase, Clock clock) throws GeneralSecurityException {
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.account = new GoogleServiceAccount(serviceAccount, SCOPE, clock, http);
        this.sendUri = fcmBase.resolve("/v1/projects/" + URLEncoder.encode(account.projectId(), StandardCharsets.UTF_8)
                + "/messages:send");
    }

    /** Reads the key file: raw JSON, or the same JSON base64-encoded (easier to paste into a server variable). */
    public static JsonNode readServiceAccount(String setting) {
        return GoogleServiceAccount.readKeyFile(setting, "FIREBASE_SERVICE_ACCOUNT");
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public PushSender.Result send(PushSubscription device, NotificationJob message) {
        try {
            HttpRequest request = HttpRequest.newBuilder(sendUri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + account.accessToken())
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body(device, message))))
                    .build();
            HttpResponse<String> answer = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = answer.statusCode();
            if (status >= 200 && status < 300) {
                return PushSender.Result.SENT;
            }
            if (status == 401) {
                account.forgetToken();   // refused (revoked or expired early): the next send signs in again
            }
            String code = errorCode(answer.body());
            // Never the token itself: it is a secret that lets anyone message the phone
            log.warn("FCM answered {} {} for device {}", status, code, device.id());
            return gone(status, code, errorMessage(answer.body())) ? PushSender.Result.GONE : PushSender.Result.FAILED;
        } catch (GoogleServiceAccount.SignInFailed e) {
            log.warn("Google refused the Firebase service account's sign-in ({}): check FIREBASE_SERVICE_ACCOUNT",
                    e.getMessage());
            return PushSender.Result.FAILED;
        } catch (IOException | JacksonException e) {
            log.warn("FCM send failed: {}", e.getClass().getSimpleName());
            return PushSender.Result.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PushSender.Result.FAILED;
        }
    }

    /** The signed sign-in request this sender makes (for tests). */
    String jwt(java.time.Instant issued) {
        return account.jwt(issued);
    }

    /**
     * The app is gone from that phone (uninstalled, its data cleared) or the token was never valid here: FCM answers
     * 404 UNREGISTERED, 403 SENDER_ID_MISMATCH (a token of another Firebase project), or 400 INVALID_ARGUMENT saying
     * the registration token isn't valid. Delete the device. Any other INVALID_ARGUMENT is about the message itself
     * (one of ours with a bad field would otherwise delete every device it was sent to): tried again, and logged.
     * Everything else is tried again.
     */
    static boolean gone(int status, String code, String message) {
        return status == 404 || "UNREGISTERED".equals(code) || "SENDER_ID_MISMATCH".equals(code)
                || status == 400 && "INVALID_ARGUMENT".equals(code)
                && message.toLowerCase(java.util.Locale.ROOT).contains("registration token");
    }

    /** FCM's error message (error.message), or "". */
    static String errorMessage(String answer) {
        try {
            return JSON.readTree(answer).path("error").path("message").asString("");
        } catch (JacksonException e) {
            return "";
        }
    }

    /** FCM's error code (details[].errorCode, else error.status), or "" if the answer has none. */
    static String errorCode(String answer) {
        try {
            JsonNode error = JSON.readTree(answer).path("error");
            for (JsonNode detail : error.path("details")) {
                String code = detail.path("errorCode").asString("");
                if (!code.isEmpty()) {
                    return code;
                }
            }
            return error.path("status").asString("");
        } catch (JacksonException e) {
            return "";
        }
    }

    /** The FCM v1 message for one device. */
    static Map<String, Object> body(PushSubscription device, NotificationJob message) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", message.type());
        if (message.url() != null) {
            data.put("url", message.url());
        }
        Map<String, Object> androidNotification = new LinkedHashMap<>();
        androidNotification.put("channel_id", CHANNEL);
        androidNotification.put("icon", "ic_stat_clozest");
        Map<String, Object> android = new LinkedHashMap<>();
        android.put("priority", "HIGH");
        android.put("ttl", TTL);
        android.put("notification", androidNotification);
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("token", device.endpoint());
        msg.put("notification", Map.of("title", message.title(), "body", message.body()));
        msg.put("data", data);
        msg.put("android", android);
        return Map.of("message", msg);
    }
}
