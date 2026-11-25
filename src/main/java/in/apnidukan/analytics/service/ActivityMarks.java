package in.apnidukan.analytics.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Who used Clozest today, once per person, side (customer, partner, admin) and channel (Play Store app or website):
 * the active-user counts. Marked by {@link in.apnidukan.analytics.api.ActivityFilter} on every signed-in request, but
 * never written in the request: the first mark of the day is queued in memory and written every 30 seconds in one
 * batch (daily_activity, a duplicate from another copy of the backend is skipped by its key). So a burst of requests
 * costs nothing but a set lookup. Kept at most 200,000 marks a day per copy in memory (past that, marks are still
 * written, just not de-duplicated here first).
 */
@Service
public class ActivityMarks {

    private static final Logger log = LoggerFactory.getLogger(ActivityMarks.class);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final int MAX_SEEN = 200_000;
    static final int BATCH = 2_000;

    /** Who and where: the side of Clozest and the channel. */
    public record Side(String audience, String channel) {
    }

    record Mark(LocalDate day, String audience, String channel, UUID user) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Set<Mark> seen = ConcurrentHashMap.newKeySet();
    private final Queue<Mark> pending = new ConcurrentLinkedQueue<>();
    private volatile LocalDate day;

    public ActivityMarks(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * The side and channel of a request: what the app says (X-Clozest-Client: partner-app, partner-web,
     * customer-app, customer-web, admin-web), else the website it came from (Origin; an app from before this header
     * counts as its website). Empty for a call from no Clozest page (a script, CI).
     */
    public static Optional<Side> side(String client, String origin) {
        if (client != null) {
            switch (client.strip().toLowerCase(Locale.ROOT)) {
                case "partner-app" -> {
                    return Optional.of(new Side("PARTNER", "APP"));
                }
                case "partner-web" -> {
                    return Optional.of(new Side("PARTNER", "WEB"));
                }
                case "customer-app" -> {
                    return Optional.of(new Side("CUSTOMER", "APP"));
                }
                case "customer-web" -> {
                    return Optional.of(new Side("CUSTOMER", "WEB"));
                }
                case "admin-web" -> {
                    return Optional.of(new Side("ADMIN", "WEB"));
                }
                default -> {
                    // not one of ours: judged by the page it came from
                }
            }
        }
        if (origin == null || origin.isBlank()) {
            return Optional.empty();
        }
        String host = origin.strip().toLowerCase(Locale.ROOT).replaceFirst("^https?://", "");
        if (host.startsWith("admin.")) {
            return Optional.of(new Side("ADMIN", "WEB"));
        }
        if (host.startsWith("partner.")) {
            return Optional.of(new Side("PARTNER", "WEB"));
        }
        return Optional.of(new Side("CUSTOMER", "WEB"));
    }

    /** This person used this side of Clozest today. */
    public void mark(UUID user, Side side) {
        LocalDate today = LocalDate.now(clock.withZone(INDIA));
        if (!today.equals(day)) {
            synchronized (this) {
                if (!today.equals(day)) {
                    seen.clear();
                    day = today;
                }
            }
        }
        Mark mark = new Mark(today, side.audience(), side.channel(), user);
        // Past MAX_SEEN people today on this copy: no more marks (memory stays bounded; counts are a floor then)
        if (seen.size() < MAX_SEEN && seen.add(mark)) {
            pending.add(mark);
        }
    }

    /** Writes what was marked since the last time, in batches. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void flush() {
        while (!pending.isEmpty()) {
            List<Mark> batch = new ArrayList<>(BATCH);
            for (Mark m = pending.poll(); m != null; m = batch.size() < BATCH ? pending.poll() : null) {
                batch.add(m);
            }
            try {
                jdbc.batchUpdate("INSERT INTO daily_activity (day, audience, channel, user_id) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT DO NOTHING", batch, batch.size(), (ps, m) -> {
                            ps.setDate(1, Date.valueOf(m.day()));
                            ps.setString(2, m.audience());
                            ps.setString(3, m.channel());
                            ps.setObject(4, m.user());
                        });
            } catch (RuntimeException e) {
                // Not lost for good: forgotten here, so the person's next request marks them again
                batch.forEach(seen::remove);
                log.warn("Couldn't write {} activity marks: {}", batch.size(), e.toString());
                return;
            }
            if (batch.size() < BATCH) {
                return;
            }
        }
    }

    @PreDestroy
    void beforeStop() {
        flush();
    }
}
