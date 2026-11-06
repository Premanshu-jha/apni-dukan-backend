package in.apnidukan.common.async;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Runs small jobs (like sending a notice email) after the reply has gone out. Used where the reply must not wait
 * for, or reveal anything through, a slow outside service: the recovery email (so the reply takes the same time
 * whether or not the email has an account), the "your email was changed" and "your account moved" notices.
 * Failures are logged, never thrown.
 *
 * The waiting line is bounded (app.background.queue), so a flood can't make the server run out of memory. What
 * happens when it is full depends on the job:
 * <ul>
 *   <li>{@link #run}: dropped with a warning. For what the person can simply ask for again (a recovery code).</li>
 *   <li>{@link #runAlways}: run right away on the caller's thread instead, so it is never dropped; that request
 *       just takes longer. For security notices ("your account moved", "your email was changed"): they tell a real
 *       owner that someone else may have taken the account, and must go out even during a flood.</li>
 * </ul>
 * Jobs live in memory: on a normal stop, those already queued still run (stop() waits for them); only a crash in
 * the second between the reply and the send loses one. A durable queue (rows in notification_jobs) would survive
 * that too, but would keep phone numbers, emails and the notices' text in Postgres for a seconds-long window and
 * tie sign-in to the notifications module; the caller-runs fallback closes the realistic gap (a full queue).
 */
@Component
public class BackgroundTasks {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTasks.class);

    private final ThreadPoolExecutor pool;

    public BackgroundTasks(@Value("${app.background.threads:4}") int threads,
                           @Value("${app.background.queue:1000}") int queue) {
        this.pool = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queue), r -> {
                    Thread t = new Thread(r, "background-task");
                    t.setDaemon(true);
                    return t;
                });
    }

    /** Queues the job; false if the line is full (the job is dropped and a warning logged). */
    public boolean run(String what, Runnable job) {
        return run(what, job, true);
    }

    private boolean run(String what, Runnable job, boolean warnWhenDropped) {
        try {
            pool.execute(safely(what, job));
            return true;
        } catch (RejectedExecutionException full) {
            if (warnWhenDropped) {
                log.warn("Background task '{}' dropped: {} waiting already", what, pool.getQueue().size());
            }
            return false;
        }
    }

    private static Runnable safely(String what, Runnable job) {
        return () -> {
            try {
                job.run();
            } catch (RuntimeException e) {
                log.warn("Background task '{}' failed: {}", what, e.getClass().getSimpleName());
            }
        };
    }

    /** Like {@link #run}, but never dropped: with the line full, the caller runs it now (see the class comment). */
    public void runAlways(String what, Runnable job) {
        if (!run(what, job, false)) {
            log.warn("Background line full: running '{}' on the caller's thread", what);
            safely(what, job).run();
        }
    }

    @PreDestroy
    void stop() throws InterruptedException {
        pool.shutdown();
        if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
            pool.shutdownNow();
        }
    }
}
