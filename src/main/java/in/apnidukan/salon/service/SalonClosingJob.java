package in.apnidukan.salon.service;

import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.context.ShopDirectory.ShopCard;
import in.apnidukan.common.jobs.JobLeases;
import in.apnidukan.common.jobs.LongStatements;
import in.apnidukan.salon.domain.Appointment;
import in.apnidukan.salon.repo.AppointmentStore;
import in.apnidukan.salon.repo.SalonClosingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every 10 seconds: the upcoming online bookings of salons closed by their owners or suspended by Apni Dukan (queued
 * in salon_closings by SalonShopChanges.closed and .suspended) are cancelled, {@code batch} (500) at a time, each slice its own short transaction with the
 * customers' messages ("The salon has closed."). The salon was CLOSED in the closing's own request, so no booking can
 * be made meanwhile; a salon with 2,000 bookings ahead is done in a few seconds.
 *
 * Safe to run twice and on several copies: a job lease lets one copy run the round, each salon's row is taken with
 * SKIP LOCKED, and a booking is cancelled only while still CONFIRMED (one a customer is cancelling at that moment is
 * skipped and looked at again). A salon's row goes only when none of its bookings is left to cancel.
 */
@Component
public class SalonClosingJob {

    private static final Logger log = LoggerFactory.getLogger(SalonClosingJob.class);
    static final String JOB = "salon-closings";
    static final Duration MAX_RUN = Duration.ofMinutes(5);

    private final SalonClosingStore closings;
    private final AppointmentStore appointments;
    private final ShopDirectory shops;
    private final SalonNotices notices;
    private final JobLeases leases;
    private final LongStatements statements;
    private final Clock clock;
    private final int batch;
    private final boolean enabled;

    public SalonClosingJob(SalonClosingStore closings, AppointmentStore appointments, ShopDirectory shops,
                           SalonNotices notices, JobLeases leases, LongStatements statements, Clock clock,
                           @Value("${app.jobs.salon-closing-batch:500}") int batch,
                           @Value("${app.jobs.enabled:true}") boolean enabled) {
        if (batch < 1) {   // 0 would loop forever (every slice "full")
            throw new IllegalStateException("app.jobs.salon-closing-batch must be at least 1");
        }
        this.closings = closings;
        this.appointments = appointments;
        this.shops = shops;
        this.notices = notices;
        this.leases = leases;
        this.statements = statements;
        this.clock = clock;
        this.batch = batch;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 15_000)
    public void scheduled() {
        if (enabled) {
            leases.runExclusively(JOB, MAX_RUN, this::runOnce);
        }
    }

    /** One round over every queued salon; returns how many bookings it cancelled. Called by tests too. */
    public int runOnce() {
        int cancelled = 0;
        for (UUID shopId : closings.due(50)) {
            int n;
            do {   // a full slice means there may be more
                n = statements.run(() -> slice(shopId));
                cancelled += Math.max(n, 0);
            } while (n == batch);
        }
        if (cancelled > 0) {
            log.info("Cancelled {} bookings of closed salons", cancelled);
        }
        return cancelled;
    }

    /**
     * One slice for one salon, in one transaction: its row (SKIP LOCKED), up to `batch` bookings cancelled, each
     * customer told. Returns how many were cancelled, or -1 when another server holds the salon or it is finished.
     */
    private int slice(UUID shopId) {
        Optional<SalonClosingStore.Closing> closing = closings.lock(shopId);
        if (closing.isEmpty()) {
            return -1;
        }
        SalonClosingStore.Closing c = closing.get();
        // The shop's row held (FOR SHARE) for the slice: a reinstate (it locks the row) either committed before, and is
        // seen here, or waits until this slice is done; it never lands in the middle of one
        ShopCard card = shops.findForBooking(shopId).orElseThrow();
        if (!"CLOSED".equals(card.status()) && !"SUSPENDED".equals(card.status())) {
            // Reinstated after a suspension before the job was done: bookings not cancelled yet stay
            closings.done(shopId);
            return -1;
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<Appointment> cancelled = appointments.cancelUpcomingSlice(shopId, c.cancelFrom(), c.reason(), c.detail(),
                now, batch);
        if (!cancelled.isEmpty()) {
            notices.cancelledBySalon(cancelled, card, c.reason(), now);
        }
        if (cancelled.size() < batch && !appointments.hasUpcoming(shopId, c.cancelFrom())) {
            closings.done(shopId);   // nothing left (one held by a customer a moment ago comes next round)
        }
        return cancelled.size();
    }
}
