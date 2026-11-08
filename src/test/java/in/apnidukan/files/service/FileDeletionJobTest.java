package in.apnidukan.files.service;

import in.apnidukan.files.repo.FileDeletionStore;
import in.apnidukan.files.repo.FileDeletionStore.Pending;
import in.apnidukan.files.storage.FileStorage;
import in.apnidukan.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileDeletionJobTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-11-10T04:30:00Z"));
    private final FileDeletionStore queue = mock(FileDeletionStore.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final FileDeletionJob job = new FileDeletionJob(queue, storage, null, clock, true,
            mock(in.apnidukan.files.repo.VideoUploadStore.class));

    @Test
    void aSlowR2StopsTheRoundWhenItsTimeIsUpNotAtTheLease() {
        List<Pending> full = IntStream.range(0, FileDeletionJob.SLICE).mapToObj(i -> new Pending("k" + i, 0)).toList();
        when(queue.due(any(), anyInt())).thenReturn(full);
        doAnswer(inv -> {
            clock.advance(Duration.ofMinutes(1));   // each delete takes a minute
            return null;
        }).when(storage).delete(anyString());

        assertThat(job.runOnce()).isEqualTo(9);   // 10-minute lease, 1 minute kept back
        verify(storage, times(9)).delete(anyString());
        assertThat(FileDeletionJob.MAX_RUN.minus(FileDeletionJob.BUDGET_MARGIN)).isEqualTo(Duration.ofMinutes(9));
    }

    @Test
    void aFailedKeyWaitsLongerEachTimeUpTo6Hours() {
        assertThat(FileDeletionJob.wait(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(FileDeletionJob.wait(4)).isEqualTo(Duration.ofMinutes(8));
        assertThat(FileDeletionJob.wait(50)).isEqualTo(Duration.ofHours(6));
    }
}
