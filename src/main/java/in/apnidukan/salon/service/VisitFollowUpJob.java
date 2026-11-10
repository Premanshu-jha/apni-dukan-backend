package in.apnidukan.salon.service;

import in.apnidukan.common.jobs.JobLeases;
import in.apnidukan.common.jobs.LongStatements;
import in.apnidukan.common.notify.Notifications;
import in.apnidukan.common.notify.Notifications.Notice;
import in.apnidukan.salon.domain.SalonTime;
import in.apnidukan.salon.repo.VisitFollowUpStore;
import in.apnidukan.salon.repo.VisitFollowUpStore.Prompt;
import in.apnidukan.salon.repo.VisitFollowUpStore.Reminder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Every minute, after online bookings ended (rows written by the automatic end and Finish, VisitFollowUpStore):
 * <ul>
 *   <li>"How was your visit?" 10 minutes after the end (a visit that ends late in the evening: at 9:00 the next
 *       morning), unless the customer rated it already, works in that salon, deleted the account, or the salon is no
 *       longer live. Not sent at all if it is over a day late (the job was down): by then it would be noise;</li>
 *   <li>"Time for your next haircut?" at 10:00 India time, the services' come-back interval after the visit, unless
 *       the customer switched these off, deleted the account, works in that salon, already has a booking coming up or
 *       visited again since (at any salon), or the salon is no longer live, has paused online bookings, or no longer
 *       offers any of those services. It names, and its link picks, only the services due
 *       again (a visit with a haircut every 3 weeks and a hair wash, 0, is about the haircut), at most
 *       {@value #MAX_LINK_SERVICES} of them, so the link fits.</li>
 * </ul>
 * Both go out only between 9:00 and 21:00 India time: a round outside those hours leaves them for the morning.
 * Both are in-app messages and Web Push (BOOKING_ types: the customer app's), never SMS unless a template is set for
 * the type. Each row is taken off the queue in the same transaction that queues its message, so nothing is sent twice.
 */
@Component
public class VisitFollowUpJob {

    private static final Logger log = LoggerFactory.getLogger(VisitFollowUpJob.class);

    static final String RATE_TYPE = "BOOKING_RATE_VISIT";
    static final String COME_BACK_TYPE = "BOOKING_COME_BACK";
    /** The most services a come-back link picks (notification_jobs.url is 300 characters; each id takes 37). */
    static final int MAX_LINK_SERVICES = 5;
    /** A rating request this late is dropped, not sent. */
    static final Duration PROMPT_STALE_AFTER = Duration.ofDays(1);
    /** Rating requests and come-back reminders go out only in these hours (India time). */
    static final LocalTime REMIND_FROM = LocalTime.of(9, 0);
    static final LocalTime REMIND_UNTIL = LocalTime.of(21, 0);
    private static final Duration MAX_ROUND = Duration.ofMinutes(5);

    private final VisitFollowUpStore followUps;
    private final Notifications notifications;
    private final JobLeases leases;
    private final LongStatements statements;
    private final Clock clock;
    private final boolean enabled;
    private final int batchSize;

    public VisitFollowUpJob(VisitFollowUpStore followUps, Notifications notifications, JobLeases leases,
                            LongStatements statements, Clock clock,
                            @Value("${app.jobs.enabled:true}") boolean enabled,
                            @Value("${app.jobs.follow-up-batch:500}") int batchSize) {
        if (batchSize < 1) {   // 0 would loop forever (every batch "full"), below 0 fail every minute
            throw new IllegalStateException("app.jobs.follow-up-batch must be at least 1");
        }
        this.followUps = followUps;
        this.notifications = notifications;
        this.leases = leases;
        this.statements = statements;
        this.clock = clock;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void scheduled() {
        if (enabled) {
            // One server at a time does the round; the others skip it
            leases.runExclusively("visit-follow-ups", MAX_ROUND, this::runOnce);
        }
    }

    /** One round, in batches until nothing is due; returns how many messages it queued. Called by tests too. */
    public int runOnce() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int sent = 0;
        int taken;
        if (remindingHours(now)) {   // nothing at night: a visit done at 23:55 is asked about at 9:00
            do {
                int[] counts = statements.run(() -> prompts(now));
                taken = counts[0];
                sent += counts[1];
            } while (taken == batchSize);
            do {
                int[] counts = statements.run(() -> reminders(now));
                taken = counts[0];
                sent += counts[1];
            } while (taken == batchSize);
        }
        if (sent > 0) {
            log.info("Queued {} rating requests and come-back reminders", sent);
        }
        return sent;
    }

    /** One batch of rating requests: {taken off the queue, queued as messages}. */
    private int[] prompts(Instant now) {
        List<Prompt> due = followUps.takePrompts(now, batchSize);
        List<Notice> out = new ArrayList<>();
        for (Prompt p : due) {
            if (p.rated() || p.worksThere() || !p.salonLive() || !p.accountLive() || p.completedAt().isBefore(now.minus(PROMPT_STALE_AFTER))) {
                continue;
            }
            out.add(new Notice(p.customerId(), RATE_TYPE, p.appointmentId(), now, "How was your visit?",
                    "Rate your " + services(p.services()) + " at " + p.salonName()
                            + ". It helps others choose, and tells the salon what went well.",
                    "/bookings/" + p.appointmentId() + "?rate=1", java.util.Map.of()));
        }
        if (!out.isEmpty()) {
            notifications.queueAll(out);
        }
        return new int[] {due.size(), out.size()};
    }

    /** One batch of come-back reminders: {taken off the queue, queued as messages}. */
    private int[] reminders(Instant now) {
        List<Reminder> due = followUps.takeReminders(now, batchSize);
        List<Notice> out = new ArrayList<>();
        LocalDate today = LocalDate.ofInstant(now, SalonTime.ZONE);
        for (Reminder r : due) {
            if (!send(r)) {
                continue;
            }
            long days = ChronoUnit.DAYS.between(LocalDate.ofInstant(r.completedAt(), SalonTime.ZONE), today);
            String what = services(r.serviceNames().subList(0, Math.min(MAX_LINK_SERVICES, r.serviceNames().size())));
            out.add(new Notice(r.customerId(), COME_BACK_TYPE, r.appointmentId(), now,
                    "Time for your next " + what + "?",
                    "It's been " + ago(days) + " since your " + what + " at " + r.salonName()
                            + ". Book your next visit in a few taps.",
                    "/salons/" + r.shopId() + "?services="
                            + r.serviceIds().stream().limit(MAX_LINK_SERVICES).map(UUID::toString)
                                    .collect(Collectors.joining(",")),
                    java.util.Map.of()));
        }
        if (!out.isEmpty()) {
            notifications.queueAll(out);
        }
        return new int[] {due.size(), out.size()};
    }

    /** Whether a due reminder should go out (see the class comment). */
    static boolean send(Reminder r) {
        return r.salonApproved() && r.wantsReminders() && !r.hasUpcoming() && !r.visitedSince()
                && !r.serviceIds().isEmpty();
    }

    /** Whether rating requests and come-back reminders may go out now (India time, 9:00 to 21:00). */
    static boolean remindingHours(Instant now) {
        LocalTime t = LocalTime.ofInstant(now, SalonTime.ZONE);
        return !t.isBefore(REMIND_FROM) && t.isBefore(REMIND_UNTIL);
    }

    /** "Haircut", "Haircut and Beard trim", "Haircut, Beard trim and Facial". */
    static String services(List<String> names) {
        if (names.isEmpty()) {
            return "visit";
        }
        if (names.size() == 1) {
            return names.get(0);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    /** "a week", "2 weeks", "10 days", "a month", "3 months". */
    static String ago(long days) {
        if (days >= 60) {
            return (days / 30) + " months";
        }
        if (days >= 28 && days < 35) {
            return "a month";
        }
        if (days >= 14 && days % 7 == 0) {
            return (days / 7) + " weeks";
        }
        if (days == 7) {
            return "a week";
        }
        return days + (days == 1 ? " day" : " days");
    }
}
