package in.apnidukan.salon.service;

import in.apnidukan.salon.repo.AppointmentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Every minute:
 * <ul>
 *   <li>an appointment in service ends by itself at its planned end (there is no Done button). If a service runs
 *       long, the app doesn't know; the next customer may wait;</li>
 *   <li>a booking not started 15 minutes after its start becomes a no-show (see
 *       SalonDayService.markNoShows).</li>
 * </ul>
 */
@Component
public class AutoEndJob {

    private static final Logger log = LoggerFactory.getLogger(AutoEndJob.class);

    private final AppointmentStore appointments;
    private final SalonDayService day;
    private final Clock clock;
    private final boolean enabled;

    public AutoEndJob(AppointmentStore appointments, SalonDayService day, Clock clock,
                      @Value("${app.jobs.enabled:true}") boolean enabled) {
        this.appointments = appointments;
        this.day = day;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void scheduled() {
        if (enabled) {
            runOnce();
        }
    }

    /** One round; returns how many appointments it closed (completed plus no-shows). Called by tests too. */
    public int runOnce() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int done = appointments.completeEnded(now);
        int missed = day.markNoShows(now);
        if (done + missed > 0) {
            log.info("Completed {} appointments at their planned end; {} not started became no-shows", done, missed);
        }
        return done + missed;
    }
}
