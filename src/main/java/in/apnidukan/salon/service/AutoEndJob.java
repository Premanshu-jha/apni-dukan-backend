package in.apnidukan.salon.service;

import in.apnidukan.salon.repo.AppointmentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

/**
 * Every minute: an appointment in service ends by itself at its planned end (there is no Done button). If a
 * service runs long, the app doesn't know; the next customer may wait. A booking nobody started is left CONFIRMED
 * (the day screen shows "not started"), because ending it would hide a no-show.
 */
@Component
public class AutoEndJob {

    private static final Logger log = LoggerFactory.getLogger(AutoEndJob.class);

    private final AppointmentStore appointments;
    private final Clock clock;
    private final boolean enabled;

    public AutoEndJob(AppointmentStore appointments, Clock clock, @Value("${app.jobs.enabled:true}") boolean enabled) {
        this.appointments = appointments;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void scheduled() {
        if (enabled) {
            runOnce();
        }
    }

    /** One round (a single SQL statement); returns how many appointments were completed. Called by tests too. */
    public int runOnce() {
        int done = appointments.completeEnded(clock.instant().truncatedTo(ChronoUnit.MICROS));
        if (done > 0) {
            log.info("Completed {} appointments at their planned end", done);
        }
        return done;
    }
}
