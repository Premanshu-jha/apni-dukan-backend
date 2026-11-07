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
 * rl:otp-fail:{purpose}:{target}:{net}    wrong codes              10 per day per network → that network locked
 * rl:otp-send-all:{purpose}:{target}      codes sent, any network  past 20 a day: unknown networks wait between
 * rl:otp-fail-all:{purpose}:{target}      wrong codes, any network past 50 a day: unknown networks wait between
 * otp-wait:{send|fail}:{purpose}:{target} the wait running now     1, 2, 4 … 60 minutes
 * otp-known:{purpose}:{target}:{net}      a right code came from this network (30 days): never waits
 * </pre>
 * Limits are counted per target AND network, so a stranger guessing or requesting codes for your number from their
 * own network locks only themselves; you can still sign in from yours. Over all networks together nothing locks a
 * number for the day any more (a stranger with a few networks could keep its owner out): past 20 codes or 50 wrong
 * codes in a day, a network that never gave a right code for it waits between tries, 1 minute, then 2, 4 … up to 60.
 * A network that did (the owner's home or phone network) keeps its own allowance; a fresh network (no wrong code from
 * it today) still gets its first 3 codes; and the right code always works, waiting or not: waits slow down only wrong
 * tries and further codes. Guessing stays bounded: about 70
 * wrong codes a day over every network together, so a 6-digit code would take decades on average.
 * Every attempt is counted <b>before</b> the code is compared, so guesses sent at the same moment can't slip past.
 */
@Service
public class OtpService {

    static final int MAX_SENDS_PER_WINDOW = 3;
    static final Duration SEND_WINDOW = Duration.ofMinutes(10);
    static final int MAX_SENDS_PER_DAY = 10;
    /** Codes a day a network may still get without waiting while the all-network wait runs, if it never failed. */
    static final int FRESH_NETWORK_SENDS = 3;
    /** Codes over all networks in a day before networks that never gave a right code start waiting between sends. */
    static final int WAIT_AFTER_SENDS_ALL_NETWORKS = 20;
    static final int MAX_TRIES_PER_CODE = 5;
    static final int MAX_WRONG_PER_DAY = 10;
    /** Wrong codes over all networks in a day before networks that never gave a right code wait between tries. */
    static final int WAIT_AFTER_WRONG_ALL_NETWORKS = 50;
    /** The first wait past those numbers; each one after it doubles, up to LONGEST_WAIT. */
    static final Duration FIRST_WAIT = Duration.ofMinutes(1);
    static final Duration LONGEST_WAIT = Duration.ofMinutes(60);
    /** A network that gave a right code for a target stays known for it this long. */
    static final Duration KNOWN_FOR = Duration.ofDays(30);
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
        checkNotLocked(net);
        // Past the day's codes over all networks, a network that never gave a right code waits between sends, unless
        // it is fresh: no wrong code from it today and fewer than FRESH_NETWORK_SENDS codes (a stranger's tries can
        // never block a phone signing in for the first time; the networks they use back off)
        boolean fresh = limits.current("otp-fail:" + net) == 0
                && limits.current("otp-send-day:" + net) < FRESH_NETWORK_SENDS;
        if (!fresh && !known(net)) {
            requireNoWait("send", id);
        }
        if (!limits.tryHit("otp-send:" + net, MAX_SENDS_PER_WINDOW, SEND_WINDOW)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_CODES",
                    "Too many codes requested. Try again in 10 minutes.").retryAfter(limits.secondsLeft("otp-send:" + net));
        }
        if (!limits.tryHit("otp-send-day:" + net, MAX_SENDS_PER_DAY, DAY)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_CODES",
                    "Too many codes requested today. Try again tomorrow.")
                    .retryAfter(limits.secondsLeft("otp-send-day:" + net));
        }
        long all = limits.hit("otp-send-all:" + id, DAY);
        if (all > WAIT_AFTER_SENDS_ALL_NETWORKS) {
            startWait("send", id, all - WAIT_AFTER_SENDS_ALL_NETWORKS);   // for the next code from an unknown network
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
        checkNotLocked(net);
        // A wait over all networks slows down wrong tries only: the right code always works (the 5 tries per code
        // and the 10 wrong codes a day per network still bound guessing), so a real person on a new network is never
        // kept out by a stranger's tries
        long waitLeft = known(net) ? 0 : waitLeft("fail", id);
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
            // This network proved it reaches the code's owner: from now on it keeps its own allowance
            redis.opsForValue().set(knownKey(net), "1", KNOWN_FOR);
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
        if (wrongAll > WAIT_AFTER_WRONG_ALL_NETWORKS) {
            startWait("fail", id, wrongAll - WAIT_AFTER_WRONG_ALL_NETWORKS);   // for the next try from an unknown one
        }
        if (wrongHere >= MAX_WRONG_PER_DAY) {
            redis.delete(codeKey);
            throw lockedForTheDay(net);
        }
        if (attemptNo == MAX_TRIES_PER_CODE) {
            redis.delete(codeKey);
            throw tooManyAttempts();
        }
        if (waitLeft > 0) {
            throw tryLater(waitLeft);
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "WRONG_CODE", "That code is not correct",
                Map.of("attemptsLeft", MAX_TRIES_PER_CODE - attemptNo));
    }

    /** 429 LOCKED_FOR_TODAY after 10 wrong codes from this network today (only this network: the guesser's own). */
    private void checkNotLocked(String net) {
        if (limits.current("otp-fail:" + net) >= MAX_WRONG_PER_DAY) {
            throw lockedForTheDay(net);
        }
    }

    private boolean known(String net) {
        return Boolean.TRUE.equals(redis.hasKey(knownKey(net)));
    }

    /** 429 TRY_LATER while a wait over all networks is running (only networks that never gave a right code wait). */
    private void requireNoWait(String kind, String id) {
        long left = waitLeft(kind, id);
        if (left > 0) {
            throw tryLater(left);
        }
    }

    /** Seconds left of the wait running now over all networks (0: none). */
    private long waitLeft(String kind, String id) {
        Long left = redis.getExpire(waitKey(kind, id));
        return left == null || left < 0 ? 0 : left;
    }

    private static ApiException tryLater(long seconds) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TRY_LATER",
                "Many tries for this number today. For its owner's safety, wait a little and try again.")
                .retryAfter(seconds);
    }

    /**
     * Starts the wait before the next try from an unknown network: 1 minute for the first one past the day's number,
     * then twice as long each time (the count over that number is {@code over}), at most LONGEST_WAIT.
     */
    private void startWait(String kind, String id, long over) {
        redis.opsForValue().set(waitKey(kind, id), "1", waitFor(over));
    }

    /** The wait for each try past the day's number, by how far past it is (1 → 1 minute, 2 → 2, … at most 60). */
    static Duration waitFor(long over) {
        long minutes = over >= 7 ? LONGEST_WAIT.toMinutes()
                : Math.min(LONGEST_WAIT.toMinutes(), FIRST_WAIT.toMinutes() << (int) (over - 1));
        return Duration.ofMinutes(minutes);
    }

    private static String knownKey(String net) {
        return "otp-known:" + net;
    }

    private static String waitKey(String kind, String id) {
        return "otp-wait:" + kind + ":" + id;
    }

    private static ApiException codeExpired() {
        return ApiException.badRequest("CODE_EXPIRED", "This code has expired. Ask for a new one.");
    }

    private static ApiException tooManyAttempts() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                "Too many wrong codes. Ask for a new one.").retryAfter(1);
    }

    private ApiException lockedForTheDay(String net) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LOCKED_FOR_TODAY",
                "Too many wrong codes from this network today. For your safety, try again tomorrow.")
                .retryAfter(limits.secondsLeft("otp-fail:" + net));
    }

    private static String codeKey(OtpPurpose purpose, String target) {
        return "otp:" + purpose.name() + ":" + target;
    }

    private String hash(String target, String code) {
        return Hashing.hmacSha256Hex(hashKey, target + ":" + code);
    }
}
