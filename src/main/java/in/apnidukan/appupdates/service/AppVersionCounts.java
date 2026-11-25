package in.apnidukan.appupdates.service;

import in.apnidukan.appupdates.domain.AppName;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Phones per app version per day, for the analytics' App versions: each Play Store app adds "first=true" to its
 * first update check of the day (AppUpdatesController), and that check is counted here. Counted in memory and added
 * to app_version_daily once a minute (several copies of the backend add to the same row), so the check itself still
 * never touches the database. Nothing about the phone is kept: only the number.
 */
@Service
public class AppVersionCounts {

    private static final Logger log = LoggerFactory.getLogger(AppVersionCounts.class);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    /** Different versions a copy counts before the next write: far more than ever exist. */
    static final int MAX_KEYS = 10_000;

    record Key(LocalDate day, AppName app, int versionCode, boolean store) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Map<Key, LongAdder> counts = new ConcurrentHashMap<>();

    public AppVersionCounts(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** One phone's first check of the day: this app, this version, from the Play Store (or a test APK). */
    public void count(AppName app, int versionCode, boolean store) {
        Key key = new Key(LocalDate.now(clock.withZone(INDIA)), app, versionCode, store);
        LongAdder adder = counts.get(key);
        if (adder == null) {
            if (counts.size() >= MAX_KEYS) {
                return;   // only ever by made-up version numbers: not counted
            }
            adder = counts.computeIfAbsent(key, k -> new LongAdder());
        }
        adder.increment();
    }

    /**
     * Adds what was counted since the last time to the daily rows. The counters stay in place (a phone counted while
     * this runs is in the next write, never lost); a finished day's are dropped once empty. A failed write puts the
     * numbers back for the next one.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public synchronized void flush() {
        LocalDate today = LocalDate.now(clock.withZone(INDIA));
        List<Object[]> rows = new ArrayList<>();
        Map<Key, Long> taken = new java.util.HashMap<>();
        for (Map.Entry<Key, LongAdder> e : counts.entrySet()) {
            long n = e.getValue().sumThenReset();
            if (n > 0) {
                Key key = e.getKey();
                taken.put(key, n);
                rows.add(new Object[] {Date.valueOf(key.day()), key.app().value(), key.versionCode(), key.store(),
                        (int) Math.min(n, Integer.MAX_VALUE)});
            } else if (e.getKey().day().isBefore(today)) {
                counts.remove(e.getKey(), e.getValue());
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        try {
            jdbc.batchUpdate("INSERT INTO app_version_daily (day, app, version_code, store, phones) "
                    + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (day, app, version_code, store) "
                    + "DO UPDATE SET phones = app_version_daily.phones + EXCLUDED.phones", rows);
        } catch (RuntimeException e) {
            // Back into the counters: written with the next ones
            taken.forEach((key, n) -> counts.computeIfAbsent(key, k -> new LongAdder()).add(n));
            log.warn("Couldn't write {} app version counts: {}", rows.size(), e.toString());
        }
    }

    @PreDestroy
    void beforeStop() {
        flush();
    }
}
