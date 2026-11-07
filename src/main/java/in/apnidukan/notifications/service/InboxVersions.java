package in.apnidukan.notifications.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Each person's inbox version in Redis (inbox-v:{user}), for the bell's ETags (InboxService): it moves after every
 * write that changes what their list or unread count shows (a message queued, read, hidden or emptied), once the
 * write has committed. The apps ask for the unread count every 30 to 60 seconds; while the version is the same (and no
 * message waiting for a later time has become due) the answer is 304 without reading the database at all.
 *
 * Shared by every copy of the backend (Redis), so a write on one copy is seen by all. The key expires 2 days after
 * its last change; it then reads 0 again, which no tag still in use can carry (a tag lasts at most 15 minutes).
 * If Redis doesn't answer: no version, so no 304 (the answer is built from the database as before); a bump that
 * didn't reach Redis leaves a tag standing until it runs out (at most 15 minutes).
 */
@Component
public class InboxVersions {

    private static final Logger log = LoggerFactory.getLogger(InboxVersions.class);
    static final Duration KEEP = Duration.ofDays(2);
    static final String EPOCH = "inbox-epoch";

    private final StringRedisTemplate redis;

    public InboxVersions(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * The person's version now, as "epoch:version"; empty if Redis didn't answer (then no tag is given or accepted).
     * The epoch is a random id kept in Redis without expiry (inbox-epoch) and made again when missing: if Redis loses
     * its data (a failover, an eviction, a flush), every version reads 0 again, and without the epoch an old tag made
     * at version 0 could still match; with it, every tag from before the loss stops matching at once.
     */
    public Optional<String> current(UUID userId) {
        try {
            List<String> both = redis.opsForValue().multiGet(List.of(EPOCH, key(userId)));
            String epoch = both == null ? null : both.get(0);
            if (epoch == null) {
                redis.opsForValue().setIfAbsent(EPOCH, UUID.randomUUID().toString());
                epoch = redis.opsForValue().get(EPOCH);
            }
            String version = both == null ? null : both.get(1);
            return Optional.of(epoch + ":" + (version == null ? "0" : version));
        } catch (DataAccessException redisDown) {
            log.warn("Redis didn't answer the inbox version ({}); no 304 this time",
                    redisDown.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Moves these people's versions once the current transaction commits (at once when none is running): moved
     * earlier, an answer read in between would carry the new version with the old messages.
     */
    public void bumpAfterCommit(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        Set<UUID> people = new LinkedHashSet<>(userIds);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            bump(people);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                bump(people);
            }
        });
    }

    /** All of them in one round trip to Redis (a salon closed with 2,000 customers to tell). */
    void bump(Collection<UUID> userIds) {
        long keepMillis = KEEP.toMillis();
        try {
            redis.executePipelined((RedisCallback<Object>) connection -> {
                for (UUID id : userIds) {
                    bumpOne(connection, key(id).getBytes(StandardCharsets.UTF_8), keepMillis);
                }
                return null;
            });
        } catch (DataAccessException redisDown) {
            log.warn("Redis didn't take the inbox versions of {} people ({}); their bell answers may lag up to 15 "
                    + "minutes", userIds.size(), redisDown.getClass().getSimpleName());
        }
    }

    private static void bumpOne(RedisConnection connection, byte[] key, long keepMillis) {
        connection.stringCommands().incr(key);
        connection.keyCommands().pExpire(key, keepMillis);
    }

    private static String key(UUID userId) {
        return "inbox-v:" + userId;
    }
}
