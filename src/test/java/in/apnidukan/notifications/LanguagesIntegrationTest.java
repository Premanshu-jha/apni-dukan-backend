package in.apnidukan.notifications;

import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.notify.Notifications;
import in.apnidukan.common.notify.Notifications.Notice;
import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.JwtService;
import in.apnidukan.common.security.SessionRevocations;
import in.apnidukan.support.CapturingSenders;
import in.apnidukan.support.TestContainers;
import in.apnidukan.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static in.apnidukan.support.CapturingSenders.LAST_EMAIL;
import static in.apnidukan.support.CapturingSenders.LAST_SMS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Languages end to end, against real Postgres and Redis: the language a person saves (PATCH /auth/me), messages
 * written in it when queued (one lookup per batch, English for those who chose none, English byte for byte as
 * before), the fixed reasons the field team sends a shop back with (stored, returned, the old free reason still
 * accepted), and the reader's language on the admin pages.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CapturingSenders.class)
class LanguagesIntegrationTest {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtService jwt;
    @Autowired SessionRevocations sessions;
    @Autowired Notifications notifications;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean UserDirectory users;

    RestClient http;
    UUID boss;

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port") + "/api/v1")
                .build();
        TestData.clean(jdbc);
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        LAST_SMS.clear();
        LAST_EMAIL.clear();
        boss = person("9000000001");
        jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN', name = 'Prem' WHERE id = ?", boss);
    }

    // ---------------- PATCH /auth/me ----------------

    @Test
    void aPersonSavesTheirLanguageAndAnythingElseIsRefused() {
        UUID me = person("9000000011");
        Reply saved = patch("/auth/me", Map.of("language", "hi"), me);
        assertThat(saved.status()).as(saved.text()).isEqualTo(200);
        assertThat(field(saved.body(), "language")).isEqualTo("hi");
        assertThat(field(get("/auth/me", me).body(), "language")).isEqualTo("hi");

        // Not one of the six (also a wrong case): 400 VALIDATION_FAILED on the field, nothing changes
        for (String wrong : List.of("xx", "HI", "hindi", "")) {
            Reply bad = patch("/auth/me", Map.of("language", wrong), me);
            assertThat(bad.status()).as(bad.text()).isEqualTo(400);
            assertThat(field(bad.body(), "code")).isEqualTo("VALIDATION_FAILED");
            assertThat(field(field(bad.body(), "details"), "language")).isNotNull();
        }
        // A wrong language with a name: the name isn't saved either
        Reply both = patch("/auth/me", Map.of("name", "Ravi Kumar", "language", "fr"), me);
        assertThat(both.status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT name FROM users WHERE id = ?", String.class, me))
                .isNotEqualTo("Ravi Kumar");
        assertThat(lang(me)).isEqualTo("hi");

        // The name alone, as before: the language stays
        Reply renamed = patch("/auth/me", Map.of("name", "Ravi Kumar"), me);
        assertThat(renamed.status()).as(renamed.text()).isEqualTo(200);
        assertThat(field(renamed.body(), "name")).isEqualTo("Ravi Kumar");
        assertThat(field(renamed.body(), "language")).isEqualTo("hi");

        // null clears it (English again); every one of the six is accepted
        Map<String, Object> cleared = new HashMap<>();
        cleared.put("language", null);
        Reply none = patch("/auth/me", cleared, me);
        assertThat(none.status()).as(none.text()).isEqualTo(200);
        assertThat(field(none.body(), "language")).isNull();
        assertThat(lang(me)).isNull();
        for (String code : List.of("en", "hi", "te", "mr", "ta", "kn")) {
            assertThat(field(patch("/auth/me", Map.of("language", code), me).body(), "language")).isEqualTo(code);
        }

        // Nothing at all: 400 as before (name was required)
        Reply empty = patch("/auth/me", Map.of(), me);
        assertThat(empty.status()).isEqualTo(400);
        assertThat(field(field(empty.body(), "details"), "name")).isNotNull();
        // The database refuses anything else too
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname = 'ck_users_language' "
                + "AND convalidated", Long.class)).isEqualTo(1);
    }

    @Test
    void aNewAccountWithoutEmailOrNameCanSaveItsLanguage() {
        // As the apps do right after the first sign-in (the profile isn't complete yet): no PROFILE_INCOMPLETE
        UUID bare = UUID.randomUUID();
        TestData.bareUser(jdbc, bare, "9000000012");
        Reply saved = patch("/auth/me", Map.of("language", "te"), bare);
        assertThat(saved.status()).as(saved.text()).isEqualTo(200);
        assertThat(field(saved.body(), "language")).isEqualTo("te");
        assertThat(field(saved.body(), "name")).isNull();
        assertThat(lang(bare)).isEqualTo("te");
    }

    // ---------------- messages in the reader's language ----------------

    @Test
    void aShopSentBackIsToldInTheOwnersLanguageAndInEnglishWhenNoneIsChosen() {
        UUID hindi = shop("SALON", "PENDING_VISIT", "Sharma Hair", "9200000001");
        UUID english = shop("SALON", "PENDING_VISIT", "Raju Cuts", "9200000002");
        jdbc.update("UPDATE users SET language = 'hi' WHERE id = ?", ownerOf(hindi));

        for (UUID shop : List.of(hindi, english)) {
            Reply r = post("/admin/shops/" + shop + "/reject", Map.of("version", version(shop),
                    "reasonCodes", List.of("LICENCE_EXPIRED", "ADDRESS_WRONG"), "reason", "Call us before the visit."),
                    boss);
            assertThat(r.status()).as(r.text()).isEqualTo(200);
        }

        Map<String, Object> hi = notice(ownerOf(hindi), "SHOP_NOT_APPROVED");
        assertThat(hi.get("title")).isEqualTo("आपकी दुकान में कुछ ठीक करना है");
        assertThat(hi.get("body")).isEqualTo("Sharma Hair अभी मंज़ूर नहीं हुआ: ट्रेड लाइसेंस की तारीख़ निकल गई है, "
                + "पता ग़लत है। Call us before the visit. ऐप में ठीक करके फिर से भेजें।");
        Map<String, Object> en = notice(ownerOf(english), "SHOP_NOT_APPROVED");
        assertThat(en.get("title")).isEqualTo("Your shop needs a change");
        assertThat(en.get("body")).isEqualTo("Raju Cuts wasn't approved yet: the trade licence has expired, the "
                + "address is wrong. Call us before the visit. Fix it in the app and submit again.");
    }

    @Test
    void aBatchIsWrittenInEachReadersLanguageWithOneLookup() {
        UUID hindi = person("9000000021");
        UUID telugu = person("9000000022");
        UUID none = person("9000000023");
        jdbc.update("UPDATE users SET language = 'hi' WHERE id = ?", hindi);
        jdbc.update("UPDATE users SET language = 'te' WHERE id = ?", telugu);
        Mockito.clearInvocations(users);

        Instant now = Instant.now();
        List<Notice> batch = List.of(hindi, telugu, none).stream().map(u -> new Notice(u, "CUSTOMERHELP_REPLIED",
                null, now, "Support replied on CLZ-1001", "We replied to your question. Tap to read it.",
                "/help/requests/x", Map.of()).in("HELP_REPLIED", Map.of("ticket", "CLZ-1001"))).toList();
        new TransactionTemplate(transactions).executeWithoutResult(s -> notifications.queueAll(batch));

        verify(users, times(1)).languages(any());   // one query for the whole batch
        assertThat(notice(hindi, "CUSTOMERHELP_REPLIED").get("title")).isEqualTo("सपोर्ट ने CLZ-1001 पर जवाब दिया");
        assertThat(notice(telugu, "CUSTOMERHELP_REPLIED").get("title")).isEqualTo("CLZ-1001కి సపోర్ట్ జవాబు ఇచ్చింది");
        Map<String, Object> english = notice(none, "CUSTOMERHELP_REPLIED");
        assertThat(english.get("title")).isEqualTo("Support replied on CLZ-1001");
        assertThat(english.get("body")).isEqualTo("We replied to your question. Tap to read it.");

        // A batch whose notices have no text of their own: no lookup at all
        Mockito.clearInvocations(users);
        new TransactionTemplate(transactions).executeWithoutResult(s -> notifications.queueAll(List.of(
                new Notice(hindi, "SALON_TEST", null, now, "Plain", "Plain words", null, Map.of()))));
        verify(users, times(0)).languages(any());
        assertThat(notice(hindi, "SALON_TEST").get("title")).isEqualTo("Plain");
    }

    // ---------------- fixed reasons ----------------

    @Test
    void reasonCodesAreSavedAndShownToTheOwnerAndTheTeam() {
        UUID salon = shop("SALON", "PENDING_VISIT", "Sharma Hair", "9200000001");
        UUID owner = ownerOf(salon);

        // Codes only: no words for the owner, still "sent back"
        Reply back = post("/admin/shops/" + salon + "/reject", Map.of("version", version(salon),
                "reasonCodes", List.of("SHOP_FRONT_PHOTO", "BOARD_NOT_VISIBLE", "SHOP_FRONT_PHOTO"),
                "note", "Only for us"), boss);
        assertThat(back.status()).as(back.text()).isEqualTo(200);
        assertThat(field(back.body(), "statusReasonCodes")).isEqualTo(List.of("SHOP_FRONT_PHOTO", "BOARD_NOT_VISIBLE"));
        assertThat(field(back.body(), "statusReason")).isNull();
        assertThat(field(back.body(), "group")).isEqualTo("SENT_BACK");
        Reply mine = get("/shops/me", owner);
        assertThat(field(mine.body(), "statusReasonCodes")).isEqualTo(List.of("SHOP_FRONT_PHOTO", "BOARD_NOT_VISIBLE"));
        assertThat(field(mine.body(), "statusReason")).isNull();
        assertThat((List<?>) get("/admin/shops?type=SALON&group=SENT_BACK", boss).body()).hasSize(1);
        Object entry = ((List<?>) field(get("/admin/shops/" + salon, boss).body(), "timeline")).get(0);
        assertThat(field(entry, "reasonCodes")).isEqualTo(List.of("SHOP_FRONT_PHOTO", "BOARD_NOT_VISIBLE"));
        assertThat(field(entry, "note")).isEqualTo("Only for us");
        assertThat(notice(owner, "SHOP_NOT_APPROVED").get("body")).isEqualTo("Sharma Hair wasn't approved yet: the "
                + "shop front photo isn't clear, the name board can't be seen in the photo. Fix it in the app and "
                + "submit again.");

        // Submitted again: the reasons go with the words
        jdbc.update("UPDATE shops SET status = 'PENDING_VISIT', status_reason = NULL, status_reason_codes = NULL "
                + "WHERE id = ?", salon);
        // An unknown code, OTHER without words, neither codes nor words: 400 on the field
        assertThat(details(post("/admin/shops/" + salon + "/reject", Map.of("version", version(salon),
                "reasonCodes", List.of("NOT_A_REASON")), boss))).containsKey("reasonCodes");
        assertThat(details(post("/admin/shops/" + salon + "/reject", Map.of("version", version(salon),
                "reasonCodes", List.of("OTHER")), boss))).containsKey("reason");
        assertThat(details(post("/admin/shops/" + salon + "/reject", Map.of("version", version(salon),
                "reasonCodes", List.of()), boss))).containsKey("reason");
        assertThat(details(post("/admin/shops/" + salon + "/reject", Map.of("version", version(salon)), boss)))
                .containsKey("reason");
        // OTHER with words: the words are all the owner is told
        Reply other = post("/admin/shops/" + salon + "/reject", Map.of("version", version(salon),
                "reasonCodes", List.of("OTHER"), "reason", "The shop was shut at 11."), boss);
        assertThat(other.status()).as(other.text()).isEqualTo(200);
        assertThat(field(get("/shops/me", owner).body(), "statusReasonCodes")).isEqualTo(List.of("OTHER"));
        assertThat(field(get("/shops/me", owner).body(), "statusReason")).isEqualTo("The shop was shut at 11.");
    }

    @Test
    void theOldFreeReasonIsStillAcceptedAndSaysExactlyWhatItSaidBefore() {
        UUID salon = shop("SALON", "PENDING_VISIT", "Sharma Hair", "9200000001");
        UUID owner = ownerOf(salon);
        Reply back = post("/admin/shops/" + salon + "/reject", Map.of("version", version(salon),
                "reason", "The shop photo doesn't show the name board."), boss);
        assertThat(back.status()).as(back.text()).isEqualTo(200);
        Reply mine = get("/shops/me", owner);
        assertThat(field(mine.body(), "statusReason")).isEqualTo("The shop photo doesn't show the name board.");
        assertThat(field(mine.body(), "statusReasonCodes")).isEqualTo(List.of());
        assertThat(notice(owner, "SHOP_NOT_APPROVED").get("body")).isEqualTo("Sharma Hair wasn't approved yet: The "
                + "shop photo doesn't show the name board. Fix it in the app and submit again.");
        // A row from before V84: codes empty, the reason is the note
        jdbc.update("UPDATE shops SET status_reason_codes = NULL WHERE id = ?", salon);
        assertThat(field(get("/shops/me", owner).body(), "statusReasonCodes")).isEqualTo(List.of());
    }

    @Test
    void aLiveShopAskedForAFixAndADeclinedAddressCarryTheirCodes() {
        UUID salon = shop("SALON", "PENDING_VISIT", "Sharma Hair", "9200000001");
        UUID owner = ownerOf(salon);
        jdbc.update("UPDATE users SET language = 'kn' WHERE id = ?", owner);
        approve(salon);

        // A new address waits: declined with codes, the owner's latest request shows them
        Reply asked = post("/shops/me/address-request", Map.of("version", version(salon), "addressLine",
                "Shop 9, New Market Road", "area", "Kondapur", "city", "Hyderabad", "pincode", "500084",
                "latitude", 17.470, "longitude", 78.364000), owner);
        assertThat(asked.status()).as(asked.text()).isEqualTo(201);
        Object request = field(get("/admin/shops/" + salon, boss).body(), "addressRequest");
        assertThat(field(request, "requesterLanguage")).isEqualTo("kn");
        Reply declined = post("/admin/shops/" + salon + "/address-request/decline", Map.of("version", version(salon),
                "reasonCodes", List.of("PIN_WRONG_PLACE")), boss);
        assertThat(declined.status()).as(declined.text()).isEqualTo(200);
        Reply latest = get("/shops/me/address-request", owner);
        assertThat(field(latest.body(), "status")).isEqualTo("DECLINED");
        assertThat(field(latest.body(), "statusReasonCodes")).isEqualTo(List.of("PIN_WRONG_PLACE"));
        assertThat(field(latest.body(), "reason")).isNull();
        assertThat(notice(owner, "SHOP_ADDRESS_DECLINED").get("body")).asString()
                .contains("ಮ್ಯಾಪ್‌ನಲ್ಲಿ ಪಿನ್ ತಪ್ಪು ಜಾಗದಲ್ಲಿದೆ");

        // The old body still works for a decline: {version, reason}
        post("/shops/me/address-request", Map.of("version", version(salon), "addressLine", "Shop 10, New Market Road",
                "area", "Kondapur", "city", "Hyderabad", "pincode", "500084", "latitude", 17.471,
                "longitude", 78.364000), owner);
        Reply old = post("/admin/shops/" + salon + "/address-request/decline", Map.of("version", version(salon),
                "reason", "The board shows another salon."), boss);
        assertThat(old.status()).as(old.text()).isEqualTo(200);
        assertThat(field(get("/shops/me/address-request", owner).body(), "reason"))
                .isEqualTo("The board shows another salon.");
    }

    @Test
    void aDeclineWithEveryReasonAndLongWordsFitsTheTimelineAndAProblemNamesTheFieldSent() {
        UUID salon = shop("SALON", "PENDING_VISIT", "Sharma Hair", "9200000001");
        UUID owner = ownerOf(salon);
        approve(salon);
        askForAddress(salon, owner, "Shop 9, New Market Road");

        // Every reason and 199 characters of words: the reasons in words would not fit the timeline's 500
        List<String> every = java.util.Arrays.stream(in.apnidukan.shops.domain.ReviewReason.values())
                .map(Enum::name).toList();
        String words = "Please come to the shop with the original papers. ".repeat(4).strip();
        Reply declined = post("/admin/shops/" + salon + "/address-request/decline", Map.of("version", version(salon),
                "reasonCodes", every, "note", words), boss);
        assertThat(declined.status()).as(declined.text()).isEqualTo(200);
        List<?> timeline = (List<?>) field(get("/admin/shops/" + salon, boss).body(), "timeline");
        Object entry = timeline.stream().filter(e -> String.valueOf(field(e, "note"))
                .startsWith("Address change declined")).findFirst().orElseThrow();
        assertThat(field(entry, "note")).isEqualTo("Address change declined: " + words);   // the words only
        assertThat(field(entry, "reasonCodes")).isEqualTo(every);   // the reasons beside them
        assertThat(field(get("/shops/me/address-request", owner).body(), "statusReasonCodes")).isEqualTo(every);
        assertThat(notice(owner, "SHOP_ADDRESS_DECLINED").get("body")).asString()
                .startsWith("Our field team didn't approve the new address of Sharma Hair: the shop front photo "
                        + "isn't clear");

        // Words that aren't valid: the error names the field they came in
        askForAddress(salon, owner, "Shop 10, New Market Road");
        assertThat(details(post("/admin/shops/" + salon + "/address-request/decline", Map.of("version",
                version(salon), "note", "x"), boss))).containsOnlyKeys("note");
        assertThat(details(post("/admin/shops/" + salon + "/address-request/decline", Map.of("version",
                version(salon), "reason", "x"), boss))).containsOnlyKeys("reason");
        assertThat(details(post("/admin/shops/" + salon + "/address-request/decline", Map.of("version",
                version(salon), "reasonCodes", List.of("OTHER"), "note", " "), boss))).containsOnlyKeys("note");
        assertThat(details(post("/admin/shops/" + salon + "/address-request/decline", Map.of("version",
                version(salon), "reasonCodes", List.of("OTHER")), boss))).containsOnlyKeys("reason");
        // Nothing was decided meanwhile: the request still waits
        assertThat(field(get("/shops/me/address-request", owner).body(), "status")).isEqualTo("PENDING");
    }

    @Test
    void aSendBackWithEveryReasonTheLongestWordsAndNoteFitsTheTimeline() {
        UUID salon = shop("SALON", "PENDING_VISIT", "Sharma Hair", "9200000001");
        List<String> every = java.util.Arrays.stream(in.apnidukan.shops.domain.ReviewReason.values())
                .map(Enum::name).toList();
        String words = "w".repeat(200);
        String note = "n".repeat(500);
        Reply back = post("/admin/shops/" + salon + "/reject", Map.of("version", version(salon),
                "reasonCodes", every, "reason", words, "note", note), boss);
        assertThat(back.status()).as(back.text()).isEqualTo(200);
        Object entry = ((List<?>) field(get("/admin/shops/" + salon, boss).body(), "timeline")).get(0);
        assertThat(field(entry, "reason")).isEqualTo(words);   // the words only: the reasons are their codes
        assertThat(field(entry, "note")).isEqualTo(note);
        assertThat(field(entry, "reasonCodes")).isEqualTo(every);
    }

    private void askForAddress(UUID salon, UUID owner, String line) {
        Reply asked = post("/shops/me/address-request", Map.of("version", version(salon), "addressLine", line,
                "area", "Kondapur", "city", "Hyderabad", "pincode", "500084", "latitude", 17.470,
                "longitude", 78.364000), owner);
        assertThat(asked.status()).as(asked.text()).isEqualTo(201);
    }

    // ---------------- the team sees the reader's language ----------------

    @Test
    void adminPagesShowWhichLanguageThePersonReads() {
        UUID salon = shop("SALON", "PENDING_VISIT", "Sharma Hair", "9200000001");
        UUID other = shop("SALON", "PENDING_VISIT", "Raju Cuts", "9200000002");
        jdbc.update("UPDATE users SET language = 'ta' WHERE id = ?", ownerOf(salon));

        assertThat(field(field(get("/admin/shops/" + salon, boss).body(), "shop"), "ownerLanguage")).isEqualTo("ta");
        assertThat(field(field(get("/admin/shops/" + other, boss).body(), "shop"), "ownerLanguage")).isNull();
        List<?> list = (List<?>) get("/admin/shops?type=SALON&group=FIRST_VISIT", boss).body();
        assertThat(list).extracting(s -> field(s, "name") + ":" + field(s, "ownerLanguage"))
                .containsExactlyInAnyOrder("Sharma Hair:ta", "Raju Cuts:null");

        // A help ticket: the person who asked
        UUID asker = person("9000000031");
        jdbc.update("UPDATE users SET language = 'mr' WHERE id = ?", asker);
        Reply asked = post("/helpdesk/tickets", Map.of("app", "CUSTOMER", "message",
                "My booking shows cancelled but the salon says it is fine."), asker);
        assertThat(asked.status()).as(asked.text()).isEqualTo(201);
        Reply detail = get("/admin/tickets/" + field(asked.body(), "id"), boss);
        assertThat(detail.status()).as(detail.text()).isEqualTo(200);
        assertThat(field(detail.body(), "requesterLanguage")).isEqualTo("mr");
    }

    // ---------------- helpers ----------------

    private UUID person(String phone) {
        UUID id = UUID.randomUUID();
        TestData.user(jdbc, id, phone);
        return id;
    }

    private UUID shop(String type, String status, String name, String ownerPhone) {
        UUID owner = person(ownerPhone);
        UUID id = UUID.randomUUID();
        if ("SALON".equals(type)) {
            TestData.salon(jdbc, id, owner);
        } else {
            TestData.shop(jdbc, id, owner);
        }
        jdbc.update("UPDATE shops SET status = ?, name = ? WHERE id = ?", status, name, id);
        return id;
    }

    private void approve(UUID shop) {
        Reply r = post("/admin/shops/" + shop + "/approve", Map.of("version", version(shop)), boss);
        assertThat(r.status()).as(r.text()).isEqualTo(200);
    }

    /** The push row of the person's latest message of this type. */
    private Map<String, Object> notice(UUID userId, String type) {
        return jdbc.queryForMap("SELECT title, body FROM notification_jobs WHERE user_id = ? AND type = ? "
                + "AND channel = 'PUSH' ORDER BY created_at DESC LIMIT 1", userId, type);
    }

    private String lang(UUID userId) {
        return jdbc.queryForObject("SELECT language FROM users WHERE id = ?", String.class, userId);
    }

    /** The 400's field errors, by field name. */
    private Map<String, Object> details(Reply r) {
        assertThat(r.status()).as(r.text()).isEqualTo(400);
        assertThat(field(r.body(), "code")).isEqualTo("VALIDATION_FAILED");
        Map<String, Object> details = new java.util.HashMap<>();
        ((Map<?, ?>) field(r.body(), "details")).forEach((k, v) -> details.put(String.valueOf(k), v));
        return details;
    }

    private long version(UUID shop) {
        return jdbc.queryForObject("SELECT version FROM shops WHERE id = ?", Long.class, shop);
    }

    private UUID ownerOf(UUID shop) {
        return jdbc.queryForObject("SELECT user_id FROM shop_members WHERE shop_id = ? AND role = 'OWNER'",
                UUID.class, shop);
    }

    private String token(UUID who) {
        Boolean isAdmin = jdbc.queryForObject("SELECT admin_role <> 'NONE' FROM users WHERE id = ?", Boolean.class,
                who);
        return jwt.issue(new AuthPrincipal(who, null, null, Boolean.TRUE.equals(isAdmin)),
                sessions.currentGeneration(who)).token();
    }

    private Reply get(String path, UUID who) {
        return call(HttpMethod.GET, path, null, who);
    }

    private Reply post(String path, Object body, UUID who) {
        if (path.startsWith("/shops/me")) {
            TestData.unlockEditing(jdbc, redis);
        }
        return call(HttpMethod.POST, path, body, who);
    }

    private Reply patch(String path, Object body, UUID who) {
        return call(HttpMethod.PATCH, path, body, who);
    }

    private Reply call(HttpMethod method, String path, Object body, UUID who) {
        RestClient.RequestBodySpec spec = http.method(method).uri(path).headers(h -> h.setBearerAuth(token(who)));
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((rq, res) -> read(res));
    }

    private static Reply read(org.springframework.http.client.ClientHttpResponse res) throws java.io.IOException {
        int status = res.getStatusCode().value();
        String text = new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        Object parsed = text.isBlank() ? Map.of() : JSON.readValue(text, Object.class);
        return new Reply(status, parsed, text);
    }

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    private static Object field(Object json, String name) {
        return json instanceof Map<?, ?> m ? m.get(name) : null;
    }

    private record Reply(int status, Object body, String text) {
    }
}
