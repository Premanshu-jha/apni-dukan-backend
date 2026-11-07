package in.apnidukan.auth;

import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.JwtService;
import in.apnidukan.common.security.SessionRevocations;
import in.apnidukan.support.CapturingSenders;
import in.apnidukan.support.TestContainers;
import in.apnidukan.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

import static in.apnidukan.support.CapturingSenders.LAST_SMS;
import static in.apnidukan.support.CapturingSenders.codeSentTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * "Sign out everywhere" is decided in the database; Redis only makes access tokens stop at once. When Redis doesn't
 * take it, the database's sign-out must stand and the answer must not be an error: a copied refresh token is still
 * caught (and every refresh token ended), and removing a helper is still 200.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CapturingSenders.class)
class RedisDownSignOutIntegrationTest {

    private static final String PHONE = "9848012399";

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
    }

    @MockitoSpyBean SessionRevocations sessions;
    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtService jwt;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port") + "/api/v1").build();
        TestData.clean(jdbc);
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        LAST_SMS.clear();
        // Every "end all sessions" call to Redis fails from here on (reads still work)
        doThrow(new RedisConnectionFailureException("Redis is down")).when(sessions).revokeAll(any());
    }

    @Test
    void aCopiedRefreshTokenIsStillCaughtAndEveryRefreshTokenEndsWhenRedisFails() {
        post("/auth/otp/send", Map.of("phone", PHONE), null);
        Map<?, ?> first = post("/auth/otp/verify", Map.of("phone", PHONE, "code", codeSentTo(LAST_SMS, PHONE)), null)
                .body();
        String r1 = (String) first.get("refreshToken");
        String r2 = (String) post("/auth/refresh", Map.of("refreshToken", r1), null).body().get("refreshToken");
        jdbc.update("UPDATE refresh_tokens SET revoked_at = revoked_at - interval '2 minutes' "
                + "WHERE revoke_reason = 'ROTATED'");
        Reply copied = post("/auth/refresh", Map.of("refreshToken", r1), null);
        assertThat(copied.status()).isEqualTo(401);
        assertThat(copied.body().get("code")).isEqualTo("REFRESH_TOKEN_REUSED");
        // Kept, not rolled back: nothing works any more, and the alarm went once
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL",
                Integer.class)).isZero();
        assertThat(post("/auth/refresh", Map.of("refreshToken", r2), null).status()).isEqualTo(401);
    }

    @Test
    void removingAHelperIsStill200WhenRedisDoesntTakeTheSignOut() {
        UUID owner = UUID.randomUUID();
        TestData.user(jdbc, owner, "9000000001");
        UUID shop = UUID.randomUUID();
        TestData.shop(jdbc, shop, owner);
        UUID helper = UUID.randomUUID();
        TestData.user(jdbc, helper, "9000000002");
        TestData.member(jdbc, shop, helper, "HELPER");
        Reply removed = call(HttpMethod.DELETE, "/shops/me/helpers/" + helper, null,
                jwt.issue(new AuthPrincipal(owner, shop, "OWNER", false), 0).token());
        assertThat(removed.status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop_members WHERE user_id = ?", Integer.class, helper))
                .isZero();
    }

    private Reply post(String path, Object body, String token) {
        return call(HttpMethod.POST, path, body, token);
    }

    private Reply call(HttpMethod method, String path, Object body, String token) {
        RestClient.RequestBodySpec spec = http.method(method).uri(path).headers(h -> {
            if (token != null) {
                h.setBearerAuth(token);
            }
        });
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((rq, res) -> {
            int status = res.getStatusCode().value();
            String text = new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            Object read = text.isBlank() ? Map.of() : JSON.readValue(text, Object.class);
            Map<?, ?> parsed = read instanceof Map<?, ?> m ? m : Map.of("list", read);   // a list answer
            return new Reply(status, parsed);
        });
    }

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    private record Reply(int status, Map<?, ?> body) {
    }
}
