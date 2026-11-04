package in.apnidukan.notifications.service;

import in.apnidukan.notifications.repo.NotificationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Once a day (03:40 in India): deletes messages that finished (sent, failed, cancelled, no device) more than
 * 90 days ago. Bookings and their history (appointment_events) are kept; a message is only a copy of what was
 * said about them.
 */
@Component
public class MessageCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(MessageCleanupJob.class);
    static final Duration KEEP = Duration.ofDays(90);

    private final NotificationStore jobs;
    private final Clock clock;
    private final boolean enabled;

    public MessageCleanupJob(NotificationStore jobs, Clock clock, @Value("${app.jobs.enabled:true}") boolean enabled) {
        this.jobs = jobs;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(cron = "0 40 3 * * *", zone = "Asia/Kolkata")
    public void scheduled() {
        if (enabled) {
            runOnce();
        }
    }

    /** Called by tests too; returns how many rows went. */
    public int runOnce() {
        int gone = jobs.deleteFinishedBefore(Instant.now(clock).minus(KEEP));
        log.info("Deleted {} messages finished more than {} days ago", gone, KEEP.toDays());
        return gone;
    }
}
