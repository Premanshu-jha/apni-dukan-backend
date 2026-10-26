package in.apnidukan.auth.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.security.JwtProperties;
import in.apnidukan.common.security.RateLimiter;
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
 * otp:{purpose}:{target}          keyed hash of the code     expires with the code (5-10 min)
 * otp:{purpose}:{target}:tries    attempts on this code      the 5th wrong attempt destroys the code
 * rl:otp-send:{purpose}:{target}  codes sent                 max 3 per 10 minutes and 10 per day
 * rl:otp-fail:{purpose}:{target}  wrong codes in a day       10 → no more codes or attempts for a day
 * </pre>
 * Why the daily limits: 5 tries per code × 3 codes per 10 minutes would still allow ~2,000 guesses a day on one
 * phone. With 10 wrong codes a day, guessing a 6-digit code would take on average over 100 years.
 * Every attempt is counted <b>before</b> the code is compared, so many guesses sent at the same moment can't
 * slip past the limit.
 */
@Service
public class OtpService {

    static final int MAX_SENDS_PER_WINDOW = 3;
    static final Duration SEND_WINDOW = Duration.ofMinutes(10);
    static final int MAX_SENDS_PER_DAY = 10;
    static final int MAX_TRIES_PER_CODE = 5;
    static final int MAX_WRONG_PER_DAY = 10;
    static final Duration DAY = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final RateLimiter limits;
    private final byte[] hashKey;
    private final SecureRandom random = new SecureRandom();

    public OtpService(StringRedisTemplate redis, RateLimiter limits, JwtProperties jwt) {
        this.redis = redis;
        this.limits = limits;
        this.hashKey = ("otp:" + jwt.secret()).getBytes(StandardCharsets.UTF_8);
    }

    /** Creates a new 6-digit code for this target and returns it so the caller can send it. */
    public String issue(OtpPurpose purpose, String target) {
        String id = purpose.name() + ":" + target;
        if (limits.current("otp-fail:" + id) >= MAX_WRONG_PER_DAY) {
            throw lockedForTheDay();
        }
        if (!limits.tryHit("otp-send:" + id, MAX_SENDS_PER_WINDOW, SEND_WINDOW)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_CODES",
                    "Too many codes requested. Try again in 10 minutes.");
        }
        if (!limits.tryHit("otp-send-day:" + id, MAX_SENDS_PER_DAY, DAY)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_CODES",
                    "Too many codes requested today. Try again tomorrow.");
        }
        String code = "%06d".formatted(random.nextInt(1_000_000));
        String codeKey = codeKey(purpose, target);
        redis.opsForValue().set(codeKey, hash(target, code), purpose.validFor());
        redis.delete(codeKey + ":tries");
        return code;
    }

    /**
     * Checks a code. A correct code works exactly once. Wrong attempts 1-4 answer WRONG_CODE with the attempts
     * left; the 5th wrong attempt destroys the code (TOO_MANY_ATTEMPTS) and a new one must be requested.
     */
    public void verify(OtpPurpose purpose, String target, String code) {
        String id = purpose.name() + ":" + target;
        String codeKey = codeKey(purpose, target);
        if (limits.current("otp-fail:" + id) >= MAX_WRONG_PER_DAY) {
            redis.delete(codeKey);
            throw lockedForTheDay();
        }
        String stored = redis.opsForValue().get(codeKey);
        if (stored == null) {
            throw codeExpired();
        }
        // Count the attempt first: of many guesses arriving together, only the first 5 are ever compared.
        String triesKey = codeKey + ":tries";
        long attemptNo = RateLimiter.incrementWithExpiry(redis, triesKey, purpose.validFor());
        if (attemptNo > MAX_TRIES_PER_CODE) {
            redis.delete(codeKey);
            throw tooManyAttempts();
        }
        if (code != null && Hashing.sameHash(stored, hash(target, code.trim()))) {
            // Single use: only the request that actually deletes the key wins, even if two arrive together.
            if (!Boolean.TRUE.equals(redis.delete(codeKey))) {
                throw codeExpired();
            }
            redis.delete(triesKey);
            return;
        }
        long wrongToday = limits.hit("otp-fail:" + id, DAY);
        if (attemptNo == MAX_TRIES_PER_CODE || wrongToday >= MAX_WRONG_PER_DAY) {
            redis.delete(codeKey);
            throw wrongToday >= MAX_WRONG_PER_DAY ? lockedForTheDay() : tooManyAttempts();
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "WRONG_CODE", "That code is not correct",
                Map.of("attemptsLeft", MAX_TRIES_PER_CODE - attemptNo));
    }

    private static ApiException codeExpired() {
        return ApiException.badRequest("CODE_EXPIRED", "This code has expired. Ask for a new one.");
    }

    private static ApiException tooManyAttempts() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                "Too many wrong codes. Ask for a new one.");
    }

    private static ApiException lockedForTheDay() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LOCKED_FOR_TODAY",
                "Too many wrong codes today. For your safety, try again tomorrow.");
    }

    private static String codeKey(OtpPurpose purpose, String target) {
        return "otp:" + purpose.name() + ":" + target;
    }

    private String hash(String target, String code) {
        return Hashing.hmacSha256Hex(hashKey, target + ":" + code);
    }
}
