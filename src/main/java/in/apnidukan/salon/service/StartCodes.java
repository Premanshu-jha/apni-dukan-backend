package in.apnidukan.salon.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.security.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The 4-digit start code of an online booking, like Ola or Rapido: the customer shows it from My bookings and the
 * salon types it to start the service, which proves the customer is really there. Five wrong codes lock code entry
 * for that booking (only "Start without code" by the owner or a helper works then). Wrong tries are counted in
 * Redis, atomically, so parallel guesses are all counted.
 */
@Component
public class StartCodes {

    static final int MAX_WRONG = 5;
    /** Longer than any booking can wait for its start. */
    private static final Duration WINDOW = Duration.ofDays(2);

    private final SecureRandom random = new SecureRandom();
    private final RateLimiter counters;

    public StartCodes(RateLimiter counters) {
        this.counters = counters;
    }

    public String newCode() {
        return String.format(Locale.ROOT, "%04d", random.nextInt(10_000));
    }

    /** Passes if the code matches; otherwise counts a wrong try and throws (400, or 429 once locked). */
    public void check(UUID appointmentId, String expected, String given) {
        String key = key(appointmentId);
        if (counters.current(key) >= MAX_WRONG) {
            throw locked();
        }
        boolean ok = given != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                given.strip().getBytes(StandardCharsets.US_ASCII));   // same time whatever digit is wrong
        if (ok) {
            return;
        }
        long wrong = counters.hit(key, WINDOW);
        if (wrong >= MAX_WRONG) {
            throw locked();
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "WRONG_START_CODE", "That code doesn't match.",
                Map.of("triesLeft", MAX_WRONG - wrong));
    }

    private static ApiException locked() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "START_CODE_LOCKED",
                "Too many wrong codes. The owner or a helper can use Start without code.");
    }

    private static String key(UUID appointmentId) {
        return "start-code:" + appointmentId;
    }
}
