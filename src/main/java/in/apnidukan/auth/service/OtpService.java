package in.apnidukan.auth.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.security.ClientIp;
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
 * otp:{purpose}:{target}                  keyed hash of the code   expires with the code (5-10 min)
 * otp:{purpose}:{target}:tries            attempts on this code    the 5th wrong attempt destroys the code
 * rl:otp-send:{purpose}:{target}:{net}    codes sent               3 per 10 min and 10 per day, per network
 * rl:otp-send-all:{purpose}:{target}      codes sent, any network  20 per day
 * rl:otp-fail:{purpose}:{target}:{net}    wrong codes              10 per day per network → that network locked
 * rl:otp-fail-all:{purpose}:{target}      wrong codes, any network 50 per day → locked for everyone
 * </pre>
 * Limits are counted per target AND network, so a stranger guessing or requesting codes for your number from their
 * own network locks only themselves; you can still sign in from yours. The higher "any network" caps still bound
 * guessing: 50 wrong codes a day on a 6-digit code would take over 50 years on average.
 * Every attempt is counted <b>before</b> the code is compared, so guesses sent at the same moment can't slip past.
 */
@Service
public class OtpService {

    static final int MAX_SENDS_PER_WINDOW = 3;
    static final Duration SEND_WINDOW = Duration.ofMinutes(10);
    static final int MAX_SENDS_PER_DAY = 10;
    static final int MAX_SENDS_PER_DAY_ALL_NETWORKS = 20;
    static final int MAX_TRIES_PER_CODE = 5;
    static final int MAX_WRONG_PER_DAY = 10;
    static final int MAX_WRONG_PER_DAY_ALL_NETWORKS = 50;
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
    public String issue(OtpPurpose purpose, String target, String ip) {
        String id = purpose.name() + ":" + target;
        String net = id + ":" + ClientIp.limitKey(ip);
        checkNotLocked(id, net);
        if (!limits.tryHit("otp-send:" + net, MAX_SENDS_PER_WINDOW, SEND_WINDOW)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_CODES",
                    "Too many codes requested. Try again in 10 minutes.").retryAfter(limits.secondsLeft("otp-send:" + net));
        }
        if (!limits.tryHit("otp-send-day:" + net, MAX_SENDS_PER_DAY, DAY)
                || !limits.tryHit("otp-send-all:" + id, MAX_SENDS_PER_DAY_ALL_NETWORKS, DAY)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_CODES",
                    "Too many codes requested today. Try again tomorrow.")
                    .retryAfter(Math.max(limits.secondsLeft("otp-send-day:" + net), limits.secondsLeft("otp-send-all:" + id)));
        }
        String code = "%06d".formatted(random.nextInt(1_000_000));
        String codeKey = codeKey(purpose, target);
        // Reset the tries first, so no attempt can be counted on the old counter and then wiped
        redis.delete(codeKey + ":tries");
        redis.opsForValue().set(codeKey, hash(target, code), purpose.validFor());
        return code;
    }

    /**
     * Checks a code. A correct code works exactly once. Wrong attempts 1-4 answer WRONG_CODE with the attempts
     * left; the 5th wrong attempt destroys the code (TOO_MANY_ATTEMPTS) and a new one must be requested.
     */
    public void verify(OtpPurpose purpose, String target, String code, String ip) {
        compare(purpose, target, code, ip, true);
    }

    /**
     * Like verify, but a right code stays (single use is up to consume). For two codes that must both be right
     * (recovery): check both, then consume both, so a typo in one doesn't use up the other.
     */
    public void check(OtpPurpose purpose, String target, String code, String ip) {
        compare(purpose, target, code, ip, false);
    }

    /** Uses up a code that check accepted. CODE_EXPIRED if someone else used it in the meantime. */
    public void consume(OtpPurpose purpose, String target) {
        String codeKey = codeKey(purpose, target);
        if (!Boolean.TRUE.equals(redis.delete(codeKey))) {
            throw codeExpired();
        }
        redis.delete(codeKey + ":tries");
    }

    private void compare(OtpPurpose purpose, String target, String code, String ip, boolean useUp) {
        String id = purpose.name() + ":" + target;
        String net = id + ":" + ClientIp.limitKey(ip);
        String codeKey = codeKey(purpose, target);
        checkNotLocked(id, net);
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
            if (!useUp) {
                return;
            }
            // Single use: only the request that actually deletes the key wins, even if two arrive together.
            if (!Boolean.TRUE.equals(redis.delete(codeKey))) {
                throw codeExpired();
            }
            redis.delete(triesKey);
            return;
        }
        long wrongHere = limits.hit("otp-fail:" + net, DAY);
        long wrongAll = limits.hit("otp-fail-all:" + id, DAY);
        if (wrongHere >= MAX_WRONG_PER_DAY || wrongAll >= MAX_WRONG_PER_DAY_ALL_NETWORKS) {
            redis.delete(codeKey);
            throw lockedForTheDay(id, net);
        }
        if (attemptNo == MAX_TRIES_PER_CODE) {
            redis.delete(codeKey);
            throw tooManyAttempts();
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "WRONG_CODE", "That code is not correct",
                Map.of("attemptsLeft", MAX_TRIES_PER_CODE - attemptNo));
    }

    private void checkNotLocked(String id, String net) {
        if (limits.current("otp-fail:" + net) >= MAX_WRONG_PER_DAY
                || limits.current("otp-fail-all:" + id) >= MAX_WRONG_PER_DAY_ALL_NETWORKS) {
            throw lockedForTheDay(id, net);
        }
    }

    private static ApiException codeExpired() {
        return ApiException.badRequest("CODE_EXPIRED", "This code has expired. Ask for a new one.");
    }

    private static ApiException tooManyAttempts() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                "Too many wrong codes. Ask for a new one.").retryAfter(1);
    }

    private ApiException lockedForTheDay(String id, String net) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LOCKED_FOR_TODAY",
                "Too many wrong codes today. For your safety, try again tomorrow.")
                .retryAfter(Math.max(limits.secondsLeft("otp-fail:" + net), limits.secondsLeft("otp-fail-all:" + id)));
    }

    private static String codeKey(OtpPurpose purpose, String target) {
        return "otp:" + purpose.name() + ":" + target;
    }

    private String hash(String target, String code) {
        return Hashing.hmacSha256Hex(hashKey, target + ":" + code);
    }
}
