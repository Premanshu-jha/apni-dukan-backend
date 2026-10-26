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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static in.apnidukan.support.CapturingSenders.LAST_EMAIL;
import static in.apnidukan.support.CapturingSenders.LAST_SMS;
import static in.apnidukan.support.CapturingSenders.codeSentTo;
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
        http = RestClient.builder().baseUrl(base + "/api/v1").build();
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
        for (int i = 1; i <= 5; i++) {
            Reply r = post("/auth/otp/verify", Map.of("phone", PHONE, "code", wrong), null);
            assertThat(r.body().get("code")).isEqualTo("WRONG_CODE");
        }
        Reply sixth = post("/auth/otp/verify", Map.of("phone", PHONE, "code", real), null);
        assertThat(sixth.status()).isEqualTo(429);
        assertThat(sixth.body().get("code")).isEqualTo("TOO_MANY_ATTEMPTS");
    }

    @Test
    void onlyThreeCodesPerTenMinutes() {
        for (int i = 0; i < 3; i++) {
            assertThat(post("/auth/otp/send", Map.of("phone", PHONE), null).status()).isEqualTo(202);
        }
        Reply fourth = post("/auth/otp/send", Map.of("phone", PHONE), null);
        assertThat(fourth.status()).isEqualTo(429);
        assertThat(fourth.body().get("code")).isEqualTo("TOO_MANY_CODES");
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

        Reply rotated = post("/auth/refresh", Map.of("refreshToken", refresh1), null);
        assertThat(rotated.status()).isEqualTo(200);
        String refresh2 = (String) rotated.body().get("refreshToken");
        assertThat(refresh2).isNotEqualTo(refresh1);

        // The old one comes back (e.g. copied by someone): refused, and the new one stops working too
        Reply reused = post("/auth/refresh", Map.of("refreshToken", refresh1), null);
        assertThat(reused.status()).isEqualTo(401);
        assertThat(reused.body().get("code")).isEqualTo("REFRESH_TOKEN_REUSED");
        assertThat(post("/auth/refresh", Map.of("refreshToken", refresh2), null).status()).isEqualTo(401);
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
        Reply r = post("/auth/email/send-code", Map.of("email", "shop@gmail.com"), other);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body().get("code")).isEqualTo("EMAIL_IN_USE");
    }

    @Test
    void recoveryMovesTheAccountToANewPhoneAndSignsOutOldSessions() {
        Reply first = signIn(PHONE);
        String oldRefresh = (String) first.body().get("refreshToken");
        verifyEmail(first, "shop@gmail.com");

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
        assertThat(post("/auth/refresh", Map.of("refreshToken", oldRefresh), null).status()).isEqualTo(401);
    }

    @Test
    void recoveryStartDoesNotRevealWhetherAnEmailExists() {
        Reply r = post("/auth/recover/start", Map.of("email", "nobody@gmail.com", "newPhone", "9700000003"), null);
        assertThat(r.status()).isEqualTo(202);
        assertThat(LAST_EMAIL).isEmpty();
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
                .exchange((rq, res) -> new Reply(res.getStatusCode(), readBody(res)));
    }

    private Reply get(String path, String token) {
        return http.get().uri(path)
                .headers(h -> bearer(h, token))
                .exchange((rq, res) -> new Reply(res.getStatusCode(), readBody(res)));
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

    private record Reply(int status, Map<?, ?> body) {
        Reply(HttpStatusCode code, Map<?, ?> body) {
            this(code.value(), body == null ? Map.of() : body);
        }
    }
}
