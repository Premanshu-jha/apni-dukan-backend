package in.apnidukan.auth.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.security.JwtProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;

/**
 * One-time codes (phone OTP, email codes) kept in Redis Cloud, never in Postgres.
 *
 * <pre>
 * otp:{purpose}:{target}        keyed hash of the code      expires with the code (5-10 min)
 * otp:{purpose}:{target}:tries  wrong guesses so far        max 5, then the code is destroyed
 * otp-rate:{purpose}:{target}   codes sent in this window   max 3 per 10 minutes
 * </pre>
 * Redis INCR is atomic, so counting stays correct even when two requests arrive at the same moment.
 */
@Service
public class OtpService {

    static final int MAX_SENDS_PER_WINDOW = 3;
    static final Duration SEND_WINDOW = Duration.ofMinutes(10);
    static final int MAX_WRONG_TRIES = 5;

    private final StringRedisTemplate redis;
    private final byte[] hashKey;
    private final SecureRandom random = new SecureRandom();

    public OtpService(StringRedisTemplate redis, JwtProperties jwt) {
        this.redis = redis;
        this.hashKey = ("otp:" + jwt.secret()).getBytes(StandardCharsets.UTF_8);
    }

    /** Creates a new 6-digit code for this target and returns it so the caller can send it. */
    public String issue(OtpPurpose purpose, String target) {
        String rateKey = "otp-rate:" + purpose + ":" + target;
        Long sends = redis.opsForValue().increment(rateKey);
        if (sends != null && sends == 1) {
            redis.expire(rateKey, SEND_WINDOW);
        }
        if (sends != null && sends > MAX_SENDS_PER_WINDOW) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_CODES",
                    "Too many codes requested. Try again in 10 minutes.");
        }
        String code = "%06d".formatted(random.nextInt(1_000_000));
        String codeKey = codeKey(purpose, target);
        redis.opsForValue().set(codeKey, hash(target, code), purpose.validFor());
        redis.delete(codeKey + ":tries");
        return code;
    }

    /** Checks a code. A correct code works exactly once; 5 wrong guesses destroy it. */
    public void verify(OtpPurpose purpose, String target, String code) {
        String codeKey = codeKey(purpose, target);
        String triesKey = codeKey + ":tries";
        String stored = redis.opsForValue().get(codeKey);
        if (stored == null) {
            throw ApiException.badRequest("CODE_EXPIRED", "This code has expired. Ask for a new one.");
        }
        Long tries = redis.opsForValue().increment(triesKey);
        if (tries != null && tries == 1) {
            redis.expire(triesKey, purpose.validFor());
        }
        if (tries != null && tries > MAX_WRONG_TRIES) {
            redis.delete(codeKey);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                    "Too many wrong codes. Ask for a new one.");
        }
        if (code == null || !Hashing.sameHash(stored, hash(target, code.trim()))) {
            long left = MAX_WRONG_TRIES - (tries == null ? 0 : tries);
            throw new ApiException(HttpStatus.BAD_REQUEST, "WRONG_CODE", "That code is not correct",
                    Map.of("attemptsLeft", Math.max(0, left)));
        }
        // Single use: only the request that actually deletes the key wins, even if two arrive together.
        if (!Boolean.TRUE.equals(redis.delete(codeKey))) {
            throw ApiException.badRequest("CODE_EXPIRED", "This code has expired. Ask for a new one.");
        }
        redis.delete(triesKey);
    }

    private static String codeKey(OtpPurpose purpose, String target) {
        return "otp:" + purpose + ":" + target;
    }

    private String hash(String target, String code) {
        return Hashing.hmacSha256Hex(hashKey, target + ":" + code);
    }
}
