package in.apnidukan.shops;

import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.JwtService;
import in.apnidukan.common.security.SessionRevocations;
import in.apnidukan.files.storage.FileStorage;
import in.apnidukan.shops.service.LicenceAiWatch;
import in.apnidukan.shops.service.LicenceCheckJob;
import in.apnidukan.shops.service.LicenceExtract;
import in.apnidukan.shops.service.LicenceReader;
import in.apnidukan.support.CapturingSenders;
import in.apnidukan.support.TestClock;
import in.apnidukan.support.TestContainers;
import in.apnidukan.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The super admins' emails about the licence-reading AI: what it has cost against the month's credit (80%, 100%),
 * and Anthropic refusing our account (out of credit, a wrong key) and working again, with a fake AI that reports the
 * tokens each test says. "Now" is Saturday 14 Nov 2026, 10:00 in India; the credit arrives on the 4th
 * (LICENCE_AI_BUDGET_DAY), so the period is 4 Nov to 3 Dec.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.licence.ai-budget-day=4", "app.licence.ai-budget-usd=100"})
@Import({CapturingSenders.class, LicenceAiAlertsIntegrationTest.Fakes.class})
class LicenceAiAlertsIntegrationTest {

    static final Instant NOW = Instant.parse("2026-11-14T04:30:00Z");
    static final TestClock CLOCK = new TestClock(NOW);
    /** The next answer's input tokens (at $2 a million: 5,000,000 is $10); output tokens are 0. */
    static volatile long tokens = 0;
    /** Set to make the fake AI refuse the account like Anthropic does. */
    static volatile LicenceAiWatch.Problem refuse;
    /** Set to make the fake AI refuse the file itself (too big): says nothing about the account. */
    static volatile boolean refuseFile;

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        TestClock testClock() {
            return CLOCK;
        }

        /** Answers a genuine licence, reporting {@link #tokens} like AnthropicLicenceReader does for every answer. */
        @Bean
        LicenceReader fakeAi(org.springframework.beans.factory.ObjectProvider<LicenceAiWatch> watch) {
            return (bytes, contentType) -> {
                if (refuse != null) {
                    throw new LicenceReader.ReaderUnavailableException("refused", refuse);
                }
                if (refuseFile) {
                    throw new LicenceReader.UnusableAnswerException("file refused", false);
                }
                watch.getObject().record(tokens, 0);
                return new LicenceExtract(true, true, false, false, true, "GHMC", "TR-" + UUID.randomUUID(),
                        "Sharma Hair", "Ravi Sharma", "Shop 4, Kondapur", "500084", "Saloon", LocalDate.of(2027, 3, 31));
            };
        }
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired SessionRevocations sessions;
    @Autowired FileStorage storage;
    @Autowired LicenceCheckJob job;
    @Autowired LicenceAiWatch watch;

    RestClient http;
    UUID boss;
    UUID otherBoss;
    UUID fieldAgent;
    int phones;

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port") + "/api/v1")
                .build();
        TestData.clean(jdbc);
        CLOCK.set(NOW);
        // The pause after a failed email lives on the bean: each test starts without one
        org.springframework.test.util.ReflectionTestUtils.setField(watch, "emailsPausedUntil", Instant.MIN);
        tokens = 0;
        refuse = null;
        refuseFile = false;
        CapturingSenders.emailDown = false;
        CapturingSenders.EMAILS_SENT.clear();
        CapturingSenders.LAST_EMAIL.clear();
        phones = 0;
        boss = admin("SUPER_ADMIN", null, "boss@clozest.in");
        otherBoss = admin("SUPER_ADMIN", null, "second@clozest.in");
        admin("SUPER_ADMIN", null, null);   // no verified email: nobody to send to, never an error
        fieldAgent = admin("ADMIN", "FIELD_AGENT", "agent@clozest.in");
    }

    @AfterEach
    void tearDown() {
        CapturingSenders.emailDown = false;
    }

    @Test
    void theSuperAdminsAreEmailedOnceAt80AndOnceAt100PercentOfTheMonthsCredit() {
        read(25_000_000);   // $50
        assertThat(emails("boss@clozest.in")).isZero();
        read(15_000_000);   // $80: 80%
        assertThat(emails("boss@clozest.in")).isEqualTo(1);
        assertThat(emails("second@clozest.in")).isEqualTo(1);
        assertThat(emails("agent@clozest.in")).isZero();   // super admins only
        assertThat(CapturingSenders.LAST_EMAIL.get("boss@clozest.in"))
                .contains("$80.00 of the $100.00").contains("4 Nov 2026 to 3 Dec 2026").contains("2 licences read");
        read(500_000);   // $81: still 80%, no second email
        assertThat(emails("boss@clozest.in")).isEqualTo(1);
        read(9_500_000);   // $100
        assertThat(emails("boss@clozest.in")).isEqualTo(2);
        assertThat(CapturingSenders.LAST_EMAIL.get("boss@clozest.in")).contains("$100.00 of the $100.00")
                .contains("about 4 Dec 2026");

        Reply usage = get("/admin/shops/licence-ai-usage", boss);
        assertThat(usage.status()).as(usage.text()).isEqualTo(200);
        assertThat(field(usage.body(), "periodStart")).isEqualTo("2026-11-04");
        assertThat(field(usage.body(), "periodEnd")).isEqualTo("2026-12-03");
        assertThat(field(usage.body(), "reads")).isEqualTo(4);
        assertThat(((Number) field(usage.body(), "spentUsd")).doubleValue()).isEqualTo(100.0);
        assertThat(((Number) field(usage.body(), "budgetUsd")).doubleValue()).isEqualTo(100.0);
        assertThat(field(usage.body(), "problem")).isNull();
        assertThat(field(usage.body(), "keySet")).isEqualTo(true);
        assertThat(field(usage.body(), "alertRecipients")).isEqualTo(2);   // the third super admin has no email
        assertThat(get("/admin/shops/licence-ai-usage", fieldAgent).status()).isEqualTo(403);

        // The next period starts on the 4th (India's date) from nothing
        CLOCK.set(Instant.parse("2026-12-03T18:31:00Z"));   // 4 Dec, 00:01 in India
        assertThat(field(get("/admin/shops/licence-ai-usage", boss).body(), "reads")).isEqualTo(0);
        read(45_000_000);   // $90 at once: straight to the 80% email
        assertThat(emails("boss@clozest.in")).isEqualTo(3);
    }

    @Test
    void aBudgetEmailNobodyGotIsSentWithTheNextAnswer() {
        CapturingSenders.emailDown = true;
        read(40_000_000);   // $80, the provider down
        assertThat(emails("boss@clozest.in")).isZero();
        assertThat(jdbc.queryForObject("SELECT budget_notice FROM licence_ai_spend", Integer.class)).isZero();
        CapturingSenders.emailDown = false;
        read(1);   // within 10 minutes of the failure: no email tried (checks don't wait on a failing provider)
        assertThat(emails("boss@clozest.in")).isZero();
        CLOCK.advance(Duration.ofMinutes(11));
        read(1);
        assertThat(emails("boss@clozest.in")).isEqualTo(1);
        read(1);
        assertThat(emails("boss@clozest.in")).isEqualTo(1);
    }

    @Test
    void outOfCreditEmailsAtOnceAgainADayLaterAndOnceWhenItWorksAgainReadingTheWaitingAtOnce() {
        refuse = LicenceAiWatch.Problem.CREDIT;
        UUID salon = salonWithLicence();
        job.runOnce();
        assertThat(emails("boss@clozest.in")).isEqualTo(1);
        assertThat(emails("second@clozest.in")).isEqualTo(1);   // every super admin with an email
        assertThat(emails("agent@clozest.in")).isZero();
        assertThat(CapturingSenders.LAST_EMAIL.get("boss@clozest.in")).contains("credit")
                .contains("platform.claude.com/settings/billing").contains("Since 14 Nov, 10:00 AM")
                .contains("about 4 Dec 2026");   // when next month's credit comes
        assertThat(status(salon)).isEqualTo("PENDING");   // never failed for our credit
        Reply usage = get("/admin/shops/licence-ai-usage", boss);
        assertThat(field(usage.body(), "problem")).isEqualTo("CREDIT");
        assertThat(field(usage.body(), "problemSince")).isNotNull();

        // Tried again meanwhile: no second email within a day (it may well wait for next month's credit)
        CLOCK.advance(Duration.ofHours(6).plusMinutes(10));
        dueNow(salon);
        job.runOnce();
        CLOCK.advance(Duration.ofHours(17));
        dueNow(salon);
        job.runOnce();
        assertThat(emails("boss@clozest.in")).isEqualTo(1);
        CLOCK.advance(Duration.ofHours(1));   // a day and 10 minutes after the first
        dueNow(salon);
        job.runOnce();
        assertThat(emails("boss@clozest.in")).isEqualTo(2);
        assertThat(CapturingSenders.LAST_EMAIL.get("boss@clozest.in")).contains("Since 14 Nov, 10:00 AM");

        // Credit added: the next read passes, every licence still waiting is due now, and one "working again"
        UUID later = salonWithLicence();
        jdbc.update("UPDATE shops SET licence_check_due_at = ? WHERE id = ?",
                java.sql.Timestamp.from(CLOCK.instant().plus(Duration.ofHours(5))), later);
        refuse = null;
        dueNow(salon);
        jdbc.update("UPDATE shops SET licence_check_due_at = ? WHERE id = ?",
                java.sql.Timestamp.from(CLOCK.instant().minusSeconds(1)), salon);   // read first
        job.runOnce();
        assertThat(status(salon)).isEqualTo("PASSED");
        assertThat(jdbc.queryForObject("SELECT licence_check_due_at <= ? FROM shops WHERE id = ?", Boolean.class,
                java.sql.Timestamp.from(CLOCK.instant()), later)).isTrue();   // due now, not in 5 hours
        job.runOnce();
        assertThat(status(later)).isEqualTo("PASSED");
        assertThat(emails("boss@clozest.in")).isEqualTo(3);
        assertThat(CapturingSenders.LAST_EMAIL.get("boss@clozest.in")).contains("reading trade licences again")
                .contains("refused from 14 Nov, 10:00 AM");
        assertThat(field(get("/admin/shops/licence-ai-usage", boss).body(), "problem")).isNull();
        salonWithLicence();
        job.runOnce();
        assertThat(emails("boss@clozest.in")).isEqualTo(3);   // working stays quiet
    }

    @Test
    void aWrongKeyOrModelAfterOutOfCreditIsEmailedAtOnceAndAFailedEmailIsTriedAgainAfterAPause() {
        refuse = LicenceAiWatch.Problem.CREDIT;
        UUID salon = salonWithLicence();
        job.runOnce();
        assertThat(emails("boss@clozest.in")).isEqualTo(1);
        refuse = LicenceAiWatch.Problem.KEY;   // a different problem: emailed now
        dueNow(salon);
        job.runOnce();
        assertThat(emails("boss@clozest.in")).isEqualTo(2);
        assertThat(CapturingSenders.LAST_EMAIL.get("boss@clozest.in")).contains("ANTHROPIC_API_KEY")
                .contains("platform.claude.com/settings/keys").contains("Since 14 Nov, 10:00 AM");
        refuse = LicenceAiWatch.Problem.MODEL;   // the model's name: the fix is the model, not a new key
        dueNow(salon);
        job.runOnce();
        assertThat(emails("boss@clozest.in")).isEqualTo(3);
        assertThat(CapturingSenders.LAST_EMAIL.get("boss@clozest.in")).contains("LICENCE_AI_MODEL")
                .contains("The API key itself works");

        // The email provider down: nobody got it; nothing is tried for 10 minutes, then the next refusal sends it
        jdbc.update("UPDATE licence_ai_alert SET problem = NULL, problem_since = NULL, emailed_at = NULL");
        CapturingSenders.emailDown = true;
        dueNow(salon);
        job.runOnce();
        CapturingSenders.emailDown = false;
        dueNow(salon);
        job.runOnce();
        assertThat(emails("boss@clozest.in")).isEqualTo(3);   // paused
        assertThat(field(get("/admin/shops/licence-ai-usage", boss).body(), "problem")).isEqualTo("MODEL");
        CLOCK.advance(Duration.ofMinutes(11));
        dueNow(salon);
        job.runOnce();
        assertThat(emails("boss@clozest.in")).isEqualTo(4);
    }

    @Test
    void aRefusedFileDoesntSayTheAccountWorksAgain() {
        refuse = LicenceAiWatch.Problem.CREDIT;
        UUID salon = salonWithLicence();
        job.runOnce();
        assertThat(emails("boss@clozest.in")).isEqualTo(1);
        refuse = null;
        refuseFile = true;   // the next licence is too big: refused before reaching the account
        dueNow(salon);
        job.runOnce();
        assertThat(emails("boss@clozest.in")).isEqualTo(1);   // no "working again"
        assertThat(field(get("/admin/shops/licence-ai-usage", boss).body(), "problem")).isEqualTo("CREDIT");
    }

    @Test
    void aWorkingAgainEmailNobodyGotIsSentAtTheNextRoundEvenWithNothingToRead() {
        refuse = LicenceAiWatch.Problem.CREDIT;
        UUID salon = salonWithLicence();
        job.runOnce();
        assertThat(emails("boss@clozest.in")).isEqualTo(1);
        refuse = null;
        CapturingSenders.emailDown = true;
        dueNow(salon);
        job.runOnce();
        assertThat(status(salon)).isEqualTo("PASSED");
        // The AI works: the dashboard says so at once; only the email is owed
        assertThat(field(get("/admin/shops/licence-ai-usage", boss).body(), "problem")).isNull();
        CapturingSenders.emailDown = false;
        job.runOnce();   // within the 10-minute pause
        assertThat(emails("boss@clozest.in")).isEqualTo(1);
        CLOCK.advance(Duration.ofMinutes(11));
        assertThat(job.runOnce()).isZero();   // nothing to read, and the email still goes
        assertThat(emails("boss@clozest.in")).isEqualTo(2);
        assertThat(CapturingSenders.LAST_EMAIL.get("boss@clozest.in")).contains("reading trade licences again");
        job.runOnce();
        assertThat(emails("boss@clozest.in")).isEqualTo(2);   // once
    }

    @Test
    void aRefusalBeforeAnyEmailWasSentSendsNoWorkingAgain() {
        CapturingSenders.emailDown = true;
        refuse = LicenceAiWatch.Problem.CREDIT;
        UUID salon = salonWithLicence();
        job.runOnce();
        CapturingSenders.emailDown = false;
        refuse = null;
        CLOCK.advance(Duration.ofMinutes(11));
        dueNow(salon);
        job.runOnce();
        assertThat(status(salon)).isEqualTo("PASSED");
        assertThat(emails("boss@clozest.in")).isZero();   // nobody heard of the problem: nothing to take back
    }

    // ---------- helpers ----------

    /** One licence read by the job, its answer reporting {@code inputTokens}. */
    private void read(long inputTokens) {
        tokens = inputTokens;
        salonWithLicence();
        assertThat(job.runOnce()).isEqualTo(1);
    }

    /** A new salon whose trade licence waits for its check. */
    private UUID salonWithLicence() {
        UUID owner = person();
        UUID shop = UUID.randomUUID();
        TestData.salon(jdbc, shop, owner);
        jdbc.update("UPDATE shops SET status = 'APPROVED', name = 'Sharma Hair', owner_name = 'Ravi Sharma' "
                + "WHERE id = ?", shop);
        UUID file = UUID.randomUUID();
        byte[] bytes = "licence".getBytes(StandardCharsets.UTF_8);
        String key = "shops/" + shop + "/reg-doc/" + file + ".jpg";
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, "image/jpeg");
        jdbc.update("INSERT INTO files (id, shop_id, purpose, storage_key, content_type, size_bytes, sha256, "
                        + "uploaded_by, created_at) VALUES (?, ?, 'REG_DOC', ?, 'image/jpeg', ?, ?, ?, now())",
                file, shop, key, bytes.length, file.toString().replace("-", "") + "0".repeat(32), owner);
        jdbc.update("UPDATE shops SET registration_doc_type = 'TRADE_LICENCE', registration_doc_file_id = ?, "
                + "licence_check_status = 'PENDING', licence_check_due_at = ?, licence_check_since = ? WHERE id = ?",
                file, java.sql.Timestamp.from(CLOCK.instant()), java.sql.Timestamp.from(CLOCK.instant()), shop);
        return shop;
    }

    private void dueNow(UUID shop) {
        jdbc.update("UPDATE shops SET licence_check_due_at = ? WHERE id = ?", java.sql.Timestamp.from(CLOCK.instant()),
                shop);
    }

    private String status(UUID shop) {
        return jdbc.queryForObject("SELECT licence_check_status FROM shops WHERE id = ?", String.class, shop);
    }

    private int emails(String to) {
        return CapturingSenders.EMAILS_SENT.getOrDefault(to, 0);
    }

    private UUID admin(String role, String designation, String email) {
        UUID id = person();
        jdbc.update("UPDATE users SET admin = true, admin_role = ?, admin_designation = ?, admin_since = now() "
                + "WHERE id = ?", role, designation, id);
        if (email == null) {   // never verified one
            jdbc.update("UPDATE users SET email = NULL, email_verified_at = NULL WHERE id = ?", id);
        } else {
            jdbc.update("UPDATE users SET email = ?, email_verified_at = now() WHERE id = ?", email, id);
        }
        return id;
    }

    private UUID person() {
        UUID id = UUID.randomUUID();
        TestData.user(jdbc, id, "94" + String.format("%08d", ++phones));
        return id;
    }

    private Reply get(String path, UUID who) {
        boolean isAdmin = !"NONE".equals(jdbc.queryForObject("SELECT admin_role FROM users WHERE id = ?",
                String.class, who));
        String token = jwt.issue(new AuthPrincipal(who, null, null, isAdmin), sessions.currentGeneration(who)).token();
        return http.get().uri(path).headers(h -> h.setBearerAuth(token)).exchange((rq, res) -> {
            String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            Object parsed = text.isBlank() ? Map.of() : JSON.readValue(text, Object.class);
            return new Reply(res.getStatusCode().value(), parsed, text);
        });
    }

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    private static Object field(Object json, String name) {
        return ((Map<?, ?>) json).get(name);
    }

    private record Reply(int status, Object body, String text) {
    }
}
