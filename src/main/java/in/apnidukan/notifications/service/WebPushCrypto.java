package in.apnidukan.notifications.service;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * The cryptography of Web Push, with the JDK only (no extra library):
 * <ul>
 *   <li>Message encryption, RFC 8291 with the aes128gcm content coding of RFC 8188: only the device can read the
 *       message, not the push service (Google, Mozilla, Apple) that carries it.</li>
 *   <li>VAPID, RFC 8292: a short signed token that proves the message comes from Apni Dukan's server, whose public
 *       key the browser was given when it subscribed.</li>
 * </ul>
 * Keys are P-256 (secp256r1). Public keys travel as 65 bytes: 0x04, then x and y, 32 bytes each.
 */
public final class WebPushCrypto {

    /** Size of one encrypted record; our messages are far smaller, so there is always exactly one. */
    static final int RECORD_SIZE = 4096;
    /** AES-GCM tag (16) and the padding delimiter (1) that every record carries on top of the text. */
    private static final int OVERHEAD = 17;
    private static final ECParameterSpec P256 = p256();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private WebPushCrypto() {
    }

    /**
     * Encrypts a message for one device.
     * @param uaPublic   the device's public key (the subscription's p256dh, decoded)
     * @param authSecret the device's 16-byte secret (the subscription's auth, decoded)
     * @return the request body to POST to the device's endpoint (with Content-Encoding: aes128gcm)
     */
    public static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret)
            throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, uaPublic, authSecret, generator.generateKeyPair(), salt);
    }

    /** Same, with a given one-off server key pair and salt (fixed values let a test check the exact output). */
    static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair asKeys, byte[] salt)
            throws GeneralSecurityException {
        if (plaintext.length > RECORD_SIZE - OVERHEAD) {
            throw new IllegalArgumentException("Message too long for one record: " + plaintext.length + " bytes");
        }
        if (authSecret.length != 16 || salt.length != 16) {
            throw new IllegalArgumentException("The auth secret and the salt must be 16 bytes");
        }
        byte[] asPublic = uncompressed((ECPublicKey) asKeys.getPublic());

        // RFC 8291 section 3.3: combine the shared secret with the device's auth secret
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(asKeys.getPrivate());
        agreement.doPhase(publicKey(uaPublic), true);
        byte[] ecdhSecret = agreement.generateSecret();
        byte[] prkKey = hmac(authSecret, ecdhSecret);
        byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
        byte[] ikm = hmac(prkKey, concat(keyInfo, new byte[] {1}));

        // RFC 8188 section 2.2 and 2.3: the content key and nonce, from the salt
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, concat(
                "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[] {1})), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, concat(
                "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[] {1})), 12);

        // One record, and it is the last: the text, then the delimiter 0x02, no further padding
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = aes.doFinal(concat(plaintext, new byte[] {2}));

        // Header: salt (16), record size (4), key id length (1), key id = the server's one-off public key (65)
        ByteBuffer body = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length + ciphertext.length);
        body.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic).put(ciphertext);
        return body.array();
    }

    /**
     * The VAPID token for one push service: a JWT signed with ES256.
     * @param audience the push service's origin, e.g. https://fcm.googleapis.com
     * @param subject  how the push service can contact us, e.g. mailto:support@clozest.in
     * @param expiresAtEpochSeconds at most 24 hours ahead (RFC 8292)
     */
    public static String vapidToken(String audience, String subject, long expiresAtEpochSeconds,
                                    ECPrivateKey key) throws GeneralSecurityException {
        String header = B64.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = B64.encodeToString(("{\"aud\":\"" + json(audience) + "\",\"exp\":" + expiresAtEpochSeconds
                + ",\"sub\":\"" + json(subject) + "\"}").getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + claims;
        // P1363 format = the raw 64 bytes r||s that JWT wants (the plain "SHA256withECDSA" gives DER instead)
        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(key);
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + B64.encodeToString(signer.sign());
    }

    /** True if the signature on a token made by vapidToken checks out with this public key. */
    static boolean verify(String token, ECPublicKey key) throws GeneralSecurityException {
        int lastDot = token.lastIndexOf('.');
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(key);
        verifier.update(token.substring(0, lastDot).getBytes(StandardCharsets.US_ASCII));
        return verifier.verify(Base64.getUrlDecoder().decode(token.substring(lastDot + 1)));
    }

    /** A P-256 public key from its 65-byte form. Refuses anything that isn't a point on the curve. */
    public static ECPublicKey publicKey(byte[] uncompressed) throws GeneralSecurityException {
        if (uncompressed.length != 65 || uncompressed[0] != 4) {
            throw new GeneralSecurityException("A public key must be 65 bytes starting with 0x04");
        }
        ECPoint point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33)),
                new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65)));
        // A point that isn't on the curve (a crafted key) is refused: here by the key factory, or at the latest by
        // ECDH, which also checks it
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, P256));
    }

    /** A P-256 private key from its 32-byte secret number. */
    public static ECPrivateKey privateKey(byte[] d) throws GeneralSecurityException {
        if (d.length != 32) {
            throw new GeneralSecurityException("A private key must be 32 bytes");
        }
        return (ECPrivateKey) KeyFactory.getInstance("EC")
                .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, d), P256));
    }

    /** The 65-byte form of a public key. */
    public static byte[] uncompressed(ECPublicKey key) {
        return concat(new byte[] {4}, fixed32(key.getW().getAffineX()), fixed32(key.getW().getAffineY()));
    }

    /** A number as exactly 32 bytes, big-endian (BigInteger may add a sign byte or drop leading zeros). */
    private static byte[] fixed32(BigInteger n) {
        byte[] raw = n.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** Escapes a value for a JSON string. The values here are our own settings, but stay safe anyway. */
    static String json(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            return params.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("This Java has no P-256 curve", e);
        }
    }
}
