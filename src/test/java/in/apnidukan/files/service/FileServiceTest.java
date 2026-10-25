package in.apnidukan.files.service;

import in.apnidukan.common.context.CurrentUser;
import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.files.domain.FilePurpose;
import in.apnidukan.files.domain.StoredFile;
import in.apnidukan.files.repo.StoredFileRepository;
import in.apnidukan.files.storage.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileServiceTest {

    private static final UUID SHOP = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-10-24T09:00:00Z");
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

    @Mock StoredFileRepository repository;
    @Mock FileStorage storage;
    @Mock CurrentUserProvider currentUser;
    @Mock FileUsageChecker usageChecker;

    FileService service;

    @BeforeEach
    void setUp() {
        service = new FileService(repository, storage, currentUser, Clock.fixed(NOW, ZoneOffset.UTC), 5,
                List.of(usageChecker));
        when(currentUser.get()).thenReturn(new CurrentUser(USER, SHOP));
    }

    @Test
    void storesANewBillPhotoInTheShopsBillsFolder() {
        MockMultipartFile upload = new MockMultipartFile("file", "KD-4471.jpg", "image/jpeg", JPEG);
        when(repository.findByShopIdAndPurposeAndSha256(eq(SHOP), eq(FilePurpose.BILL), anyString()))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(StoredFile.class))).thenAnswer(inv -> inv.getArgument(0));

        FileUploadResult result = service.upload(upload, FilePurpose.BILL);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).put(key.capture(), any(InputStream.class), eq((long) JPEG.length), eq("image/jpeg"));
        assertThat(key.getValue()).startsWith("shops/" + SHOP + "/bills/").endsWith(".jpg");
        assertThat(result.duplicate()).isFalse();
        assertThat(result.file().getShopId()).isEqualTo(SHOP);
        assertThat(result.file().getUploadedBy()).isEqualTo(USER);
        assertThat(result.file().getSha256()).hasSize(64);
        assertThat(result.file().getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void returnsTheEarlierUploadForTheSameFileWithoutStoringItAgain() {
        MockMultipartFile upload = new MockMultipartFile("file", "KD-4471.jpg", "image/jpeg", JPEG);
        StoredFile earlier = new StoredFile(UUID.randomUUID(), SHOP, FilePurpose.BILL, "shops/x/bills/y.jpg",
                "image/jpeg", JPEG.length, "a".repeat(64), "KD-4471.jpg", USER, NOW);
        when(repository.findByShopIdAndPurposeAndSha256(eq(SHOP), eq(FilePurpose.BILL), anyString()))
                .thenReturn(Optional.of(earlier));

        FileUploadResult result = service.upload(upload, FilePurpose.BILL);

        assertThat(result.duplicate()).isTrue();
        assertThat(result.file()).isSameAs(earlier);
        verify(storage, never()).put(anyString(), any(), anyLong(), anyString());
    }

    @Test
    void refusesATextFileEvenIfItIsNamedJpg() {
        MockMultipartFile upload = new MockMultipartFile("file", "bill.jpg", "image/jpeg", "not a photo".getBytes());

        assertThatThrownBy(() -> service.upload(upload, FilePurpose.BILL))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("UNSUPPORTED_FILE_TYPE");
        verify(storage, never()).put(anyString(), any(), anyLong(), anyString());
    }

    @Test
    void removesTheStoredBytesIfTheDatabaseSaveFails() {
        MockMultipartFile upload = new MockMultipartFile("file", "bill.jpg", "image/jpeg", JPEG);
        when(repository.findByShopIdAndPurposeAndSha256(eq(SHOP), eq(FilePurpose.BILL), anyString()))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(StoredFile.class))).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> service.upload(upload, FilePurpose.BILL)).isInstanceOf(IllegalStateException.class);
        verify(storage).delete(anyString());
    }

    @Test
    void anotherShopsFileIsNotFound() {
        UUID fileId = UUID.randomUUID();
        when(repository.findByIdAndShopId(fileId, SHOP)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.link(fileId, false))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    void linkLastsFiveMinutes() {
        UUID fileId = UUID.randomUUID();
        StoredFile file = new StoredFile(fileId, SHOP, FilePurpose.BILL, "shops/s/bills/f.jpg",
                "image/jpeg", 10, "b".repeat(64), "KD-4471.jpg", USER, NOW);
        when(repository.findByIdAndShopId(fileId, SHOP)).thenReturn(Optional.of(file));
        when(storage.signedLink(eq("shops/s/bills/f.jpg"), eq(Duration.ofMinutes(5)), isNull()))
                .thenReturn("https://r2.example/signed");

        FileLink link = service.link(fileId, false);

        assertThat(link.url()).isEqualTo("https://r2.example/signed");
        assertThat(link.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void deletesAnUnusedFileFromTheDatabaseThenFromR2() {
        StoredFile file = billPhoto();
        when(repository.findByIdAndShopId(file.getId(), SHOP)).thenReturn(Optional.of(file));
        when(usageChecker.isInUse(file)).thenReturn(false);

        service.delete(file.getId());

        InOrder order = inOrder(repository, storage);
        order.verify(repository).delete(file);
        order.verify(repository).flush();
        order.verify(storage).delete(file.getStorageKey());
    }

    @Test
    void refusesToDeleteAFileThatIsAttachedToARecord() {
        StoredFile file = billPhoto();
        when(repository.findByIdAndShopId(file.getId(), SHOP)).thenReturn(Optional.of(file));
        when(usageChecker.isInUse(file)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(file.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("FILE_IN_USE");
        verify(repository, never()).delete(any(StoredFile.class));
        verify(storage, never()).delete(anyString());
    }

    @Test
    void cannotDeleteAnotherShopsFile() {
        UUID fileId = UUID.randomUUID();
        when(repository.findByIdAndShopId(fileId, SHOP)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(fileId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("NOT_FOUND");
        verify(storage, never()).delete(anyString());
    }

    private static StoredFile billPhoto() {
        return new StoredFile(UUID.randomUUID(), SHOP, FilePurpose.BILL, "shops/s/bills/f.jpg",
                "image/jpeg", 10, "c".repeat(64), "KD-4471.jpg", USER, NOW);
    }
}
