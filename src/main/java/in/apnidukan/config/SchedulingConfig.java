package in.apnidukan.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the background jobs (@Scheduled): sending notifications and ending appointments at their planned end.
 * Each job also checks app.jobs.enabled, which tests set to false so they can run a job exactly when they want.
 * Jobs are safe on several servers at once (see NotificationStore.claimDue and AppointmentStore.completeEnded).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
