package in.apnidukan.appupdates;

import in.apnidukan.appupdates.service.AppUpdates;
import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.JwtService;
import in.apnidukan.common.security.SessionRevocations;
import in.apnidukan.support.TestContainers;
import in.apnidukan.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * App updates end to end, against real Postgres, Redis and S3-compatible storage: CI sends a release (start, PUT to
 * the upload link, publish), the apps ask which screens to use, and the super admin pulls releases back, puts them
 * back and sets the version numbers. Only the partner app has a release token here: the customer app's releases are
 * off (503 RELEASES_OFF).
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AppReleasesIntegrationTest {

    static final String TOKEN = "partner-release-token-0123456789abcdef";

    private static final UUID BOSS = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    private static final UUID AGENT = UUID.fromString("a0000000-0000-0000-0000-000000000002");
    private static final UUID CUSTOMER = UUID.fromString("a0000000-0000-0000-0000-000000000003");

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
        registry.add("app.app-releases.token.partner", () -> TOKEN);
        registry.add("app.app-releases.token.customer", () -> "too-short");
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired SessionRevocations sessions;
    @Autowired AppUpdates updates;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port") + "/api/v1")
                .build();
        TestData.clean(jdbc);
        TestData.user(jdbc, BOSS, "9000000201");
        TestData.user(jdbc, AGENT, "9000000202");
        TestData.user(jdbc, CUSTOMER, "9000000203");
        jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN', name = 'Prem' WHERE id = ?", BOSS);
        jdbc.update("UPDATE users SET admin_role = 'ADMIN', admin_designation = 'FIELD_AGENT' WHERE id = ?", AGENT);
        updates.refresh();
    }

    // ---- CI ----------------------------------------------------------------------------------------------------

    @Test
    void ciSendsAReleaseAndItGoesLive() throws Exception {
        byte[] zip = bundle("first");
        Reply started = start("partner", TOKEN, release(100, 1, zip.length));
        assertThat(started.status()).isEqualTo(200);
        String upload = (String) field(started.body(), "uploadUrl");

        // Not sent yet: nothing to publish, it stays UPLOADING
        assertThat(code(publish("partner", TOKEN, 100))).isEqualTo("UPLOAD_MISSING");
        assertThat(jdbc.queryForObject("SELECT status FROM app_releases WHERE build = 100", String.class))
                .isEqualTo("UPLOADING");

        assertThat(put(upload, zip)).isEqualTo(200);
        Reply published = publish("partner", TOKEN, 100);
        assertThat(published.status()).isEqualTo(200);
        assertThat(field(published.body(), "status")).isEqualTo("LIVE");
        // A repeated publish (a retried CI step) changes nothing
        assertThat(publish("partner", TOKEN, 100).status()).isEqualTo(200);
        // A published build is never replaced
        assertThat(code(start("partner", TOKEN, release(100, 1, zip.length)))).isEqualTo("RELEASE_EXISTS");

        // A test APK of an older run gets it, and the link downloads exactly the bytes CI sent
        Reply check = check("partner", 1, 99, true);
        Map<?, ?> bundle = (Map<?, ?>) field(check.body(), "bundle");
        assertThat(bundle.get("build")).isEqualTo(100);
        assertThat(bundle.get("checksum")).isEqualTo("abcdef0123456789abcdef0123456789");
        assertThat(bundle.get("sessionKey")).isEqualTo("aXZpdml2aXZpdml2:a2V5a2V5a2V5a2V5");
        HttpResponse<byte[]> download = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create((String) bundle.get("url"))).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(zip);
        assertThat(field(check.body(), "updateRequired")).isEqualTo(false);
    }

    @Test
    void aRetriedStartBeforePublishingTakesTheNewDetails() throws Exception {
        start("partner", TOKEN, release(101, 1, 10));
        byte[] zip = bundle("retried");
        Reply again = start("partner", TOKEN, release(101, 2, zip.length));
        assertThat(again.status()).isEqualTo(200);
        assertThat(put((String) field(again.body(), "uploadUrl"), zip)).isEqualTo(200);
        assertThat(publish("partner", TOKEN, 101).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT native_level FROM app_releases WHERE build = 101", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_releases", Integer.class)).isEqualTo(1);
    }

    @Test
    void ciNeedsTheAppsOwnTokenAndValidDetails() {
        Map<String, Object> ok = release(102, 1, 10);
        assertThat(start("partner", null, ok).status()).isEqualTo(401);
        assertThat(code(start("partner", "wrong-token-wrong-token-wrong-token", ok))).isEqualTo("BAD_RELEASE_TOKEN");
        // The customer app's token is too short to be safe: its releases are off
        assertThat(code(start("customer", TOKEN, ok))).isEqualTo("RELEASES_OFF");
        assertThat(start("someone", TOKEN, ok).status()).isEqualTo(404);
        assertThat(code(publish("partner", "wrong-token-wrong-token-wrong-token", 102))).isEqualTo("BAD_RELEASE_TOKEN");
        assertThat(publish("partner", TOKEN, 999).status()).isEqualTo(404);

        for (Map.Entry<String, Object> bad : List.<Map.Entry<String, Object>>of(
                Map.entry("build", 0), Map.entry("nativeLevel", 0), Map.entry("versionName", "1.0 beta"),
                Map.entry("commitSha", "NOTHEX!"), Map.entry("sizeBytes", 52_428_801L),
                Map.entry("checksum", "short"), Map.entry("sessionKey", "no-colon-here"))) {
            Map<String, Object> body = new HashMap<>(ok);
            body.put(bad.getKey(), bad.getValue());
            Reply r = start("partner", TOKEN, body);
            assertThat(r.status()).as(bad.getKey()).isEqualTo(400);
            assertThat(((Map<?, ?>) field(r.body(), "details")).containsKey(bad.getKey())).as(bad.getKey()).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_releases", Integer.class)).isZero();
    }

    @Test
    void twoRunsSendingTheSameBuildAtOnceMakeOneRelease() throws Exception {
        byte[] zip = bundle("together");
        List<Reply> starts = together(8, () -> start("partner", TOKEN, release(103, 1, zip.length)));
        assertThat(starts).allMatch(r -> r.status() == 200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_releases", Integer.class)).isEqualTo(1);
        // Each start gave the build a new key (a retried start never reuses an earlier attempt's zip): the last one
        // counts, so CI sends its zip after its own start
        Reply last = start("partner", TOKEN, release(103, 1, zip.length));
        assertThat(put((String) field(last.body(), "uploadUrl"), zip)).isEqualTo(200);
        List<Reply> publishes = together(8, () -> publish("partner", TOKEN, 103));
        assertThat(publishes).allMatch(r -> r.status() == 200);
        assertThat(jdbc.queryForObject("SELECT status FROM app_releases WHERE build = 103", String.class))
                .isEqualTo("LIVE");
    }

    // ---- Which screens a phone gets --------------------------------------------------------------------------

    @Test
    void phonesGetTheNewestReleaseTheirAppCanRun() throws Exception {
        live(200, 1);
        live(210, 2);   // needs the native part of level 2

        // A test APK of level 1 skips 210 (needs level 2) and gets 200; level 2 gets 210
        assertThat(build(check("partner", 1, 150, true))).isEqualTo(200);
        assertThat(build(check("partner", 2, 150, true))).isEqualTo(210);
        // An app installed with screens as new as the newest release: its own screens
        assertThat(build(check("partner", 2, 210, true))).isNull();
        assertThat(build(check("partner", 2, 205, true))).isEqualTo(210);
        // Another app's releases are never offered
        assertThat(build(check("customer", 2, 150, true))).isNull();

        // Play Store phones get nothing until the super admin says which version Google has approved...
        assertThat(build(check("partner", 2, 150, false))).isNull();
        settings(BOSS, null, 150, 0);
        assertThat(build(check("partner", 2, 150, false))).isEqualTo(210);
        // ...and a newer version (still in Google's review) keeps the screens it was submitted with
        assertThat(build(check("partner", 2, 160, false))).isNull();
    }

    @Test
    void aPulledReleaseSendsPhonesBackAndCanBePutBack() throws Exception {
        live(300, 1);
        live(310, 1);
        assertThat(build(check("partner", 1, 290, true))).isEqualTo(310);

        Reply pulled = post("/admin/app-releases/partner/310/pull-back", BOSS);
        assertThat(pulled.status()).isEqualTo(200);
        assertThat(field(pulled.body(), "status")).isEqualTo("PULLED");
        assertThat(field(pulled.body(), "changedByName")).isEqualTo("Prem");
        assertThat(build(check("partner", 1, 290, true))).isEqualTo(300);
        assertThat(code(post("/admin/app-releases/partner/310/pull-back", BOSS))).isEqualTo("RELEASE_NOT_LIVE");
        // CI can't put it back by publishing again
        assertThat(code(publish("partner", TOKEN, 310))).isEqualTo("RELEASE_PULLED");

        post("/admin/app-releases/partner/300/pull-back", BOSS);
        assertThat(build(check("partner", 1, 290, true))).isNull();   // back to the app's own screens

        assertThat(post("/admin/app-releases/partner/310/restore", BOSS).status()).isEqualTo(200);
        assertThat(build(check("partner", 1, 290, true))).isEqualTo(310);
        assertThat(code(post("/admin/app-releases/partner/310/restore", BOSS))).isEqualTo("RELEASE_NOT_PULLED");
        assertThat(post("/admin/app-releases/partner/999/restore", BOSS).status()).isEqualTo(404);
        assertThat(post("/admin/app-releases/partner/999/pull-back", BOSS).status()).isEqualTo(404);

        assertThat(jdbc.queryForList("SELECT event || ' ' || detail FROM admin_events ORDER BY at", String.class))
                .containsExactly("APP_RELEASE_PULLED partner build 310", "APP_RELEASE_PULLED partner build 300",
                        "APP_RELEASE_RESTORED partner build 310");
        assertThat(jdbc.queryForList("SELECT DISTINCT by_user FROM admin_events", UUID.class)).containsExactly(BOSS);
    }

    @Test
    void anUrgentReleaseIsMarkedByTheSuperAdminOrByCiAndPhonesAreToldWhichBuildHasIt() throws Exception {
        live(400, 1);
        live(410, 1);
        live(420, 1);
        Reply plain = check("partner", 1, 390, true);
        assertThat(build(plain)).isEqualTo(420);
        assertThat(field(plain.body(), "urgentBuild")).isNull();

        // 410 fixed something serious: phones on older screens are told 410 is urgent (the bundle, 420, includes it)
        assertThat(code(call(HttpMethod.POST, "/admin/app-releases/partner/410/urgent", Map.of("note", "  "), BOSS)))
                .isEqualTo("VALIDATION_FAILED");
        assertThat(code(call(HttpMethod.POST, "/admin/app-releases/partner/410/urgent", Map.of("note", "Fix it"),
                AGENT))).isEqualTo("SUPER_ADMIN_ONLY");
        Reply marked = call(HttpMethod.POST, "/admin/app-releases/partner/410/urgent",
                Map.of("note", "  Fixed   the wrong bill total "), BOSS);
        assertThat(marked.status()).isEqualTo(200);
        assertThat(field(marked.body(), "urgent")).isEqualTo(true);
        assertThat(field(marked.body(), "urgentNote")).isEqualTo("Fixed the wrong bill total");
        assertThat(field(marked.body(), "urgentByName")).isEqualTo("Prem");
        Reply told = check("partner", 1, 390, true);
        assertThat(build(told)).isEqualTo(420);
        assertThat(field(told.body(), "urgentBuild")).isEqualTo(410);
        // An app installed with 410's screens already: nothing urgent to get
        assertThat(field(check("partner", 1, 410, true).body(), "urgentBuild")).isNull();
        // Marked again with the same note (a retry): fine. Another super admin's other note: told, theirs stays
        assertThat(call(HttpMethod.POST, "/admin/app-releases/partner/410/urgent",
                Map.of("note", "Fixed the wrong bill total"), BOSS).status()).isEqualTo(200);
        assertThat(code(call(HttpMethod.POST, "/admin/app-releases/partner/410/urgent",
                Map.of("note", "Another note"), BOSS))).isEqualTo("ALREADY_URGENT");
        assertThat(field(get("/admin/app-releases/partner", BOSS).body(), "releases").toString())
                .contains("Fixed the wrong bill total").doesNotContain("Another note");
        assertThat(code(call(HttpMethod.POST, "/admin/app-releases/partner/410/urgent",
                Map.of("note", "x".repeat(121)), BOSS))).isEqualTo("VALIDATION_FAILED");
        // Back to silent
        assertThat(post("/admin/app-releases/partner/410/not-urgent", BOSS).status()).isEqualTo(200);
        assertThat(field(check("partner", 1, 390, true).body(), "urgentBuild")).isNull();
        assertThat(jdbc.queryForList("SELECT event FROM admin_events ORDER BY at", String.class))
                .containsExactly("APP_RELEASE_URGENT", "APP_RELEASE_NOT_URGENT");

        // A pulled-back release can't be urgent
        post("/admin/app-releases/partner/400/pull-back", BOSS);
        assertThat(code(call(HttpMethod.POST, "/admin/app-releases/partner/400/urgent", Map.of("note", "Fix"), BOSS)))
                .isEqualTo("RELEASE_NOT_LIVE");

        // CI sends an [urgent] PR's title as the note: urgent from the moment it goes live, by nobody on the team
        byte[] zip = bundle("build 430");
        Map<String, Object> urgent = release(430, 1, zip.length);
        urgent.put("urgentNote", "Fix payments failing on the subscription screen");
        Reply started = start("partner", TOKEN, urgent);
        assertThat(started.status()).isEqualTo(200);
        assertThat(field(check("partner", 1, 390, true).body(), "urgentBuild")).isNull();   // not live yet
        put((String) field(started.body(), "uploadUrl"), zip);
        publish("partner", TOKEN, 430);
        assertThat(field(check("partner", 1, 390, true).body(), "urgentBuild")).isEqualTo(430);
        Map<?, ?> row = (Map<?, ?>) ((List<?>) field(get("/admin/app-releases/partner", BOSS).body(), "releases")).get(0);
        assertThat(row.get("urgentNote")).isEqualTo("Fix payments failing on the subscription screen");
        assertThat(row.get("urgentByName")).isNull();
        // A note that is no note is refused before anything is uploaded
        Map<String, Object> bad = release(440, 1, zip.length);
        bad.put("urgentNote", "x".repeat(121));
        assertThat(code(start("partner", TOKEN, bad))).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void anUrgentReleaseANewerNativePartNeedsIsNeverAnnouncedToAnOlderApp() throws Exception {
        live(500, 1);
        live(510, 2);   // needs the native part of level 2
        call(HttpMethod.POST, "/admin/app-releases/partner/510/urgent", Map.of("note", "Fix for level 2"), BOSS);
        // A level-1 app gets 500, which doesn't have 510's fix: nothing urgent for it
        Reply old = check("partner", 1, 490, true);
        assertThat(build(old)).isEqualTo(500);
        assertThat(field(old.body(), "urgentBuild")).isNull();
        // A level-2 app gets 510 and is told it is urgent
        assertThat(field(check("partner", 2, 490, true).body(), "urgentBuild")).isEqualTo(510);
        // An older urgent release inside a newer bundle is still announced
        call(HttpMethod.POST, "/admin/app-releases/partner/510/not-urgent", null, BOSS);
        call(HttpMethod.POST, "/admin/app-releases/partner/500/urgent", Map.of("note", "Fix for all"), BOSS);
        Reply both = check("partner", 2, 490, true);
        assertThat(build(both)).isEqualTo(510);
        assertThat(field(both.body(), "urgentBuild")).isEqualTo(500);
    }

    @Test
    void anAppOlderThanTheOldestAllowedMustUpdate() {
        settings(BOSS, 120, 150, 0);
        assertThat(field(check("partner", 1, 119, false).body(), "updateRequired")).isEqualTo(true);
        assertThat(field(check("partner", 1, 120, false).body(), "updateRequired")).isEqualTo(false);
        // A test APK is never made to update; the other app has its own numbers
        assertThat(field(check("partner", 1, 1, true).body(), "updateRequired")).isEqualTo(false);
        assertThat(field(check("customer", 1, 1, false).body(), "updateRequired")).isEqualTo(false);
        // The customer app's own numbers change nothing for the partner app
        assertThat(call(HttpMethod.PUT, "/admin/app-releases/customer/settings",
                Map.of("minVersionCode", 500, "storeVersionCode", 500, "version", 0), BOSS).status()).isEqualTo(200);
        assertThat(field(check("customer", 1, 499, false).body(), "updateRequired")).isEqualTo(true);
        assertThat(field(check("partner", 1, 120, false).body(), "updateRequired")).isEqualTo(false);
    }

    @Test
    void theCheckRefusesWrongNumbers() {
        assertThat(check("partner", 0, 10, false).status()).isEqualTo(400);
        assertThat(check("partner", 1, 0, false).status()).isEqualTo(400);
        assertThat(check("tablet", 1, 10, false).status()).isEqualTo(404);
        Reply missing = http.get().uri("/app-updates/partner?versionCode=10").exchange((rq, res) -> read(res));
        assertThat(missing.status()).isEqualTo(400);
    }

    // ---- Super admin ------------------------------------------------------------------------------------------

    @Test
    void onlyASuperAdminSeesAndChangesReleases() throws Exception {
        live(400, 1);
        assertThat(code(get("/admin/app-releases/partner", AGENT))).isEqualTo("SUPER_ADMIN_ONLY");
        assertThat(code(post("/admin/app-releases/partner/400/pull-back", AGENT))).isEqualTo("SUPER_ADMIN_ONLY");
        assertThat(code(settings(AGENT, null, 10, 0))).isEqualTo("SUPER_ADMIN_ONLY");
        jdbc.update("UPDATE users SET admin_designation = 'SUPPORT' WHERE id = ?", AGENT);
        assertThat(code(get("/admin/app-releases/partner", AGENT))).isEqualTo("SUPER_ADMIN_ONLY");
        assertThat(code(post("/admin/app-releases/partner/400/pull-back", AGENT))).isEqualTo("SUPER_ADMIN_ONLY");
        assertThat(get("/admin/app-releases/partner", CUSTOMER).status()).isEqualTo(403);
        assertThat(get("/admin/app-releases/partner", null).status()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT status FROM app_releases WHERE build = 400", String.class))
                .isEqualTo("LIVE");

        Reply page = get("/admin/app-releases/partner", BOSS);
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.text()).doesNotContain("storage", "sessionKey", "checksum", "app-releases/");
    }

    @Test
    void theListPagesNewestFirst() {
        for (int build = 1; build <= 25; build++) {
            start("partner", TOKEN, release(build, 1, 10));
        }
        Reply first = get("/admin/app-releases/partner", BOSS);
        List<?> releases = (List<?>) field(first.body(), "releases");
        assertThat(releases).hasSize(20);
        assertThat(((Map<?, ?>) releases.get(0)).get("build")).isEqualTo(25);
        assertThat(((Map<?, ?>) releases.get(0)).get("status")).isEqualTo("UPLOADING");
        assertThat(field(first.body(), "more")).isEqualTo(true);
        Reply second = get("/admin/app-releases/partner?before=6", BOSS);
        assertThat((List<?>) field(second.body(), "releases")).hasSize(5);
        assertThat(field(second.body(), "more")).isEqualTo(false);
    }

    @Test
    void settingsAreCheckedAndNeverOverwriteAnotherAdminsChange() {
        assertThat(code(settings(BOSS, 200, 150, 0))).isEqualTo("MIN_ABOVE_STORE");
        assertThat(code(settings(BOSS, 10, null, 0))).isEqualTo("MIN_ABOVE_STORE");
        assertThat(settings(BOSS, null, 0, 0).status()).isEqualTo(400);

        Reply saved = settings(BOSS, 100, 150, 0);
        assertThat(saved.status()).isEqualTo(200);
        assertThat(field(saved.body(), "version")).isEqualTo(1);
        assertThat(field(saved.body(), "changedByName")).isEqualTo("Prem");
        // Another super admin's page still shows version 0
        assertThat(code(settings(BOSS, null, 160, 0))).isEqualTo("SETTINGS_CHANGED");
        assertThat(field(get("/admin/app-releases/partner", BOSS).body(), "settings"))
                .isEqualTo(Map.of("minVersionCode", 100, "storeVersionCode", 150, "version", 1,
                        "changedAt", ((Map<?, ?>) saved.body()).get("changedAt"), "changedByName", "Prem"));
        assertThat(jdbc.queryForObject("SELECT detail FROM admin_events", String.class))
                .isEqualTo("partner: oldest allowed 100, on Play Store 150");
    }

    @Test
    void aRetriedStartNeverPublishesTheEarlierAttemptsZip() throws Exception {
        byte[] first = bundle("attempt 1");
        Reply one = start("partner", TOKEN, release(104, 1, first.length));
        assertThat(put((String) field(one.body(), "uploadUrl"), first)).isEqualTo(200);
        // The run is retried before attempt 1 published: a new key, so attempt 1's zip isn't this attempt's
        byte[] second = bundle("attempt 2");
        Map<String, Object> retried = release(104, 1, second.length);
        retried.put("checksum", "fedcba9876543210fedcba9876543210");
        Reply two = start("partner", TOKEN, retried);
        assertThat(field(two.body(), "uploadUrl")).isNotEqualTo(field(one.body(), "uploadUrl"));
        assertThat(code(publish("partner", TOKEN, 104))).isEqualTo("UPLOAD_MISSING");
        assertThat(put((String) field(two.body(), "uploadUrl"), second)).isEqualTo(200);
        assertThat(publish("partner", TOKEN, 104).status()).isEqualTo(200);
        // A late PUT of attempt 1 lands on its own key: phones still get attempt 2's zip and checksum
        assertThat(put((String) field(one.body(), "uploadUrl"), first)).isEqualTo(200);
        Map<?, ?> bundle = (Map<?, ?>) field(check("partner", 1, 1, true).body(), "bundle");
        assertThat(bundle.get("checksum")).isEqualTo("fedcba9876543210fedcba9876543210");
        assertThat(HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create((String) bundle.get("url")))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray()).body()).isEqualTo(second);
    }

    @Test
    void anOlderAppStillGetsItsNewestReleaseAfterManyForANewerNativePart() {
        insertLive(500, 1);
        for (int build = 501; build <= 560; build++) {
            insertLive(build, 2);
        }
        updates.refresh();
        assertThat(build(check("partner", 1, 400, true))).isEqualTo(500);
        assertThat(build(check("partner", 2, 400, true))).isEqualTo(560);
    }

    // ---- Helpers ------------------------------------------------------------------------------------------------

    /** A release sent by CI and made live, with a few bytes of "bundle". */
    private void live(int build, int nativeLevel) throws Exception {
        byte[] zip = bundle("build " + build);
        Reply started = start("partner", TOKEN, release(build, nativeLevel, zip.length));
        assertThat(started.status()).isEqualTo(200);
        assertThat(put((String) field(started.body(), "uploadUrl"), zip)).isEqualTo(200);
        assertThat(publish("partner", TOKEN, build).status()).isEqualTo(200);
    }

    /** A LIVE release written straight into the table (its zip is never downloaded in the test that uses it). */
    private void insertLive(int build, int nativeLevel) {
        jdbc.update("INSERT INTO app_releases (id, app, build, native_level, version_name, commit_sha, size_bytes, "
                + "checksum, session_key, storage_key, status, created_at, published_at) VALUES (?, 'partner', ?, ?, "
                + "'1.4.0', '3afac83', 10, 'abcdef0123456789', 'aXZpdml2:a2V5a2V5', ?, 'LIVE', now(), now())",
                UUID.randomUUID(), build, nativeLevel, "app-releases/partner/" + build + ".zip");
    }

    private static byte[] bundle(String text) {
        return ("PK-not-really-a-zip " + text).getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> release(int build, int nativeLevel, long size) {
        Map<String, Object> m = new HashMap<>();
        m.put("build", build);
        m.put("nativeLevel", nativeLevel);
        m.put("versionName", "1.4.0");
        m.put("commitSha", "3afac83");
        m.put("sizeBytes", size);
        m.put("checksum", "abcdef0123456789abcdef0123456789");
        m.put("sessionKey", "aXZpdml2aXZpdml2:a2V5a2V5a2V5a2V5");
        return m;
    }

    private Reply start(String app, String token, Map<String, Object> body) {
        return http.post().uri("/app-releases/" + app).headers(h -> {
            if (token != null) {
                h.set("X-Release-Token", token);
            }
        }).contentType(MediaType.APPLICATION_JSON).body(body).exchange((rq, res) -> read(res));
    }

    private Reply publish(String app, String token, int build) {
        return http.post().uri("/app-releases/" + app + "/" + build + "/publish")
                .headers(h -> h.set("X-Release-Token", token)).exchange((rq, res) -> read(res));
    }

    private Reply check(String app, int nativeLevel, int versionCode, boolean debug) {
        return http.get().uri("/app-updates/" + app + "?nativeLevel=" + nativeLevel + "&versionCode=" + versionCode
                + "&debug=" + debug).exchange((rq, res) -> read(res));
    }

    private static Integer build(Reply check) {
        assertThat(check.status()).isEqualTo(200);
        Object bundle = field(check.body(), "bundle");
        return bundle == null ? null : (Integer) ((Map<?, ?>) bundle).get("build");
    }

    private Reply settings(UUID who, Integer min, Integer store, int version) {
        Map<String, Object> body = new HashMap<>();
        body.put("minVersionCode", min);
        body.put("storeVersionCode", store);
        body.put("version", version);
        return call(HttpMethod.PUT, "/admin/app-releases/partner/settings", body, who);
    }

    private Reply get(String path, UUID who) {
        return call(HttpMethod.GET, path, null, who);
    }

    private Reply post(String path, UUID who) {
        return call(HttpMethod.POST, path, null, who);
    }

    private Reply call(HttpMethod method, String path, Object body, UUID who) {
        RestClient.RequestBodySpec spec = http.method(method).uri(path).headers(h -> {
            if (who != null) {
                h.setBearerAuth(token(who));
            }
        });
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((rq, res) -> read(res));
    }

    private String token(UUID who) {
        Boolean isAdmin = jdbc.queryForObject("SELECT admin_role <> 'NONE' FROM users WHERE id = ?", Boolean.class,
                who);
        return jwt.issue(new AuthPrincipal(who, null, null, Boolean.TRUE.equals(isAdmin)),
                sessions.currentGeneration(who)).token();
    }

    private static int put(String url, byte[] bytes) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/zip")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static <T> List<T> together(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(task));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static Object field(Object body, String name) {
        return ((Map<?, ?>) body).get(name);
    }

    private static String code(Reply r) {
        return (String) field(r.body(), "code");
    }

    private static Reply read(org.springframework.http.client.ClientHttpResponse res) throws java.io.IOException {
        int status = res.getStatusCode().value();
        String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
        Object parsed = text.isBlank() ? Map.of() : JSON.readValue(text, Object.class);
        return new Reply(status, parsed, text);
    }

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    private record Reply(int status, Object body, String text) {
    }
}
