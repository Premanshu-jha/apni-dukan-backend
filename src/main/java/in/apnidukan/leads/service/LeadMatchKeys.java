package in.apnidukan.leads.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Proof that a phone the page sends back for a business is the one the map gave for it. A scan sends each business's
 * phone with a key (an HMAC of the business's id and its 10-digit phone, keyed by a secret derived from the JWT
 * secret); a refresh uses a phone for matching only when its key checks out. So nobody can send made-up numbers to
 * find out which salon an owner's phone belongs to (an owner's phone is used for matching and never shown).
 */
@Component
public final class LeadMatchKeys {

    private final byte[] secret;

    public LeadMatchKeys(@Value("${app.jwt.secret}") String jwtSecret) {
        // Its own key: never the same bytes the tokens are signed with
        this.secret = hmac(jwtSecret.getBytes(StandardCharsets.UTF_8), "leads-phone-match-v1");
    }

    /** The key for this business's phone (10 digits), or null without a phone. */
    public String of(String mapboxId, String phone10) {
        if (mapboxId == null || phone10 == null) {
            return null;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(secret, mapboxId + "\n" + phone10));
    }

    /** Whether {@code key} is this business's key for this phone (compared in constant time). */
    public boolean valid(String mapboxId, String phone10, String key) {
        String expected = of(mapboxId, phone10);
        return expected != null && key != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), key.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] hmac(byte[] key, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is missing", e);
        }
    }
}
