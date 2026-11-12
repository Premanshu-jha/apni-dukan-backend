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
        Reply late = post("/admin/tickets/" + id + "/reply", Map.of("seenSeq", 99, "body", "Hello"), neha);
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
        post("/admin/tickets/" + id + "/close", Map.of("seenSeq", 99), neha);
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
        assertThat(jdbc.queryForObject("SELECT body FROM ticket_messages", String.class)).contains("support Gmail");
    }

    // ---------------- super admins ----------------

    @Test
    void theSuperAdminSeesHowManyEachPersonHas() {
        UUID neha = support("9800000001", "Neha");
        String id = ask(customer, "Question one about bookings");
        ask(customer, "Question two about bookings");
        post("/admin/tickets/" + id + "/close", Map.of("seenSeq", 99), neha);
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
        Reply r = post("/admin/tickets/" + id + "/reply", Map.of("seenSeq", 99, "body", "Done"), boss);
        assertThat(field(r.body(), "code")).isEqualTo("REQUESTER_GONE");
        assertThat(post("/admin/tickets/" + id + "/close", Map.of("seenSeq", 99), boss).status()).isEqualTo(200);
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
