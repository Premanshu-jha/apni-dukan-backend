package in.apnidukan.common.async;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The waiting line is bounded: a full one drops the job instead of growing until memory runs out. */
class BackgroundTasksTest {

    @Test
    void aFullLineDropsTheJobAndAFailingJobIsOnlyLogged() throws InterruptedException {
        BackgroundTasks tasks = new BackgroundTasks(1, 1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        assertThat(tasks.run("busy", () -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        })).isTrue();
        assertThat(tasks.run("waiting", done::countDown)).isTrue();
        assertThat(tasks.run("one too many", () -> { })).isFalse();
        release.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(tasks.run("fails", () -> {
            throw new IllegalStateException("boom");
        })).isTrue();
        tasks.stop();
    }

    @Test
    void aSecurityNoticeIsNeverDroppedTheCallerRunsItWhenTheLineIsFull() throws InterruptedException {
        BackgroundTasks tasks = new BackgroundTasks(1, 1);
        CountDownLatch release = new CountDownLatch(1);
        tasks.run("busy", () -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        tasks.run("waiting", () -> { });
        Thread caller = Thread.currentThread();
        java.util.concurrent.atomic.AtomicReference<Thread> ranOn = new java.util.concurrent.atomic.AtomicReference<>();
        tasks.runAlways("account-moved notice", () -> ranOn.set(Thread.currentThread()));
        assertThat(ranOn.get()).isSameAs(caller);
        release.countDown();
        tasks.stop();
    }
}
