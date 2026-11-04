package in.apnidukan.auth.service;

import in.apnidukan.auth.repo.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Once a day (03:30 in India): deletes sign-ins that ended more than 30 days ago. Every refresh adds a row and
 * ends the old one, so this table grows fastest of all. 30 days still covers the "a swapped token came back"
 * check, which only ever looks at tokens from the last minutes.
 */
@Component
public class SignInCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(SignInCleanupJob.class);
    static final Duration KEEP = Duration.ofDays(30);

    private final RefreshTokenRepository tokens;
    private final Clock clock;
    private final boolean enabled;

    public SignInCleanupJob(RefreshTokenRepository tokens, Clock clock,
                            @Value("${app.jobs.enabled:true}") boolean enabled) {
        this.tokens = tokens;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Kolkata")
    public void scheduled() {
        if (enabled) {
            runOnce();
        }
    }

    /** Called by tests too; returns how many rows went. */
    @Transactional
    public int runOnce() {
        int gone = tokens.deleteEndedBefore(Instant.now(clock).minus(KEEP));
        log.info("Deleted {} sign-ins that ended more than {} days ago", gone, KEEP.toDays());
        return gone;
    }
}
