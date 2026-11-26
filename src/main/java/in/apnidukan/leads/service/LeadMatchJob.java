package in.apnidukan.leads.service;

import in.apnidukan.common.jobs.JobLeases;
import in.apnidukan.leads.repo.LeadStore;
import in.apnidukan.leads.repo.LeadStore.Lead;
import in.apnidukan.leads.repo.LeadStore.Salon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every 15 minutes (one server at a time: {@link JobLeases}), ties contacted leads to the Clozest salons they became:
 * a salon that registered after the team first tried to reach it gets its lead marked signed up, and the person
 * assigned (else the last to work on it) gets the credit ({@link LeadStore#link} holds the rules). Matching as on the page ({@link ClozestMatch}: the same phone, or a similar
 * name within 120 m); a lead without a saved point is matched by phone only. Works through all contacted leads not
 * tied yet, {@link #BATCH} at a time (the salons of the batch's pincodes {@link #PINCODES_AT_ONCE} pincodes a query).
 */
@Component
public class LeadMatchJob {

    private static final Logger log = LoggerFactory.getLogger(LeadMatchJob.class);
    static final String JOB = "lead-match";
    static final int BATCH = 500;
    /** The salons of this many pincodes are read in one query. */
    static final int PINCODES_AT_ONCE = 50;
    private static final Duration MAX_RUN = Duration.ofMinutes(10);

    private final LeadStore store;
    private final JobLeases leases;
    private final Clock clock;
    private final boolean enabled;

    public LeadMatchJob(LeadStore store, JobLeases leases, Clock clock,
                        @Value("${app.jobs.enabled:true}") boolean enabled) {
        this.store = store;
        this.leases = leases;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 900_000, initialDelay = 180_000)
    public void scheduled() {
        if (enabled) {
            leases.runExclusively(JOB, MAX_RUN, this::runOnce);
        }
    }

    /** One pass over every contacted lead not tied to a salon; returns how many it tied. Called by tests too. */
    public int runOnce() {
        int linked = 0;
        String after = "";
        while (true) {
            List<Lead> batch = store.unmatched(after, BATCH);
            if (batch.isEmpty()) {
                break;
            }
            after = batch.getLast().mapboxId();
            Set<String> pins = new TreeSet<>();
            batch.forEach(l -> pins.add(l.pincode()));
            Map<String, List<Salon>> salonsByPin = new HashMap<>();
            List<String> pinList = new ArrayList<>(pins);
            for (int i = 0; i < pinList.size(); i += PINCODES_AT_ONCE) {
                for (Salon s : store.salonsIn(pinList.subList(i, Math.min(pinList.size(), i + PINCODES_AT_ONCE)))) {
                    salonsByPin.computeIfAbsent(s.pincode(), k -> new ArrayList<>()).add(s);
                }
            }
            for (Lead l : batch) {
                List<Salon> salons = salonsByPin.getOrDefault(l.pincode(), List.of());
                if (salons.isEmpty()) {
                    continue;
                }
                Salon s = ClozestMatch.match(List.of(new ClozestMatch.Candidate(l.mapboxId(), l.name(),
                        l.latitude() == null ? 0 : l.latitude(), l.longitude() == null ? 0 : l.longitude(),
                        l.phone())), salons).get(l.mapboxId());
                // Each tie stamped when it is made (not at the batch's start), so a check for changes finds it
                Instant now = clock.instant();
                if (s != null && store.link(l.mapboxId(), s.shopId(), now)) {
                    store.event(l.mapboxId(), null, "SIGNED_UP", null, s.name(), now);
                    linked++;
                }
            }
            if (batch.size() < BATCH) {
                break;
            }
        }
        if (linked > 0) {
            log.info("Leads: {} signed up on Clozest", linked);
        }
        return linked;
    }
}
