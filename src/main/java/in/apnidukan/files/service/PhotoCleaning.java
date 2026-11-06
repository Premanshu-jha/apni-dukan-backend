package in.apnidukan.files.service;

import in.apnidukan.common.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Cleans photos (PhotoCleaner) a few at a time per server. Drawing a photo again holds up to about 30 MB while it
 * runs (a 2,560 px decode plus the 1,280 px copy), so 50 owners uploading at the same moment must not mean 50 at
 * once: the rest wait their turn, and after 10 seconds get 503 PHOTOS_BUSY ("try again") instead of the server
 * running out of memory.
 */
@Component
class PhotoCleaning {

    static final Duration MAX_WAIT = Duration.ofSeconds(10);

    /** Package-private for the test, which takes every turn to see the busy answer. */
    final Semaphore turns;
    private final Duration maxWait;

    @org.springframework.beans.factory.annotation.Autowired
    PhotoCleaning(@Value("${app.files.photo-cleaning-at-once:4}") int atOnce) {
        this(atOnce, MAX_WAIT);
    }

    PhotoCleaning(int atOnce, Duration maxWait) {
        if (atOnce < 1) {
            throw new IllegalStateException("app.files.photo-cleaning-at-once must be at least 1");
        }
        this.turns = new Semaphore(atOnce, true);
        this.maxWait = maxWait;
    }

    byte[] clean(byte[] original) {
        boolean got;
        try {
            got = turns.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            got = false;
        }
        if (!got) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PHOTOS_BUSY",
                    "Lots of photos are being sent right now. Try again in a minute.");
        }
        try {
            return PhotoCleaner.clean(original);
        } finally {
            turns.release();
        }
    }
}
