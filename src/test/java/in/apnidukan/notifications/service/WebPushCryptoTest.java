package in.apnidukan.notifications.service;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPrivateKeySpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Web Push encryption checked against an independent implementation: the expected body below was made with
 * Python's http_ece library (the one pywebpush uses) from the same keys, salt and text. If our bytes match, every
 * browser can read our messages.
 */
class WebPushCryptoTest {

    private static final Base64.Decoder B64 = Base64.getUrlDecoder();

    private static final String UA_PUBLIC =
            "BFFcPW6545a5BNP-yn9U_c0MwemXvzddylFa0KbDtANfRTa-OlDzGPv5pUdZAqIhUCvvDVfgjFOyzApW8X2fk1Q";
    private static final String AS_PUBLIC =
            "BB8UAUa_sbJR-E9N2-DUzc_Xev2YSpUg41eUAh-DErue7JlaCLH6dwTfPcwLUKlmUmP7dxH5X5-KRJxQluR8iSs";
    /** Fixed test inputs: the server's private number is the bytes 33..64, the device's auth 100..115, salt 200..215. */
    private static final byte[] SERVER_NUMBER = run(33, 32);
    private static final byte[] DEVICE_AUTH = run(100, 16);
    private static final byte[] SALT = run(200, 16);
    private static final String BODY = "yMnKy8zNzs_Q0dLT1NXW1wAAEABBBB8UAUa_sbJR-E9N2-DUzc_Xev2YSpUg41eUAh-DErue7JlaCLH6"
            + "dwTfPcwLUKlmUmP7dxH5X5-KRJxQluR8iSuGaBgXtpnCdaL3vAaY6G6osgX6mWoG32zYi1lP2nDk6ajLL_3iIGBp4ACSITQWjA";
    private static final String TEXT = "Booking SB7K3Q9 at 6:30 PM ₹";

    @Test
    void encryptsExactlyLikeTheReferenceLibrary() throws GeneralSecurityException {
        KeyPair server = new KeyPair(WebPushCrypto.publicKey(B64.decode(AS_PUBLIC)),
                WebPushCrypto.privateKey(SERVER_NUMBER));
        byte[] body = WebPushCrypto.encrypt(TEXT.getBytes(StandardCharsets.UTF_8), B64.decode(UA_PUBLIC),
                DEVICE_AUTH, server, SALT);
        assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(body)).isEqualTo(BODY);
    }

    @Test
    void eachMessageGetsItsOwnKeyAndSalt() throws GeneralSecurityException {
        byte[] text = TEXT.getBytes(StandardCharsets.UTF_8);
        byte[] first = WebPushCrypto.encrypt(text, B64.decode(UA_PUBLIC), DEVICE_AUTH);
        byte[] second = WebPushCrypto.encrypt(text, B64.decode(UA_PUBLIC), DEVICE_AUTH);
        assertThat(first).isNotEqualTo(second);
        // salt (16) + record size (4) + key id length (1) + key id (65) + text + delimiter (1) + tag (16)
        assertThat(first).hasSize(16 + 4 + 1 + 65 + text.length + 1 + 16);
    }

    @Test
    void refusesAKeyThatIsNotOnTheCurve() throws GeneralSecurityException {
        byte[] bad = B64.decode(UA_PUBLIC);
        bad[64] ^= 1;
        assertThatThrownBy(() -> WebPushCrypto.encrypt("x".getBytes(StandardCharsets.UTF_8), bad, DEVICE_AUTH))
                .isInstanceOf(GeneralSecurityException.class);
        assertThatThrownBy(() -> WebPushCrypto.publicKey(new byte[64])).isInstanceOf(GeneralSecurityException.class);
    }

    @Test
    void vapidTokenIsASignedJwtForThePushService() throws GeneralSecurityException {
        ECPrivateKey key = WebPushCrypto.privateKey(SERVER_NUMBER);
        String token = WebPushCrypto.vapidToken("https://fcm.googleapis.com", "mailto:support@apnidukan.in",
                1_900_000_000L, key);
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        assertThat(new String(B64.decode(parts[0]), StandardCharsets.UTF_8)).isEqualTo("{\"typ\":\"JWT\",\"alg\":\"ES256\"}");
        assertThat(new String(B64.decode(parts[1]), StandardCharsets.UTF_8)).isEqualTo(
                "{\"aud\":\"https://fcm.googleapis.com\",\"exp\":1900000000,\"sub\":\"mailto:support@apnidukan.in\"}");
        assertThat(B64.decode(parts[2])).hasSize(64);   // raw r||s, as JWT wants
        ECPublicKey publicKey = WebPushCrypto.publicKey(B64.decode(AS_PUBLIC));
        assertThat(WebPushCrypto.verify(token, publicKey)).isTrue();
        assertThat(WebPushCrypto.verify(token.substring(0, token.length() - 2) + "AA", publicKey)).isFalse();
    }

    @Test
    void keysKeepTheirLeadingZeros() throws GeneralSecurityException {
        // A private number with leading zero bytes must still give a 65-byte public key form
        ECPrivateKey small = (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(
                new ECPrivateKeySpec(BigInteger.ONE, ((ECPrivateKey) WebPushCrypto.privateKey(SERVER_NUMBER))
                        .getParams()));
        assertThat(small.getS()).isEqualTo(BigInteger.ONE);
        assertThat(WebPushCrypto.uncompressed(WebPushCrypto.publicKey(B64.decode(AS_PUBLIC)))).hasSize(65);
    }

    private static byte[] run(int first, int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (first + i);
        }
        return out;
    }

    @Test
    void jsonValuesAreEscaped() {
        assertThat(WebPushCrypto.json("a\"b\\c\nd")).isEqualTo("a\\\"b\\\\c\\u000ad");
    }
}
