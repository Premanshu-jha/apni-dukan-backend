package in.apnidukan.auth;

import in.apnidukan.support.CapturingSenders;
import in.apnidukan.support.TestContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static in.apnidukan.support.CapturingSenders.LAST_EMAIL;
import static in.apnidukan.support.CapturingSenders.LAST_SMS;
import static in.apnidukan.support.CapturingSenders.codeSentTo;
import static in.apnidukan.support.CapturingSenders.messageTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sign-in end to end, against real Postgres and Redis in Docker: OTP, tokens, refresh rotation, sign-out,
 * email verification, recovery, limits and CORS.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CapturingSenders.class)
class AuthIntegrationTest {

    private static final String PHONE = "9848012345";

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    RestClient http;
    String base;

    @BeforeEach
    void setUp() {
        base = "http://localhost:" + environment.getProperty("local.server.port");
        http = RestClient.builder()
                // JDK client: the Apache client pulled in by Testcontainers silently retries 429 replies
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory()).baseUrl(base + "/api/v1").build();
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM users");
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        LAST_SMS.clear();
        LAST_EMAIL.clear();
    }

    @Test
    void firstSignInCreatesTheAccountAndTheTokenOpensProtectedEndpoints() {
        assertThat(post("/auth/otp/send", Map.of("phone", "+91 98480 12345"), null).status()).isEqualTo(202);
        String code = codeSentTo(LAST_SMS, PHONE);

        Reply signIn = post("/auth/otp/verify", Map.of("phone", PHONE, "code", code, "device", "Test phone"), null);
        assertThat(signIn.status()).isEqualTo(200);
        assertThat(signIn.body().get("newUser")).isEqualTo(true);
        String access = (String) signIn.body().get("accessToken");

        Reply me = get("/auth/me", access);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().get("phone")).isEqualTo(PHONE);

        // Second sign-in with the same phone: same account, not new
        post("/auth/otp/send", Map.of("phone", PHONE), null);
        Reply again = post("/auth/otp/verify", Map.of("phone", PHONE, "code", codeSentTo(LAST_SMS, PHONE)), null);
        assertThat(again.body().get("newUser")).isEqualTo(false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
    }

    @Test
    void codeWorksOnlyOnce() {
        post("/auth/otp/send", Map.of("phone", PHONE), null);
        String code = codeSentTo(LAST_SMS, PHONE);
        assertThat(post("/auth/otp/verify", Map.of("phone", PHONE, "code", code), null).status()).isEqualTo(200);
        Reply reuse = post("/auth/otp/verify", Map.of("phone", PHONE, "code", code), null);
        assertThat(reuse.status()).isEqualTo(400);
        assertThat(reuse.body().get("code")).isEqualTo("CODE_EXPIRED");
    }

    @Test
    void fiveWrongCodesDestroyTheCode() {
        post("/auth/otp/send", Map.of("phone", PHONE), null);
        String real = codeSentTo(LAST_SMS, PHONE);
        String wrong = real.equals("000000") ? "111111" : "000000";
        for (int i = 1; i <= 4; i++) {
            Reply r = post("/auth/otp/verify", Map.of("phone", PHONE, "code", wrong), null);
            assertThat(r.status()).as(r.body().toString()).isEqualTo(400);
            assertThat(r.body().get("code")).isEqualTo("WRONG_CODE");
            assertThat(((Map<?, ?>) r.body().get("details")).get("attemptsLeft")).isEqualTo(5 - i);
        }
        Reply fifth = post("/auth/otp/verify", Map.of("phone", PHONE, "code", wrong), null);
        assertThat(fifth.status()).as(fifth.body().toString()).isEqualTo(429);
        assertThat(fifth.body().get("code")).isEqualTo("TOO_MANY_ATTEMPTS");

        // Even the real code no longer works: a new one has to be requested
        Reply afterwards = post("/auth/otp/verify", Map.of("phone", PHONE, "code", real), null);
        assertThat(afterwards.status()).as(afterwards.body().toString()).isEqualTo(400);
        assertThat(afterwards.body().get("code")).isEqualTo("CODE_EXPIRED");
    }

    @Test
    void everyCounterInRedisAlwaysHasATimer() {
        post("/auth/otp/send", Map.of("phone", PHONE), null);
        post("/auth/otp/verify", Map.of("phone", PHONE, "code", "000000".equals(codeSentTo(LAST_SMS, PHONE))
                ? "111111" : "000000"), null);
        // Counters that never expired would block that phone or network forever
        for (String key : redis.keys("*")) {
            assertThat(redis.getExpire(key)).as(key).isPositive();
        }
        assertThat(redis.keys("*")).contains("rl:otp-send-all:LOGIN:" + PHONE, "rl:otp-fail-all:LOGIN:" + PHONE,
                "rl:sms-day", "otp:LOGIN:" + PHONE, "otp:LOGIN:" + PHONE + ":tries");
        assertThat(redis.keys("rl:otp-send:LOGIN:" + PHONE + ":*")).hasSize(1);
        assertThat(redis.keys("rl:otp-send-day:LOGIN:" + PHONE + ":*")).hasSize(1);
        assertThat(redis.keys("rl:otp-fail:LOGIN:" + PHONE + ":*")).hasSize(1);
        assertThat(redis.keys("rl:ip-send:*")).hasSize(1);
        assertThat(redis.keys("rl:ip-verify:*")).hasSize(1);
    }

    @Test
    void onlyThreeCodesPerTenMinutes() {
        for (int i = 0; i < 3; i++) {
            assertThat(post("/auth/otp/send", Map.of("phone", PHONE), null).status()).isEqualTo(202);
        }
        Reply fourth = post("/auth/otp/send", Map.of("phone", PHONE), null);
        assertThat(fourth.status()).isEqualTo(429);
        assertThat(fourth.body().get("code")).isEqualTo("TOO_MANY_CODES");
        // The app is told how long to wait (up to 10 minutes)
        assertThat(Integer.parseInt(fourth.retryAfter())).isBetween(1, 600);
    }

    @Test
    void invalidPhoneIsRefused() {
        Reply r = post("/auth/otp/send", Map.of("phone", "12345"), null);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("code")).isEqualTo("INVALID_PHONE");
    }

    @Test
    void refreshRotatesAndAReusedRefreshTokenSignsOutEverywhere() {
        Map<?, ?> first = signIn(PHONE).body();
        String refresh1 = (String) first.get("refreshToken");
        String access1 = (String) first.get("accessToken");

        Reply rotated = post("/auth/refresh", Map.of("refreshToken", refresh1), null);
        assertThat(rotated.status()).isEqualTo(200);
        String refresh2 = (String) rotated.body().get("refreshToken");
        assertThat(refresh2).isNotEqualTo(refresh1);

        // Coming back right away is treated as the app retrying (reply lost on a weak network): no alarm
        Reply retry = post("/auth/refresh", Map.of("refreshToken", refresh1), null);
        assertThat(retry.body().get("code")).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(get("/auth/me", (String) rotated.body().get("accessToken")).status()).isEqualTo(200);

        // Coming back minutes later means it was copied: refused, and the new one stops working too
        jdbc.update("UPDATE refresh_tokens SET revoked_at = revoked_at - interval '2 minutes' WHERE revoke_reason = 'ROTATED'");
        Reply reused = post("/auth/refresh", Map.of("refreshToken", refresh1), null);
        assertThat(reused.status()).isEqualTo(401);
        assertThat(reused.body().get("code")).isEqualTo("REFRESH_TOKEN_REUSED");
        assertThat(post("/auth/refresh", Map.of("refreshToken", refresh2), null).status()).isEqualTo(401);

        // ...and the access tokens already handed out stop at once, not after 15 minutes
        Reply me = get("/auth/me", access1);
        assertThat(me.status()).isEqualTo(401);
        assertThat(me.body().get("code")).isEqualTo("SIGNED_OUT");
        assertThat(get("/auth/me", (String) rotated.body().get("accessToken")).status()).isEqualTo(401);

        // Signing in again works normally
        Map<?, ?> fresh = signIn(PHONE).body();
        assertThat(get("/auth/me", (String) fresh.get("accessToken")).status()).isEqualTo(200);

        // Whoever holds the copied token can't keep signing the owner out: it works as an alarm only once
        Reply again = post("/auth/refresh", Map.of("refreshToken", refresh1), null);
        assertThat(again.body().get("code")).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(get("/auth/me", (String) fresh.get("accessToken")).status()).isEqualTo(200);
        assertThat(post("/auth/refresh", Map.of("refreshToken", fresh.get("refreshToken")), null).status())
                .isEqualTo(200);
    }

    @Test
    void anOldDeviceTryingItsSignedOutTokenDoesNotSignOutTheOthers() {
        Map<?, ?> phoneA = signIn(PHONE).body();
        Map<?, ?> phoneB = signIn(PHONE).body();
        post("/auth/logout", Map.of("refreshToken", phoneA.get("refreshToken")), null);

        Reply r = post("/auth/refresh", Map.of("refreshToken", phoneA.get("refreshToken")), null);
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.body().get("code")).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(get("/auth/me", (String) phoneB.get("accessToken")).status()).isEqualTo(200);
        assertThat(post("/auth/refresh", Map.of("refreshToken", phoneB.get("refreshToken")), null).status())
                .isEqualTo(200);
    }

    @Test
    void logoutEndsTheSession() {
        String refresh = (String) signIn(PHONE).body().get("refreshToken");
        assertThat(post("/auth/logout", Map.of("refreshToken", refresh), null).status()).isEqualTo(204);
        assertThat(post("/auth/refresh", Map.of("refreshToken", refresh), null).status()).isEqualTo(401);
    }

    @Test
    void protectedEndpointsNeedAValidToken() {
        Reply none = get("/auth/me", null);
        assertThat(none.status()).isEqualTo(401);
        assertThat(none.body().get("code")).isEqualTo("NOT_SIGNED_IN");

        Reply forged = get("/auth/me", "not.a.real.token");
        assertThat(forged.status()).isEqualTo(401);
        assertThat(forged.body().get("code")).isEqualTo("INVALID_TOKEN");
    }

    @Test
    void adminPagesNeedAnAdminAccount() {
        String access = (String) signIn(PHONE).body().get("accessToken");
        Reply r = get("/admin/shops", access);
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body().get("code")).isEqualTo("FORBIDDEN");
    }

    @Test
    void emailIsVerifiedWithACode() {
        String access = (String) signIn(PHONE).body().get("accessToken");
        assertThat(post("/auth/email/send-code", Map.of("email", " Sharma.Store@Gmail.com "), access).status())
                .isEqualTo(202);
        String code = codeSentTo(LAST_EMAIL, "sharma.store@gmail.com");

        Reply verified = post("/auth/email/verify", Map.of("email", "sharma.store@gmail.com", "code", code), access);
        assertThat(verified.status()).isEqualTo(200);
        assertThat(verified.body().get("email")).isEqualTo("sharma.store@gmail.com");
        assertThat(verified.body().get("emailVerified")).isEqualTo(true);
    }

    @Test
    void emailAlreadyVerifiedByAnotherAccountIsRefused() {
        verifyEmail(signIn(PHONE), "shop@gmail.com");
        String other = (String) signIn("9000000001").body().get("accessToken");
        // Sending answers like for any email (otherwise anyone could test which emails have accounts)...
        assertThat(post("/auth/email/send-code", Map.of("email", "shop@gmail.com"), other).status()).isEqualTo(202);
        // ...the clash shows only to someone who has the code from that inbox
        Reply r = post("/auth/email/verify",
                Map.of("email", "shop@gmail.com", "code", codeSentTo(LAST_EMAIL, "shop@gmail.com")), other);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body().get("code")).isEqualTo("EMAIL_IN_USE");
    }

    @Test
    void recoveryMovesTheAccountToANewPhoneAndSignsOutOldSessions() {
        Reply first = signIn(PHONE);
        String oldRefresh = (String) first.body().get("refreshToken");
        String oldAccess = (String) first.body().get("accessToken");
        verifyEmail(first, "shop@gmail.com");
        LAST_SMS.clear();
        LAST_EMAIL.clear();

        String newPhone = "9700000002";
        assertThat(post("/auth/recover/start", Map.of("email", "shop@gmail.com", "newPhone", newPhone), null)
                .status()).isEqualTo(202);
        Reply done = post("/auth/recover/complete", Map.of(
                "email", "shop@gmail.com",
                "emailCode", codeSentTo(LAST_EMAIL, "shop@gmail.com"),
                "newPhone", newPhone,
                "phoneCode", codeSentTo(LAST_SMS, newPhone)), null);
        assertThat(done.status()).isEqualTo(200);
        assertThat(((Map<?, ?>) done.body().get("user")).get("phone")).isEqualTo(newPhone);
        // The lost phone trying its old token gets "sign in again" and does not end the new session
        Reply oldTry = post("/auth/refresh", Map.of("refreshToken", oldRefresh), null);
        assertThat(oldTry.status()).isEqualTo(401);
        assertThat(oldTry.body().get("code")).isEqualTo("INVALID_REFRESH_TOKEN");
        // Old sessions end at once; the new one works
        assertThat(get("/auth/me", oldAccess).body().get("code")).isEqualTo("SIGNED_OUT");
        assertThat(get("/auth/me", (String) done.body().get("accessToken")).status()).isEqualTo(200);
        // The real owner is told on the old phone and by email
        assertThat(messageTo(LAST_SMS, PHONE)).contains("account moved to a new number ending 0002");
        long until = System.currentTimeMillis() + 3000;
        while (!LAST_EMAIL.getOrDefault("shop@gmail.com", "").contains("ending 0002")
                && System.currentTimeMillis() < until) {
            Thread.yield();
        }
        assertThat(LAST_EMAIL.get("shop@gmail.com")).contains("ending 0002");
    }

    @Test
    void recoveryDoesNotRevealWhetherAnEmailHasAnAccount() {
        verifyEmail(signIn(PHONE), "shop@gmail.com");
        LAST_SMS.clear();
        LAST_EMAIL.clear();

        // Someone tries a real email and a made-up one, using their own phone each time
        Reply real = post("/auth/recover/start", Map.of("email", "shop@gmail.com", "newPhone", "9700000003"), null);
        Reply fake = post("/auth/recover/start", Map.of("email", "nobody@gmail.com", "newPhone", "9700000004"), null);
        assertThat(real.status()).isEqualTo(202);
        assertThat(fake.status()).isEqualTo(202);
        // Both phones get an SMS, so the SMS doesn't give it away; only the real owner's inbox gets a code
        assertThat(LAST_SMS).containsKeys("9700000003", "9700000004");
        messageTo(LAST_EMAIL, "shop@gmail.com"); // sent in the background, so both replies take the same time
        assertThat(LAST_EMAIL).containsOnlyKeys("shop@gmail.com");

        // Guessing the email code gives the same answer for both
        Reply realGuess = post("/auth/recover/complete", Map.of("email", "shop@gmail.com", "emailCode", "123456",
                "newPhone", "9700000003", "phoneCode", codeSentTo(LAST_SMS, "9700000003")), null);
        Reply fakeGuess = post("/auth/recover/complete", Map.of("email", "nobody@gmail.com", "emailCode", "123456",
                "newPhone", "9700000004", "phoneCode", codeSentTo(LAST_SMS, "9700000004")), null);
        assertThat(realGuess.status()).isEqualTo(fakeGuess.status()).isEqualTo(400);
        assertThat(realGuess.body().get("code")).isEqualTo(fakeGuess.body().get("code")).isEqualTo("WRONG_CODE");

        // And the same limits apply to both
        for (int i = 0; i < 2; i++) {
            post("/auth/recover/start", Map.of("email", "shop@gmail.com", "newPhone", "9700000003"), null);
            post("/auth/recover/start", Map.of("email", "nobody@gmail.com", "newPhone", "9700000004"), null);
        }
        assertThat(post("/auth/recover/start", Map.of("email", "shop@gmail.com", "newPhone", "9700000005"), null)
                .body().get("code")).isEqualTo("TOO_MANY_CODES");
        assertThat(post("/auth/recover/start", Map.of("email", "nobody@gmail.com", "newPhone", "9700000006"), null)
                .body().get("code")).isEqualTo("TOO_MANY_CODES");
    }

    @Test
    void guessesSentAllAtOnceCannotSlipPastTheFiveTryLimit() throws Exception {
        post("/auth/otp/send", Map.of("phone", PHONE), null);
        String real = codeSentTo(LAST_SMS, PHONE);
        String wrong = real.equals("000000") ? "111111" : "000000";

        ExecutorService pool = Executors.newFixedThreadPool(25);
        try {
            List<Future<Reply>> replies = new java.util.ArrayList<>();
            for (int i = 0; i < 25; i++) {
                replies.add(pool.submit(() -> post("/auth/otp/verify", Map.of("phone", PHONE, "code", wrong), null)));
            }
            int wrongCodeAnswers = 0;
            for (Future<Reply> f : replies) {
                Reply r = f.get();
                assertThat(r.status()).as(r.body().toString()).isIn(400, 429);
                if ("WRONG_CODE".equals(r.body().get("code"))) {
                    wrongCodeAnswers++;
                }
            }
            // Only the first 5 attempts were ever compared: 4 "wrong", then the code was destroyed
            assertThat(wrongCodeAnswers).isEqualTo(4);
        } finally {
            pool.shutdownNow();
        }
        assertThat(post("/auth/otp/verify", Map.of("phone", PHONE, "code", real), null).body().get("code"))
                .isEqualTo("CODE_EXPIRED");
    }

    @Test
    void tenWrongCodesInADayLockThePhoneUntilTomorrow() {
        String wrong;
        for (int round = 1; round <= 2; round++) {
            post("/auth/otp/send", Map.of("phone", PHONE), null);
            String real = codeSentTo(LAST_SMS, PHONE);
            wrong = real.equals("000000") ? "111111" : "000000";
            for (int i = 1; i <= 5; i++) {
                post("/auth/otp/verify", Map.of("phone", PHONE, "code", wrong), null);
            }
        }
        Reply send = post("/auth/otp/send", Map.of("phone", PHONE), null);
        assertThat(send.status()).isEqualTo(429);
        assertThat(send.body().get("code")).isEqualTo("LOCKED_FOR_TODAY");
    }

    @Test
    void aStrangerGuessingFromTheirNetworkCannotLockTheOwnerOut() {
        String stranger = "203.0.113.50";
        for (int round = 1; round <= 2; round++) {
            postFrom(stranger, "/auth/otp/send", Map.of("phone", PHONE));
            String real = codeSentTo(LAST_SMS, PHONE);
            String wrong = real.equals("000000") ? "111111" : "000000";
            for (int i = 1; i <= 5; i++) {
                postFrom(stranger, "/auth/otp/verify", Map.of("phone", PHONE, "code", wrong));
            }
        }
        assertThat(postFrom(stranger, "/auth/otp/send", Map.of("phone", PHONE)).body().get("code"))
                .isEqualTo("LOCKED_FOR_TODAY");
        // The owner, on their own network, still signs in normally
        assertThat(signIn(PHONE).status()).isEqualTo(200);
    }

    @Test
    void oneNetworkCannotSprayCodesToManyNumbers() {
        for (int i = 0; i < 10; i++) {
            assertThat(post("/auth/otp/send", Map.of("phone", "90000000" + String.format("%02d", i)), null).status())
                    .isEqualTo(202);
        }
        Reply eleventh = post("/auth/otp/send", Map.of("phone", "9000000099"), null);
        assertThat(eleventh.status()).isEqualTo(429);
        assertThat(eleventh.body().get("code")).isEqualTo("TOO_MANY_REQUESTS");

        // Another caller (IP passed on by the proxy) is not affected
        Reply other = postFrom("203.0.113.9", "/auth/otp/send", Map.of("phone", "9000000099"));
        assertThat(other.status()).isEqualTo(202);
    }

    @Test
    void dailySmsCapPausesSendingToProtectTheBill() {
        redis.opsForValue().set("rl:sms-day", "500");
        Reply r = post("/auth/otp/send", Map.of("phone", PHONE), null);
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.body().get("code")).isEqualTo("SMS_PAUSED");
        assertThat(LAST_SMS).isEmpty();
    }

    @Test
    void hugeRequestBodiesAreRefusedBeforeBeingRead() {
        Reply r = post("/auth/otp/send", Map.of("phone", "9".repeat(70_000)), null);
        assertThat(r.status()).isEqualTo(413);
        assertThat(r.body().get("code")).isEqualTo("REQUEST_TOO_LARGE");
    }

    @Test
    void hugeBodiesWithoutALengthAreStoppedWhileReading() throws Exception {
        byte[] big = ("{\"phone\":\"" + "9".repeat(70_000) + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpRequest chunked = HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/otp/send"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(big)))
                .build();
        HttpResponse<String> r = HttpClient.newHttpClient().send(chunked, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(413);
        assertThat(r.body()).contains("REQUEST_TOO_LARGE");
    }

    @Test
    void onlyTheFileUploadMayBeALargeMultipartRequest() throws Exception {
        String boundary = "ApniDukan";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"x\"\r\n\r\n"
                + "9".repeat(70_000) + "\r\n--" + boundary + "--\r\n";
        HttpRequest multipart = HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/otp/send"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> r = HttpClient.newHttpClient().send(multipart, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(413);
    }

    @Test
    void anExpiredRefreshTokenTriedTwiceDoesNotSignOutEverywhere() {
        Map<?, ?> other = signIn(PHONE).body();
        Map<?, ?> idle = signIn(PHONE).body();
        jdbc.update("UPDATE refresh_tokens SET expires_at = now() - interval '1 day' WHERE token_hash = ?",
                in.apnidukan.auth.service.Hashing.sha256Hex((String) idle.get("refreshToken")));

        for (int i = 0; i < 2; i++) {
            Reply r = post("/auth/refresh", Map.of("refreshToken", idle.get("refreshToken")), null);
            assertThat(r.status()).isEqualTo(401);
            assertThat(r.body().get("code")).isIn("REFRESH_TOKEN_EXPIRED", "INVALID_REFRESH_TOKEN");
        }
        // The other device is untouched
        assertThat(get("/auth/me", (String) other.get("accessToken")).status()).isEqualTo(200);
        assertThat(post("/auth/refresh", Map.of("refreshToken", other.get("refreshToken")), null).status())
                .isEqualTo(200);
    }

    @Test
    void changingAVerifiedEmailNeedsACodeFromTheOwnPhoneAndTellsTheOldAddress() {
        Reply signedIn = signIn(PHONE);
        String access = (String) signedIn.body().get("accessToken");
        verifyEmail(signedIn, "owner@gmail.com");

        // Someone holding only the token can't swap in their own email
        Reply blocked = post("/auth/email/send-code", Map.of("email", "attacker@gmail.com"), access);
        assertThat(blocked.status()).isEqualTo(403);
        assertThat(blocked.body().get("code")).isEqualTo("PHONE_CODE_REQUIRED");
        assertThat(LAST_EMAIL).doesNotContainKey("attacker@gmail.com");

        // The owner, with their phone, can
        assertThat(post("/auth/email/change-phone-code", Map.of(), access).status()).isEqualTo(202);
        String phoneCode = codeSentTo(LAST_SMS, PHONE);
        assertThat(post("/auth/email/send-code", Map.of("email", "new.owner@gmail.com", "phoneCode", phoneCode), access)
                .status()).isEqualTo(202);
        Reply changed = post("/auth/email/verify",
                Map.of("email", "new.owner@gmail.com", "code", codeSentTo(LAST_EMAIL, "new.owner@gmail.com")), access);
        assertThat(changed.status()).isEqualTo(200);
        assertThat(changed.body().get("email")).isEqualTo("new.owner@gmail.com");

        // ...and the old address hears about it
        long until = System.currentTimeMillis() + 3000;
        while (!LAST_EMAIL.getOrDefault("owner@gmail.com", "").contains("was changed")
                && System.currentTimeMillis() < until) {
            Thread.yield();
        }
        assertThat(LAST_EMAIL.get("owner@gmail.com")).contains("was changed to n***@gmail.com");
    }

    @Test
    void tokenSignedWithAnotherSecretIsRefused() {
        var other = new in.apnidukan.common.security.JwtService(
                new in.apnidukan.common.security.JwtProperties("some-other-secret-0123456789-abcdefghijkl", 15, 30),
                java.time.Clock.systemUTC());
        String forged = other.issue(new in.apnidukan.common.security.AuthPrincipal(
                java.util.UUID.randomUUID(), null, null, true), 0).token();
        Reply r = get("/admin/shops", forged);
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.body().get("code")).isEqualTo("INVALID_TOKEN");
    }

    @Test
    void corsAllowsTheShopAppAndRefusesOtherSites() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> allowed = client.send(preflight("http://localhost:5173"), HttpResponse.BodyHandlers.ofString());
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:5173");

        HttpResponse<String> refused = client.send(preflight("https://evil.example"), HttpResponse.BodyHandlers.ofString());
        assertThat(refused.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(refused.statusCode()).isEqualTo(403);
    }

    // ---------- helpers ----------

    private Reply signIn(String phone) {
        post("/auth/otp/send", Map.of("phone", phone), null);
        Reply r = post("/auth/otp/verify", Map.of("phone", phone, "code", codeSentTo(LAST_SMS, phone)), null);
        assertThat(r.status()).isEqualTo(200);
        return r;
    }

    private void verifyEmail(Reply signedIn, String email) {
        String access = (String) signedIn.body().get("accessToken");
        post("/auth/email/send-code", Map.of("email", email), access);
        Reply r = post("/auth/email/verify", Map.of("email", email, "code", codeSentTo(LAST_EMAIL, email)), access);
        assertThat(r.status()).isEqualTo(200);
    }

    private HttpRequest preflight(String origin) {
        return HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/otp/send"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
                .build();
    }

    private Reply post(String path, Object body, String token) {
        return http.post().uri(path)
                .headers(h -> bearer(h, token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((rq, res) -> reply(res));
    }

    private Reply get(String path, String token) {
        return http.get().uri(path)
                .headers(h -> bearer(h, token))
                .exchange((rq, res) -> reply(res));
    }

    private static void bearer(org.springframework.http.HttpHeaders headers, String token) {
        if (token != null) {
            headers.setBearerAuth(token);
        }
    }

    private static Map<?, ?> readBody(org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse res)
            throws java.io.IOException {
        int status = res.getStatusCode().value();
        if (status == 202 || status == 204) {
            return Map.of();
        }
        return res.bodyTo(Map.class);
    }

    private Reply postFrom(String ip, String path, Object body) {
        return http.post().uri(path)
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((rq, res) -> reply(res));
    }

    private static Reply reply(org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse res)
            throws java.io.IOException {
        Map<?, ?> body = readBody(res);
        return new Reply(res.getStatusCode().value(), body == null ? Map.of() : body,
                res.getHeaders().getFirst("Retry-After"));
    }

    private record Reply(int status, Map<?, ?> body, String retryAfter) {
    }
}
