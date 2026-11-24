package in.apnidukan.common.google;

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
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Signs in to a Google API as a service account (a key file from Google Cloud or Firebase): a short-lived access
 * token made from a JWT signed with the account's private key (RS256), Google's OAuth 2.0 JWT bearer grant. The token
 * is kept until a few minutes before it expires, so one sign-in serves every call for most of an hour; one call makes
 * a new one while the others wait for it. No Google SDK: the JDK's HTTP client and signature.
 *
 * <p>Used for Firebase Cloud Messaging (the apps' notifications) and the Google Play Developer API (the Play Store
 * app's subscriptions). One instance per key file and scope.
 */
public final class GoogleServiceAccount {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** A new access token this long before the old one expires (a call never uses one about to run out). */
    private static final Duration RENEW_BEFORE = Duration.ofMinutes(5);

    private final String projectId;
    private final String clientEmail;
    private final String keyId;
    private final PrivateKey key;
    private final URI tokenUri;
    private final String scope;
    private final Clock clock;
    private final HttpClient http;

    /** The access token in use and when it stops working; replaced together (one volatile field). */
    private volatile AccessToken token;

    private record AccessToken(String value, Instant expires) {
    }

    /** Google didn't give an access token (a wrong or deleted key, a stopped account, no answer). */
    public static final class SignInFailed extends IOException {
        private static final long serialVersionUID = 1L;

        public SignInFailed(String message) {
            super(message);
        }
    }

    /**
     * @param keyFile the key file (JSON): project_id, client_email, private_key_id, private_key (PEM, PKCS#8) and
     *                token_uri
     * @param scope   the API's OAuth scope, e.g. https://www.googleapis.com/auth/firebase.messaging
     */
    public GoogleServiceAccount(JsonNode keyFile, String scope, Clock clock, HttpClient http)
            throws GeneralSecurityException {
        this.projectId = required(keyFile, "project_id");
        this.clientEmail = required(keyFile, "client_email");
        this.keyId = keyFile.path("private_key_id").asString("");
        this.key = privateKey(required(keyFile, "private_key"));
        String uri = keyFile.path("token_uri").asString("");
        this.tokenUri = URI.create(uri.isBlank() ? "https://oauth2.googleapis.com/token" : uri);
        if (!"https".equals(tokenUri.getScheme()) && !"localhost".equals(tokenUri.getHost())) {
            throw new IllegalStateException("The service account's token_uri must be https");   // (tests: local)
        }
        this.scope = scope;
        this.clock = clock;
        this.http = http;
    }

    /**
     * Reads a key file from a setting: raw JSON, or the same JSON base64-encoded (easier to paste into a server
     * variable). {@code setting} names the variable for the error message.
     */
    public static JsonNode readKeyFile(String value, String setting) {
        String text = value.strip();
        try {
            if (!text.startsWith("{")) {
                text = new String(Base64.getMimeDecoder().decode(text), StandardCharsets.UTF_8);
            }
            return JSON.readTree(text);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new IllegalStateException(setting + " isn't a service-account key file (JSON)", e);
        }
    }

    public String projectId() {
        return projectId;
    }

    /** The access token, made again a few minutes before it runs out. */
    public String accessToken() throws IOException, InterruptedException {
        AccessToken now = token;
        if (now != null && clock.instant().isBefore(now.expires().minus(RENEW_BEFORE))) {
            return now.value();
        }
        synchronized (this) {
            now = token;   // another call may have just made one
            if (now != null && clock.instant().isBefore(now.expires().minus(RENEW_BEFORE))) {
                return now.value();
            }
            AccessToken fresh = signIn();
            token = fresh;
            return fresh.value();
        }
    }

    /** The API refused the token (401): the next call makes a new one. */
    public void forgetToken() {
        token = null;
    }

    private AccessToken signIn() throws IOException, InterruptedException {
        Instant issued = clock.instant();
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer",
                StandardCharsets.UTF_8) + "&assertion=" + jwt(issued);
        HttpRequest request = HttpRequest.newBuilder(tokenUri)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> answer = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (answer.statusCode() != 200) {
            throw new SignInFailed(Integer.toString(answer.statusCode()));
        }
        JsonNode json;
        try {
            json = JSON.readTree(answer.body());
        } catch (JacksonException e) {
            throw new SignInFailed("unreadable answer");
        }
        String value = json.path("access_token").asString("");
        long seconds = json.path("expires_in").asLong(0);
        if (value.isEmpty() || seconds <= 0) {
            throw new SignInFailed("no token in the answer");
        }
        return new AccessToken(value, issued.plusSeconds(seconds));
    }

    /** The signed JWT that asks for a token: this account, the scope, valid for an hour. */
    public String jwt(Instant issued) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        if (!keyId.isEmpty()) {
            header.put("kid", keyId);
        }
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", clientEmail);
        claims.put("scope", scope);
        claims.put("aud", tokenUri.toString());
        claims.put("iat", issued.getEpochSecond());
        claims.put("exp", issued.getEpochSecond() + 3600);
        String signed = b64.encodeToString(JSON.writeValueAsBytes(header)) + "."
                + b64.encodeToString(JSON.writeValueAsBytes(claims));
        try {
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initSign(key);
            rsa.update(signed.getBytes(StandardCharsets.US_ASCII));
            return signed + "." + b64.encodeToString(rsa.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Signing the Google sign-in failed", e);
        }
    }

    /** The PEM private key of the key file (PKCS#8, RSA, between its BEGIN and END marker lines). */
    static PrivateKey privateKey(String pem) throws GeneralSecurityException {
        // The PEM's two marker lines (-----BEGIN ... -----), then every line break and space
        String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder()
                    .decode(base64)));
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("private_key isn't a PEM key", e);
        }
    }

    private static String required(JsonNode account, String field) {
        String value = account.path(field).asString("");
        if (value.isBlank()) {
            throw new IllegalStateException("The service-account key file has no " + field);
        }
        return value;
    }
}
