package in.apnidukan.common.async;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs small jobs (like sending a notice email) after the reply has gone out. Used where the reply must not wait
 * for, or reveal anything through, a slow outside service. Failures are logged, never thrown.
 */
@Component
public class BackgroundTasks {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTasks.class);

    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "background-task");
        t.setDaemon(true);
        return t;
    });

    public void run(String what, Runnable job) {
        pool.execute(() -> {
            try {
                job.run();
            } catch (RuntimeException e) {
                log.warn("Background task '{}' failed: {}", what, e.getClass().getSimpleName());
            }
        });
    }

    @PreDestroy
    void stop() throws InterruptedException {
        pool.shutdown();
        if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
            pool.shutdownNow();
        }
    }
}
