package in.apnidukan.files.service;

import in.apnidukan.files.domain.StoredFile;
import in.apnidukan.files.repo.StoredFileRepository;
import in.apnidukan.files.storage.FileStorage;
import in.apnidukan.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Photo links are signed at the start of the hour, so every copy of the backend gives the same link all hour. */
class FileRefServiceTest {

    private final UUID fileId = UUID.randomUUID();

    private FileRefService copy(TestClock clock) {
        StoredFile file = mock(StoredFile.class);
        when(file.getId()).thenReturn(fileId);
        when(file.getStorageKey()).thenReturn("shops/s/photos/p.jpg");
        StoredFileRepository repository = mock(StoredFileRepository.class);
        when(repository.findById(fileId)).thenReturn(Optional.of(file));
        when(repository.findAllById(any())).thenReturn(List.of(file));
        FileStorage storage = mock(FileStorage.class);
        when(storage.signedLinkAt(anyString(), any(Instant.class), any(Duration.class)))
                .thenAnswer(i -> "https://r2/" + i.getArgument(0) + "?at=" + i.getArgument(1) + "&for="
                        + i.getArgument(2));
        return new FileRefService(repository, mock(in.apnidukan.files.repo.FileDeletionStore.class), storage, clock, 60,
                mock(in.apnidukan.files.repo.VideoUploadStore.class));
    }

    @Test
    void twoCopiesGiveTheSameLinkAllHourValidForTheRestOfItAndAnHourMore() {
        TestClock early = new TestClock(Instant.parse("2026-11-10T04:05:13Z"));
        TestClock late = new TestClock(Instant.parse("2026-11-10T04:59:59Z"));
        String a = copy(early).link(fileId);
        String b = copy(late).links(List.of(fileId)).get(fileId);
        assertThat(a).isEqualTo(b).isEqualTo("https://r2/shops/s/photos/p.jpg?at=2026-11-10T04:00:00Z&for=PT2H");
        // The next hour: a new link (signed again in memory, no call to R2)
        FileRefService one = copy(early);
        one.link(fileId);
        early.set(Instant.parse("2026-11-10T05:00:00Z"));
        assertThat(one.link(fileId)).contains("at=2026-11-10T05:00:00Z");
    }

    @org.junit.jupiter.api.Test
    void aFileThatRunsWhenOpenedIsNeverSavedUnderARunnableName() {
        assertThat(FileRefService.safeDownloadName("invoice.EXE")).isEqualTo("invoice.EXE.download");
        assertThat(FileRefService.safeDownloadName("page.html")).isEqualTo("page.html.download");
        assertThat(FileRefService.safeDownloadName("quote.docx")).isEqualTo("quote.docx");
        assertThat(FileRefService.safeDownloadName("IMG_0001.HEIC")).isEqualTo("IMG_0001.HEIC");
        assertThat(FileRefService.safeDownloadName(null)).isEqualTo("file");
    }
}
