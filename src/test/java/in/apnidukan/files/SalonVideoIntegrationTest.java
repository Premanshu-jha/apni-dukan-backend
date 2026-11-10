package in.apnidukan.files;

import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.JwtService;
import in.apnidukan.files.service.FileDeletionJob;
import in.apnidukan.support.TestContainers;
import in.apnidukan.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The salon video end to end, against real Postgres and an S3-compatible store (S3Mock): an upload link, the phone's
 * PUT straight to storage, "complete", setting it on the shop, submitting, and who may see it.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SalonVideoIntegrationTest {

    private static final UUID SALON = UUID.fromString("51000000-0000-0000-0000-000000000001");
    private static final UUID OWNER = UUID.fromString("52000000-0000-0000-0000-000000000001");
    private static final UUID HELPER = UUID.fromString("52000000-0000-0000-0000-000000000002");
    private static final UUID BARBER = UUID.fromString("52000000-0000-0000-0000-000000000003");
    private static final UUID CUSTOMER = UUID.fromString("52000000-0000-0000-0000-000000000004");
    private static final UUID ADMIN = UUID.fromString("52000000-0000-0000-0000-000000000005");

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired FileDeletionJob deletionJob;

    RestClient http;

    @Autowired in.apnidukan.files.repo.FileDeletionStore deletionStore;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    private static final UUID KIRANA = UUID.fromString("51000000-0000-0000-0000-000000000002");
    private static final UUID KIRANA_OWNER = UUID.fromString("52000000-0000-0000-0000-000000000006");

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port") + "/api/v1")
                .build();
        TestData.clean(jdbc);
        TestData.user(jdbc, OWNER, "9100000001");
        TestData.user(jdbc, HELPER, "9100000002");
        TestData.user(jdbc, BARBER, "9100000003");
        TestData.user(jdbc, CUSTOMER, "9100000004");
        TestData.user(jdbc, ADMIN, "9100000005");
        jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN' WHERE id = ?", ADMIN);
        TestData.salon(jdbc, SALON, OWNER);
        TestData.member(jdbc, SALON, HELPER, "HELPER");
        TestData.member(jdbc, SALON, BARBER, "BARBER");
        TestData.user(jdbc, KIRANA_OWNER, "9100000006");
        TestData.shop(jdbc, KIRANA, KIRANA_OWNER);
    }

    @Test
    void aVideoGoesThroughStagingIsCheckedCopiedAndSetOnTheSalonThenItMaySubmit() throws Exception {
        byte[] video = fakeMp4(4096);
        Map<?, ?> link = start(OWNER, video.length, "video/mp4", 30);
        assertThat(link.get("contentType")).isEqualTo("video/mp4");
        assertThat(((Number) link.get("expiresInSeconds")).intValue()).isEqualTo(900);
        String uploadId = (String) link.get("uploadId");
        String staging = keyOf(link);
        assertThat(staging).startsWith("shops/" + SALON + "/videos/staging/").endsWith(".mp4");
        // The staging key is queued at once, due an hour after the link was given, whatever happens
        assertThat(jdbc.queryForObject("SELECT next_try_at BETWEEN now() + interval '55 minutes' AND now() + "
                + "interval '65 minutes' FROM file_deletions WHERE storage_key = ?", Boolean.class, staging)).isTrue();

        assertThat(put((String) link.get("uploadUrl"), video, "video/mp4")).isEqualTo(200);
        Reply done = post("/files/video-uploads/" + uploadId + "/complete", Map.of(), OWNER);
        assertThat(done.status()).as(done.text()).isEqualTo(201);
        assertThat(done.body().get("purpose")).isEqualTo("SHOP_VIDEO");
        String fileId = (String) done.body().get("id");
        String key = fileKey(fileId);
        assertThat(key).startsWith("shops/" + SALON + "/videos/").doesNotContain("staging");
        assertThat(stored(key)).isEqualTo(video);
        // Staging stays queued (never taken off); the kept copy is queued a day later until the shop uses it
        assertThat(queued(staging)).isTrue();
        assertThat(jdbc.queryForObject("SELECT next_try_at > now() + interval '23 hours' FROM file_deletions "
                + "WHERE storage_key = ?", Boolean.class, key)).isTrue();
        Reply again = post("/files/video-uploads/" + uploadId + "/complete", Map.of(), OWNER);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("id")).isEqualTo(fileId);

        // Not ready for a visit without it
        setPhotos();
        TestData.setUpForVisit(jdbc, SALON, OWNER);   // all it needs to submit besides the photos and the video
        Reply early = post("/shops/me/submit", Map.of("version", version()), OWNER);
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.body().get("code")).isEqualTo("PHOTOS_REQUIRED");
        assertThat(early.text()).contains("shopVideoFileId");

        Reply set = call(HttpMethod.PUT, "/shops/me/video", Map.of("version", version(), "fileId", fileId), OWNER);
        assertThat(set.status()).as(set.text()).isEqualTo(200);
        assertThat(set.body().get("shopVideoFileId")).isEqualTo(fileId);
        assertThat(queued(key)).isFalse();   // used now: kept
        Reply submitted = post("/shops/me/submit", Map.of("version", version()), OWNER);
        assertThat(submitted.status()).as(submitted.text()).isEqualTo(200);

        // The owner can play it back and the video in use can't be deleted
        assertThat(get("/files/" + fileId + "/link", OWNER).status()).isEqualTo(200);
        assertThat(code(call(HttpMethod.DELETE, "/files/" + fileId, null, OWNER))).isEqualTo("FILE_IN_USE");
    }

    @Test
    void aSecondPutToTheSameLinkAfterCompleteDoesntChangeTheKeptVideo() throws Exception {
        byte[] video = fakeMp4(4096);
        Map<?, ?> link = start(OWNER, video.length, "video/mp4", 30);
        assertThat(put((String) link.get("uploadUrl"), video, "video/mp4")).isEqualTo(200);
        String fileId = (String) post("/files/video-uploads/" + link.get("uploadId") + "/complete", Map.of(), OWNER)
                .body().get("id");

        byte[] other = fakeMp4(4096);
        other[100] = 42;
        other[200] = 43;
        assertThat(put((String) link.get("uploadUrl"), other, "video/mp4")).isEqualTo(200);   // the link still lasts
        Reply again = post("/files/video-uploads/" + link.get("uploadId") + "/complete", Map.of(), OWNER);
        assertThat(again.body().get("id")).isEqualTo(fileId);
        assertThat(stored(fileKey(fileId))).isEqualTo(video);
        // What was put again sits on the staging key, which is still due for deletion
        assertThat(queued(keyOf(link))).isTrue();
    }

    @Test
    void theFieldTeamSeesTheVideoButHelpersAndCustomersNever() throws Exception {
        String fileId = uploadedVideo();
        call(HttpMethod.PUT, "/shops/me/video", Map.of("version", version(), "fileId", fileId), OWNER);
        jdbc.update("UPDATE shops SET status = 'APPROVED' WHERE id = ?", SALON);

        Reply detail = get("/admin/shops/" + SALON, ADMIN);
        assertThat(detail.status()).as(detail.text()).isEqualTo(200);
        String url = (String) detail.body().get("videoUrl");
        assertThat(url).contains("/videos/");
        HttpResponse<byte[]> played = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(played.statusCode()).isEqualTo(200);
        assertThat(get("/admin/shops?type=SALON&group=LIVE", ADMIN).text()).doesNotContain("/videos/");

        // A helper gets neither its link nor may delete it, nor sees its id
        assertThat(get("/shops/me", HELPER).body().get("shopVideoFileId")).isNull();
        Reply helperLink = get("/files/" + fileId + "/link", HELPER);
        assertThat(helperLink.status()).isEqualTo(403);
        assertThat(code(helperLink)).isEqualTo("OWNER_ONLY");
        assertThat(code(call(HttpMethod.DELETE, "/files/" + fileId, null, HELPER))).isEqualTo("OWNER_ONLY");

        String key = fileKey(fileId);
        Reply near = get("/salons?lat=17.465&lng=78.364", CUSTOMER);
        assertThat(near.status()).as(near.text()).isEqualTo(200);
        assertThat(near.text()).contains(SALON.toString()).doesNotContain(fileId).doesNotContain(key)
                .doesNotContainIgnoringCase("video");
        Reply page = get("/salons/" + SALON, CUSTOMER);
        assertThat(page.text()).doesNotContain(fileId).doesNotContain(key).doesNotContainIgnoringCase("video");
    }

    @Test
    void aKiranaNeedsNoVideoToSubmitAndCantSendOne() {
        jdbc.update("UPDATE shops SET owner_photo_file_id = ?::uuid, shop_photo_file_id = ?::uuid WHERE id = ?",
                photoFile(KIRANA, KIRANA_OWNER, "OWNER_PHOTO"), photoFile(KIRANA, KIRANA_OWNER, "SHOP_PHOTO"), KIRANA);
        Reply start = startReply(KIRANA_OWNER, 1000, "video/mp4", 30);
        assertThat(start.status()).isEqualTo(409);
        assertThat(code(start)).isEqualTo("NOT_A_SALON");
        Reply set = call(HttpMethod.PUT, "/shops/me/video", Map.of("version", version(KIRANA),
                "fileId", UUID.randomUUID().toString()), KIRANA_OWNER);
        assertThat(code(set)).isEqualTo("NOT_A_SALON");
        Reply submitted = post("/shops/me/submit", Map.of("version", version(KIRANA)), KIRANA_OWNER);
        assertThat(submitted.status()).as(submitted.text()).isEqualTo(200);
        assertThat(submitted.body().get("status")).isEqualTo("PENDING_VISIT");
    }

    @Test
    void lengthSizeAndTypeAreCheckedBeforeAnyLinkAndAnUnknownLengthIsAllowed() {
        assertThat(code(startReply(OWNER, 1000, "video/mp4", 13))).isEqualTo("VALIDATION_FAILED");
        assertThat(startReply(OWNER, 1000, "video/mp4", 13).text()).contains("durationSeconds");
        assertThat(code(startReply(OWNER, 1000, "video/mp4", 47))).isEqualTo("VALIDATION_FAILED");
        // A second either way is the same video
        assertThat(startReply(OWNER, 1000, "video/mp4", 14).status()).isEqualTo(201);
        assertThat(startReply(OWNER, 1000, "video/quicktime", 46).status()).isEqualTo(201);
        Reply big = startReply(OWNER, 100L * 1024 * 1024 + 1, "video/mp4", 30);
        assertThat(big.status()).isEqualTo(413);
        assertThat(code(big)).isEqualTo("FILE_TOO_LARGE");
        assertThat(code(startReply(OWNER, 1000, "image/jpeg", 30))).isEqualTo("UNSUPPORTED_FILE_TYPE");
        // The phone couldn't read the length: still size-capped, then let through
        assertThat(startReply(OWNER, 100L * 1024 * 1024 + 1, "video/mp4", null).status()).isEqualTo(413);
        assertThat(startReply(OWNER, 1000, "video/webm", null).status()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM video_uploads WHERE duration_seconds IS NULL",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void fiveUploadLinksADayThenTooManyUploads() {
        for (int i = 0; i < 5; i++) {
            assertThat(startReply(OWNER, 1000, "video/webm", 30).status()).isEqualTo(201);
        }
        Reply sixth = startReply(OWNER, 1000, "video/webm", 30);
        assertThat(sixth.status()).isEqualTo(429);
        assertThat(code(sixth)).isEqualTo("TOO_MANY_UPLOADS");
    }

    @Test
    void manyStartsAtTheSameMomentStillGiveAtMostFiveLinks() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(10);
        try {
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<Integer>> replies = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                replies.add(pool.submit(() -> {
                    go.await();
                    return startReply(OWNER, 1000, "video/mp4", 30).status();
                }));
            }
            go.countDown();
            List<Integer> statuses = new java.util.ArrayList<>();
            for (var r : replies) {
                statuses.add(r.get());
            }
            assertThat(statuses).filteredOn(st -> st == 201).hasSize(5);
            assertThat(statuses).filteredOn(st -> st == 429).hasSize(5);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM video_uploads", Integer.class)).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void onlyTheOwnerMayUploadTheVideo() {
        assertThat(startReply(HELPER, 1000, "video/mp4", 30).status()).isEqualTo(403);
        assertThat(code(startReply(HELPER, 1000, "video/mp4", 30))).isEqualTo("OWNER_ONLY");
        assertThat(code(startReply(BARBER, 1000, "video/mp4", 30))).isEqualTo("OWNER_ONLY");
        assertThat(code(startReply(CUSTOMER, 1000, "video/mp4", 30))).isEqualTo("SHOP_REQUIRED");
    }

    @Test
    void theVideoCantComeThroughThePhotoUpload() {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("purpose", "SHOP_VIDEO");
        form.add("file", new ByteArrayResource(fakeMp4(2048)) {
            @Override
            public String getFilename() {
                return "salon.mp4";
            }
        });
        Reply reply = http.post().uri("/files").header("Authorization", "Bearer " + token(OWNER))
                .contentType(MediaType.MULTIPART_FORM_DATA).body(form)
                .exchange((req, res) -> Reply.of(res.getStatusCode().value(), res.bodyTo(String.class)));
        assertThat(reply.status()).isEqualTo(400);
        assertThat(code(reply)).isEqualTo("UNSUPPORTED_FILE_TYPE");
    }

    @Test
    void completingBeforeTheVideoArrivedSaysSoAndNothingIsRecorded() {
        Map<?, ?> link = start(OWNER, 4096, "video/mp4", 30);
        Reply early = post("/files/video-uploads/" + link.get("uploadId") + "/complete", Map.of(), OWNER);
        assertThat(early.status()).isEqualTo(409);
        assertThat(code(early)).isEqualTo("UPLOAD_NOT_FINISHED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files", Integer.class)).isZero();
        assertThat(code(post("/files/video-uploads/" + UUID.randomUUID() + "/complete", Map.of(), OWNER)))
                .isEqualTo("UPLOAD_NOT_FOUND");
    }

    @Test
    void aStoredObjectOfAnotherSizeOrNotAVideoIsRefusedAndAnyLaterPutIsStillDeleted() throws Exception {
        Map<?, ?> wrongSize = start(OWNER, 4096, "video/mp4", 30);
        assertThat(put((String) wrongSize.get("uploadUrl"), fakeMp4(2048), "video/mp4")).isEqualTo(200);
        Reply mismatch = post("/files/video-uploads/" + wrongSize.get("uploadId") + "/complete", Map.of(), OWNER);
        assertThat(mismatch.status()).isEqualTo(400);
        assertThat(code(mismatch)).isEqualTo("UPLOAD_SIZE_MISMATCH");
        assertGone(keyOf(wrongSize));
        // A PUT after the refusal lands on the staging key, which is still due for deletion
        assertThat(put((String) wrongSize.get("uploadUrl"), fakeMp4(2048), "video/mp4")).isEqualTo(200);
        assertThat(queued(keyOf(wrongSize))).isTrue();

        Map<?, ?> notVideo = start(OWNER, 4096, "video/mp4", 30);
        byte[] photo = fakeMp4(4096);
        System.arraycopy("heic".getBytes(), 0, photo, 8, 4);   // an iPhone photo: the same box, a photo brand
        System.arraycopy("mif1heic".getBytes(), 0, photo, 16, 8);
        assertThat(put((String) notVideo.get("uploadUrl"), photo, "video/mp4")).isEqualTo(200);
        java.util.Set<String> before = keptVideos();
        Reply refused = post("/files/video-uploads/" + notVideo.get("uploadId") + "/complete", Map.of(), OWNER);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(code(refused)).isEqualTo("UNSUPPORTED_FILE_TYPE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files", Integer.class)).isZero();
        // Only the two staging keys stay queued; the refused copy is gone and off the queue
        assertThat(jdbc.queryForList("SELECT storage_key FROM file_deletions", String.class))
                .containsExactlyInAnyOrder(keyOf(wrongSize), keyOf(notVideo));
        assertThat(keptVideos()).isEqualTo(before);   // no copy left (the bucket keeps other tests' objects)
    }

    /** The salon's video objects outside staging. */
    private static java.util.Set<String> keptVideos() {
        try (S3Client s3 = TestContainers.s3Client()) {
            return s3.listObjectsV2(b -> b.bucket(TestContainers.BUCKET).prefix("shops/" + SALON + "/videos/"))
                    .contents().stream().map(o -> o.key()).filter(k -> !k.contains("/staging/"))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    @Test
    void anUploadTooLateToCompleteIsRefusedAndItsObjectGoesWithTheDeletionJob() throws Exception {
        Map<?, ?> link = start(OWNER, 4096, "video/webm", 30);
        assertThat(put((String) link.get("uploadUrl"), fakeWebm(4096), "video/webm")).isEqualTo(200);
        jdbc.update("UPDATE video_uploads SET created_at = now() - interval '45 minutes 1 second'");
        Reply late = post("/files/video-uploads/" + link.get("uploadId") + "/complete", Map.of(), OWNER);
        assertThat(late.status()).isEqualTo(409);
        assertThat(code(late)).isEqualTo("UPLOAD_EXPIRED");

        String key = keyOf(link);
        assertThat(deletionJob.runOnce()).isZero();   // not due yet
        jdbc.update("UPDATE file_deletions SET next_try_at = now() - interval '1 second' WHERE storage_key = ?", key);
        assertThat(deletionJob.runOnce()).isEqualTo(1);
        assertGone(key);
    }

    @Test
    void aVideoCompletedButNeverSetOnTheShopIsPurgedWithItsRowAndCanNoLongerBeSetOrCompleted() throws Exception {
        String fileId = uploadedVideo();
        String key = fileKey(fileId);
        String upload = uploadOf(fileId);
        assertThat(queued(key)).isTrue();
        long before = version();
        jdbc.update("UPDATE file_deletions SET next_try_at = now() - interval '1 second' WHERE storage_key = ?", key);
        deletionJob.runOnce();
        assertGone(key);
        assertThat(fileKeyOrNull(fileId)).isNull();   // the row went with the bytes: nothing can attach it

        Reply set = call(HttpMethod.PUT, "/shops/me/video", Map.of("version", before, "fileId", fileId), OWNER);
        assertThat(set.status()).isEqualTo(409);
        assertThat(code(set)).isEqualTo("UPLOAD_GONE");
        assertThat(version()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT shop_video_file_id FROM shops WHERE id = ?", UUID.class, SALON))
                .isNull();
        Reply again = post("/files/video-uploads/" + upload + "/complete", Map.of(), OWNER);
        assertThat(again.status()).isEqualTo(409);
        assertThat(code(again)).isEqualTo("UPLOAD_GONE");
        assertThat(again.text()).contains("This video expired. Upload it again.");
    }

    @Test
    void aPurgeAtTheSameMomentAsTheSaveIsAnsweredUploadGoneAndTheShopStaysAsItWas() throws Exception {
        String fileId = uploadedVideo();
        String key = fileKey(fileId);
        long before = version();
        jdbc.update("UPDATE file_deletions SET next_try_at = now() - interval '1 second' WHERE storage_key = ?", key);
        // The job's claim has the queue row (and has deleted the files row) but hasn't committed yet
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        java.util.concurrent.CountDownLatch claimed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var claim = pool.submit(() -> tx.executeWithoutResult(st -> {
                deletionStore.claimDue(java.time.Instant.now(), 10, java.time.Instant.now().plusSeconds(600));
                claimed.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            claimed.await();
            var save = pool.submit(() -> call(HttpMethod.PUT, "/shops/me/video",
                    Map.of("version", before, "fileId", fileId), OWNER));
            Thread.sleep(300);   // the save waits on the queue row
            assertThat(save.isDone()).isFalse();
            release.countDown();
            claim.get();
            Reply reply = save.get();
            assertThat(reply.status()).isEqualTo(409);
            assertThat(code(reply)).isEqualTo("UPLOAD_GONE");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(version()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT shop_video_file_id FROM shops WHERE id = ?", UUID.class, SALON))
                .isNull();
    }

    @Test
    void theJobSkipsAQueueRowASaveHoldsSoASetVideoIsNeverPurged() throws Exception {
        String fileId = uploadedVideo();
        String key = fileKey(fileId);
        jdbc.update("UPDATE file_deletions SET next_try_at = now() - interval '1 second' WHERE storage_key = ?", key);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var holder = pool.submit(() -> tx.executeWithoutResult(st -> {
                deletionStore.lock(key);   // as setVideo does before keeping it
                held.countDown();
                try {
                    done.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            held.await();
            assertThat(deletionJob.runOnce()).isZero();
            assertThat(fileKeyOrNull(fileId)).isEqualTo(key);
            done.countDown();
            holder.get();
        } finally {
            done.countDown();
            pool.shutdownNow();
        }
        assertThat(stored(key)).hasSize(4096);
    }

    @Test
    void theJobForgetsUploadLinksOlderThanAWeek() {
        start(OWNER, 1000, "video/mp4", 30);
        start(OWNER, 1000, "video/mp4", 30);
        jdbc.update("UPDATE video_uploads SET created_at = now() - interval '8 days' WHERE id = "
                + "(SELECT id FROM video_uploads LIMIT 1)");
        deletionJob.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM video_uploads", Integer.class)).isEqualTo(1);
    }

    @Test
    void aNewVideoReplacesTheOldOneWhoseBytesAreQueuedDueNow() throws Exception {
        String first = uploadedVideo();
        call(HttpMethod.PUT, "/shops/me/video", Map.of("version", version(), "fileId", first), OWNER);
        String firstKey = fileKey(first);

        String second = uploadedVideo();
        // A save with an old version changes nothing: the old video stays, nothing is queued, the new stays unused
        Reply stale = call(HttpMethod.PUT, "/shops/me/video", Map.of("version", version() - 1, "fileId", second),
                OWNER);
        assertThat(code(stale)).isEqualTo("SHOP_CHANGED");
        assertThat(fileKeyOrNull(first)).isEqualTo(firstKey);
        assertThat(queued(firstKey)).isFalse();
        assertThat(queued(fileKey(second))).isTrue();

        Reply replaced = call(HttpMethod.PUT, "/shops/me/video", Map.of("version", version(), "fileId", second),
                OWNER);
        assertThat(replaced.status()).as(replaced.text()).isEqualTo(200);
        assertThat(replaced.body().get("shopVideoFileId")).isEqualTo(second);
        assertThat(fileKeyOrNull(first)).isNull();
        assertThat(jdbc.queryForObject("SELECT next_try_at <= now() FROM file_deletions WHERE storage_key = ?",
                Boolean.class, firstKey)).isTrue();
        assertThat(queued(fileKey(second))).isFalse();
        deletionJob.runOnce();
        assertGone(firstKey);
        // Its upload, completed again, says it is gone
        assertThat(code(post("/files/video-uploads/" + uploadOf(first) + "/complete", Map.of(), OWNER)))
                .isEqualTo("UPLOAD_GONE");

        // Only this shop's own video may be set
        Reply photo = call(HttpMethod.PUT, "/shops/me/video", Map.of("version", version(),
                "fileId", photoFile(SALON, OWNER, "SHOP_PHOTO")), OWNER);
        assertThat(photo.status()).isEqualTo(400);
        assertThat(photo.text()).contains("fileId");
        Reply helper = call(HttpMethod.PUT, "/shops/me/video", Map.of("version", version(), "fileId", second),
                HELPER);
        assertThat(code(helper)).isEqualTo("OWNER_ONLY");
    }

    @Test
    void aNewVideoOnALiveSalonGoesBackToTheFieldTeamWithTheChangeNamed() throws Exception {
        String first = uploadedVideo();
        call(HttpMethod.PUT, "/shops/me/video", Map.of("version", version(), "fileId", first), OWNER);
        setPhotos();
        TestData.setUpForVisit(jdbc, SALON, OWNER);
        Reply submitted = post("/shops/me/submit", Map.of("version", version()), OWNER);
        assertThat(submitted.status()).as(submitted.text()).isEqualTo(200);
        Reply approved = post("/admin/shops/" + SALON + "/approve", Map.of("version", version()), ADMIN);
        assertThat(approved.status()).as(approved.text()).isEqualTo(200);

        String second = uploadedVideo();
        Reply replaced = call(HttpMethod.PUT, "/shops/me/video", Map.of("version", version(), "fileId", second),
                OWNER);
        assertThat(replaced.status()).as(replaced.text()).isEqualTo(200);
        assertThat(replaced.body().get("status")).isNotEqualTo("APPROVED");
        Reply detail = get("/admin/shops/" + SALON, ADMIN);
        assertThat(detail.text()).contains("\"field\":\"shopVideo\"").contains("Salon video");
    }

    // ---------- helpers ----------

    private String uploadedVideo() throws Exception {
        byte[] video = fakeMp4(4096);
        Map<?, ?> link = start(OWNER, video.length, "video/mp4", 30);
        assertThat(put((String) link.get("uploadUrl"), video, "video/mp4")).isEqualTo(200);
        Reply done = post("/files/video-uploads/" + link.get("uploadId") + "/complete", Map.of(), OWNER);
        assertThat(done.status()).as(done.text()).isEqualTo(201);
        return (String) done.body().get("id");
    }

    private void setPhotos() {
        jdbc.update("UPDATE shops SET owner_photo_file_id = ?::uuid, shop_photo_file_id = ?::uuid WHERE id = ?",
                photoFile(SALON, OWNER, "OWNER_PHOTO"), photoFile(SALON, OWNER, "SHOP_PHOTO"), SALON);
    }

    private String photoFile(UUID shop, UUID by, String purpose) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO files (id, shop_id, purpose, storage_key, content_type, size_bytes, sha256, "
                        + "uploaded_by, created_at) VALUES (?, ?, ?, ?, 'image/jpeg', 10, ?, ?, now())",
                id, shop, purpose, "test/" + id + ".jpg", id.toString().replace("-", "") + "0".repeat(32), by);
        return id.toString();
    }

    private long version() {
        return version(SALON);
    }

    private long version(UUID shop) {
        Long v = jdbc.queryForObject("SELECT version FROM shops WHERE id = ?", Long.class, shop);
        return v == null ? 0 : v;
    }

    private String fileKey(String fileId) {
        return jdbc.queryForObject("SELECT storage_key FROM files WHERE id = ?::uuid", String.class, fileId);
    }

    private String fileKeyOrNull(String fileId) {
        return jdbc.queryForList("SELECT storage_key FROM files WHERE id = ?::uuid", String.class, fileId).stream()
                .findFirst().orElse(null);
    }

    private String uploadOf(String fileId) {
        return jdbc.queryForObject("SELECT id::text FROM video_uploads WHERE file_id = ?::uuid", String.class, fileId);
    }

    private boolean queued(String key) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM file_deletions WHERE storage_key = ?", Integer.class,
                key);
        return n != null && n > 0;
    }

    private static byte[] stored(String key) {
        try (S3Client s3 = TestContainers.s3Client()) {
            return s3.getObjectAsBytes(b -> b.bucket(TestContainers.BUCKET).key(key)).asByteArray();
        }
    }

    private String keyOf(Map<?, ?> link) {
        return jdbc.queryForObject("SELECT storage_key FROM video_uploads WHERE id = ?::uuid", String.class,
                link.get("uploadId"));
    }

    private static void assertGone(String key) {
        try (S3Client s3 = TestContainers.s3Client()) {
            assertThatThrownBy(() -> s3.headObject(b -> b.bucket(TestContainers.BUCKET).key(key)))
                    .isInstanceOf(NoSuchKeyException.class);
        }
    }

    private Map<?, ?> start(UUID who, long size, String type, int seconds) {
        Reply reply = startReply(who, size, type, seconds);
        assertThat(reply.status()).as(reply.text()).isEqualTo(201);
        return reply.body();
    }

    private Reply startReply(UUID who, long size, String type, Integer seconds) {
        Map<String, Object> body = new HashMap<>();
        body.put("sizeBytes", size);
        body.put("contentType", type);
        body.put("durationSeconds", seconds);
        return post("/files/video-uploads", body, who);
    }

    /** The phone's PUT straight to storage, with the type the link was signed for. */
    private static int put(String url, byte[] bytes, String contentType) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", contentType)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private Reply post(String path, Object body, UUID who) {
        return call(HttpMethod.POST, path, body, who);
    }

    private Reply get(String path, UUID who) {
        return call(HttpMethod.GET, path, null, who);
    }

    private Reply call(HttpMethod method, String path, Object body, UUID who) {
        var spec = http.method(method).uri(path).header("Authorization", "Bearer " + token(who));
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> Reply.of(res.getStatusCode().value(), res.bodyTo(String.class)));
    }

    /** A token for the person, acting for the salon when they work in it. */
    private String token(UUID who) {
        List<Map<String, Object>> m = jdbc.queryForList("SELECT shop_id, role FROM shop_members WHERE user_id = ?",
                who);
        UUID shop = m.isEmpty() ? null : (UUID) m.get(0).get("shop_id");
        String role = m.isEmpty() ? null : (String) m.get(0).get("role");
        return jwt.issue(new AuthPrincipal(who, shop, role, ADMIN.equals(who)), 0).token();
    }

    private static String code(Reply reply) {
        return (String) reply.body().get("code");
    }

    private static byte[] fakeMp4(int size) {
        byte[] bytes = new byte[size];
        new Random(7).nextBytes(bytes);
        bytes[0] = 0;
        bytes[1] = 0;
        bytes[2] = 0;
        bytes[3] = 0x20;
        System.arraycopy("ftypisom\0\0\2\0isomiso2avc1mp41".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                0, bytes, 4, 28);   // an ftyp box of 32 bytes, as a phone's MP4 starts
        return bytes;
    }

    private static byte[] fakeWebm(int size) {
        byte[] bytes = new byte[size];
        new Random(8).nextBytes(bytes);
        bytes[0] = 0x1A;
        bytes[1] = 0x45;
        bytes[2] = (byte) 0xDF;
        bytes[3] = (byte) 0xA3;
        return bytes;
    }

    private record Reply(int status, String text, Map<?, ?> body) {
        static Reply of(int status, String text) {
            Map<?, ?> body = Map.of();
            if (text != null && text.startsWith("{")) {
                try {
                    body = new tools.jackson.databind.ObjectMapper().readValue(text, Map.class);
                } catch (Exception ignored) {
                    // not JSON: leave the body empty
                }
            }
            return new Reply(status, text == null ? "" : text, body);
        }
    }
}
