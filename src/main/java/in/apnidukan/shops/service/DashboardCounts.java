package in.apnidukan.shops.service;

import in.apnidukan.shops.domain.ReviewGroup;
import in.apnidukan.shops.domain.ShopType;
import in.apnidukan.shops.repo.ShopReviewStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The admin dashboard's counts, kept {@link #KEEP} in Redis so every server shares one copy: counting every shop by
 * group on each admin page open would read all of them at tens of thousands of shops. Stored as plain text, e.g.
 * "KIRANA.FIRST_VISIT=3;KIRANA.RECHECK=0;...". Redis not answering never breaks the page: the counts then come from
 * the database.
 *
 * A field-team decision moves a generation number ({@link #GENERATION}) once it commits, and the counts are kept
 * under a key naming the generation they were counted in. A reader reads the generation before counting, so one that
 * counted just before a decision committed writes its (old) counts under the old generation's key, which nobody reads
 * any more: the admin who decided always sees the new counts.
 */
@Component
public class DashboardCounts {

    private static final Logger log = LoggerFactory.getLogger(DashboardCounts.class);
    static final String GENERATION = "admin:dashboard:generation";
    static final String KEY_PREFIX = "admin:dashboard:";
    static final Duration KEEP = Duration.ofSeconds(30);

    private final ShopReviewStore review;
    private final StringRedisTemplate redis;

    public DashboardCounts(ShopReviewStore review, StringRedisTemplate redis) {
        this.review = review;
        this.redis = redis;
    }

    /** The counts: from Redis while fresh for the current generation, else counted and kept. */
    Map<ShopType, Map<ReviewGroup, Long>> get() {
        String key;
        try {
            String generation = redis.opsForValue().get(GENERATION);   // first: before anything is counted
            key = KEY_PREFIX + (generation == null ? "0" : generation);
            Map<ShopType, Map<ReviewGroup, Long>> kept = parse(redis.opsForValue().get(key));
            if (kept != null) {
                return kept;
            }
        } catch (DataAccessException redisDown) {
            log.warn("Redis didn't answer for the dashboard counts ({}); counting in the database",
                    redisDown.getClass().getSimpleName());
            return review.counts();
        }
        Map<ShopType, Map<ReviewGroup, Long>> counts = review.counts();
        try {
            redis.opsForValue().set(key, format(counts), KEEP);
        } catch (DataAccessException redisDown) {
            log.warn("Redis didn't keep the dashboard counts ({})", redisDown.getClass().getSimpleName());
        }
        return counts;
    }

    /** Starts a new generation once the current transaction commits (a decision moved a shop between groups). */
    void clearAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            clear();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                clear();
            }
        });
    }

    private void clear() {
        try {
            redis.opsForValue().increment(GENERATION);
        } catch (DataAccessException redisDown) {
            log.warn("Redis didn't take the new dashboard generation ({}); the counts refresh within {} s",
                    redisDown.getClass().getSimpleName(), KEEP.toSeconds());
        }
    }

    static String format(Map<ShopType, Map<ReviewGroup, Long>> counts) {
        StringJoiner out = new StringJoiner(";");
        counts.forEach((type, groups) -> groups.forEach((group, n) -> out.add(type + "." + group + "=" + n)));
        return out.toString();
    }

    /** The counts from their text, with every type and group present; null if missing or not readable. */
    static Map<ShopType, Map<ReviewGroup, Long>> parse(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Map<ShopType, Map<ReviewGroup, Long>> out = new EnumMap<>(ShopType.class);
        try {
            for (String part : text.split(";")) {
                String[] keyValue = part.split("=", 2);
                String[] typeGroup = keyValue[0].split("\\.", 2);
                out.computeIfAbsent(ShopType.valueOf(typeGroup[0]), t -> new EnumMap<>(ReviewGroup.class))
                        .put(ReviewGroup.valueOf(typeGroup[1]), Long.parseLong(keyValue[1]));
            }
        } catch (RuntimeException unreadable) {
            return null;
        }
        boolean complete = out.size() == ShopType.values().length
                && out.values().stream().allMatch(g -> g.size() == ReviewGroup.values().length);
        return complete ? out : null;
    }
}
