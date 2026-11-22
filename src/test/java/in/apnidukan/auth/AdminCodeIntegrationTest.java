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

import java.util.Map;
import java.util.UUID;

import static in.apnidukan.support.CapturingSenders.LAST_EMAIL;
import static in.apnidukan.support.CapturingSenders.LAST_SMS;
import static in.apnidukan.support.CapturingSenders.codeSentTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin website's second step, end to end against real Postgres and Redis: an admin signed in with the phone code
 * has no admin access until a code sent to their verified email is typed on that device; then it lasts 12 hours
 * across refreshes, and only on that device. Everyone else signs in exactly as before.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CapturingSenders.class)
class AdminCodeIntegrationTest {

    private static final String PHONE = "9848012345";
    private static final String EMAIL = "boss@example.com";

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port") + "/api/v1").build();
        in.apnidukan.support.TestData.clean(jdbc);
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        LAST_SMS.clear();
        LAST_EMAIL.clear();
        CapturingSenders.emailDown = false;
    }

    @Test
    void aPhoneCodeAloneGivesAnAdminNoAdminPagesTheEmailCodeDoes() {
        UUID boss = superAdminWithEmail();
        Map<?, ?> signIn = signIn("Office laptop");
        String access = (String) signIn.get("accessToken");
        assertThat(user(signIn).get("admin")).isEqualTo(true);
        assertThat(user(signIn).get("adminCodeNeeded")).isEqualTo(true);
        // Their own account works; the admin pages don't
        assertThat(get("/auth/me", access).body().get("adminCodeNeeded")).isEqualTo(true);
        Reply refused = get("/admin/me", access);
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.body().get("code")).isEqualTo("FORBIDDEN");

        assertThat(post("/auth/admin-code/send", Map.of(), access).status()).isEqualTo(202);
        String code = codeSentTo(LAST_EMAIL, EMAIL);
        String wrong = code.equals("000000") ? "111111" : "000000";
        Reply bad = post("/auth/admin-code/verify", Map.of("code", wrong), access);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().get("code")).isEqualTo("WRONG_CODE");
        assertThat(count(boss)).isZero();

        Reply ok = post("/auth/admin-code/verify", Map.of("code", code), access);
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
        assertThat(user(ok.body()).get("adminCodeNeeded")).isEqualTo(false);
        String adminAccess = (String) ok.body().get("accessToken");
        assertThat(get("/admin/me", adminAccess).status()).isEqualTo(200);
        assertThat(get("/auth/me", adminAccess).body().get("adminCodeNeeded")).isEqualTo(false);
        assertThat(count(boss)).isEqualTo(1);

        // The code works once
        assertThat(post("/auth/admin-code/verify", Map.of("code", code), access).body().get("code"))
                .isEqualTo("CODE_EXPIRED");

        // Every refresh on this device keeps admin access
        Reply refreshed = post("/auth/refresh", Map.of("refreshToken", signIn.get("refreshToken")), null);
        assertThat(user(refreshed.body()).get("adminCodeNeeded")).isEqualTo(false);
        assertThat(get("/admin/me", (String) refreshed.body().get("accessToken")).status()).isEqualTo(200);
    }

    @Test
    void theCodeIsForTheDeviceThatAskedAndAnotherDeviceNeedsItsOwn() {
        superAdminWithEmail();
        Map<?, ?> laptop = signIn("Office laptop");
        Map<?, ?> phone = signIn("Phone");
        post("/auth/admin-code/send", Map.of(), (String) laptop.get("accessToken"));
        String laptopCode = codeSentTo(LAST_EMAIL, EMAIL);

        // A SIM thief signed in on their phone can't use a code meant for the owner's laptop
        Reply stolen = post("/auth/admin-code/verify", Map.of("code", laptopCode), (String) phone.get("accessToken"));
        assertThat(stolen.status()).isEqualTo(400);
        assertThat(stolen.body().get("code")).isEqualTo("CODE_EXPIRED");

        Reply mine = post("/auth/admin-code/verify", Map.of("code", laptopCode), (String) laptop.get("accessToken"));
        assertThat(mine.status()).isEqualTo(200);
        // The phone still has no admin access, even after a refresh
        Reply phoneRefresh = post("/auth/refresh", Map.of("refreshToken", phone.get("refreshToken")), null);
        assertThat(user(phoneRefresh.body()).get("adminCodeNeeded")).isEqualTo(true);
        assertThat(get("/admin/me", (String) phoneRefresh.body().get("accessToken")).status()).isEqualTo(403);
    }

    @Test
    void after12HoursTheStepIsAskedAgainAndLeavingTheTeamEndsIt() {
        UUID boss = superAdminWithEmail();
        Map<?, ?> signIn = signIn("Office laptop");
        String access = (String) signIn.get("accessToken");
        post("/auth/admin-code/send", Map.of(), access);
        post("/auth/admin-code/verify", Map.of("code", codeSentTo(LAST_EMAIL, EMAIL)), access);

        jdbc.update("UPDATE admin_sessions SET verified_at = now() - interval '12 hours 1 minute' WHERE user_id = ?",
                boss);
        Reply later = post("/auth/refresh", Map.of("refreshToken", signIn.get("refreshToken")), null);
        assertThat(user(later.body()).get("adminCodeNeeded")).isEqualTo(true);
        assertThat(get("/admin/me", (String) later.body().get("accessToken")).status()).isEqualTo(403);

        // Passed again, then removed from the team: no admin access at the next refresh, and no more codes
        String again = (String) later.body().get("accessToken");
        post("/auth/admin-code/send", Map.of(), again);
        post("/auth/admin-code/verify", Map.of("code", codeSentTo(LAST_EMAIL, EMAIL)), again);
        jdbc.update("UPDATE users SET admin_role = 'NONE' WHERE id = ?", boss);
        Reply removed = post("/auth/refresh", Map.of("refreshToken", later.body().get("refreshToken")), null);
        assertThat(user(removed.body()).get("admin")).isEqualTo(false);
        assertThat(user(removed.body()).get("adminCodeNeeded")).isEqualTo(false);
        String customerAccess = (String) removed.body().get("accessToken");
        assertThat(get("/admin/me", customerAccess).status()).isEqualTo(403);
        assertThat(post("/auth/admin-code/send", Map.of(), customerAccess).body().get("code"))
                .isEqualTo("NOT_AN_ADMIN");
    }

    @Test
    void customersNeverSeeTheStepAndAnAdminWithoutAVerifiedEmailIsToldToAddOne() {
        Map<?, ?> customer = signIn("Phone");
        assertThat(user(customer).get("adminCodeNeeded")).isEqualTo(false);
        assertThat(post("/auth/admin-code/send", Map.of(), (String) customer.get("accessToken")).body().get("code"))
                .isEqualTo("NOT_AN_ADMIN");
        assertThat(post("/auth/admin-code/verify", Map.of("code", "123456"), (String) customer.get("accessToken"))
                .body().get("code")).isEqualTo("NOT_AN_ADMIN");

        jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN', name = 'Prem' WHERE phone = ?", PHONE);
        Map<?, ?> admin = signIn("Office laptop");
        Reply noEmail = post("/auth/admin-code/send", Map.of(), (String) admin.get("accessToken"));
        assertThat(noEmail.status()).isEqualTo(409);
        assertThat(noEmail.body().get("code")).isEqualTo("EMAIL_REQUIRED");
        assertThat(LAST_EMAIL).isEmpty();
        // Without a code (or without being signed in), nothing
        assertThat(post("/auth/admin-code/verify", Map.of("code", ""), (String) admin.get("accessToken")).status())
                .isEqualTo(400);
        assertThat(post("/auth/admin-code/send", Map.of(), null).status()).isEqualTo(401);
    }

    // ---------------- helpers ----------------

    /** The person who signs in with PHONE, made a super admin with a verified email. */
    private UUID superAdminWithEmail() {
        signIn("First sign-in");
        jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN', name = 'Prem', email = ?, email_verified_at = now() "
                + "WHERE phone = ?", EMAIL, PHONE);
        return jdbc.queryForObject("SELECT id FROM users WHERE phone = ?", UUID.class, PHONE);
    }

    private Map<?, ?> signIn(String device) {
        assertThat(post("/auth/otp/send", Map.of("phone", PHONE), null).status()).isEqualTo(202);
        Reply r = post("/auth/otp/verify", Map.of("phone", PHONE, "code", codeSentTo(LAST_SMS, PHONE),
                "device", device), null);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.body();
    }

    private int count(UUID who) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM admin_sessions WHERE user_id = ?", Integer.class, who);
        return n == null ? 0 : n;
    }

    private static Map<?, ?> user(Map<?, ?> body) {
        return (Map<?, ?>) body.get("user");
    }

    private Reply post(String path, Object body, String token) {
        return http.post().uri(path)
                .headers(h -> {
                    if (token != null) {
                        h.setBearerAuth(token);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((rq, res) -> reply(res));
    }

    private Reply get(String path, String token) {
        return http.get().uri(path)
                .headers(h -> h.setBearerAuth(token))
                .exchange((rq, res) -> reply(res));
    }

    private static Reply reply(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse res)
            throws java.io.IOException {
        int status = res.getStatusCode().value();
        Map<?, ?> body = status == 202 || status == 204 ? Map.of() : res.bodyTo(Map.class);
        return new Reply(status, body == null ? Map.of() : body);
    }

    private record Reply(int status, Map<?, ?> body) {
    }
}
