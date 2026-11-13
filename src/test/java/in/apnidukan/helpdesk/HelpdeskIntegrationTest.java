package in.apnidukan.helpdesk;

import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.JwtService;
import in.apnidukan.common.security.SessionRevocations;
import in.apnidukan.helpdesk.service.HelpdeskAccountDeletion;
import in.apnidukan.helpdesk.service.TicketEmailJob;
import in.apnidukan.support.CapturingSenders;
import in.apnidukan.support.TestClock;
import in.apnidukan.support.TestContainers;
import in.apnidukan.support.TestData;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static in.apnidukan.support.CapturingSenders.EMAILS_SENT;
import static in.apnidukan.support.CapturingSenders.LAST_EMAIL;
import static in.apnidukan.support.CapturingSenders.LAST_SMS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Support tickets against real Postgres and Redis: support people on the Team page (and what they may not see),
 * tickets from the apps and from signed support emails, who gets each one, replies by email that come back into the
 * same ticket, the super admins' views, people leaving support, and an account's deletion. Scenario numbers refer to
 * the "Support tickets" section of the admin website's docs/scenarios.md.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({CapturingSenders.class, HelpdeskIntegrationTest.Fakes.class})
class HelpdeskIntegrationTest {

    static final Instant NOW = Instant.parse("2026-11-12T06:30:00Z");
    static final TestClock CLOCK = new TestClock(NOW);
    static final String SECRET = "test-helpdesk-secret-0123456789-abcdef";

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        TestClock testClock() {
            return CLOCK;
        }
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
        registry.add("app.helpdesk.inbound-secret", () -> SECRET);
        registry.add("app.helpdesk.new-per-hour", () -> 3);
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtService jwt;
    @Autowired SessionRevocations sessions;
    @Autowired TicketEmailJob emailJob;
    @Autowired HelpdeskAccountDeletion deletion;
    @Autowired org.springframework.transaction.support.TransactionOperations transactions;

    RestClient http;
    UUID boss;
    UUID customer;

    @BeforeEach
    void setUp() {
        String base = "http://localhost:" + environment.getProperty("local.server.port") + "/api/v1";
        http = RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory()).baseUrl(base)
                .build();
        TestData.clean(jdbc);
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        LAST_SMS.clear();
        LAST_EMAIL.clear();
        EMAILS_SENT.clear();
        CapturingSenders.emailDown = false;
        CLOCK.set(NOW);
        jdbc.execute("ALTER TABLE tickets ALTER COLUMN number RESTART WITH 1001");   // CLZ-1001 in every test
        boss = person("9000000001");
        jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN', name = 'Prem' WHERE id = ?", boss);
        customer = person("9000000003");
        jdbc.update("UPDATE users SET name = 'Asha Rao' WHERE id = ?", customer);
    }

    // ---------------- support people ----------------

    @Test
    void aSupportPersonIsAddedWithoutPincodesAndSeesOnlyTickets() {
        Reply added = post("/admin/team", member("9800000001", "neha@example.com", "Neha", "SUPPORT",
                List.of("500084")), boss);
        assertThat(added.status()).as(added.text()).isEqualTo(201);
        assertThat(field(added.body(), "designation")).isEqualTo("SUPPORT");
        assertThat(field(added.body(), "pincodes")).isEqualTo(List.of());   // pincodes sent anyway are dropped
        assertThat(field(added.body(), "openTickets")).isEqualTo(0);
        UUID neha = userId(added);

        assertThat(field(get("/admin/me", neha).body(), "designation")).isEqualTo("SUPPORT");
        assertThat(get("/admin/tickets", neha).status()).isEqualTo(200);
        // Not the businesses, the dashboard, the team or test mode
        assertThat(get("/admin/shops?type=SALON&group=LIVE", neha).status()).isEqualTo(404);
        assertThat(get("/admin/dashboard", neha).status()).isEqualTo(404);
        assertThat(get("/admin/team", neha).status()).isEqualTo(403);
        assertThat(get("/admin/tickets/stats", neha).status()).isEqualTo(403);
        assertThat(get("/admin/tickets?view=UNASSIGNED", neha).status()).isEqualTo(403);
    }

    @Test
    void aFieldAgentAndACustomerCantSeeTickets() {
        Reply agent = post("/admin/team", member("9800000002", "ravi@example.com", "Ravi", "FIELD_AGENT",
                List.of("500084")), boss);
        assertThat(agent.status()).as(agent.text()).isEqualTo(201);
        assertThat(get("/admin/tickets", userId(agent)).status()).isEqualTo(404);
        assertThat(get("/admin/tickets", customer).status()).isEqualTo(403);   // not an admin token at all
    }

    // ---------------- tickets from the apps ----------------

    @Test
    void aCustomersQuestionGoesToTheSupportPersonAndTheyAreEmailed() {
        UUID neha = support("9800000001", "Neha");
        Reply asked = post("/helpdesk/tickets", Map.of("app", "CUSTOMER",
                "message", "My booking SB12 shows cancelled but\nthe salon says it is fine."), customer);
        assertThat(asked.status()).as(asked.text()).isEqualTo(201);
        assertThat(field(asked.body(), "number")).isEqualTo("CLZ-1001");
        assertThat(field(asked.body(), "sentTo")).isEqualTo("9000000003@test.in");

        Map<?, ?> t = only(get("/admin/tickets", neha));
        assertThat(t.get("subject")).isEqualTo("My booking SB12 shows cancelled but the salon says it is fine.");
        assertThat(t.get("status")).isEqualTo("OPEN");
        assertThat(t.get("source")).isEqualTo("CUSTOMER");
        assertThat(t.get("requester")).isEqualTo("Asha Rao");
        assertThat(t.get("assigneeName")).isEqualTo("Neha");

        // The acknowledgement goes out with the job
        assertThat(emailJob.runOnce()).isEqualTo(1);
        assertThat(LAST_EMAIL.get("9000000003@test.in")).contains("Hi Asha,").contains("CLZ-1001")
                .contains("> My booking SB12");

        // The ticket shows who asked: the account and its latest bookings, and the view is logged once
        Reply detail = get("/admin/tickets/" + t.get("id"), neha);
        List<?> profile = (List<?>) field(detail.body(), "profile");
        assertThat(field(profile.get(0), "title")).isEqualTo("Account");
        assertThat(strings(field(profile.get(0), "lines"))).contains("Phone: 9000000003",
                "Email: 9000000003@test.in (verified)");
        get("/admin/tickets/" + t.get("id"), neha);
        assertThat(count("ticket_events WHERE event = 'PROFILE_VIEWED'")).isEqualTo(1);
    }

    @Test
    void withoutAVerifiedEmailTheAppIsToldToVerifyFirst() {
        UUID bare = UUID.randomUUID();
        TestData.bareUser(jdbc, bare, "9000000004");
        Reply r = post("/helpdesk/tickets", Map.of("app", "CUSTOMER", "message", "Please help me with this"), bare);
        assertThat(r.status()).isEqualTo(403);
        assertThat(field(r.body(), "code")).isEqualTo("PROFILE_INCOMPLETE");
        assertThat(strings(field(field(r.body(), "details"), "missing"))).contains("email");
        assertThat(count("tickets")).isZero();
    }

    @Test
    void theMessageIsCheckedAndTooManyInAnHourAreRefused() {
        Reply shortOne = post("/helpdesk/tickets", Map.of("app", "PARTNER", "message", "help"), customer);
        assertThat(shortOne.status()).isEqualTo(400);
        assertThat(field(field(shortOne.body(), "details"), "message")).isNotNull();
        Reply fromEmail = post("/helpdesk/tickets", Map.of("app", "EMAIL", "message", "Please help me"), customer);
        assertThat(fromEmail.status()).isEqualTo(400);
        for (int i = 0; i < 3; i++) {
            assertThat(post("/helpdesk/tickets", Map.of("app", "PARTNER", "subject", "Payout " + i,
                    "message", "Where is my payout number " + i), customer).status()).isEqualTo(201);
        }
        Reply fourth = post("/helpdesk/tickets", Map.of("app", "PARTNER", "message", "And one more question"),
                customer);
        assertThat(fourth.status()).isEqualTo(429);
        assertThat(field(fourth.body(), "code")).isEqualTo("TOO_MANY_TICKETS");
        CLOCK.set(NOW.plusSeconds(3601));
        assertThat(post("/helpdesk/tickets", Map.of("app", "PARTNER", "message", "And one more question"),
                customer).status()).isEqualTo(201);
    }

    @Test
    void withNobodyInSupportTicketsWaitInUnassignedUntilSomeoneJoins() {
        ask(customer, "First question about my booking");
        ask(customer, "Second question about my account");
        Reply unassigned = get("/admin/tickets?view=UNASSIGNED", boss);
        assertThat((List<?>) unassigned.body()).hasSize(2);
        assertThat(field(get("/admin/tickets/counts", boss).body(), "unassigned")).isEqualTo(2);

        UUID neha = support("9800000001", "Neha");
        assertThat((List<?>) get("/admin/tickets", neha).body()).hasSize(2);
        assertThat((List<?>) get("/admin/tickets?view=UNASSIGNED", boss).body()).isEmpty();
    }

    @Test
    void eachTicketGoesToWhoeverHasFewestOpen() {
        UUID neha = support("9800000001", "Neha");
        ask(customer, "Question one about bookings");
        UUID ravi = support("9800000002", "Ravi");   // joins with 0: the next ones go to him first
        ask(customer, "Question two about bookings");
        ask(customer, "Question three about bookings");
        // The 2nd went to Ravi (0 against Neha's 1); the 3rd to either (1 each)
        assertThat(open(ravi)).isBetween(1L, 2L);
        assertThat(open(neha) + open(ravi)).isEqualTo(3);
        // The 4th goes to the one with fewer (Neha: 1 or 2, Ravi: 2 or 1): they end even
        CLOCK.set(NOW.plusSeconds(7200));   // past the hourly limit's window
        ask(customer, "Question four about bookings");
        assertThat(open(neha)).isEqualTo(2);
        assertThat(open(ravi)).isEqualTo(2);
    }

    @Test
    void ticketsArrivingTogetherAreSpreadEvenly() throws Exception {
        UUID neha = support("9800000001", "Neha");
        UUID ravi = support("9800000002", "Ravi");
        UUID[] askers = new UUID[6];
        for (int i = 0; i < askers.length; i++) {
            askers[i] = person("90000001" + (10 + i));
        }
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Integer>> all = new java.util.ArrayList<>();
        for (UUID a : askers) {
            all.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return post("/helpdesk/tickets", Map.of("app", "CUSTOMER", "message", "A question at the same time"),
                        a).status();
            }));
        }
        start.countDown();
        for (CompletableFuture<Integer> f : all) {
            assertThat(f.get(20, TimeUnit.SECONDS)).isEqualTo(201);
        }
        assertThat(open(neha)).isEqualTo(3);
        assertThat(open(ravi)).isEqualTo(3);
    }

    // ---------------- replies ----------------

    @Test
    void theSupportPersonRepliesAndResolvesAndTheCustomerIsEmailedEachTime() {
        UUID neha = support("9800000001", "Neha");
        String id = ask(customer, "My offer was not applied to my booking");
        emailJob.runOnce();
        Reply detail = get("/admin/tickets/" + id, neha);
        long seen = ((Number) field(detail.body(), "lastIncomingSeq")).longValue();

        Reply replied = post("/admin/tickets/" + id + "/reply", Map.of("seenSeq", seen,
                "body", "We have refunded the difference."), neha);
        assertThat(replied.status()).as(replied.text()).isEqualTo(200);
        assertThat(field(field(replied.body(), "ticket"), "status")).isEqualTo("WAITING");
        assertThat(field(replied.body(), "emailsWaiting")).isEqualTo(1);
        assertThat(emailJob.runOnce()).isEqualTo(1);
        assertThat(LAST_EMAIL.get("9000000003@test.in")).contains("We have refunded the difference.")
                .contains("Reply to this email").contains("Neha, Clozest Support");

        Reply resolved = post("/admin/tickets/" + id + "/reply", Map.of("seenSeq", seen,
                "body", "Closing this one.", "resolve", true), neha);
        assertThat(field(field(resolved.body(), "ticket"), "status")).isEqualTo("RESOLVED");
        emailJob.runOnce();
        assertThat(LAST_EMAIL.get("9000000003@test.in")).contains("marked ticket CLZ-1001 as resolved");
        assertThat((List<?>) get("/admin/tickets?view=RESOLVED", neha).body()).hasSize(1);
    }

    @Test
    void aReplyWrittenBeforeTheirNewMessageIsRefused() {
        UUID neha = support("9800000001", "Neha");
        String id = ask(customer, "Where is my refund for the booking?");
        long seen = ((Number) field(get("/admin/tickets/" + id, neha).body(), "lastIncomingSeq")).longValue();
        assertThat(inbound(email("9000000003@test.in", "Asha", "Re: [CLZ-1001] We've got your request",
                "It's booking SB99.\n\nOn Thu, 12 Nov 2026 at 12:00, Clozest Support <support@clozest.in> wrote:\n"
                        + "> We've got your request", "<r1@mail.test>")).body()).isEqualTo(Map.of("result", "ADDED"));
        Reply stale = post("/admin/tickets/" + id + "/reply", Map.of("seenSeq", seen, "body", "Done"), neha);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(field(stale.body(), "code")).isEqualTo("NEW_MESSAGE");
        List<?> messages = (List<?>) field(get("/admin/tickets/" + id, neha).body(), "messages");
        assertThat(field(messages.get(1), "body")).isEqualTo("It's booking SB99.");   // the quoted part is cut
    }

    @Test
    void aSupportPersonCantOpenOrAnswerAnotherOnesTicket() {
        UUID neha = support("9800000001", "Neha");
        String id = ask(customer, "Question for whoever has it");
        UUID ravi = support("9800000002", "Ravi");
        assertThat(get("/admin/tickets/" + id, ravi).status()).isEqualTo(404);
        Reply detail = get("/admin/tickets/" + id, boss);
        long version = ((Number) field(detail.body(), "version")).longValue();
        Reply moved = post("/admin/tickets/" + id + "/assign", Map.of("version", version, "assigneeId", ravi), boss);
        assertThat(moved.status()).as(moved.text()).isEqualTo(200);
        assertThat(field(field(moved.body(), "ticket"), "assigneeName")).isEqualTo("Ravi");
        Reply late = post("/admin/tickets/" + id + "/reply", Map.of("seenSeq", 999_999, "body", "Hello"), neha);
        assertThat(late.status()).isEqualTo(409);
        assertThat(field(late.body(), "code")).isEqualTo("NOT_YOURS");
        Reply stale = post("/admin/tickets/" + id + "/assign", Map.of("version", version, "assigneeId", neha), boss);
        assertThat(field(stale.body(), "code")).isEqualTo("TICKET_CHANGED");
        UUID agent = userId(post("/admin/team", member("9800000003", "ravi2@example.com", "Agent", "FIELD_AGENT",
                List.of("500084")), boss));
        long now = ((Number) field(get("/admin/tickets/" + id, boss).body(), "version")).longValue();
        assertThat(field(post("/admin/tickets/" + id + "/assign", Map.of("version", now, "assigneeId", agent), boss)
                .body(), "code")).isEqualTo("NOT_SUPPORT");
    }

    @Test
    void aFailingEmailIsTriedAgainThenShownAsFailed() {
        support("9800000001", "Neha");
        String id = ask(customer, "Question while email is down");
        CapturingSenders.emailDown = true;
        assertThat(emailJob.runOnce()).isZero();
        for (int minutes : new int[] {1, 6, 21}) {
            CLOCK.set(NOW.plusSeconds(minutes * 60L + 1));
            emailJob.runOnce();
        }
        Reply detail = get("/admin/tickets/" + id, boss);
        assertThat(field(detail.body(), "emailsFailed")).isEqualTo(1);
        assertThat(field(detail.body(), "emailsWaiting")).isEqualTo(0);
        assertThat(count("ticket_events WHERE event = 'EMAIL_FAILED'")).isEqualTo(1);
    }

    // ---------------- emails to the support address ----------------

    @Test
    void anEmailFromAnUnknownAddressBecomesATicketAndTheSameEmailTwiceIsOne() {
        UUID neha = support("9800000001", "Neha");
        byte[] mail = email("vikram@gmail.com", "Vikram S", "Salon listing", "Can you list my salon?", "<a1@x>");
        assertThat(inbound(mail).body()).isEqualTo(Map.of("result", "CREATED"));
        assertThat(inbound(mail).body()).isEqualTo(Map.of("result", "DUPLICATE"));
        Map<?, ?> t = only(get("/admin/tickets", neha));
        assertThat(t.get("requester")).isEqualTo("Vikram S");
        assertThat(t.get("source")).isEqualTo("EMAIL");
        Reply detail = get("/admin/tickets/" + t.get("id"), neha);
        assertThat((List<?>) field(detail.body(), "profile")).isEmpty();
        assertThat(field(detail.body(), "requesterEmail")).isEqualTo("vikram@gmail.com");
        emailJob.runOnce();
        assertThat(LAST_EMAIL.get("vikram@gmail.com")).contains("Hi Vikram,");
    }

    @Test
    void anEmailFromAVerifiedAddressIsMatchedToTheAccount() {
        UUID neha = support("9800000001", "Neha");
        inbound(email("9000000003@TEST.in", "", "Booking", "My booking is missing", "<a2@x>"));
        Map<?, ?> t = only(get("/admin/tickets", neha));
        assertThat(t.get("requester")).isEqualTo("Asha Rao");
        assertThat((List<?>) field(get("/admin/tickets/" + t.get("id"), neha).body(), "profile")).isNotEmpty();
    }

    @Test
    void autoRepliesBouncesAndOurOwnEmailsAreIgnored() {
        support("9800000001", "Neha");
        String auto = "From: Out <ooo@gmail.com>\r\nTo: support@clozest.in\r\nSubject: Out of office\r\n"
                + "Auto-Submitted: auto-replied\r\nMessage-ID: <o1@x>\r\n\r\nI'm away";
        assertThat(inbound(auto.getBytes(StandardCharsets.UTF_8)).body()).isEqualTo(Map.of("result", "IGNORED"));
        assertThat(inbound(email("mailer-daemon@google.com", "", "Delivery failed", "Bounce", "<b1@x>")).body())
                .isEqualTo(Map.of("result", "IGNORED"));
        assertThat(inbound(email("no-reply@clozest.in", "", "[CLZ-1001] Loop", "Ours", "<c1@x>")).body())
                .isEqualTo(Map.of("result", "IGNORED"));
        assertThat(count("tickets")).isZero();
        assertThat(EMAILS_SENT).isEmpty();
    }

    @Test
    void aReplyToAResolvedTicketOpensItAgainButOnlyFromTheSameAddress() {
        UUID neha = support("9800000001", "Neha");
        String id = ask(customer, "My offer was not applied to my booking");
        post("/admin/tickets/" + id + "/close", Map.of("seenSeq", 999_999), neha);
        inbound(email("9000000003@test.in", "Asha", "Re: [CLZ-1001] Your request", "Still not fixed", "<r2@x>"));
        Reply detail = get("/admin/tickets/" + id, neha);
        assertThat(field(field(detail.body(), "ticket"), "status")).isEqualTo("OPEN");
        assertThat(count("ticket_events WHERE event = 'REOPENED'")).isEqualTo(1);

        // Someone else quoting the number gets their own ticket
        assertThat(inbound(email("other@gmail.com", "", "Re: [CLZ-1001] Your request", "Me too", "<r3@x>")).body())
                .isEqualTo(Map.of("result", "CREATED"));
        assertThat(count("tickets")).isEqualTo(2);
    }

    @Test
    void anUnsignedOrOldOrTamperedEmailIsRefused() {
        byte[] mail = email("vikram@gmail.com", "", "Hi", "Hello there", "<s1@x>");
        assertThat(rawInbound(mail, null, null).status()).isEqualTo(403);
        long ts = NOW.getEpochSecond();
        assertThat(rawInbound(mail, String.valueOf(ts), "00ff").status()).isEqualTo(403);
        assertThat(rawInbound(mail, String.valueOf(ts - 600), sign(ts - 600, mail)).status()).isEqualTo(403);
        byte[] other = email("vikram@gmail.com", "", "Hi", "Changed text", "<s1@x>");
        assertThat(rawInbound(other, String.valueOf(ts), sign(ts, mail)).status()).isEqualTo(403);
        assertThat(count("tickets")).isZero();
    }

    @Test
    void tooManyEmailsInAnHourGoIntoTheLatestOpenTicket() {
        support("9800000001", "Neha");
        for (int i = 0; i < 4; i++) {
            inbound(email("spam@gmail.com", "", "Hello " + i, "Message number " + i, "<m" + i + "@x>"));
        }
        assertThat(count("tickets")).isEqualTo(3);
        assertThat(count("ticket_messages")).isEqualTo(4);
    }

    @Test
    void aForgedSenderNeverShowsTheAccountNorGetsTheirWordsBack() {
        UUID neha = support("9800000001", "Neha");
        // No proof from Cloudflare (and a fake "pass" written below it by the sender doesn't count)
        String raw = "ARC-Authentication-Results: i=1; mx.cloudflare.net; dmarc=fail header.from=test.in\r\n"
                + "Authentication-Results: mx.cloudflare.net; dmarc=pass\r\n"
                + "From: 9000000003@test.in\r\nSubject: Refund\r\nMessage-ID: <f1@x>\r\n\r\nClick http://evil.example";
        assertThat(inbound(raw.getBytes(StandardCharsets.UTF_8)).body()).isEqualTo(Map.of("result", "CREATED"));
        Map<?, ?> t = only(get("/admin/tickets", neha));
        assertThat(t.get("requester")).isEqualTo("9000000003@test.in");
        Reply detail = get("/admin/tickets/" + t.get("id"), neha);
        assertThat((List<?>) field(detail.body(), "profile")).isEmpty();
        assertThat(field(detail.body(), "senderVerified")).isEqualTo(false);
        emailJob.runOnce();
        assertThat(LAST_EMAIL.get("9000000003@test.in")).doesNotContain("evil.example").contains("CLZ-1001");
    }

    @Test
    void anUnprovenReplyJoinsByAddressButIsNeverWrittenAsTheirs() {
        UUID neha = support("9800000001", "Neha");
        String id = ask(customer, "Question from the app first");
        inbound(email("9000000003@test.in", "", "Re: [CLZ-1001] x", "Forged words", "<u1@x>", false));
        assertThat(jdbc.queryForObject("SELECT author_id IS NULL FROM ticket_messages WHERE body = 'Forged words'",
                Boolean.class)).isTrue();
        List<?> messages = (List<?>) field(get("/admin/tickets/" + id, neha).body(), "messages");
        assertThat(field(messages.get(1), "senderVerified")).isEqualTo(false);
    }

    @Test
    void aReplyFromTheirNewVerifiedEmailJoinsAndOurAnswersGoThere() {
        UUID neha = support("9800000001", "Neha");
        String id = ask(customer, "Question before changing my email");
        jdbc.update("UPDATE users SET email = 'asha.new@test.in' WHERE id = ?", customer);
        assertThat(inbound(email("Asha.New@test.in", "", "Re: [CLZ-1001] Your request", "From my new address", "<n1@x>"))
                .body()).isEqualTo(Map.of("result", "ADDED"));
        long seen = ((Number) field(get("/admin/tickets/" + id, neha).body(), "lastIncomingSeq")).longValue();
        post("/admin/tickets/" + id + "/reply", Map.of("seenSeq", seen, "body", "Got it."), neha);
        emailJob.runOnce();
        assertThat(LAST_EMAIL.get("asha.new@test.in")).contains("Got it.").contains("> From my new address");
    }

    @Test
    void closingWithoutReadingTheirNewMessageIsRefused() {
        UUID neha = support("9800000001", "Neha");
        String id = ask(customer, "Question about my booking");
        long seen = ((Number) field(get("/admin/tickets/" + id, neha).body(), "lastIncomingSeq")).longValue();
        inbound(email("9000000003@test.in", "", "Re: [CLZ-1001] x", "One more thing", "<c2@x>"));
        Reply r = post("/admin/tickets/" + id + "/close", Map.of("seenSeq", seen), neha);
        assertThat(field(r.body(), "code")).isEqualTo("NEW_MESSAGE");
        long now = ((Number) field(get("/admin/tickets/" + id, neha).body(), "lastIncomingSeq")).longValue();
        assertThat(post("/admin/tickets/" + id + "/close", Map.of("seenSeq", now), neha).status()).isEqualTo(200);
        emailJob.runOnce();
        assertThat(EMAILS_SENT.get("9000000003@test.in")).isEqualTo(1);   // only the acknowledgement: closing emails nobody
    }

    @Test
    void overTheHourlyLimitAResolvedTicketIsOpenedAgainInsteadOfDropped() {
        UUID neha = support("9800000001", "Neha");
        for (int i = 0; i < 3; i++) {
            inbound(email("spam@gmail.com", "", "Hello " + i, "Message number " + i, "<l" + i + "@x>"));
        }
        jdbc.update("UPDATE tickets SET status = 'RESOLVED'");
        inbound(email("spam@gmail.com", "", "Hello again", "Message number 4", "<l4@x>"));
        assertThat(count("tickets")).isEqualTo(3);
        assertThat(count("tickets WHERE status = 'OPEN'")).isEqualTo(1);
        assertThat(open(neha)).isEqualTo(1);
    }

    @Test
    void aSupportPersonsOwnQuestionGoesToSomeoneElse() {
        UUID neha = support("9800000001", "Neha");
        TestData.completeProfile(jdbc, "9800000001");
        Reply mine = post("/helpdesk/tickets", Map.of("app", "PARTNER", "message", "My own question as a person"), neha);
        assertThat(mine.status()).as(mine.text()).isEqualTo(201);
        assertThat(count("tickets WHERE assignee_id IS NULL")).isEqualTo(1);   // nobody else: Unassigned
        UUID ravi = support("9800000002", "Ravi");
        assertThat(open(ravi)).isEqualTo(1);
        String id = (String) field(mine.body(), "id");
        long version = ((Number) field(get("/admin/tickets/" + id, boss).body(), "version")).longValue();
        assertThat(field(post("/admin/tickets/" + id + "/assign", Map.of("version", version, "assigneeId", neha), boss)
                .body(), "code")).isEqualTo("OWN_TICKET");
    }

    @Test
    void aSuperAdminHoldingTicketsHandsThemOnWhenMadeAFieldAgent() {
        Reply added = post("/admin/team", Map.of("phone", "9800000009", "email", "asha@example.com", "name", "Asha",
                "role", "SUPER_ADMIN", "pincodes", List.of()), boss);
        UUID asha = userId(added);
        String id = ask(customer, "Question nobody has yet");
        long seen = ((Number) field(get("/admin/tickets/" + id, asha).body(), "lastIncomingSeq")).longValue();
        post("/admin/tickets/" + id + "/reply", Map.of("seenSeq", seen, "body", "Looking into it"), asha);
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM tickets", UUID.class)).isEqualTo(asha);
        UUID neha = support("9800000001", "Neha");
        long version = ((Number) field(((List<?>) get("/admin/team", boss).body()).stream()
                .filter(m -> asha.toString().equals(field(m, "userId"))).findFirst().orElseThrow(), "version"))
                .longValue();
        Map<String, Object> agent = member("9800000009", "asha@example.com", "Asha", "FIELD_AGENT", List.of("500084"));
        agent.put("version", version);
        assertThat(put("/admin/team/" + asha, agent, boss).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM tickets", UUID.class)).isEqualTo(neha);
    }

    @Test
    void aDeletedAccountShowsAsDeletedAccountNotBlank() {
        UUID neha = support("9800000001", "Neha");
        String id = ask(customer, "Please help me with my booking");
        jdbc.update("UPDATE users SET name = NULL, phone = NULL, deleted_at = now() WHERE id = ?", customer);
        assertThat(only(get("/admin/tickets", neha)).get("requester")).isEqualTo("Deleted account");
        assertThat(field(field(get("/admin/tickets/" + id, neha).body(), "ticket"), "requester"))
                .isEqualTo("Deleted account");
    }

    @Test
    void aTooLargeEmailThroughTheInboxSaysToReadItInGmail() {
        support("9800000001", "Neha");
        byte[] headers = ("ARC-Authentication-Results: i=1; mx.cloudflare.net; dmarc=pass\r\nFrom: big@gmail.com\r\n"
                + "Subject: Photos\r\nMessage-ID: <big@x>\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        long ts = CLOCK.instant().getEpochSecond();
        Reply r = http.post().uri("/helpdesk/inbound").contentType(MediaType.parseMediaType("message/rfc822"))
                .headers(h -> {
                    h.set("X-Helpdesk-Timestamp", String.valueOf(ts));
                    h.set("X-Helpdesk-Signature", sign(ts, headers));
                    h.set("X-Helpdesk-Truncated", "1");
                }).body(headers).exchange((rq, res) -> read(res));
        assertThat(r.body()).isEqualTo(Map.of("result", "CREATED"));
        assertThat(jdbc.queryForObject("SELECT body FROM ticket_messages", String.class)).contains("support inbox");
    }

    // ---------------- round 2: search, move, reopen, "My requests" ----------------

    @Test
    void ticketsAreFoundByNumberPhoneEmailOrAccount() {
        UUID neha = support("9800000001", "Neha");
        String id = ask(customer, "Question about my booking");
        inbound(email("vikram@gmail.com", "", "Hi", "Another question", "<s1@x>"));
        assertThat((List<?>) get("/admin/tickets/search?q=CLZ-1001", neha).body()).hasSize(1);
        assertThat((List<?>) get("/admin/tickets/search?q=1001", neha).body()).hasSize(1);
        assertThat((List<?>) get("/admin/tickets/search?q=90000 00003", neha).body()).hasSize(1);
        assertThat((List<?>) get("/admin/tickets/search?q=VIKRAM@gmail.com", neha).body()).hasSize(1);
        assertThat((List<?>) get("/admin/tickets/search?requester=" + customer, boss).body()).hasSize(1);
        assertThat(get("/admin/tickets/search?q=hello", neha).status()).isEqualTo(400);
        // A support person finds only their own
        UUID ravi = support("9800000002", "Ravi");
        assertThat((List<?>) get("/admin/tickets/search?q=CLZ-1001", ravi).body()).isEmpty();
        assertThat(id).isNotNull();
    }

    @Test
    void aSuperAdminMovesEveryOpenTicketOfSomeoneAway() {
        UUID neha = support("9800000001", "Neha");
        ask(customer, "Question one about bookings");
        ask(customer, "Question two about bookings");
        UUID ravi = support("9800000002", "Ravi");
        Reply moved = post("/admin/tickets/move", Map.of("fromUserId", neha.toString(), "toUserId", ravi.toString()), boss);
        assertThat(field(moved.body(), "moved")).isEqualTo(2);
        assertThat(open(ravi)).isEqualTo(2);
        assertThat(field(post("/admin/tickets/move", Map.of("fromUserId", ravi.toString(), "toUserId", ravi.toString()),
                boss).body(), "code")).isEqualTo("NOT_SUPPORT");
        assertThat(post("/admin/tickets/move", Map.of("fromUserId", ravi.toString()), neha).status()).isEqualTo(403);
        Map<String, Object> share = new HashMap<>();
        share.put("fromUserId", ravi.toString());
        share.put("toUserId", null);
        assertThat(field(post("/admin/tickets/move", share, boss).body(), "moved")).isEqualTo(2);
        assertThat(open(neha)).isEqualTo(2);   // the only other support person
    }

    @Test
    void aClosedTicketIsOpenedAgainWithoutEmailingAnyone() {
        UUID neha = support("9800000001", "Neha");
        String id = ask(customer, "Question about my booking");
        emailJob.runOnce();
        post("/admin/tickets/" + id + "/close", Map.of("seenSeq", 999_999), neha);
        long version = ((Number) field(get("/admin/tickets/" + id, neha).body(), "version")).longValue();
        Reply r = post("/admin/tickets/" + id + "/reopen", Map.of("version", version), neha);
        assertThat(field(field(r.body(), "ticket"), "status")).isEqualTo("OPEN");
        assertThat(emailJob.runOnce()).isZero();
        assertThat(field(post("/admin/tickets/" + id + "/reopen", Map.of("version", version), neha).body(), "code"))
                .isEqualTo("TICKET_CHANGED");
    }

    @Test
    void myRequestsShowOnlyMineAndICanAddToThem() {
        UUID neha = support("9800000001", "Neha");
        String id = ask(customer, "Question about my booking");
        UUID other = person("9000000005");
        long seen = ((Number) field(get("/admin/tickets/" + id, neha).body(), "lastIncomingSeq")).longValue();
        post("/admin/tickets/" + id + "/reply", Map.of("seenSeq", seen, "body", "Fixed it.", "resolve", true), neha);

        List<?> mine = (List<?>) get("/helpdesk/tickets", customer).body();
        assertThat(mine).hasSize(1);
        assertThat(field(mine.get(0), "status")).isEqualTo("RESOLVED");
        assertThat((List<?>) get("/helpdesk/tickets", other).body()).isEmpty();
        assertThat(get("/helpdesk/tickets/" + id, other).status()).isEqualTo(404);

        Reply detail = get("/helpdesk/tickets/" + id, customer);
        List<?> messages = (List<?>) field(detail.body(), "messages");
        assertThat(messages).hasSize(2);
        assertThat(field(messages.get(1), "fromSupport")).isEqualTo(true);
        assertThat(field(messages.get(1), "supportName")).isEqualTo("Neha");
        assertThat(detail.text()).doesNotContain("PROFILE_VIEWED");   // no internal history

        Reply added = post("/helpdesk/tickets/" + id + "/messages", Map.of("message", "Still not fixed"), customer);
        assertThat(added.status()).as(added.text()).isEqualTo(200);
        assertThat(field(field(added.body(), "ticket"), "status")).isEqualTo("OPEN");
        assertThat(post("/helpdesk/tickets/" + id + "/messages", Map.of("message", "Me too"), other).status())
                .isEqualTo(404);
        assertThat(post("/helpdesk/tickets/" + id + "/messages", Map.of("message", "x"), customer).status())
                .isEqualTo(400);
    }

    @Test
    void aTicketIsFoundByAPastedSubjectAndBySomeonesNewEmail() {
        UUID neha = support("9800000001", "Neha");
        ask(customer, "Question about my booking");
        assertThat((List<?>) get("/admin/tickets/search?q=Re: [CLZ-1001] Question", neha).body()).hasSize(1);
        assertThat((List<?>) get("/admin/tickets/search?q=clz 1001", neha).body()).hasSize(1);
        assertThat((List<?>) get("/admin/tickets/search?q=CLZ - 1001", neha).body()).hasSize(1);
        // They changed their verified email after asking: the new one finds the ticket too
        jdbc.update("UPDATE users SET email = 'asha.new@test.in' WHERE id = ?", customer);
        assertThat((List<?>) get("/admin/tickets/search?q=asha.new@test.in", neha).body()).hasSize(1);
        assertThat((List<?>) get("/admin/tickets/search?q=9000000003@test.in", neha).body()).hasSize(1);
    }

    @Test
    void aSupportPersonsOtherTicketsCountIsOnlyTheirOwn() {
        UUID neha = support("9800000001", "Neha");
        String first = ask(customer, "Question one about bookings");
        support("9800000002", "Ravi");
        ask(customer, "Question two about bookings");   // fewest open: Ravi
        assertThat(field(get("/admin/tickets/" + first, neha).body(), "earlierTickets")).isEqualTo(0);
        assertThat(field(get("/admin/tickets/" + first, boss).body(), "earlierTickets")).isEqualTo(1);
    }

    @Test
    void movingWhenNobodyElseCanTakeThemSaysHowManyStayed() {
        UUID neha = support("9800000001", "Neha");
        ask(customer, "Question one about bookings");
        String waiting = ask(customer, "Question two about bookings");
        post("/admin/tickets/" + waiting + "/reply", Map.of("seenSeq", 999_999, "body", "Which day?", "resolve",
                false), neha);
        Map<String, Object> share = new HashMap<>();
        share.put("fromUserId", neha.toString());
        share.put("toUserId", null);
        Reply r = post("/admin/tickets/move", share, boss);
        assertThat(field(r.body(), "moved")).isEqualTo(0);
        assertThat(field(r.body(), "stayed")).isEqualTo(2);   // waiting on us and waiting on them
    }

    @Test
    void aTicketReopenedAfterItsHolderLeftSupportGoesToSomeoneInSupport() {
        UUID neha = support("9800000001", "Neha");
        String id = ask(customer, "Question about my booking");
        post("/admin/tickets/" + id + "/close", Map.of("seenSeq", 999_999), neha);
        UUID ravi = support("9800000002", "Ravi");
        assertThat(delete("/admin/team/" + neha, boss).status()).isEqualTo(204);
        CLOCK.set(NOW.plusSeconds(86_400));
        long version = ((Number) field(get("/admin/tickets/" + id, boss).body(), "version")).longValue();
        Reply r = post("/admin/tickets/" + id + "/reopen", Map.of("version", version), boss);
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM tickets WHERE id = ?::uuid", UUID.class, id))
                .isEqualTo(ravi);
        assertThat(jdbc.queryForObject("SELECT last_message_at FROM tickets WHERE id = ?::uuid",
                java.sql.Timestamp.class, id).toInstant()).isEqualTo(NOW.plusSeconds(86_400));
        assertThat(count("ticket_events WHERE event = 'REOPENED'")).isEqualTo(1);
    }

    @Test
    void addingFromTheAppIsLimitedAndASendRepeatedAfterALostAnswerIsKeptOnce() {
        String id = ask(customer, "Question about my booking");   // counts as one
        assertThat(post("/helpdesk/tickets/" + id + "/messages", Map.of("message", "One more thing"), customer)
                .status()).isEqualTo(200);
        assertThat(post("/helpdesk/tickets/" + id + "/messages", Map.of("message", "One more thing"), customer)
                .status()).isEqualTo(200);
        assertThat(count("ticket_messages WHERE body = 'One more thing'")).isEqualTo(1);
        // The same short answer again after support's question is new, not a repeat
        UUID neha = support("9800000001", "Neha");
        jdbc.update("UPDATE tickets SET assignee_id = ?", neha);
        post("/admin/tickets/" + id + "/reply", Map.of("seenSeq", 999_999, "body", "Is it the 3 pm one?", "resolve",
                false), neha);
        post("/helpdesk/tickets/" + id + "/messages", Map.of("message", "One more thing"), customer);
        assertThat(count("ticket_messages WHERE body = 'One more thing'")).isEqualTo(2);
        for (int i = 0; i < 17; i++) {
            assertThat(post("/helpdesk/tickets/" + id + "/messages", Map.of("message", "Note " + i), customer)
                    .status()).isEqualTo(200);
        }
        Reply r = post("/helpdesk/tickets/" + id + "/messages", Map.of("message", "Too many now"), customer);
        assertThat(r.status()).isEqualTo(429);
        assertThat(field(r.body(), "code")).isEqualTo("TOO_MANY_MESSAGES");
    }

    @Test
    void anEmailWeCouldNotProveIsNeverShownInTheirApp() {
        support("9800000001", "Neha");
        String id = ask(customer, "Question about my booking");
        inbound(email("9000000003@test.in", "Asha", "Re: [CLZ-1001] Question", "Someone pretending", "<f1@x>",
                false));
        List<?> messages = (List<?>) field(get("/helpdesk/tickets/" + id, customer).body(), "messages");
        assertThat(messages).hasSize(1);
        assertThat(get("/helpdesk/tickets/" + id, customer).text()).doesNotContain("Someone pretending");
    }

    // ---------------- round 3: away, and "Support replied" ----------------

    @Test
    void someoneAwayGetsNoNewTicketsTheirOpenOnesGoToOthersAndBackTheyTakeTheirShare() {
        UUID neha = support("9800000001", "Neha");
        String first = ask(customer, "Question one about bookings");
        UUID ravi = support("9800000002", "Ravi");
        assertThat(assigneeOf(first)).isEqualTo(neha);

        Reply r = put("/admin/tickets/away/" + neha, Map.of("away", true), boss);
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(field(r.body(), "moved")).isEqualTo(1);
        assertThat(assigneeOf(first)).isEqualTo(ravi);
        assertThat(get("/admin/tickets/away", boss).text()).contains(neha.toString());

        // New ones and ones coming back skip her
        String second = ask(customer, "Question two about bookings");
        assertThat(assigneeOf(second)).isEqualTo(ravi);

        // A support person sets only their own switch; anyone else's is not found
        assertThat(put("/admin/tickets/away/" + neha, Map.of("away", false), ravi).status()).isEqualTo(404);
        assertThat(put("/admin/tickets/away/" + boss, Map.of("away", true), boss).status()).isEqualTo(400);

        // Back: nothing waits in Unassigned, so nothing changes hands; new ones share by the rule again
        assertThat(put("/admin/tickets/away/" + neha, Map.of("away", false), neha).status()).isEqualTo(200);
        assertThat(get("/admin/tickets/away", boss).text()).doesNotContain(neha.toString());
        String third = ask(customer, "Question three about bookings");
        assertThat(assigneeOf(third)).isEqualTo(neha);   // fewest open: 0 against 2
    }

    @Test
    void everyoneAwayLeavesNewTicketsUnassignedAndTheFirstBackTakesThem() {
        UUID neha = support("9800000001", "Neha");
        assertThat(put("/admin/tickets/away/" + neha, Map.of("away", true), neha).status()).isEqualTo(200);
        String id = ask(customer, "Question about my booking");
        assertThat(assigneeOf(id)).isNull();
        put("/admin/tickets/away/" + neha, Map.of("away", false), neha);
        assertThat(assigneeOf(id)).isEqualTo(neha);
    }

    @Test
    void aResolvedTicketOfSomeoneAwayGoesToSomeoneElseWhenTheyWriteAgain() {
        UUID neha = support("9800000001", "Neha");
        String id = ask(customer, "Question about my booking");
        post("/admin/tickets/" + id + "/close", Map.of("seenSeq", 999_999), neha);
        UUID ravi = support("9800000002", "Ravi");
        put("/admin/tickets/away/" + neha, Map.of("away", true), boss);
        post("/helpdesk/tickets/" + id + "/messages", Map.of("message", "It happened again"), customer);
        assertThat(assigneeOf(id)).isEqualTo(ravi);
    }

    @Test
    void aReplyIsInTheAppTheQuestionCameFromAndOnlyTheNewestWaits() {
        UUID neha = support("9800000001", "Neha");
        String fromApp = ask(customer, "Question about my booking");
        post("/admin/tickets/" + fromApp + "/reply", Map.of("seenSeq", 999_999, "body", "First answer",
                "resolve", false), neha);
        post("/admin/tickets/" + fromApp + "/reply", Map.of("seenSeq", 999_999, "body", "Second answer",
                "resolve", true), neha);
        // Two quick replies: one message waiting, about the newest (resolved), never the reply's words
        List<Map<String, Object>> jobs = jdbc.queryForList("SELECT type, title, body, url FROM notification_jobs "
                + "WHERE user_id = ? AND type = 'CUSTOMERHELP_REPLIED' AND status <> 'CANCELLED' AND channel = 'PUSH'",
                customer);
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).get("title")).isEqualTo("Support resolved CLZ-1001");
        assertThat(jobs.get(0).get("url")).isEqualTo("/help/requests/" + fromApp);
        assertThat(jobs.get(0).get("body").toString()).doesNotContain("Second answer").doesNotContain("First answer");

        Reply r = post("/helpdesk/tickets", Map.of("app", "PARTNER", "message", "Payout question for my salon"),
                customer);
        String fromPartner = (String) field(r.body(), "id");
        post("/admin/tickets/" + fromPartner + "/reply", Map.of("seenSeq", 999_999, "body", "It goes on Monday",
                "resolve", false), neha);
        assertThat(count("notification_jobs WHERE type = 'PARTNERHELP_REPLIED'")).isPositive();
        // Each app's own list: the customer app's has its message, not the partner one, and the reverse
        assertThat(get("/me/notifications?app=customer", customer).text()).contains("CUSTOMERHELP_REPLIED")
                .doesNotContain("PARTNERHELP_REPLIED");
        assertThat(get("/me/notifications?app=partner", customer).text()).contains("PARTNERHELP_REPLIED")
                .doesNotContain("CUSTOMERHELP_REPLIED");
        // Never the subject: it can be the first words of their own message
        assertThat(get("/me/notifications?app=customer", customer).text()).doesNotContain("Question about");
    }

    @Test
    void someoneAwayWhoOpensTheirOwnTicketAgainKeepsIt() {
        UUID neha = support("9800000001", "Neha");
        String id = ask(customer, "Question about my booking");
        post("/admin/tickets/" + id + "/close", Map.of("seenSeq", 999_999), neha);
        support("9800000002", "Ravi");
        put("/admin/tickets/away/" + neha, Map.of("away", true), neha);
        long version = ((Number) field(get("/admin/tickets/" + id, neha).body(), "version")).longValue();
        Reply r = post("/admin/tickets/" + id + "/reopen", Map.of("version", version), neha);
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(assigneeOf(id)).isEqualTo(neha);
    }

    @Test
    void aTicketIsNeverGivenByHandToSomeoneAway() {
        UUID neha = support("9800000001", "Neha");
        String id = ask(customer, "Question about my booking");
        UUID ravi = support("9800000002", "Ravi");
        put("/admin/tickets/away/" + ravi, Map.of("away", true), boss);
        long version = ((Number) field(get("/admin/tickets/" + id, boss).body(), "version")).longValue();
        Map<String, Object> to = new HashMap<>();
        to.put("version", version);
        to.put("assigneeId", ravi.toString());
        Reply r = post("/admin/tickets/" + id + "/assign", to, boss);
        assertThat(r.status()).isEqualTo(400);
        assertThat(field(r.body(), "code")).isEqualTo("AWAY");
        assertThat(assigneeOf(id)).isEqualTo(neha);
    }

    @Test
    void someoneMarkedAwayWhoBecomesASuperAdminCanStillBeSetBack() {
        UUID neha = support("9800000001", "Neha");
        put("/admin/tickets/away/" + neha, Map.of("away", true), boss);
        jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN', admin_designation = NULL WHERE id = ?", neha);
        assertThat(put("/admin/tickets/away/" + neha, Map.of("away", false), boss).status()).isEqualTo(200);
        assertThat(count("support_away")).isZero();
    }

    @Test
    void someoneMarkedAwayWhoRejoinsSupportIsNotAwayEvenWithNothingWaiting() {
        UUID neha = support("9800000001", "Neha");
        put("/admin/tickets/away/" + neha, Map.of("away", true), boss);
        Map<String, Object> boss2 = member("9800000001", "9800000001@example.com", "Neha", null, List.of());
        boss2.put("role", "SUPER_ADMIN");
        boss2.put("version", teamVersion(neha));
        assertThat(put("/admin/team/" + neha, boss2, boss).status()).isEqualTo(200);
        Map<String, Object> back = member("9800000001", "9800000001@example.com", "Neha", "SUPPORT", List.of());
        back.put("version", teamVersion(neha));
        assertThat(put("/admin/team/" + neha, back, boss).status()).isEqualTo(200);
        assertThat(count("support_away")).isZero();
    }

    private long teamVersion(UUID who) {
        return ((Number) field(((List<?>) get("/admin/team", boss).body()).stream()
                .filter(m -> who.toString().equals(field(m, "userId"))).findFirst().orElseThrow(), "version"))
                .longValue();
    }

    private UUID assigneeOf(String ticketId) {
        return jdbc.queryForObject("SELECT assignee_id FROM tickets WHERE id = ?::uuid", UUID.class, ticketId);
    }

    // ---------------- round 4a: photos, videos and PDFs ----------------

    @Autowired in.apnidukan.files.service.FileDeletionJob fileDeletions;
    @Autowired in.apnidukan.helpdesk.service.TicketFileRetentionJob retention;

    @Test
    void aPhotoSentWithAMessageIsCleanedKeptAndShownToThemAndToSupport() throws Exception {
        UUID neha = support("9800000001", "Neha");
        String id = ask(customer, "Question about my booking");
        Reply up = upload(jpeg(), "IMG_2041.jpg", customer);
        assertThat(up.status()).as(up.text()).isEqualTo(201);
        String fileId = (String) field(up.body(), "id");
        assertThat(field(up.body(), "contentType")).isEqualTo("image/jpeg");
        String key = jdbc.queryForObject("SELECT storage_key FROM files WHERE id = ?::uuid", String.class, fileId);
        assertThat(count("file_deletions WHERE storage_key = '" + key + "'")).isEqualTo(1);   // unsent: due in a day

        Map<String, Object> add = new HashMap<>();
        add.put("message", "");   // words may be left out with a file
        add.put("fileIds", List.of(fileId));
        Reply r = post("/helpdesk/tickets/" + id + "/messages", add, customer);
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(count("file_deletions WHERE storage_key = '" + key + "'")).isZero();   // sent: kept
        assertThat(r.text()).contains(fileId).contains("IMG_2041.jpg").contains("\"url\":\"http");
        assertThat(get("/admin/tickets/" + id, neha).text()).contains(fileId);

        // Sent again a moment later (the answer was lost): kept once
        assertThat(post("/helpdesk/tickets/" + id + "/messages", add, customer).status()).isEqualTo(200);
        assertThat(count("ticket_message_files")).isEqualTo(1);
        // Sent once: with other words it is refused, naming the file; someone else can't send it
        add.put("message", "the photo again");
        Reply again = post("/helpdesk/tickets/" + id + "/messages", add, customer);
        assertThat(again.status()).isEqualTo(409);
        assertThat(field(again.body(), "code")).isEqualTo("FILE_ALREADY_SENT");
        assertThat(again.text()).contains("IMG_2041.jpg");
        UUID other = person("9000000004");
        String theirs = ask(other, "Another question about bookings");
        Map<String, Object> steal = new HashMap<>(add);
        steal.put("fileIds", List.of((String) field(upload(jpeg(), "a.jpg", customer).body(), "id")));
        Reply stolen = post("/helpdesk/tickets/" + theirs + "/messages", steal, other);
        assertThat(stolen.status()).isEqualTo(400);
        assertThat(field(stolen.body(), "code")).isEqualTo("FILE_NOT_FOUND");
    }

    @Test
    void aVideoGoesStraightToStorageIsCheckedAndSentWithANewQuestion() throws Exception {
        byte[] video = new byte[4096];
        System.arraycopy(new byte[] {0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'}, 0, video, 0, 12);
        Reply link = post("/files/ticket/videos", Map.of("sizeBytes", video.length, "contentType", "video/mp4"),
                customer);
        assertThat(link.status()).as(link.text()).isEqualTo(201);
        assertThat(putTo((String) field(link.body(), "uploadUrl"), video, "video/mp4")).isEqualTo(200);
        Reply done = post("/files/ticket/videos/" + field(link.body(), "uploadId") + "/complete", Map.of(), customer);
        assertThat(done.status()).as(done.text()).isEqualTo(200);
        Map<String, Object> ask = new HashMap<>();
        ask.put("app", "CUSTOMER");
        ask.put("message", "The barber cut it wrong, see the video");
        ask.put("fileIds", List.of(field(done.body(), "id")));
        Reply r = post("/helpdesk/tickets", ask, customer);
        assertThat(r.status()).as(r.text()).isEqualTo(201);
        assertThat(get("/helpdesk/tickets/" + field(r.body(), "id"), customer).text()).contains("video/mp4");

        assertThat(post("/files/ticket/videos", Map.of("sizeBytes", 101L * 1024 * 1024, "contentType", "video/mp4"),
                customer).status()).isEqualTo(413);
        // A text file goes up too, kept only to download
        assertThat(field(upload("hello".getBytes(StandardCharsets.UTF_8), "x.txt", customer).body(), "contentType"))
                .isEqualTo("application/octet-stream");
    }

    @Test
    void aFileNeverSentIsDeletedAfterADayAndFilesGoSixMonthsAfterResolved() throws Exception {
        UUID neha = support("9800000001", "Neha");
        String unsent = (String) field(upload(jpeg(), "a.jpg", customer).body(), "id");
        String kept = (String) field(upload(pdf(), "bill.pdf", customer).body(), "id");
        Map<String, Object> ask = new HashMap<>();
        ask.put("app", "CUSTOMER");
        ask.put("message", "Here is the bill for my booking");
        ask.put("fileIds", List.of(kept));
        String id = (String) field(post("/helpdesk/tickets", ask, customer).body(), "id");

        CLOCK.set(CLOCK.instant().plus(java.time.Duration.ofHours(25)));
        fileDeletions.runOnce();
        assertThat(count("files WHERE id = '" + unsent + "'")).isZero();
        assertThat(count("files WHERE id = '" + kept + "'")).isEqualTo(1);

        post("/admin/tickets/" + id + "/close", Map.of("seenSeq", 999_999), neha);
        jdbc.update("UPDATE tickets SET resolved_at = ? WHERE id = ?::uuid",
                java.sql.Timestamp.from(CLOCK.instant().minus(java.time.Duration.ofDays(183))), id);
        assertThat(retention.runOnce()).isEqualTo(1);
        assertThat(count("files WHERE id = '" + kept + "'")).isZero();
        assertThat(get("/helpdesk/tickets/" + id, customer).text()).contains("\"removed\":true");
    }

    @Test
    void closingATicketThatChangedMeanwhileAsksToLookAgain() {
        UUID neha = support("9800000001", "Neha");
        String id = ask(customer, "Question about my booking");
        long version = ((Number) field(get("/admin/tickets/" + id, neha).body(), "version")).longValue();
        post("/admin/tickets/" + id + "/close", Map.of("seenSeq", 999_999, "version", version), neha);
        long now = ((Number) field(get("/admin/tickets/" + id, boss).body(), "version")).longValue();
        post("/admin/tickets/" + id + "/reopen", Map.of("version", now), boss);
        Reply stale = post("/admin/tickets/" + id + "/close", Map.of("seenSeq", 999_999, "version", version), neha);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(field(stale.body(), "code")).isEqualTo("TICKET_CHANGED");
    }

    @Test
    void anEmailFromAnAddressTheyGaveUpIsNeverShownAsTheirs() {
        support("9800000001", "Neha");
        String id = ask(customer, "Question about my booking");
        // They moved to a new address; someone else verified the old one since and writes on the old ticket
        jdbc.update("UPDATE users SET email = 'asha.new@test.in' WHERE id = ?", customer);
        UUID other = person("9000000004");
        jdbc.update("UPDATE users SET email = '9000000003@test.in' WHERE id = ?", other);
        inbound(email("9000000003@test.in", "Someone", "Re: [CLZ-1001] Question", "Not Asha writing", "<g1@x>"));
        assertThat(get("/helpdesk/tickets/" + id, customer).text()).doesNotContain("Not Asha writing");
        // Support sees it isn't her, not proven as hers
        List<?> messages = (List<?>) field(get("/admin/tickets/" + id, boss).body(), "messages");
        Map<?, ?> last = (Map<?, ?>) messages.get(messages.size() - 1);
        assertThat(last.get("body")).isEqualTo("Not Asha writing");
        assertThat(last.get("author")).isEqualTo("Someone else (from an email address they no longer use)");
        assertThat(last.get("senderVerified")).isEqualTo(false);
    }

    @Test
    void realSizedPhotosAndPdfsGoUpPastTheSizeLimitForOtherRequests() throws Exception {
        // Any request but an upload is refused over 64 KB; a phone photo or a bill is far bigger
        java.awt.image.BufferedImage noisy = new java.awt.image.BufferedImage(800, 600,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(7);
        for (int x = 0; x < 800; x++) {
            for (int y = 0; y < 600; y++) {
                noisy.setRGB(x, y, random.nextInt());
            }
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(noisy, "jpeg", out);
        assertThat(out.size()).isGreaterThan(200 * 1024);
        Reply photo = upload(out.toByteArray(), "IMG_2042.jpg", customer);
        assertThat(photo.status()).as(photo.text()).isEqualTo(201);

        byte[] bill = new byte[300 * 1024];
        byte[] head = pdf();
        System.arraycopy(head, 0, bill, 0, head.length);
        Reply pdf = upload(bill, "bill.pdf", customer);
        assertThat(pdf.status()).as(pdf.text()).isEqualTo(201);
        assertThat(field(pdf.body(), "sizeBytes")).isEqualTo(300 * 1024);
    }

    @Test
    void supportSendsAFileWithAReplyButNeverToSomeoneWhoOnlyWroteByEmail() throws Exception {
        UUID neha = support("9800000001", "Neha");
        String id = ask(customer, "Question about my booking");
        String fileId = (String) field(upload(pdf(), "refund.pdf", neha).body(), "id");
        Map<String, Object> reply = new HashMap<>();
        reply.put("body", "");   // a file alone
        reply.put("seenSeq", 999_999);
        reply.put("fileIds", List.of(fileId));
        Reply r = post("/admin/tickets/" + id + "/reply", reply, neha);
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(get("/helpdesk/tickets/" + id, customer).text()).contains(fileId).contains("refund.pdf");
        assertThat(jdbc.queryForObject("SELECT body FROM ticket_emails WHERE ticket_id = ?::uuid AND body LIKE '%We sent 1 file%'",
                String.class, id))
                .contains("We sent 1 file with this reply. See them in the Clozest app");

        inbound(email("vikram@gmail.com", "Vikram", "Refund", "Where is my refund for the haircut", "<v9@x>"));
        String emailed = jdbc.queryForObject("SELECT id::text FROM tickets WHERE requester_email = 'vikram@gmail.com'",
                String.class);
        reply.put("fileIds", List.of((String) field(upload(pdf(), "refund.pdf", neha).body(), "id")));
        Reply refused = post("/admin/tickets/" + emailed + "/reply", reply, neha);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(field(refused.body(), "code")).isEqualTo("FILES_NEED_APP");
    }

    @Test
    void aQuestionWithFilesSentAgainAfterALostAnswerIsOneQuestion() throws Exception {
        String fileId = (String) field(upload(jpeg(), "a.jpg", customer).body(), "id");
        Map<String, Object> ask = new HashMap<>();
        ask.put("app", "CUSTOMER");
        ask.put("message", "The barber cut it wrong, see the photo");
        ask.put("fileIds", List.of(fileId));
        Reply first = post("/helpdesk/tickets", ask, customer);
        Reply again = post("/helpdesk/tickets", ask, customer);
        assertThat(again.status()).as(again.text()).isEqualTo(201);
        assertThat(field(again.body(), "id")).isEqualTo(field(first.body(), "id"));
        assertThat(count("tickets")).isEqualTo(1);
    }

    @Test
    void thirtyFilesADayAndAVideoCountsOnce() throws Exception {
        byte[] video = new byte[4096];
        System.arraycopy(new byte[] {0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'}, 0, video, 0, 12);
        Reply link = post("/files/ticket/videos", Map.of("sizeBytes", video.length, "contentType", "video/mp4"),
                customer);
        assertThat(putTo((String) field(link.body(), "uploadUrl"), video, "video/mp4")).isEqualTo(200);
        assertThat(post("/files/ticket/videos/" + field(link.body(), "uploadId") + "/complete", Map.of(), customer)
                .status()).isEqualTo(200);
        byte[] photo = jpeg();
        for (int i = 0; i < 29; i++) {   // the video was the 1st
            assertThat(upload(photo, "p" + i + ".jpg", customer).status()).as("upload " + (i + 2)).isEqualTo(201);
        }
        Reply over = upload(photo, "p30.jpg", customer);
        assertThat(over.status()).isEqualTo(429);
        assertThat(field(over.body(), "code")).isEqualTo("TOO_MANY_UPLOADS");
    }

    // ---------------- round 4b: files that come by email ----------------

    @Autowired in.apnidukan.files.storage.FileStorage storage;

    @Test
    void everyFileOfAnEmailGoesOnItsMessageLogosAreSkipped() throws Exception {
        UUID neha = support("9800000001", "Neha");
        byte[] raw = withFiles("vikram@gmail.com", "<f1@x>", "The cut was uneven, photos attached",
                new Part("IMG_1.jpg", "image/jpeg", noisyJpeg(), false),
                new Part("bill.pdf", "application/pdf", pdf(), false),
                new Part("notes.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "PK not really".getBytes(StandardCharsets.UTF_8), false),
                new Part("logo.png", "image/png", jpeg(), true));   // a small inline image: a signature logo
        assertThat(inbound(raw).body()).isEqualTo(Map.of("result", "CREATED"));
        String id = jdbc.queryForObject("SELECT id::text FROM tickets", String.class);
        Reply detail = get("/admin/tickets/" + id, neha);
        assertThat(detail.text()).contains("IMG_1.jpg").contains("bill.pdf").contains("notes.docx")
                .doesNotContain("logo.png").contains("\"url\":\"http")
                .contains("response-content-disposition");   // the Word file (and each download link) saves it
        assertThat(jdbc.queryForList("SELECT name FROM ticket_message_files", String.class))
                .containsExactlyInAnyOrder("IMG_1.jpg", "bill.pdf", "notes.docx");
        assertThat(jdbc.queryForObject("SELECT attachments FROM ticket_messages", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT body FROM ticket_messages", String.class))
                .isEqualTo("The cut was uneven, photos attached");
        // Kept like an app upload: off the deletion queue; a cleaned JPEG, the PDF, the Word file only to download
        assertThat(count("file_deletions WHERE storage_key LIKE 'tickets/email/%'")).isZero();
        assertThat(jdbc.queryForList("SELECT content_type FROM files ORDER BY content_type", String.class))
                .containsExactly("application/octet-stream", "application/pdf", "image/jpeg");

        // The same email again (the worker tried twice): nothing more is stored
        inbound(raw);
        assertThat(count("files")).isEqualTo(3);
    }

    @Test
    void anyFileGoesUpFromTheAppAndIsOnlyDownloaded() throws Exception {
        Reply word = upload("PK\u0003\u0004 a Word file".getBytes(StandardCharsets.UTF_8), "quote.docx", customer);
        assertThat(word.status()).as(word.text()).isEqualTo(201);
        assertThat(field(word.body(), "contentType")).isEqualTo("application/octet-stream");
        String id = ask(customer, "Question about my booking");
        Map<String, Object> add = new HashMap<>();
        add.put("message", "");
        add.put("fileIds", List.of(field(word.body(), "id")));
        assertThat(post("/helpdesk/tickets/" + id + "/messages", add, customer).status()).isEqualTo(200);
        String mine = get("/helpdesk/tickets/" + id, customer).text();
        assertThat(mine).contains("quote.docx").contains("response-content-disposition").contains("downloadUrl");
    }

    @Test
    void filesOfATicketNobodyResolvesGoSixMonthsAfterItsLastMessage() throws Exception {
        String id = ask(customer, "Question about my booking");
        String fileId = (String) field(upload(pdf(), "bill.pdf", customer).body(), "id");
        Map<String, Object> add = new HashMap<>();
        add.put("message", "");
        add.put("fileIds", List.of(fileId));
        post("/helpdesk/tickets/" + id + "/messages", add, customer);
        assertThat(retention.runOnce()).isZero();
        jdbc.update("UPDATE tickets SET last_message_at = ? WHERE id = ?::uuid",
                java.sql.Timestamp.from(CLOCK.instant().minus(java.time.Duration.ofDays(183))), id);
        assertThat(retention.runOnce()).isEqualTo(1);
        assertThat(count("ticket_message_files WHERE removed_at IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void writingOnYourOwnTicketNeverWaitsOnTheHelpdeskWideLock() throws Exception {
        support("9800000001", "Neha");
        String id = ask(customer, "Question about my booking");
        // Someone holds the helpdesk-wide lock (assigning a new ticket, a long team change): adding to one's own
        // ticket, whose person still receives tickets, goes ahead all the same
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(st -> {
            jdbc.queryForObject("SELECT count(*) FROM (SELECT pg_advisory_xact_lock(7301)) l", Long.class);
            held.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            long start = System.nanoTime();
            Reply r = post("/helpdesk/tickets/" + id + "/messages", Map.of("message", "Any news on this?"), customer);
            assertThat(r.status()).as(r.text()).isEqualTo(200);
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(5));
        } finally {
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        }
    }

    @Autowired in.apnidukan.helpdesk.service.StrandedTicketJob stranded;

    @Test
    void aTicketLeftWithSomeoneAwayGoesToSomeoneHereWithinMinutes() throws Exception {
        UUID neha = support("9800000001", "Neha");
        String id = ask(customer, "Question about my booking");
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM tickets WHERE id = ?::uuid", UUID.class, id))
                .isEqualTo(neha);
        UUID ravi = support("9800000002", "Ravi");
        // Neha went away at the very moment the customer wrote (that write locks only the ticket): it stayed hers
        jdbc.update("INSERT INTO support_away (user_id, since) VALUES (?, now())", neha);
        assertThat(stranded.runOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM tickets WHERE id = ?::uuid", UUID.class, id))
                .isEqualTo(ravi);
        assertThat(stranded.runOnce()).isZero();
    }

    @Test
    void aFloodOfEmailsFromOneSenderIsCutOffAfterThirtyAnHour() throws Exception {
        support("9800000001", "Neha");
        for (int i = 0; i < 30; i++) {
            assertThat(field(inbound(withFiles("robot@gmail.com", "<fl" + i + "@x>", "Spam number " + i)).body(),
                    "result")).isIn("CREATED", "ADDED");
        }
        assertThat(inbound(withFiles("robot@gmail.com", "<fl30@x>", "Spam number 30")).body())
                .isEqualTo(Map.of("result", "IGNORED"));
        assertThat(count("ticket_messages")).isEqualTo(30);
    }

    @Test
    void aBigEmailIsReadFromWhereTheWorkerParkedItAndThenDeleted() throws Exception {
        support("9800000001", "Neha");
        byte[] whole = withFiles("vikram@gmail.com", "<big2@x>", "Video of the problem",
                new Part("cut.mp4", "video/mp4", video(), false));
        String key = "inbound/" + UUID.randomUUID() + ".eml";
        storage.put(key, new java.io.ByteArrayInputStream(whole), whole.length, "message/rfc822");
        byte[] headers = ("ARC-Authentication-Results: i=1; mx.cloudflare.net; dmarc=pass header.from=gmail.com\r\n"
                + "From: vikram@gmail.com\r\nSubject: Video\r\nMessage-ID: <big2@x>\r\nX-Clozest-Raw: " + key
                + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        assertThat(truncatedInbound(headers).body()).isEqualTo(Map.of("result", "CREATED"));
        assertThat(jdbc.queryForObject("SELECT body FROM ticket_messages", String.class))
                .isEqualTo("Video of the problem");
        assertThat(jdbc.queryForObject("SELECT content_type FROM ticket_message_files", String.class))
                .isEqualTo("video/mp4");
        assertThat(count("file_deletions WHERE storage_key = '" + key + "'")).isEqualTo(1);   // read: deleted soon

        // A key outside inbound/ is never read: headers only
        byte[] forged = ("From: vikram@gmail.com\r\nSubject: Again\r\nMessage-ID: <big3@x>\r\nX-Clozest-Raw: "
                + "tickets/someone/secret.pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        truncatedInbound(forged);
        assertThat(jdbc.queryForList("SELECT body FROM ticket_messages ORDER BY seq", String.class))
                .anyMatch(b -> b.contains("support inbox"));
    }

    @Test
    void aProvenAccountsEmailedFileShowsInTheirApp() throws Exception {
        String id = ask(customer, "Question about my booking");
        String label = jdbc.queryForObject("SELECT 'CLZ-' || number FROM tickets WHERE id = ?::uuid", String.class, id);
        byte[] raw = withFiles("9000000003@test.in", "<f4@x>", "Here is the bill",
                new Part("bill.pdf", "application/pdf", pdf(), false));
        raw = new String(raw, StandardCharsets.ISO_8859_1).replace("Subject: Files",
                "Subject: Re: [" + label + "] Question").getBytes(StandardCharsets.ISO_8859_1);
        assertThat(inbound(raw).body()).isEqualTo(Map.of("result", "ADDED"));
        assertThat(get("/helpdesk/tickets/" + id, customer).text()).contains("bill.pdf");
    }

    @Test
    void thirtyFilesAreKeptTheRestNamedAndABrokenFileNeverLosesTheEmail() throws Exception {
        support("9800000001", "Neha");
        List<Part> parts = new ArrayList<>();
        for (int i = 1; i <= 32; i++) {
            parts.add(new Part("bill" + i + ".pdf", "application/pdf",
                    ("%PDF-1.4\n% bill " + i + "\n").getBytes(StandardCharsets.UTF_8), false));
        }
        byte[] raw = withFiles("vikram@gmail.com", "<many@x>", "All my bills", parts.toArray(new Part[0]));
        // and a part in an encoding nobody can decode
        raw = new String(raw, StandardCharsets.UTF_8).replace("--b1--", "--b1\r\nContent-Type: image/jpeg; "
                + "name=\"x.jpg\"\r\nContent-Disposition: attachment; filename=\"x.jpg\"\r\n"
                + "Content-Transfer-Encoding: x-made-up\r\n\r\nabc\r\n--b1--").getBytes(StandardCharsets.UTF_8);
        assertThat(inbound(raw).body()).isEqualTo(Map.of("result", "CREATED"));
        assertThat(count("ticket_message_files")).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT attachments FROM ticket_messages", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT body FROM ticket_messages", String.class))
                .endsWith("(Only in the email: bill31.pdf, bill32.pdf, x.jpg)");
    }

    @Test
    void aReplyQuotingOurEmailDoesNotKeepItsPhotoTwice() throws Exception {
        support("9800000001", "Neha");
        byte[] photo = noisyJpeg();
        inbound(withFiles("vikram@gmail.com", "<q1@x>", "Photo of the cut",
                new Part("IMG_1.jpg", "image/jpeg", photo, false)));
        String label = jdbc.queryForObject("SELECT 'CLZ-' || number FROM tickets", String.class);
        byte[] reply = new String(withFiles("vikram@gmail.com", "<q2@x>", "Any news?",
                new Part("IMG_1.jpg", "image/jpeg", photo, true)), StandardCharsets.UTF_8)
                .replace("Subject: Files", "Subject: Re: [" + label + "] Files").getBytes(StandardCharsets.UTF_8);
        assertThat(inbound(reply).body()).isEqualTo(Map.of("result", "ADDED"));
        assertThat(count("ticket_message_files")).isEqualTo(1);
    }

    @Test
    void deletingTheAccountDeletesTheFilesItEmailedEvenUnproven() throws Exception {
        String id = ask(customer, "Question about my booking");
        String label = jdbc.queryForObject("SELECT 'CLZ-' || number FROM tickets WHERE id = ?::uuid", String.class, id);
        // From their address but without Cloudflare's proof: kept on the ticket, never written as theirs
        String raw = new String(withFiles("9000000003@test.in", "<u1@x>", "My ID card",
                new Part("id.pdf", "application/pdf", pdf(), false)), StandardCharsets.UTF_8)
                .replace("Subject: Files", "Subject: Re: [" + label + "] Question");
        raw = raw.substring(raw.indexOf("\r\n") + 2);   // no ARC-Authentication-Results line
        inbound(raw.getBytes(StandardCharsets.UTF_8));
        assertThat(count("ticket_message_files WHERE removed_at IS NULL")).isEqualTo(1);
        deletion.accountDeleted(customer, CLOCK.instant());
        assertThat(count("ticket_message_files WHERE removed_at IS NULL")).isZero();
    }

    @Test
    void aSmallEmailNamingAParkedKeyIsReadAsItIs() throws Exception {
        support("9800000001", "Neha");
        String key = "inbound/" + UUID.randomUUID() + ".eml";
        byte[] other = withFiles("someone@gmail.com", "<p0@x>", "Not yours", new Part("a.pdf", "application/pdf",
                pdf(), false));
        storage.put(key, new java.io.ByteArrayInputStream(other), other.length, "message/rfc822");
        // A sender's own X-Clozest-Raw in an email posted whole is never followed, even if marked truncated
        byte[] forged = ("From: vikram@gmail.com\r\nSubject: Hi\r\nMessage-ID: <p1@x>\r\nX-Clozest-Raw: " + key
                + "\r\n\r\nHello").getBytes(StandardCharsets.UTF_8);
        truncatedInbound(forged);
        assertThat(count("ticket_message_files")).isZero();
        assertThat(count("file_deletions WHERE storage_key = '" + key + "'")).isZero();   // never read
    }

    private record Part(String name, String type, byte[] bytes, boolean inline) {
    }

    /** A proven multipart email from {@code from} with these files. */
    private static byte[] withFiles(String from, String messageId, String text, Part... parts) {
        String domain = from.substring(from.indexOf('@') + 1);
        StringBuilder raw = new StringBuilder("ARC-Authentication-Results: i=1; mx.cloudflare.net; dkim=pass header.d="
                + domain + "; dmarc=pass header.from=" + domain + "\r\nFrom: " + from
                + "\r\nTo: support@clozest.in\r\nSubject: Files\r\nMessage-ID: " + messageId
                + "\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"b1\"\r\n\r\n--b1\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n" + text + "\r\n");
        int n = 0;
        for (Part p : parts) {
            raw.append("--b1\r\nContent-Type: ").append(p.type()).append("; name=\"").append(p.name())
                    .append("\"\r\nContent-Disposition: ").append(p.inline() ? "inline" : "attachment")
                    .append("; filename=\"").append(p.name()).append("\"\r\n")
                    .append(p.inline() ? "Content-ID: <c" + (n++) + "@x>\r\n" : "")
                    .append("Content-Transfer-Encoding: base64\r\n\r\n")
                    .append(java.util.Base64.getMimeEncoder().encodeToString(p.bytes())).append("\r\n");
        }
        raw.append("--b1--\r\n");
        return raw.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Reply truncatedInbound(byte[] headers) {
        long ts = CLOCK.instant().getEpochSecond();
        Reply r = http.post().uri("/helpdesk/inbound").contentType(MediaType.parseMediaType("message/rfc822"))
                .headers(h -> {
                    h.set("X-Helpdesk-Timestamp", String.valueOf(ts));
                    h.set("X-Helpdesk-Signature", sign(ts, headers));
                    h.set("X-Helpdesk-Truncated", "1");
                }).body(headers).exchange((rq, res) -> read(res));
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        return r;
    }

    private static byte[] noisyJpeg() throws java.io.IOException {
        java.awt.image.BufferedImage noisy = new java.awt.image.BufferedImage(300, 200,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(3);
        for (int x = 0; x < 300; x++) {
            for (int y = 0; y < 200; y++) {
                noisy.setRGB(x, y, random.nextInt());
            }
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(noisy, "jpeg", out);
        return out.toByteArray();
    }

    private static byte[] video() {
        byte[] video = new byte[4096];
        System.arraycopy(new byte[] {0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'}, 0, video, 0, 12);
        return video;
    }

    private Reply upload(byte[] bytes, String name, UUID who) {
        org.springframework.util.LinkedMultiValueMap<String, Object> parts =
                new org.springframework.util.LinkedMultiValueMap<>();
        parts.add("file", new org.springframework.core.io.ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return name;
            }
        });
        return http.post().uri("/files/ticket").headers(h -> h.setBearerAuth(token(who)))
                .contentType(MediaType.MULTIPART_FORM_DATA).body(parts).exchange((rq, res) -> read(res));
    }

    private static int putTo(String url, byte[] bytes, String contentType) throws Exception {
        return java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(url)).header("Content-Type", contentType)
                        .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static byte[] jpeg() throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(40, 30,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "jpeg", out);
        return out.toByteArray();
    }

    private static byte[] pdf() {
        return "%PDF-1.4\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
    }

    // ---------------- super admins ----------------

    @Test
    void theSuperAdminSeesHowManyEachPersonHas() {
        UUID neha = support("9800000001", "Neha");
        String id = ask(customer, "Question one about bookings");
        ask(customer, "Question two about bookings");
        post("/admin/tickets/" + id + "/close", Map.of("seenSeq", 999_999), neha);
        Reply stats = get("/admin/tickets/stats", boss);
        Map<?, ?> row = (Map<?, ?>) ((List<?>) field(stats.body(), "people")).get(0);
        assertThat(row.get("name")).isEqualTo("Neha");
        assertThat(row.get("open")).isEqualTo(1);
        assertThat(row.get("resolved")).isEqualTo(1);
        assertThat(row.get("support")).isEqualTo(true);
        assertThat((List<?>) get("/admin/tickets?assignee=" + neha + "&view=RESOLVED", boss).body()).hasSize(1);
    }

    @Test
    void whenSomeoneLeavesSupportTheirOpenTicketsMoveToTheOthers() {
        UUID neha = support("9800000001", "Neha");
        ask(customer, "Question one about bookings");
        ask(customer, "Question two about bookings");
        UUID ravi = support("9800000002", "Ravi");
        assertThat(delete("/admin/team/" + neha, boss).status()).isEqualTo(204);
        assertThat(open(ravi)).isEqualTo(2);
        assertThat(count("ticket_events WHERE event = 'REASSIGNED'")).isEqualTo(2);

        // The last one leaving: back to Unassigned
        long version = ((Number) field(((List<?>) get("/admin/team", boss).body()).stream()
                .filter(m -> ravi.toString().equals(field(m, "userId"))).findFirst().orElseThrow(), "version"))
                .longValue();
        Map<String, Object> agent = member("9800000002", "ravi@example.com", "Ravi", "FIELD_AGENT",
                List.of("500084"));
        agent.put("version", version);
        assertThat(put("/admin/team/" + ravi, agent, boss).status()).isEqualTo(200);
        assertThat((List<?>) get("/admin/tickets?view=UNASSIGNED", boss).body()).hasSize(2);
    }

    @Test
    void aDeletedAccountsTicketsForgetTheirAddressAndNothingMoreIsSent() {
        support("9800000001", "Neha");
        String id = ask(customer, "Please delete my data");
        deletion.accountDeleted(customer, NOW);
        assertThat(emailJob.runOnce()).isZero();
        Reply detail = get("/admin/tickets/" + id, boss);
        assertThat(field(detail.body(), "requesterEmail")).isNull();
        Reply r = post("/admin/tickets/" + id + "/reply", Map.of("seenSeq", 999_999, "body", "Done"), boss);
        assertThat(field(r.body(), "code")).isEqualTo("REQUESTER_GONE");
        assertThat(post("/admin/tickets/" + id + "/close", Map.of("seenSeq", 999_999), boss).status()).isEqualTo(200);
    }

    // ---------------- helpers ----------------

    private UUID person(String phone) {
        UUID id = UUID.randomUUID();
        TestData.user(jdbc, id, phone);
        return id;
    }

    private UUID support(String phone, String name) {
        CLOCK.set(CLOCK.instant().plusSeconds(1));
        Reply r = post("/admin/team", member(phone, phone + "@example.com", name, "SUPPORT", List.of()), boss);
        assertThat(r.status()).as(r.text()).isEqualTo(201);
        return userId(r);
    }

    /** A ticket from the customer app; returns its id. */
    private String ask(UUID who, String message) {
        Reply r = post("/helpdesk/tickets", Map.of("app", "CUSTOMER", "message", message), who);
        assertThat(r.status()).as(r.text()).isEqualTo(201);
        return (String) field(r.body(), "id");
    }

    private long open(UUID assignee) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM tickets WHERE assignee_id = ? AND status <> 'RESOLVED'",
                Long.class, assignee);
        return n == null ? 0 : n;
    }

    private static Map<String, Object> member(String phone, String email, String name, String designation,
                                              List<String> pincodes) {
        Map<String, Object> m = new HashMap<>();
        m.put("phone", phone);
        m.put("email", email);
        m.put("name", name);
        m.put("role", "ADMIN");
        m.put("designation", designation);
        m.put("pincodes", pincodes);
        return m;
    }

    /** An email as Cloudflare hands it over: with its DMARC pass on top (a proven sender). */
    private static byte[] email(String from, String name, String subject, String text, String messageId) {
        return email(from, name, subject, text, messageId, true);
    }

    private static byte[] email(String from, String name, String subject, String text, String messageId,
                                boolean proven) {
        String domain = from.substring(from.indexOf('@') + 1);
        String raw = (proven ? "ARC-Authentication-Results: i=1; mx.cloudflare.net; dkim=pass header.d=" + domain
                + "; dmarc=pass header.from=" + domain + "\r\n" : "")
                + "From: " + (name.isEmpty() ? from : "\"" + name + "\" <" + from + ">") + "\r\n"
                + "To: support@clozest.in\r\nSubject: " + subject + "\r\nMessage-ID: " + messageId + "\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n" + text.replace("\n", "\r\n");
        return raw.getBytes(StandardCharsets.UTF_8);
    }

    private Reply inbound(byte[] raw) {
        long ts = CLOCK.instant().getEpochSecond();
        Reply r = rawInbound(raw, String.valueOf(ts), sign(ts, raw));
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        return r;
    }

    private Reply rawInbound(byte[] raw, String ts, String sig) {
        return http.post().uri("/helpdesk/inbound").contentType(MediaType.parseMediaType("message/rfc822"))
                .headers(h -> {
                    if (ts != null) {
                        h.set("X-Helpdesk-Timestamp", ts);
                    }
                    if (sig != null) {
                        h.set("X-Helpdesk-Signature", sig);
                    }
                }).body(raw).exchange((rq, res) -> read(res));
    }

    private static String sign(long ts, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((ts + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private long count(String fromWhere) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + fromWhere, Long.class);
        return n == null ? 0 : n;
    }

    private static Map<?, ?> only(Reply r) {
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        List<?> list = (List<?>) r.body();
        assertThat(list).hasSize(1);
        return (Map<?, ?>) list.get(0);
    }

    private String token(UUID who) {
        Boolean isAdmin = jdbc.queryForObject("SELECT admin_role <> 'NONE' FROM users WHERE id = ?", Boolean.class,
                who);
        return jwt.issue(new AuthPrincipal(who, null, null, Boolean.TRUE.equals(isAdmin)),
                sessions.currentGeneration(who)).token();
    }

    private static UUID userId(Reply r) {
        return UUID.fromString((String) field(r.body(), "userId"));
    }

    private Reply get(String path, UUID who) {
        return call(HttpMethod.GET, path, null, token(who));
    }

    private Reply post(String path, Object body, UUID who) {
        return call(HttpMethod.POST, path, body, token(who));
    }

    private Reply put(String path, Object body, UUID who) {
        return call(HttpMethod.PUT, path, body, token(who));
    }

    private Reply delete(String path, UUID who) {
        return call(HttpMethod.DELETE, path, null, token(who));
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
        return spec.exchange((rq, res) -> read(res));
    }

    private static Reply read(org.springframework.http.client.ClientHttpResponse res) throws java.io.IOException {
        int status = res.getStatusCode().value();
        String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
        Object parsed = text.isBlank() ? Map.of() : JSON.readValue(text, Object.class);
        return new Reply(status, parsed, text);
    }

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    private static List<String> strings(Object list) {
        return ((List<?>) list).stream().map(String::valueOf).toList();
    }

    private static Object field(Object json, String name) {
        return json instanceof Map<?, ?> m ? m.get(name) : null;
    }

    private record Reply(int status, Object body, String text) {
    }
}
