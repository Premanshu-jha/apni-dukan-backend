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
 * Best effort: jobs are kept in memory only, so a job still waiting when the server stops is lost (none of them
 * carries anything the person can't ask for again). The waiting line is bounded (app.background.queue): if a slow
 * mail service lets it fill up, further jobs are dropped with a warning instead of the server running out of memory.
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
        try {
            pool.execute(() -> {
                try {
                    job.run();
                } catch (RuntimeException e) {
                    log.warn("Background task '{}' failed: {}", what, e.getClass().getSimpleName());
                }
            });
            return true;
        } catch (RejectedExecutionException full) {
            log.warn("Background task '{}' dropped: {} waiting already", what, pool.getQueue().size());
            return false;
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
