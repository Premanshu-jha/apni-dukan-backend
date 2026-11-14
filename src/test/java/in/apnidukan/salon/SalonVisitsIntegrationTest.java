package in.apnidukan.salon;

import in.apnidukan.common.context.AccountDeletionListener;
import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.JwtService;
import in.apnidukan.common.security.SessionRevocations;
import in.apnidukan.salon.service.AutoEndJob;
import in.apnidukan.salon.service.VisitFollowUpJob;
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
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The salon's registration (services from the list, opening hours, the registration document), the salon list's
 * cards, ratings, and the messages after a visit (VisitFollowUpJob), end to end against real Postgres and Redis.
 *
 * "Now" starts at Tuesday 10 Nov 2026, 10:00 in India. The live salon: Raju does Haircut (30 min, come back after 21
 * days) and Beard trim (15 min, 7 days); Facial (45 min) is offered by nobody. The salon and Raju are open every day
 * 10:00-21:00.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({CapturingSenders.class, SalonVisitsIntegrationTest.Fakes.class})
class SalonVisitsIntegrationTest {

    static final Instant TUESDAY_10_00 = Instant.parse("2026-11-10T04:30:00Z");
    static final TestClock CLOCK = new TestClock(TUESDAY_10_00);
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

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
        // Batches of two: the job's batch loop is gone through
        registry.add("app.jobs.follow-up-batch", () -> "2");
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtService jwt;
    @Autowired SessionRevocations sessions;
    @Autowired AutoEndJob autoEnd;
    @Autowired VisitFollowUpJob followUps;
    @Autowired List<AccountDeletionListener> deletionListeners;
    @Autowired TransactionTemplate tx;
    @Autowired in.apnidukan.salon.service.SalonOfferService offers;

    RestClient http;
    UUID owner;
    UUID salon;
    UUID raju;
    UUID haircut;
    UUID beard;
    UUID facial;
    UUID admin;
    int phones;

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port") + "/api/v1")
                .build();
        TestData.clean(jdbc);
        redis.execute((RedisCallback<Object>) (RedisConnection c) -> {
            c.serverCommands().flushAll();
            return null;
        });
        CLOCK.set(TUESDAY_10_00);
        phones = 0;
        admin = person();
        jdbc.update("UPDATE users SET admin_role = 'SUPER_ADMIN' WHERE id = ?", admin);
        owner = person();
        salon = UUID.randomUUID();
        TestData.salon(jdbc, salon, owner);
        jdbc.update("UPDATE shops SET status = 'APPROVED', name = 'Sharma Hair' WHERE id = ?", salon);
        haircut = id(post("/salon/services", service("Haircut", 30, 15000, 21), owner));
        beard = id(post("/salon/services", service("Beard trim", 15, 8000, 7), owner));
        facial = id(post("/salon/services", service("Facial", 45, 40000, 30), owner));
        Reply hours = put("/salon/hours", Map.of("days", week("10:00", "21:00")), owner);
        assertThat(hours.status()).as(hours.text()).isEqualTo(200);
        raju = barber("Raju", List.of(haircut, beard), "10:00", "21:00");
    }

    // ---------- registration ----------

    @Test
    void aSalonNeedsServicesAndHoursBeforeTheVisitTheDocumentIsOptionalAndTheFieldTeamSeesThem() {
        UUID newOwner = person();
        UUID draft = UUID.randomUUID();
        TestData.salon(jdbc, draft, newOwner);
        // Photos and the video are checked first, then the salon's setup
        assertThat(field(post("/shops/me/submit", Map.of("version", version(draft)), newOwner).body(), "code"))
                .isEqualTo("PHOTOS_REQUIRED");
        jdbc.update("UPDATE shops SET owner_photo_file_id = ?, shop_photo_file_id = ?, shop_video_file_id = ? "
                + "WHERE id = ?", file(draft, newOwner, "OWNER_PHOTO", "image/jpeg"),
                file(draft, newOwner, "SHOP_PHOTO", "image/jpeg"), file(draft, newOwner, "SHOP_VIDEO", "video/mp4"),
                draft);

        Reply early = post("/shops/me/submit", Map.of("version", version(draft)), newOwner);
        assertThat(early.status()).isEqualTo(409);
        assertThat(field(early.body(), "code")).isEqualTo("REGISTRATION_INCOMPLETE");
        assertThat(((Map<?, ?>) field(early.body(), "details")).keySet().stream().map(String::valueOf).toList())
                .containsExactly("services", "hours");   // the registration document is optional

        // The common services, then several at once: all or none
        List<?> catalog = (List<?>) get("/salon/service-catalog", newOwner).body();
        assertThat(catalog).extracting(c -> field(c, "name")).contains("Haircut", "Beard trim", "Facial");
        assertThat(field(catalog.get(0), "returnAfterDays")).isEqualTo(21);
        Reply twice = post("/salon/services/batch", Map.of("services", List.of(service("Haircut", 30, 15000, null),
                service("haircut", 30, 15000, null))), newOwner);
        assertThat(twice.status()).isEqualTo(400);
        assertThat(twice.text()).contains("services[1].name");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM salon_services WHERE shop_id = ?", Integer.class, draft))
                .isZero();
        // A picture that isn't one of the set is named with its row
        Map<String, Object> rocket = service("Hair wash", 15, 5000, 0);
        rocket.put("icon", "ROCKET");
        Reply wrongIcon = post("/salon/services/batch", Map.of("services", List.of(service("Haircut", 30, 15000, null),
                rocket)), newOwner);
        assertThat(wrongIcon.status()).isEqualTo(400);
        assertThat(wrongIcon.text()).contains("services[1].icon");
        Map<String, Object> typedIn = service("Hair wash", 15, 5000, 0);
        typedIn.put("icon", "HAIR_WASH");   // a service typed in at registration keeps the picture picked for it
        Reply added = post("/salon/services/batch", Map.of("services", List.of(service("Haircut", 30, 15000, null),
                typedIn)), newOwner);
        assertThat(added.status()).as(added.text()).isEqualTo(201);
        assertThat((List<?>) added.body()).extracting(s -> field(s, "icon")).containsExactly(null, "HAIR_WASH");
        assertThat((List<?>) added.body()).extracting(s -> field(s, "returnAfterDays")).containsExactly(14, 0);
        // In the order sent, after any the salon has (not alphabetical: Haircut is the main one)
        assertThat((List<?>) added.body()).extracting(s -> field(s, "sortOrder")).containsExactly(0, 1);
        // One already saved (a retry after a lost reply): 409 and nothing more saved
        Reply again = post("/salon/services/batch", Map.of("services", List.of(service("Facial", 45, 40000, null),
                service("Haircut", 30, 15000, null))), newOwner);
        assertThat(field(again.body(), "code")).as(again.text()).isEqualTo("SERVICE_NAME_TAKEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM salon_services WHERE shop_id = ?", Integer.class, draft))
                .isEqualTo(2);

        // Opening hours: Sunday off
        Reply none = get("/salon/hours", newOwner);
        assertThat(field(none.body(), "version")).isNull();
        assertThat((List<?>) field(none.body(), "days")).isEmpty();
        List<Map<String, Object>> sixDays = new ArrayList<>(week("09:00", "20:00").subList(0, 6));
        Reply set = put("/salon/hours", Map.of("days", sixDays), newOwner);
        assertThat(set.status()).as(set.text()).isEqualTo(200);
        assertThat((List<?>) field(set.body(), "days")).hasSize(6);
        // A second save from the same old screen
        Reply stale = put("/salon/hours", Map.of("days", sixDays), newOwner);
        assertThat(field(stale.body(), "code")).isEqualTo("HOURS_CHANGED");
        Reply twiceADay = put("/salon/hours", Map.of("version", field(set.body(), "version"), "days",
                List.of(day(1, "09:00", "13:00"), day(1, "14:00", "20:00"))), newOwner);
        assertThat(twiceADay.status()).isEqualTo(400);
        assertThat(put("/salon/hours", Map.of("version", field(set.body(), "version"), "days", List.of()),
                newOwner).status()).isEqualTo(400);

        // The document: a REG_DOC upload of this salon only
        UUID photo = file(draft, newOwner, "SHOP_PHOTO", "image/jpeg");
        Reply wrong = put("/shops/me/registration-document", Map.of("version", version(draft), "type",
                "TRADE_LICENCE", "fileId", photo.toString()), newOwner);
        assertThat(wrong.status()).isEqualTo(400);
        UUID doc = file(draft, newOwner, "REG_DOC", "application/pdf");
        // Only a trade licence counts now
        Reply otherKind = put("/shops/me/registration-document", Map.of("version", version(draft), "type",
                "SHOPS_ESTABLISHMENT", "fileId", doc.toString()), newOwner);
        assertThat(otherKind.status()).isEqualTo(400);
        assertThat(otherKind.text()).contains("type");
        Reply docSet = put("/shops/me/registration-document", Map.of("version", version(draft), "type",
                "TRADE_LICENCE", "fileId", doc.toString()), newOwner);
        assertThat(docSet.status()).as(docSet.text()).isEqualTo(200);
        assertThat(field(docSet.body(), "registrationDocType")).isEqualTo("TRADE_LICENCE");
        assertThat(field(docSet.body(), "registrationDocFileId")).isEqualTo(doc.toString());
        assertThat(field(docSet.body(), "licenceCheck")).isEqualTo("PENDING");   // the job checks it
        // In use: can't be deleted
        Reply inUse = http.method(HttpMethod.DELETE).uri("/files/" + doc)
                .headers(h -> h.setBearerAuth(jwt.issue(new AuthPrincipal(newOwner, draft, "OWNER", false),
                        sessions.currentGeneration(newOwner)).token()))
                .exchange((rq, res) -> read(res));
        assertThat(field(inUse.body(), "code")).as(inUse.text()).isEqualTo("FILE_IN_USE");

        Reply submitted = post("/shops/me/submit", Map.of("version", version(draft)), newOwner);
        assertThat(field(submitted.body(), "status")).as(submitted.text()).isEqualTo("PENDING_VISIT");

        Reply detail = get("/admin/shops/" + draft, admin);
        assertThat(detail.status()).as(detail.text()).isEqualTo(200);
        Map<?, ?> regDoc = (Map<?, ?>) field(detail.body(), "registrationDoc");
        assertThat(regDoc.get("label")).isEqualTo("Trade licence");
        assertThat(regDoc.get("check")).isEqualTo("PENDING");
        assertThat((String) regDoc.get("url")).isNotBlank();
        Map<?, ?> facts = (Map<?, ?>) field(detail.body(), "salon");
        assertThat((List<?>) facts.get("hours")).hasSize(6);
        assertThat((List<?>) facts.get("serviceList")).extracting(s -> field(s, "name"))
                .containsExactly("Haircut", "Hair wash");

        // Approved: the visit doesn't decide on the licence (the automatic check does)
        assertThat(post("/admin/shops/" + draft + "/approve", Map.of("version", version(draft)), admin).status())
                .isEqualTo(200);
        Reply mine = get("/shops/me", newOwner);
        assertThat(field(mine.body(), "registrationDocCheckedAt")).isNull();
        assertThat(field(mine.body(), "licenceCheck")).isEqualTo("PENDING");
    }

    @Test
    void aLiveSalonChangesItsLicenceAndStaysLiveLicensedOnlyWhileItsCheckPassedAndItIsValid() {
        assertThat(card().get("registered")).isEqualTo(false);
        UUID licence = file(salon, owner, "REG_DOC", "image/jpeg");
        Reply added = put("/shops/me/registration-document", Map.of("version", version(salon), "type",
                "TRADE_LICENCE", "fileId", licence.toString()), owner);
        assertThat(field(added.body(), "status")).as(added.text()).isEqualTo("APPROVED");   // stays live
        assertThat(field(added.body(), "licenceCheck")).isEqualTo("PENDING");
        assertThat(card().get("registered")).isEqualTo(false);   // not checked yet
        // The field team's manual check is gone (the job checks it)
        assertThat(post("/admin/shops/" + salon + "/registration-document/check", Map.of("version", version(salon)),
                admin).status()).isNotEqualTo(200);
        assertThat(get("/admin/shops/documents-to-check", admin).status()).isNotEqualTo(200);

        // Passed (as LicenceCheckJob writes it): "Licensed" until its last valid day, then no badge
        jdbc.update("UPDATE shops SET licence_check_status = 'PASSED', licence_number = 'TL-1', "
                + "licence_valid_until = '2026-11-10', registration_doc_checked_at = now() WHERE id = ?", salon);
        assertThat(card().get("registered")).isEqualTo(true);
        CLOCK.set(TUESDAY_10_00.plus(Duration.ofDays(1)));
        assertThat(card().get("registered")).isEqualTo(false);   // expired
        CLOCK.set(TUESDAY_10_00);
        Map<?, ?> doc = (Map<?, ?>) field(get("/admin/shops/" + salon, admin).body(), "registrationDoc");
        assertThat(doc.get("check")).isEqualTo("PASSED");
        assertThat(doc.get("licenceNumber")).isEqualTo("TL-1");

        // The field team takes a wrong one off, with the reason the owner is told
        Reply removed = post("/admin/shops/" + salon + "/registration-document/remove", Map.of("version",
                version(salon), "reason", "This licence is for another shop."), admin);
        assertThat(removed.status()).as(removed.text()).isEqualTo(200);
        Reply mine = get("/shops/me", owner);
        assertThat(field(mine.body(), "registrationDocFileId")).isNull();
        assertThat(field(mine.body(), "licenceCheck")).isNull();
        assertThat(card().get("registered")).isEqualTo(false);
        assertThat(count("notification_jobs WHERE type = 'SHOP_DOCUMENT_REMOVED' AND url = '/setup/document' "
                + "AND user_id = '" + owner + "'")).isPositive();
        assertThat(field(post("/admin/shops/" + salon + "/registration-document/remove", Map.of("version",
                version(salon), "reason", "Again please"), admin).body(), "code")).isEqualTo("NO_DOCUMENT");

        // The owner adds one again, then removes it himself
        put("/shops/me/registration-document", Map.of("version", version(salon), "type", "TRADE_LICENCE", "fileId",
                licence.toString()), owner);
        Reply gone = call(HttpMethod.DELETE, "/shops/me/registration-document?version=" + version(salon), null, owner,
                null);
        assertThat(gone.status()).as(gone.text()).isEqualTo(200);
        assertThat(field(gone.body(), "registrationDocType")).isNull();
        assertThat(field(gone.body(), "licenceCheck")).isNull();
        assertThat(field(gone.body(), "status")).isEqualTo("APPROVED");
        assertThat(call(HttpMethod.DELETE, "/shops/me/registration-document?version=0", null, owner, null).status())
                .isEqualTo(409);   // stale version
    }

    @Test
    void theDocumentIsTheOwnersAndOnlyForSalons() {
        UUID helper = person();
        TestData.member(jdbc, salon, helper, "HELPER");
        UUID doc = file(salon, owner, "REG_DOC", "application/pdf");
        Reply byHelper = put("/shops/me/registration-document", Map.of("version", version(salon), "type",
                "TRADE_LICENCE",
                "fileId", doc.toString()), helper);
        assertThat(byHelper.status()).isEqualTo(403);
        assertThat(get("/files/" + doc + "/link", helper).status()).isEqualTo(403);
        // A barber never sees it on My shop; a helper doesn't either
        put("/shops/me/registration-document", Map.of("version", version(salon), "type", "TRADE_LICENCE", "fileId",
                doc.toString()), owner);
        assertThat(field(get("/shops/me", helper).body(), "registrationDocFileId")).isNull();
        assertThat(field(get("/shops/me", helper).body(), "licenceCheck")).isNull();

        UUID kiranaOwner = person();
        UUID kirana = UUID.randomUUID();
        TestData.shop(jdbc, kirana, kiranaOwner);
        UUID kiranaDoc = file(kirana, kiranaOwner, "REG_DOC", "application/pdf");
        Reply notSalon = put("/shops/me/registration-document", Map.of("version", version(kirana), "type",
                "TRADE_LICENCE",
                "fileId", kiranaDoc.toString()), kiranaOwner);
        assertThat(field(notSalon.body(), "code")).isEqualTo("NOT_A_SALON");
    }

    @Test
    void barbersWorkOnlyInsideTheSalonsHoursAndNarrowerHoursCutThemWhenAsked() {
        Reply outside = put("/salon/staff/" + raju + "/hours", Map.of("version", staffVersion(raju), "hours",
                List.of(Map.of("weekday", 1, "start", "09:00", "end", "18:00"))), owner);
        assertThat(outside.status()).isEqualTo(400);
        assertThat(outside.text()).contains("outside the salon's hours");

        String version = (String) field(get("/salon/hours", owner).body(), "version");
        List<Map<String, Object>> shorter = new ArrayList<>(week("10:00", "20:00").subList(0, 6));   // Sunday off
        Reply refused = put("/salon/hours", Map.of("version", version, "days", shorter), owner);
        assertThat(field(refused.body(), "code")).isEqualTo("STAFF_HOURS_OUTSIDE");
        assertThat(refused.text()).contains("Raju");

        // A booking at 20:00 would be stranded: nothing changes
        UUID customer = person();
        UUID late = id(book(customer, at("20:00"), haircut));
        Reply blocked = put("/salon/hours", Map.of("version", version, "days", shorter, "trimStaffHours", true), owner);
        assertThat(field(blocked.body(), "code")).isEqualTo("BOOKINGS_IN_THE_WAY");
        assertThat(field(get("/salon/hours", owner).body(), "version")).isEqualTo(version);

        assertThat(post("/bookings/" + late + "/cancel", Map.of(), customer).status()).isEqualTo(200);
        Reply cut = put("/salon/hours", Map.of("version", version, "days", shorter, "trimStaffHours", true), owner);
        assertThat(cut.status()).as(cut.text()).isEqualTo(200);
        assertThat(jdbc.queryForList("SELECT weekday || ' ' || start_min || '-' || end_min FROM staff_hours "
                + "WHERE staff_id = ? ORDER BY weekday", String.class, raju))
                .containsExactly("1 600-1200", "2 600-1200", "3 600-1200", "4 600-1200", "5 600-1200", "6 600-1200");
        // The customer's salon page shows the salon's hours, Sunday left out
        assertThat((List<?>) field(get("/salons/" + salon, person()).body(), "hours")).hasSize(6);
    }

    // ---------- the salon list's cards ----------

    @Test
    void aCardShowsRatingsServicesTodaysHoursAndWhetherItIsOpen() {
        Map<?, ?> card = card();
        assertThat(card.get("addressLine")).isEqualTo("Shop 4, Main Road");
        assertThat((List<?>) card.get("topServices")).extracting(s -> field(s, "name"))
                .containsExactly("Haircut", "Beard trim");   // in the order added; Facial: no barber does it
        assertThat(card.get("serviceCount")).isEqualTo(2);
        assertThat(card.get("openNow")).isEqualTo(true);
        assertThat(card.get("closedToday")).isEqualTo(false);
        assertThat(field(card.get("today"), "close")).isEqualTo("21:00");
        assertThat(field(card.get("rating"), "count")).isEqualTo(0);
        assertThat(field(card.get("rating"), "average")).isNull();
        assertThat(card.get("registered")).isEqualTo(false);   // no registration document
        UUID doc = file(salon, owner, "REG_DOC", "application/pdf");
        jdbc.update("UPDATE shops SET registration_doc_type = 'TRADE_LICENCE', registration_doc_file_id = ?, "
                + "registration_doc_checked_at = now(), licence_check_status = 'PASSED', licence_number = 'TL-9', "
                + "licence_valid_until = '2027-03-31' WHERE id = ?", doc, salon);
        assertThat(card().get("registered")).isEqualTo(true);

        CLOCK.set(at("21:30"));
        assertThat(card().get("openNow")).isEqualTo(false);
        CLOCK.set(TUESDAY_10_00);

        // No working barber (the only one switched off): closed today, whatever the opening hours say
        jdbc.update("UPDATE salon_staff SET active = false WHERE id = ?", raju);
        assertThat(card().get("closedToday")).isEqualTo(true);
        assertThat(card().get("openNow")).isEqualTo(false);
        jdbc.update("UPDATE salon_staff SET active = true WHERE id = ?", raju);

        // A closed day (holiday) today
        Reply closed = post("/salon/closures", Map.of("date", "2026-11-10"), owner);
        assertThat(closed.status()).as(closed.text()).isEqualTo(201);
        Map<?, ?> holiday = card();
        assertThat(holiday.get("closedToday")).isEqualTo(true);
        assertThat(holiday.get("openNow")).isEqualTo(false);
        assertThat(holiday.get("today")).isNull();
        assertThat(field(get("/salons/" + salon, person()).body(), "closedDates")).isEqualTo(List.of("2026-11-10"));
    }

    @Test
    void theAverageShowsFromFiveRatings() {
        for (int i = 1; i <= 5; i++) {
            UUID customer = person();
            UUID b = completedVisit(customer, at("11:00").plus(Duration.ofMinutes(30L * i)));
            Reply rated = post("/bookings/" + b + "/rating", Map.of("stars", i == 1 ? 3 : 5,
                    "tags", List.of("CLEAN")), customer);
            assertThat(rated.status()).as(rated.text()).isEqualTo(200);
            Object average = field(field(card(), "rating"), "average");
            if (i < 5) {
                assertThat(average).isNull();
            } else {
                assertThat(average).isEqualTo(4.6);
            }
        }
        Map<?, ?> page = (Map<?, ?>) get("/salons/" + salon, person()).body();
        assertThat(field(page.get("rating"), "count")).isEqualTo(5);
        assertThat((List<?>) field(page.get("rating"), "tags")).extracting(t -> field(t, "tag"))
                .containsExactly("CLEAN");
    }

    // ---------- ratings ----------

    @Test
    void aCompletedVisitCanBeRatedAndChangedForFourteenDaysAndTheSalonSeesTheComment() {
        UUID customer = person();
        UUID b = id(book(customer, at("11:00"), haircut));
        Reply tooSoon = post("/bookings/" + b + "/rating", Map.of("stars", 5), customer);
        assertThat(field(tooSoon.body(), "code")).isEqualTo("NOT_RATEABLE");
        assertThat(field(get("/bookings/" + b, customer).body(), "rateUntil")).isNull();

        complete(b);
        Map<?, ?> done = (Map<?, ?>) get("/bookings/" + b, customer).body();
        assertThat(done.get("rateUntil")).isNotNull();
        assertThat(done.get("rating")).isNull();
        assertThat(post("/bookings/" + b + "/rating", Map.of("stars", 5), person()).status()).isEqualTo(404);
        assertThat(post("/bookings/" + b + "/rating", Map.of("stars", 6), customer).status()).isEqualTo(400);

        Reply first = post("/bookings/" + b + "/rating", Map.of("stars", 2, "tags", List.of("ON_TIME"),
                "comment", "Waited 20 minutes"), customer);
        assertThat(first.status()).as(first.text()).isEqualTo(200);
        assertThat(field(field(first.body(), "rating"), "stars")).isEqualTo(2);
        Reply changed = post("/bookings/" + b + "/rating", Map.of("stars", 4, "tags", List.of("SKILLED", "CLEAN")),
                customer);
        assertThat(field(field(changed.body(), "rating"), "tags")).isEqualTo(List.of("CLEAN", "SKILLED"));
        assertThat(field(field(changed.body(), "rating"), "comment")).isNull();
        // The totals moved by the difference: still one rating
        assertThat(jdbc.queryForMap("SELECT ratings, stars_sum, on_time, clean, skilled FROM salon_rating_totals"))
                .containsEntry("ratings", 1).containsEntry("stars_sum", 4L).containsEntry("on_time", 0)
                .containsEntry("clean", 1).containsEntry("skilled", 1);

        post("/bookings/" + b + "/rating", Map.of("stars", 4, "comment", "Good cut, thank you"), customer);
        Map<?, ?> page = (Map<?, ?>) get("/salon/ratings", owner).body();
        assertThat(field(page.get("summary"), "average")).isEqualTo(4.0);   // the salon sees it from the first
        Map<?, ?> item = (Map<?, ?>) ((List<?>) page.get("items")).get(0);
        assertThat(item.get("comment")).isEqualTo("Good cut, thank you");
        assertThat(item.get("services")).isEqualTo(List.of("Haircut"));
        assertThat(item.get("customerName")).isEqualTo("Test person");
        UUID barber = person();
        jdbc.update("UPDATE salon_staff SET user_id = ? WHERE id = ?", barber, raju);
        TestData.member(jdbc, salon, barber, "BARBER");
        assertThat(get("/salon/ratings", barber).status()).isEqualTo(403);

        // Fourteen days after the visit: closed
        CLOCK.set(at("11:30").plus(Duration.ofDays(14)));
        assertThat(field(get("/bookings/" + b, customer).body(), "rateUntil")).isNull();
        assertThat(field(post("/bookings/" + b + "/rating", Map.of("stars", 1), customer).body(), "code"))
                .isEqualTo("RATING_CLOSED");

        // A deleted account's comment goes; the stars stay in the totals
        tx.executeWithoutResult(s -> deletionListeners.forEach(l -> l.accountDeleted(customer, CLOCK.instant())));
        assertThat(jdbc.queryForObject("SELECT comment FROM salon_ratings", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT ratings FROM salon_rating_totals", Integer.class)).isEqualTo(1);
        // ... and nothing more is sent about their visits
        assertThat(count("visit_follow_ups WHERE customer_id = '" + customer + "'")).isZero();
        assertThat(count("salon_customer_prefs WHERE user_id = '" + customer + "'")).isZero();
    }

    @Test
    void aWalkInInsideAnOffersHoursGetsItsPriceAndThePricesSayItBeforehand() {
        // Today (Tuesday) 10:00-12:00, 20% off the haircut only
        Reply published = post("/salon/offers", offer(20, List.of(haircut), "2026-11-10", "2026-11-10", "10:00", "12:00"),
                owner);
        assertThat(published.status()).as(published.text()).isEqualTo(201);
        UUID offerId = UUID.fromString((String) field(field(published.body(), "offer"), "id"));
        CLOCK.set(at("10:00"));   // its first minute
        assertThat(byService(get("/salon/walk-ins/prices", owner).body(), haircut).get("pricePaise")).isEqualTo(12000);
        CLOCK.set(at("10:15"));
        Reply prices = get("/salon/walk-ins/prices", owner);
        assertThat(prices.status()).as(prices.text()).isEqualTo(200);
        Map<?, ?> cut = byService(prices.body(), haircut);
        Map<?, ?> trim = byService(prices.body(), beard);
        assertThat(cut.get("pricePaise")).isEqualTo(12000);   // the offer price now, till 12:00
        assertThat(cut.get("listPricePaise")).isEqualTo(15000);
        assertThat(cut.get("offerUntil")).isEqualTo("12:00");
        assertThat(trim.get("pricePaise")).isEqualTo(8000);   // not in the offer
        assertThat(trim.get("listPricePaise")).isNull();
        assertThat(trim.get("offerUntil")).isNull();

        // The screen showed the own prices (read before the offer started): nothing is added, and the new total is said
        Reply stale = call(HttpMethod.POST, "/salon/walk-ins", Map.of("staffId", raju.toString(), "serviceIds",
                List.of(haircut, beard), "expectedPricePaise", 23000), owner, UUID.randomUUID());
        assertThat(stale.status()).as(stale.text()).isEqualTo(409);
        assertThat(field(stale.body(), "code")).isEqualTo("PRICE_CHANGED");
        assertThat(field(field(stale.body(), "details"), "pricePaise")).isEqualTo(20000);
        assertThat(count("appointments")).isZero();

        Reply walkIn = call(HttpMethod.POST, "/salon/walk-ins", Map.of("staffId", raju.toString(), "serviceIds",
                List.of(haircut, beard), "expectedPricePaise", 20000), owner, UUID.randomUUID());
        assertThat(walkIn.status()).as(walkIn.text()).isEqualTo(201);
        assertThat(jdbc.queryForList("SELECT price_paise || '/' || coalesce(list_price_paise::text, '-') FROM "
                + "appointment_services ORDER BY position", String.class)).containsExactly("12000/15000", "8000/-");
        // The owner's offer list counts online bookings only, not walk-ins priced with it
        Map<?, ?> mine = (Map<?, ?>) ((List<?>) get("/salon/offers", owner).body()).stream()
                .filter(o -> offerId.toString().equals(field(field(o, "offer"), "id"))).findFirst().orElseThrow();
        assertThat(mine.get("bookings")).isEqualTo(0);

        // From its last minute (12:00 is no longer in it): the walk-in pays the service's own price, and the prices say so
        CLOCK.set(at("12:00"));
        Map<?, ?> later = byService(get("/salon/walk-ins/prices", owner).body(), haircut);
        assertThat(later.get("pricePaise")).isEqualTo(15000);
        assertThat(later.get("listPricePaise")).isNull();
        assertThat(later.get("offerUntil")).isNull();
        // A customer can't read the salon's walk-in prices
        assertThat(get("/salon/walk-ins/prices", person()).status()).isIn(403, 404);
    }

    @Test
    void aWalkInAddedWhileTheOwnerEndsTheOfferWaitsAndPaysTheOwnPrice() throws Exception {
        Reply published = post("/salon/offers", offer(20, List.of(haircut), "2026-11-10", "2026-11-10", "10:00", "12:00"),
                owner);
        assertThat(published.status()).as(published.text()).isEqualTo(201);
        UUID offerId = UUID.fromString((String) field(field(published.body(), "offer"), "id"));
        CLOCK.set(at("10:15"));
        get("/salon/walk-ins/prices", owner);   // the setup with the offer, in memory
        java.util.concurrent.CountDownLatch ending = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            // The owner's "End offer" has ended it but not yet committed when the walk-in arrives
            java.util.concurrent.Future<?> end = threads.submit(() -> tx.executeWithoutResult(s -> {
                offers.end(owner, offerId);
                ending.countDown();
                try {
                    release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(ending.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            java.util.concurrent.Future<Reply> walkIn = threads.submit(() -> call(HttpMethod.POST, "/salon/walk-ins",
                    Map.of("staffId", raju.toString(), "serviceIds", List.of(haircut)), owner, UUID.randomUUID()));
            for (int i = 0; i < 100 && waitingForTheShop() < 1; i++) {
                Thread.sleep(50);
            }
            assertThat(waitingForTheShop()).isEqualTo(1);   // it waits for the end instead of reading the offer
            release.countDown();
            end.get(30, java.util.concurrent.TimeUnit.SECONDS);
            Reply added = walkIn.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(added.status()).as(added.text()).isEqualTo(201);
        } finally {
            release.countDown();
            threads.shutdown();
        }
        assertThat(jdbc.queryForList("SELECT price_paise || '/' || coalesce(offer_id::text, '-') FROM "
                + "appointment_services", String.class)).containsExactly("15000/-");
    }

    private int waitingForTheShop() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                + "AND query ILIKE '%from shops%for share%'", Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void customersSeeEachServicesIconAndNoneForOneSavedBefore() {
        Reply set = call(HttpMethod.PATCH, "/salon/services/" + haircut, Map.of("version", 0, "icon", "HAIRCUT"), owner,
                null);
        assertThat(set.status()).as(set.text()).isEqualTo(200);
        Reply services = get("/salons/" + salon + "/services", person());
        assertThat(services.status()).as(services.text()).isEqualTo(200);
        assertThat(byId(services.body(), haircut).get("icon")).isEqualTo("HAIRCUT");
        assertThat(byId(services.body(), beard).get("icon")).isNull();   // the app picks one from the name
        assertThat(byId(get("/salon/services", owner).body(), haircut).get("icon")).isEqualTo("HAIRCUT");
    }

    @Test
    void aWalkInIsNotRatedAndHasNoFollowUps() {
        Reply walkIn = call(HttpMethod.POST, "/salon/walk-ins", Map.of("staffId", raju.toString(), "serviceIds",
                List.of(haircut)), owner, UUID.randomUUID());
        assertThat(walkIn.status()).as(walkIn.text()).isEqualTo(201);
        CLOCK.set(at("11:00"));
        autoEnd.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit_follow_ups", Integer.class)).isZero();
    }

    // ---------- after the visit ----------

    @Test
    void tenMinutesAfterTheVisitTheCustomerIsAskedToRateItUnlessTheyAlreadyDid() {
        UUID asked = person();
        UUID quick = person();
        UUID one = completedVisit(asked, at("11:00"));
        UUID two = completedVisit(quick, at("11:30"));
        post("/bookings/" + two + "/rating", Map.of("stars", 5), quick);
        // Rating took its request off the queue (and waits for a round that holds it: see BookingService.rate)
        assertThat(count("visit_follow_ups WHERE prompt_at IS NOT NULL AND appointment_id = '" + two + "'")).isZero();
        Instant completed = moment("completed_at", one);
        assertThat(moment("prompt_at", one)).isEqualTo(completed.plusSeconds(600));
        // Haircut: back after 21 days, at 10:00 India time
        assertThat(moment("remind_at", one)).isEqualTo(at("10:00").plus(Duration.ofDays(21)));

        CLOCK.set(at("11:35"));
        assertThat(followUps.runOnce()).isZero();   // not yet
        CLOCK.set(at("12:15"));
        assertThat(followUps.runOnce()).isEqualTo(1);
        Map<String, Object> job = jdbc.queryForMap("SELECT user_id, title, body, url FROM notification_jobs "
                + "WHERE type = 'BOOKING_RATE_VISIT'");
        assertThat(job.get("user_id")).isEqualTo(asked);
        assertThat(job.get("url")).isEqualTo("/bookings/" + one + "?rate=1");
        assertThat((String) job.get("body")).contains("Rate your Haircut at Sharma Hair");
        assertThat(followUps.runOnce()).isZero();   // each only once
    }

    @Test
    void theComeBackReminderGoesAtTenOnTheDayUnlessTheCustomerBookedVisitedAgainOrSwitchedItOff() {
        UUID plain = person();
        UUID optedOut = person();
        UUID booked = person();
        UUID returned = person();
        UUID a = completedVisit(plain, at("11:00"));
        completedVisit(optedOut, at("11:30"));
        completedVisit(booked, at("12:00"));
        completedVisit(returned, at("12:30"));
        Reply off = put("/me/salon-preferences", Map.of("comeBackReminders", false), optedOut);
        assertThat(field(off.body(), "comeBackReminders")).isEqualTo(false);
        assertThat(field(get("/me/salon-preferences", optedOut).body(), "comeBackReminders")).isEqualTo(false);
        assertThat(field(get("/me/salon-preferences", plain).body(), "comeBackReminders")).isEqualTo(true);

        Instant due = at("10:00").plus(Duration.ofDays(21));
        // The returning customer had another haircut the week after (its own reminder comes 3 weeks after that)
        CLOCK.set(at("11:00").plus(Duration.ofDays(7)));
        completedVisit(returned, CLOCK.instant().plus(Duration.ofHours(1)));
        // The booked one has a booking coming up when the reminder is due
        CLOCK.set(due.minus(Duration.ofHours(2)));
        id(book(booked, due.plus(Duration.ofHours(5)), haircut));

        // At 08:00 India time nothing goes out, even if due (the hours are 9 to 21)
        CLOCK.set(due.minus(Duration.ofHours(2)));
        jdbc.update("UPDATE visit_follow_ups SET prompt_at = NULL");
        assertThat(followUps.runOnce()).isZero();
        CLOCK.set(due);
        followUps.runOnce();
        List<Map<String, Object>> sent = jdbc.queryForList("SELECT user_id, title, body, url FROM notification_jobs "
                + "WHERE type = 'BOOKING_COME_BACK' AND channel = 'PUSH'");
        assertThat(sent).extracting(r -> r.get("user_id")).containsExactly(plain);
        assertThat(sent.get(0).get("title")).isEqualTo("Time for your next Haircut?");
        assertThat((String) sent.get(0).get("body")).contains("It's been 3 weeks since your Haircut at Sharma Hair");
        assertThat(sent.get(0).get("url")).isEqualTo("/salons/" + salon + "?services=" + haircut);
        assertThat(count("visit_follow_ups WHERE remind_at IS NOT NULL AND appointment_id = '" + a + "'")).isZero();
        assertThat(count("notification_jobs WHERE type = 'BOOKING_COME_BACK' AND channel = 'SMS'")).isZero();
    }

    @Test
    void aVisitEndingLateInTheEveningIsAskedAboutTheNextMorning() {
        UUID customer = person();
        UUID b = completedVisit(customer, at("20:30"));
        CLOCK.set(at("21:15"));
        assertThat(followUps.runOnce()).isZero();
        CLOCK.set(at("09:00").plus(Duration.ofDays(1)));
        assertThat(followUps.runOnce()).isEqualTo(1);
        assertThat(count("notification_jobs WHERE type = 'BOOKING_RATE_VISIT' AND ref_id = '" + b + "'"))
                .isPositive();
    }

    @Test
    void theReminderIsAboutTheServicesDueAndIsDroppedWhenTheSalonIsNoLongerLive() {
        UUID both = person();
        UUID other = person();
        // Haircut every 21 days, beard trim every 7: the reminder comes after 7 days, about the beard trim only
        UUID a = completedVisit(both, at("11:00"), haircut, beard);
        completedVisit(other, at("12:00"), beard);
        assertThat(moment("remind_at", a)).isEqualTo(at("10:00").plus(Duration.ofDays(7)));
        jdbc.update("UPDATE visit_follow_ups SET prompt_at = NULL");
        CLOCK.set(at("10:00").plus(Duration.ofDays(7)));
        jdbc.update("UPDATE visit_follow_ups SET remind_at = NULL WHERE customer_id = ?", other);
        followUps.runOnce();
        Map<String, Object> sent = jdbc.queryForMap("SELECT title, url FROM notification_jobs "
                + "WHERE type = 'BOOKING_COME_BACK' AND channel = 'PUSH'");
        assertThat(sent.get("title")).isEqualTo("Time for your next Beard trim?");
        assertThat(sent.get("url")).isEqualTo("/salons/" + salon + "?services=" + beard);

        // The owner lengthens the beard trim to 10 days after a reminder was scheduled for day 7: it still goes
        UUID third = person();
        UUID c = completedVisit(third, at("13:00").plus(Duration.ofDays(7)), beard);
        jdbc.update("UPDATE visit_follow_ups SET prompt_at = NULL");
        jdbc.update("UPDATE salon_services SET return_after_days = 10 WHERE id = ?", beard);
        CLOCK.set(at("10:00").plus(Duration.ofDays(14)));
        assertThat(moment("remind_at", c)).isEqualTo(at("10:00").plus(Duration.ofDays(14)));
        assertThat(followUps.runOnce()).isEqualTo(1);
        assertThat(count("notification_jobs WHERE type = 'BOOKING_COME_BACK' AND channel = 'PUSH' AND user_id = '"
                + third + "'")).isEqualTo(1);

        // The other customer's reminder is due while the salon is back with the field team: not sent
        jdbc.update("UPDATE visit_follow_ups SET remind_at = ? WHERE customer_id = ?",
                java.sql.Timestamp.from(CLOCK.instant()), other);
        jdbc.update("UPDATE shops SET status = 'PENDING_VISIT' WHERE id = ?", salon);
        assertThat(followUps.runOnce()).isZero();
        assertThat(count("visit_follow_ups WHERE remind_at IS NOT NULL")).isZero();
    }

    @Test
    void someoneWorkingInTheSalonCannotRateIt() {
        UUID helper = person();
        UUID b = completedVisit(helper, at("11:00"));
        TestData.member(jdbc, salon, helper, "HELPER");
        CLOCK.set(at("12:00"));
        assertThat(followUps.runOnce()).isZero();   // not asked to rate it either
        Reply own = post("/bookings/" + b + "/rating", Map.of("stars", 5), helper);
        assertThat(own.status()).isEqualTo(409);
        assertThat(field(own.body(), "code")).isEqualTo("OWN_SALON");
        assertThat(field(get("/bookings/" + b, helper).body(), "rateUntil")).isNull();   // not offered either
        assertThat(count("salon_ratings")).isZero();
    }

    @Test
    void noRatingRequestForASuspendedSalonOrADeletedAccountNoReminderWhileTheSalonIsPaused() {
        UUID gone = person();
        UUID stays = person();
        UUID paused = person();
        completedVisit(gone, at("11:00"));
        completedVisit(stays, at("11:30"));
        UUID p = completedVisit(paused, at("12:00"));
        tx.executeWithoutResult(s -> deletionListeners.forEach(l -> l.accountDeleted(gone, CLOCK.instant())));
        jdbc.update("UPDATE users SET deleted_at = now(), phone = NULL WHERE id = ?", gone);
        jdbc.update("UPDATE visit_follow_ups SET prompt_at = NULL WHERE customer_id = ?", paused);
        jdbc.update("UPDATE shops SET status = 'SUSPENDED', status_reason = 'Fake' WHERE id = ?", salon);
        CLOCK.set(at("13:00"));
        assertThat(followUps.runOnce()).isZero();
        assertThat(count("notification_jobs WHERE type = 'BOOKING_RATE_VISIT'")).isZero();

        // Live again, but paused for online bookings when the reminder is due: not sent
        jdbc.update("UPDATE shops SET status = 'APPROVED', status_reason = NULL WHERE id = ?", salon);
        Instant due = moment("remind_at", p);
        jdbc.update("INSERT INTO salon_pauses (shop_id, paused_until, updated_by, updated_at) VALUES (?, ?, ?, now())",
                salon, java.sql.Timestamp.from(due.plus(Duration.ofDays(1))), owner);
        jdbc.update("UPDATE visit_follow_ups SET remind_at = NULL WHERE appointment_id <> ?", p);
        CLOCK.set(due);
        assertThat(followUps.runOnce()).isZero();
        assertThat(count("visit_follow_ups WHERE remind_at IS NOT NULL")).isZero();
    }

    @Test
    void aStaleRatingRequestIsDropped() {
        UUID customer = person();
        completedVisit(customer, at("11:00"));
        CLOCK.set(at("11:45").plus(Duration.ofDays(1)));   // the job was down for over a day
        assertThat(followUps.runOnce()).isZero();
        assertThat(count("visit_follow_ups WHERE prompt_at IS NOT NULL")).isZero();
    }

    @Test
    void theSalonsRatingsComeThirtyAPageNewestFirstTheSummaryOnTheFirst() {
        // 31 ratings straight into the table (the booking flow is tested above), two at the same moment
        Instant base = at("09:00");
        for (int i = 0; i < 31; i++) {
            UUID who = person();
            UUID booking = UUID.randomUUID();
            Instant when = base.plus(Duration.ofMinutes(i == 30 ? 29 : i));   // the last two rated at once
            Instant visit = base.minus(Duration.ofDays(1)).plus(Duration.ofMinutes(40L * i));
            jdbc.update("INSERT INTO appointments (id, shop_id, staff_id, kind, customer_id, booking_no, start_code, "
                    + "starts_at, ends_at, status, created_by, created_at, updated_at) VALUES (?, ?, ?, 'BOOKING', ?, "
                    + "?, '1234', ?, ?, 'COMPLETED', ?, ?, ?)", booking, salon, raju, who, "R" + i,
                    java.sql.Timestamp.from(visit), java.sql.Timestamp.from(visit.plusSeconds(1800)), who,
                    java.sql.Timestamp.from(visit), java.sql.Timestamp.from(visit));
            jdbc.update("INSERT INTO salon_ratings (appointment_id, shop_id, customer_id, stars, tags, created_at, "
                    + "updated_at) VALUES (?, ?, ?, 4, 0, ?, ?)", booking, salon, who, java.sql.Timestamp.from(when),
                    java.sql.Timestamp.from(when));
        }
        Map<?, ?> first = (Map<?, ?>) get("/salon/ratings", owner).body();
        assertThat((List<?>) first.get("items")).hasSize(30);
        assertThat(first.get("summary")).isNotNull();
        Map<?, ?> next = (Map<?, ?>) first.get("next");
        assertThat(next).isNotNull();
        Map<?, ?> second = (Map<?, ?>) get("/salon/ratings?afterAt=" + next.get("afterAt") + "&afterId="
                + next.get("afterId"), owner).body();
        assertThat((List<?>) second.get("items")).hasSize(1);
        assertThat(second.get("summary")).isNull();
        assertThat(second.get("next")).isNull();
        assertThat(get("/salon/ratings?afterAt=" + next.get("afterAt"), owner).status()).isEqualTo(400);
    }

    @Test
    void helpersSeeButCantChangeTheHoursAndAContractBarberCanBeRemovedForGood() {
        UUID helper = person();
        TestData.member(jdbc, salon, helper, "HELPER");
        assertThat(get("/salon/hours", helper).status()).isEqualTo(200);
        assertThat(put("/salon/hours", Map.of("days", week("10:00", "20:00")), helper).status()).isEqualTo(403);

        // Raju has a booking coming up: he can't be removed until it is moved
        UUID b = id(book(person(), at("11:00"), haircut));
        Reply busy = call(HttpMethod.DELETE, "/salon/staff/" + raju, null, owner, null);
        assertThat(field(busy.body(), "code")).isEqualTo("STAFF_HAS_BOOKINGS");
        assertThat(b).isNotNull();

        UUID temp = barber("Temp", List.of(haircut), "10:00", "21:00");
        assertThat(call(HttpMethod.DELETE, "/salon/staff/" + temp, null, helper, null).status()).isEqualTo(403);
        Reply removed = call(HttpMethod.DELETE, "/salon/staff/" + temp, null, owner, null);
        assertThat(removed.status()).as(removed.text()).isEqualTo(204);
        assertThat((List<?>) get("/salon/staff", owner).body()).extracting(s -> field(s, "name"))
                .containsExactly("Raju");
        assertThat(call(HttpMethod.PATCH, "/salon/staff/" + temp, Map.of("version", 0, "active", true), owner, null)
                .status()).isEqualTo(404);   // can't be switched back on
        assertThat(call(HttpMethod.DELETE, "/salon/staff/" + temp, null, owner, null).status()).isEqualTo(404);
    }

    @Test
    void holidaysBelongToTheSalonEvenBeforeAnyBarberAndEveryBarberAddedLaterGetsThem() {
        UUID newOwner = person();
        UUID draft = UUID.randomUUID();
        TestData.salon(jdbc, draft, newOwner);
        Reply diwali = post("/salon/closures", Map.of("date", "2026-11-12"), newOwner);
        assertThat(diwali.status()).as(diwali.text()).isEqualTo(201);
        assertThat(field(diwali.body(), "barbers")).isEqualTo(0);   // no barbers yet: still closed that day
        assertThat(field(post("/salon/closures", Map.of("date", "2026-11-12"), newOwner).body(), "code"))
                .isEqualTo("ALREADY_CLOSED");

        // A barber added later is off that day too
        UUID first = id(post("/salon/staff", Map.of("name", "Imran"), newOwner));
        List<?> days = (List<?>) get("/salon/closures", newOwner).body();
        assertThat(days).extracting(d -> field(d, "date")).containsExactly("2026-11-12");
        assertThat(field(days.get(0), "barbers")).isEqualTo(1);
        assertThat(count("appointments WHERE staff_id = '" + first + "' AND block_type = 'CLOSED' "
                + "AND status = 'BLOCKED'")).isEqualTo(1);

        // Opened again: the day and its blocks go
        Reply open = call(HttpMethod.DELETE, "/salon/closures/" + field(diwali.body(), "closureId"), null, newOwner,
                null);
        assertThat(open.status()).as(open.text()).isEqualTo(204);
        assertThat((List<?>) get("/salon/closures", newOwner).body()).isEmpty();
        assertThat(count("appointments WHERE staff_id = '" + first + "' AND status = 'BLOCKED'")).isZero();
    }

    @Test
    void closingADayTwiceSaysAlreadyClosedAlsoWhenTheSalonHasBarbers() {
        Reply first = post("/salon/closures", Map.of("date", "2026-11-20"), owner);
        assertThat(first.status()).as(first.text()).isEqualTo(201);
        Reply again = post("/salon/closures", Map.of("date", "2026-11-20"), owner);
        assertThat(again.status()).isEqualTo(409);
        assertThat(field(again.body(), "code")).isEqualTo("ALREADY_CLOSED");
        assertThat(count("salon_closed_days WHERE shop_id = '" + salon + "'")).isEqualTo(1);
        assertThat(count("appointments WHERE staff_id = '" + raju + "' AND block_type = 'CLOSED'")).isEqualTo(1);
    }

    @Test
    void aClosedDayAnOlderServerLeftWithoutItsRowCanStillBeOpened() {
        // During a deploy an older copy wrote only the barbers' blocks
        UUID closure = UUID.randomUUID();
        jdbc.update("INSERT INTO appointments (id, shop_id, staff_id, kind, block_type, closure_id, starts_at, "
                + "ends_at, status, created_by, created_at, updated_at) VALUES (?, ?, ?, 'BLOCK', 'CLOSED', ?, ?, ?, "
                + "'BLOCKED', ?, now(), now())", UUID.randomUUID(), salon, raju, closure,
                java.sql.Timestamp.from(Instant.parse("2026-11-20T18:30:00Z")),
                java.sql.Timestamp.from(Instant.parse("2026-11-21T18:30:00Z")), owner);
        Reply open = call(HttpMethod.DELETE, "/salon/closures/" + closure, null, owner, null);
        assertThat(open.status()).as(open.text()).isEqualTo(204);
        assertThat(count("appointments WHERE closure_id = '" + closure + "' AND status = 'BLOCKED'")).isZero();
        assertThat(call(HttpMethod.DELETE, "/salon/closures/" + closure, null, owner, null).status()).isEqualTo(404);

        // Or the owner closes that day again: the leftover block joins the new day instead of being "in the way"
        UUID leftover = UUID.randomUUID();
        jdbc.update("INSERT INTO appointments (id, shop_id, staff_id, kind, block_type, closure_id, starts_at, "
                + "ends_at, status, created_by, created_at, updated_at) VALUES (?, ?, ?, 'BLOCK', 'CLOSED', ?, ?, ?, "
                + "'BLOCKED', ?, now(), now())", UUID.randomUUID(), salon, raju, leftover,
                java.sql.Timestamp.from(Instant.parse("2026-11-20T18:30:00Z")),
                java.sql.Timestamp.from(Instant.parse("2026-11-21T18:30:00Z")), owner);
        Reply again = post("/salon/closures", Map.of("date", "2026-11-21"), owner);
        assertThat(again.status()).as(again.text()).isEqualTo(201);
        String day = (String) field(again.body(), "closureId");
        assertThat(field(again.body(), "barbers")).isEqualTo(1);
        assertThat(count("appointments WHERE staff_id = '" + raju + "' AND block_type = 'CLOSED' "
                + "AND status = 'BLOCKED'")).isEqualTo(1);   // not a second one
        assertThat(call(HttpMethod.DELETE, "/salon/closures/" + day, null, owner, null).status()).isEqualTo(204);
        assertThat(count("appointments WHERE closure_id = '" + leftover + "' OR (closure_id = '" + day
                + "' AND status = 'BLOCKED')")).isZero();
    }

    @Test
    void theOwnerCanAlsoBeABarberAndStaysTheOwner() {
        // A festival day closed before he adds himself: he is off that day too
        assertThat(post("/salon/closures", Map.of("date", "2026-11-20"), owner).status()).isEqualTo(201);
        // The salon's opening hours (10:00-21:00 every day) and its three services, from setUp
        Reply me = post("/salon/staff/me", Map.of(), owner);
        assertThat(me.status()).as(me.text()).isEqualTo(201);
        UUID mine = UUID.fromString((String) field(me.body(), "id"));
        assertThat(jdbc.queryForObject("SELECT user_id FROM salon_staff WHERE id = ?", UUID.class, mine))
                .isEqualTo(owner);
        assertThat((List<?>) field(me.body(), "serviceIds")).hasSize(3);
        assertThat((List<?>) field(me.body(), "hours")).hasSize(7);
        assertThat(field(post("/salon/staff/me", Map.of(), owner).body(), "code")).isEqualTo("ALREADY_A_BARBER");
        assertThat(jdbc.queryForObject("SELECT role FROM shop_members WHERE user_id = ?", String.class, owner))
                .isEqualTo("OWNER");
        // Customers can now be booked with him too (any free barber)
        assertThat(count("staff_services WHERE staff_id = '" + mine + "'")).isEqualTo(3);
        assertThat(count("appointments WHERE staff_id = '" + mine + "' AND block_type = 'CLOSED' "
                + "AND status = 'BLOCKED'")).isEqualTo(1);

        // His own sign-in can't be taken off the profile (he couldn't be invited back with his own number)
        Reply unlinked = call(HttpMethod.DELETE, "/salon/staff/" + mine + "/login", null, owner, null);
        assertThat(unlinked.status()).isEqualTo(409);
        assertThat(field(unlinked.body(), "code")).isEqualTo("OWNER_PROFILE");
        // Switched off it stays his (still linked), so it can be switched on again; no second profile
        Reply off = call(HttpMethod.PATCH, "/salon/staff/" + mine, Map.of("version", staffVersion(mine), "active",
                false), owner, null);
        assertThat(off.status()).as(off.text()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT user_id FROM salon_staff WHERE id = ?", UUID.class, mine))
                .isEqualTo(owner);
        assertThat(field(post("/salon/staff/me", Map.of(), owner).body(), "code")).isEqualTo("ALREADY_A_BARBER");
        assertThat(call(HttpMethod.PATCH, "/salon/staff/" + mine, Map.of("version", staffVersion(mine), "active",
                true), owner, null).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT role FROM shop_members WHERE user_id = ?", String.class, owner))
                .isEqualTo("OWNER");
        assertThat(get("/shops/me", owner).status()).isEqualTo(200);
        // Removed, the profile lets go of him, and he can add himself again later
        assertThat(call(HttpMethod.DELETE, "/salon/staff/" + mine, null, owner, null).status()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT user_id FROM salon_staff WHERE id = ?", UUID.class, mine)).isNull();
        assertThat(post("/salon/staff/me", Map.of(), owner).status()).isEqualTo(201);
        // A helper can't add himself
        UUID helper = person();
        TestData.member(jdbc, salon, helper, "HELPER");
        assertThat(post("/salon/staff/me", Map.of(), helper).status()).isEqualTo(403);
    }

    @Test
    void finishingEarlyAlsoSchedulesTheFollowUps() {
        UUID customer = person();
        UUID b = id(book(customer, at("11:00"), haircut));
        CLOCK.set(at("11:00"));
        assertThat(post("/salon/appointments/" + b + "/start", Map.of("withoutCode", true), owner).status())
                .isEqualTo(200);
        CLOCK.set(at("11:20"));
        Reply finished = post("/salon/appointments/" + b + "/finish", Map.of(), owner);
        assertThat(finished.status()).as(finished.text()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT prompt_at FROM visit_follow_ups WHERE appointment_id = ?",
                OffsetDateTime.class, b).toInstant()).isEqualTo(at("11:30"));
    }

    // ---------- offers ----------

    @Test
    void anOfferTellsPastCustomersWhoWantItAndPricesBookingsInsideIt() {
        UUID alice = person();
        UUID bob = person();
        UUID carol = person();
        UUID dave = person();   // never visited
        UUID erin = person();   // visited, then deleted her account
        UUID frank = person();  // visited, then started working here
        completedVisit(alice, at("11:00"));
        completedVisit(bob, at("12:00"));
        completedVisit(carol, at("13:00"));
        completedVisit(erin, at("14:00"));
        completedVisit(frank, at("15:00"));
        CLOCK.set(TUESDAY_10_00);
        jdbc.update("UPDATE users SET deleted_at = now(), phone = NULL WHERE id = ?", erin);
        TestData.member(jdbc, salon, frank, "HELPER");
        // Bob stops this salon's offers; Carol stops every salon's
        Reply muted = put("/salons/" + salon + "/offer-messages", Map.of("on", false), bob);
        assertThat(muted.status()).as(muted.text()).isEqualTo(200);
        Reply off = put("/me/salon-preferences", Map.of("salonOffers", false), carol);
        assertThat(off.body()).isEqualTo(Map.of("comeBackReminders", true, "salonOffers", false));
        assertThat(put("/me/salon-preferences", Map.of(), carol).status()).isEqualTo(400);

        // Thursday to Saturday, 12:00-16:00, 20% off the haircut
        Map<String, Object> offer = offer(20, List.of(haircut), "2026-11-12", "2026-11-14", "12:00", "16:00");
        Reply preview = post("/salon/offers/preview", offer, owner);
        assertThat(preview.status()).as(preview.text()).isEqualTo(200);
        assertThat(field(preview.body(), "customers")).isEqualTo(1);   // Alice only
        assertThat(field(preview.body(), "services")).isEqualTo(List.of(Map.of("serviceId", haircut.toString(),
                "name", "Haircut", "pricePaise", 15000, "offerPricePaise", 12000, "priceIsFrom", false)));
        assertThat(post("/salon/offers/preview", offer, alice).status()).isEqualTo(404);   // not in a salon

        Reply published = post("/salon/offers", offer, owner);
        assertThat(published.status()).as(published.text()).isEqualTo(201);
        assertThat(field(published.body(), "state")).isEqualTo("UPCOMING");
        assertThat(field(published.body(), "notified")).isEqualTo(1);
        UUID offerId = UUID.fromString((String) field(field(published.body(), "offer"), "id"));
        assertThat(jdbc.queryForList("SELECT user_id FROM notification_jobs WHERE type = 'BOOKING_OFFER' AND channel = 'PUSH'",
                UUID.class)).containsExactly(alice);
        assertThat(jdbc.queryForObject("SELECT title FROM notification_jobs WHERE type = 'BOOKING_OFFER' AND channel = 'PUSH'",
                String.class)).isEqualTo("20% off at Sharma Hair");
        assertThat(jdbc.queryForObject("SELECT body FROM notification_jobs WHERE type = 'BOOKING_OFFER' AND channel = 'PUSH'",
                String.class)).isEqualTo("Haircut · 12 Nov – 14 Nov, 12:00–16:00. Book in the app.");
        assertThat(jdbc.queryForObject("SELECT url FROM notification_jobs WHERE type = 'BOOKING_OFFER' AND channel = 'PUSH'",
                String.class)).isEqualTo("/salons/" + salon + "?offer=" + offerId);
        assertThat(count("notification_jobs WHERE type = 'BOOKING_OFFER' AND channel = 'SMS'")).isZero();
        // In Alice's list in the customer app too, not only as a push
        List<?> inbox = (List<?>) field(get("/me/notifications?app=customer", alice).body(), "items");
        assertThat(inbox.stream().map(m -> field(m, "title"))).contains("20% off at Sharma Hair");
        // A helper sees the list but can't publish; a barber can't see it
        assertThat(get("/salon/offers", frank).status()).isEqualTo(200);
        assertThat(field(post("/salon/offers/preview", offer, frank).body(), "code")).isEqualTo("OWNER_ONLY");
        // A barber sees what runs and what is to come (customers ask him), without the owner's numbers
        UUID barber = person();
        jdbc.update("UPDATE salon_staff SET user_id = ? WHERE id = ?", barber, raju);
        TestData.member(jdbc, salon, barber, "BARBER");
        assertThat(get("/salon/offers", barber).status()).isEqualTo(403);
        Reply team = get("/salon/offers/current", barber);
        assertThat(team.status()).as(team.text()).isEqualTo(200);
        assertThat((List<?>) team.body()).hasSize(1);
        assertThat(field(((List<?>) team.body()).get(0), "state")).isEqualTo("UPCOMING");
        assertThat(field(field(((List<?>) team.body()).get(0), "offer"), "percentOff")).isEqualTo(20);
        assertThat(team.text()).doesNotContain("notified").doesNotContain("bookings");
        assertThat(get("/salon/offers/current", alice).status()).isEqualTo(404);   // not in a salon
        // A double tap: refused as overlapping, nobody told twice
        Reply again = post("/salon/offers", offer, owner);
        assertThat(again.status()).isEqualTo(409);
        assertThat(field(again.body(), "code")).isEqualTo("OFFER_OVERLAPS");
        assertThat(count("notification_jobs WHERE type = 'BOOKING_OFFER'")).isEqualTo(1);
        // Another service at the same time is fine
        assertThat(post("/salon/offers", offer(10, List.of(beard), "2026-11-12", "2026-11-12", null, null), owner)
                .status()).isEqualTo(201);

        // Customers: the card's badge, the page's offers and switch, the services' offer prices
        assertThat(field(card(), "offer")).isEqualTo(Map.of("percentOff", 20, "startsOn", "2026-11-12",
                "endsOn", "2026-11-14"));
        Reply page = get("/salons/" + salon, alice);
        assertThat((List<?>) field(page.body(), "offers")).hasSize(2);
        assertThat(field(page.body(), "offerMessages")).isEqualTo(true);
        assertThat(field(get("/salons/" + salon, bob).body(), "offerMessages")).isEqualTo(false);
        assertThat(field(get("/salons/" + salon, dave).body(), "offerMessages")).isNull();
        List<?> services = (List<?>) get("/salons/" + salon + "/services", dave).body();
        assertThat(field(services.get(0), "offerPricePaise")).isEqualTo(12000);
        assertThat(field(services.get(1), "offerPricePaise")).isEqualTo(7200);

        // Free times on Thursday: the offer price only inside its hours
        Reply slots = get("/salons/" + salon + "/availability?services=" + haircut + "&date=2026-11-12", dave);
        Map<String, Map<?, ?>> byTime = new HashMap<>();
        for (Object s : (List<?>) field(slots.body(), "starts")) {
            byTime.put((String) field(s, "time"), (Map<?, ?>) s);
        }
        assertThat(field(byTime.get("12:00"), "pricePaise")).isEqualTo(12000);
        assertThat(field(byTime.get("12:00"), "listPricePaise")).isEqualTo(15000);
        assertThat(field(byTime.get("15:45"), "pricePaise")).isEqualTo(12000);
        assertThat(field(byTime.get("11:45"), "pricePaise")).isEqualTo(15000);
        assertThat(field(byTime.get("11:45"), "listPricePaise")).isNull();
        assertThat(field(byTime.get("16:00"), "listPricePaise")).isNull();

        // Dave books a haircut and beard trim at 12:00 Thursday: both offers apply
        Instant thursdayNoon = LocalDate.of(2026, 11, 12).atTime(12, 0).atZone(INDIA).toInstant();
        Reply booked = book(dave, thursdayNoon, haircut, beard);
        assertThat(booked.status()).as(booked.text()).isEqualTo(201);
        assertThat(field(booked.body(), "totalPricePaise")).isEqualTo(12000 + 7200);
        assertThat(field(booked.body(), "totalListPricePaise")).isEqualTo(23000);
        assertThat(field(((List<?>) field(booked.body(), "services")).get(0), "listPricePaise")).isEqualTo(15000);
        UUID daves = id(booked);

        // The owner's list counts it; he ends the offer: new bookings pay the normal price, Dave's keeps the offer
        List<?> list = (List<?>) get("/salon/offers", owner).body();
        Map<?, ?> mine = (Map<?, ?>) list.stream().filter(o -> offerId.toString().equals(field(field(o, "offer"), "id")))
                .findFirst().orElseThrow();
        assertThat(mine.get("bookings")).isEqualTo(1);
        Reply ended = post("/salon/offers/" + offerId + "/end", Map.of(), owner);
        assertThat(ended.status()).as(ended.text()).isEqualTo(200);
        assertThat(field(ended.body(), "state")).isEqualTo("ENDED");
        assertThat(field(ended.body(), "bookings")).isEqualTo(1);
        assertThat(field(post("/salon/offers/" + offerId + "/end", Map.of(), owner).body(), "code"))
                .isEqualTo("OFFER_OVER");
        // Its message, if it hadn't gone out yet, doesn't any more
        assertThat(count("notification_jobs WHERE ref_id = '" + offerId + "' AND status = 'PENDING'")).isZero();
        assertThat(field(get("/bookings/" + daves, dave).body(), "totalPricePaise")).isEqualTo(19200);
        Reply later = book(alice, thursdayNoon.plus(Duration.ofHours(1)), haircut);
        assertThat(field(later.body(), "totalPricePaise")).isEqualTo(15000);
        assertThat(field(later.body(), "totalListPricePaise")).isNull();
        assertThat(field(card(), "offer")).isEqualTo(Map.of("percentOff", 10, "startsOn", "2026-11-12",
                "endsOn", "2026-11-12"));

        // The move screen's prices are the move's: from what Dave booked at, even after the salon raised the haircut
        assertThat(call(HttpMethod.PATCH, "/salon/services/" + haircut, Map.of("version", 0, "pricePaise", 25000),
                owner, null).status()).isEqualTo(200);
        Reply moving = get("/salons/" + salon + "/availability?services=" + haircut + "&services=" + beard
                + "&date=2026-11-12&moving=" + daves, dave);
        Object at17 = ((List<?>) field(moving.body(), "starts")).stream()
                .filter(s -> "17:00".equals(field(s, "time"))).findFirst().orElseThrow();
        assertThat(field(at17, "pricePaise")).isEqualTo(15000 + 7200);
        // Moved out of the haircut's offer hours: the haircut at its own price again, the beard trim's offer (all
        // Thursday) still applies; an offer can't be carried to any time by moving
        Reply moved = post("/bookings/" + daves + "/reschedule", Map.of("startAt",
                thursdayNoon.plus(Duration.ofHours(5)).toString()), dave);
        assertThat(moved.status()).as(moved.text()).isEqualTo(200);
        assertThat(field(moved.body(), "totalPricePaise")).isEqualTo(15000 + 7200);
        assertThat(field(moved.body(), "totalListPricePaise")).isEqualTo(23000);
        // Moved to Friday, outside every offer: the normal prices
        Reply friday = post("/bookings/" + daves + "/reschedule", Map.of("startAt",
                thursdayNoon.plus(Duration.ofDays(1)).toString()), dave);
        assertThat(field(friday.body(), "totalPricePaise")).isEqualTo(23000);
        assertThat(field(friday.body(), "totalListPricePaise")).isNull();

        // Switches back on; a deleted account's choices go
        assertThat(put("/salons/" + salon + "/offer-messages", Map.of("on", true), bob).body())
                .isEqualTo(Map.of("on", true));
        assertThat(count("salon_offer_mutes")).isZero();
        put("/salons/" + salon + "/offer-messages", Map.of("on", false), bob);
        tx.executeWithoutResult(t -> deletionListeners.forEach(l -> l.accountDeleted(bob, CLOCK.instant())));
        assertThat(count("salon_offer_mutes")).isZero();
    }

    @Test
    void anOffersHoursMustFitTheSalonsOpeningHoursOnItsOpenDays() {
        // Before opening on Thursday and Friday: refused, naming those days
        Reply early = post("/salon/offers", offer(15, List.of(), "2026-11-12", "2026-11-13", "09:00", "12:00"), owner);
        assertThat(early.status()).isEqualTo(409);
        assertThat(field(early.body(), "code")).isEqualTo("OFFER_OUTSIDE_HOURS");
        assertThat(field(early.body(), "message")).isEqualTo("The offer's hours must be inside your opening hours: "
                + "Thursday 10:00–21:00, Friday 10:00–21:00.");
        assertThat((List<?>) field(field(early.body(), "details"), "days")).hasSize(2);
        // A closed day is skipped: nothing left to offer on it
        assertThat(post("/salon/closures", Map.of("date", "2026-11-13"), owner).status()).isEqualTo(201);
        Reply closed = post("/salon/offers", offer(15, List.of(), "2026-11-13", "2026-11-13", "09:00", "12:00"), owner);
        assertThat(field(closed.body(), "code")).isEqualTo("NO_OPEN_TIME");
        // Today's hours already over
        CLOCK.set(at("10:30"));
        Reply over = post("/salon/offers", offer(15, List.of(), "2026-11-10", "2026-11-10", "10:00", "10:30"), owner);
        assertThat(field(over.body(), "code")).isEqualTo("NO_OPEN_TIME");
        // The fields
        assertThat(field(field(post("/salon/offers", offer(15, List.of(), "2026-11-09", "2026-11-10", null, null),
                owner).body(), "details"), "startsOn")).isNotNull();
        assertThat(field(field(post("/salon/offers", offer(15, List.of(), "2026-11-10", "2027-03-01", null, null),
                owner).body(), "details"), "endsOn")).isNotNull();
        assertThat(field(field(post("/salon/offers", offer(15, List.of(), "2026-11-11", "2026-11-11", "12:00", null),
                owner).body(), "details"), "until")).isNotNull();
        assertThat(post("/salon/offers", offer(60, List.of(), "2026-11-11", "2026-11-11", null, null), owner).status())
                .isEqualTo(400);
        assertThat(field(field(post("/salon/offers", offer(15, List.of(UUID.randomUUID()), "2026-11-11",
                "2026-11-11", null, null), owner).body(), "details"), "serviceIds")).isNotNull();
        assertThat(field(field(post("/salon/offers", offer(15, List.of(), "2026-11-12", "2026-11-11", null, null),
                owner).body(), "details"), "endsOn")).isNotNull();
        assertThat(field(field(post("/salon/offers", offer(15, List.of(), "2026-11-11", "2026-11-11", "12:10", "14:00"),
                owner).body(), "details"), "from")).isNotNull();
        // Customers can book a week ahead (the default): an offer must start inside that
        Reply far = post("/salon/offers", offer(15, List.of(), "2026-11-18", "2026-11-19", null, null), owner);
        assertThat(field(far.body(), "code")).isEqualTo("OFFER_TOO_FAR");
        assertThat(field(far.body(), "message")).isEqualTo("Customers can book up to 7 days ahead, so start the offer "
                + "by 17 Nov.");
        // Its last day up to 90 days ahead, not 91
        assertThat(post("/salon/offers/preview", offer(15, List.of(), "2026-11-11", "2027-02-08", null, null), owner)
                .status()).isEqualTo(200);
        assertThat(field(field(post("/salon/offers/preview", offer(15, List.of(), "2026-11-11", "2027-02-09", null,
                null), owner).body(), "details"), "endsOn")).isNotNull();
        // A switched-off service can't be in an offer
        Reply off = call(HttpMethod.PATCH, "/salon/services/" + facial, Map.of("version", 0, "active", false), owner,
                null);
        assertThat(off.status()).as(off.text()).isEqualTo(200);
        assertThat(field(field(post("/salon/offers", offer(15, List.of(facial), "2026-11-11", "2026-11-11", null, null),
                owner).body(), "details"), "serviceIds")).isNotNull();
        // Paused over its start: refused; starting after the pause: fine
        assertThat(post("/salon/pause", Map.of("until", at("12:00").plus(Duration.ofDays(1)).toString()), owner)
                .status()).isEqualTo(200);
        assertThat(field(post("/salon/offers/preview", offer(15, List.of(), "2026-11-11", "2026-11-11", "10:00",
                "21:00"), owner).body(), "code")).isEqualTo("SALON_PAUSED");
        assertThat(post("/salon/offers/preview", offer(15, List.of(), "2026-11-11", "2026-11-11", "12:00", "21:00"),
                owner).status()).isEqualTo(200);
        assertThat(post("/salon/resume", Map.of(), owner).status()).isEqualTo(200);
        // Inside the hours, every service, one day: published (no past customers: nobody told)
        Reply ok = post("/salon/offers", offer(15, List.of(), "2026-11-11", "2026-11-11", "10:00", "21:00"), owner);
        assertThat(ok.status()).as(ok.text()).isEqualTo(201);
        assertThat(field(ok.body(), "notified")).isEqualTo(0);
        assertThat(count("notification_jobs WHERE type = 'BOOKING_OFFER'")).isZero();
        // A beard-trim offer whose only service is then switched off: no badge, and not in the way of a new offer
        assertThat(post("/salon/offers", offer(30, List.of(beard), "2026-11-12", "2026-11-12", null, null), owner)
                .status()).isEqualTo(201);
        assertThat(field(post("/salon/offers/preview", offer(15, List.of(), "2026-11-12", "2026-11-12", null, null),
                owner).body(), "code")).isEqualTo("OFFER_OVERLAPS");
        assertThat(call(HttpMethod.PATCH, "/salon/services/" + beard, Map.of("version", 0, "active", false), owner,
                null).status()).isEqualTo(200);
        assertThat(post("/salon/offers/preview", offer(15, List.of(), "2026-11-12", "2026-11-12", null, null), owner)
                .status()).isEqualTo(200);
        assertThat(((Map<?, ?>) field(card(), "offer")).get("percentOff")).isEqualTo(15);   // the 15% of today
        // A salon not live can't publish one
        jdbc.update("UPDATE shops SET status = 'SUSPENDED' WHERE id = ?", salon);
        assertThat(field(post("/salon/offers", offer(15, List.of(), "2026-11-12", "2026-11-12", null, null), owner)
                .body(), "code")).isEqualTo("SALON_NOT_LIVE");
    }

    private static Map<String, Object> offer(int percent, List<UUID> services, String from, String to, String start,
                                             String end) {
        Map<String, Object> body = new HashMap<>();
        body.put("percentOff", percent);
        body.put("serviceIds", services.stream().map(UUID::toString).toList());
        body.put("startsOn", from);
        body.put("endsOn", to);
        if (start != null) {
            body.put("from", start);
        }
        if (end != null) {
            body.put("until", end);
        }
        return body;
    }

    // ---------- helpers ----------

    /** A booking of these services (default: a haircut) at this start, started at its time and ended by the job. */
    private UUID completedVisit(UUID customer, Instant start, UUID... services) {
        Instant before = CLOCK.instant();
        CLOCK.set(start.minus(Duration.ofHours(2)));
        UUID b = id(book(customer, start, services.length == 0 ? new UUID[] {haircut} : services));
        complete(b);
        if (before.isAfter(CLOCK.instant())) {
            CLOCK.set(before);
        }
        return b;
    }

    /** Starts the booking at its start time (without the code) and lets the automatic end complete it. */
    private void complete(UUID booking) {
        Instant start = jdbc.queryForObject("SELECT starts_at FROM appointments WHERE id = ?", OffsetDateTime.class,
                booking).toInstant();
        Instant end = jdbc.queryForObject("SELECT ends_at FROM appointments WHERE id = ?", OffsetDateTime.class,
                booking).toInstant();
        CLOCK.set(start);
        Reply started = post("/salon/appointments/" + booking + "/start", Map.of("withoutCode", true), owner);
        assertThat(started.status()).as(started.text()).isEqualTo(200);
        CLOCK.set(end);
        autoEnd.runOnce();
        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, booking))
                .isEqualTo("COMPLETED");
    }

    /** A follow-up time of this booking. */
    private Instant moment(String column, UUID booking) {
        return jdbc.queryForObject("SELECT " + column + " FROM visit_follow_ups WHERE appointment_id = ?",
                OffsetDateTime.class, booking).toInstant();
    }

    private Map<?, ?> card() {
        Reply list = get("/salons?lat=17.465&lng=78.364", person());
        assertThat(list.status()).as(list.text()).isEqualTo(200);
        return (Map<?, ?>) ((List<?>) list.body()).get(0);
    }

    private UUID file(UUID shop, UUID by, String purpose, String contentType) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO files (id, shop_id, purpose, storage_key, content_type, size_bytes, sha256, "
                        + "uploaded_by, created_at) VALUES (?, ?, ?, ?, ?, 10, ?, ?, now())",
                id, shop, purpose, "test/" + id, contentType, id.toString().replace("-", "") + "0".repeat(32), by);
        return id;
    }

    private long version(UUID shop) {
        return jdbc.queryForObject("SELECT version FROM shops WHERE id = ?", Long.class, shop);
    }

    private long staffVersion(UUID staff) {
        return jdbc.queryForObject("SELECT version FROM salon_staff WHERE id = ?", Long.class, staff);
    }

    private UUID person() {
        UUID id = UUID.randomUUID();
        TestData.user(jdbc, id, "92" + String.format("%08d", ++phones));
        return id;
    }

    private UUID barber(String name, List<UUID> services, String from, String to) {
        UUID id = id(post("/salon/staff", Map.of("name", name), owner));
        Reply s = put("/salon/staff/" + id + "/services", Map.of("version", 0, "serviceIds", services), owner);
        List<Map<String, Object>> hours = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            hours.add(Map.of("weekday", day, "start", from, "end", to));
        }
        Reply h = put("/salon/staff/" + id + "/hours", Map.of("version", field(s.body(), "version"), "hours", hours),
                owner);
        assertThat(h.status()).as(h.text()).isEqualTo(200);
        return id;
    }

    private static List<Map<String, Object>> week(String open, String close) {
        List<Map<String, Object>> days = new ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            days.add(day(d, open, close));
        }
        return days;
    }

    private static Map<String, Object> day(int weekday, String open, String close) {
        return Map.of("weekday", weekday, "open", open, "close", close);
    }

    private Reply book(UUID who, Instant start, UUID... services) {
        Map<String, Object> body = new HashMap<>();
        body.put("salonId", salon.toString());
        body.put("serviceIds", List.of(services));
        body.put("startAt", start.toString());
        return call(HttpMethod.POST, "/bookings", body, who, UUID.randomUUID());
    }

    private static Map<String, Object> service(String name, int minutes, long paise, Integer returnAfterDays) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("durationMinutes", minutes);
        body.put("pricePaise", paise);
        if (returnAfterDays != null) {
            body.put("returnAfterDays", returnAfterDays);
        }
        return body;
    }

    /** A moment on Tuesday 10 Nov 2026, India time. */
    private static Instant at(String hhmm) {
        return LocalDate.of(2026, 11, 10).atTime(LocalTime.parse(hhmm)).atZone(INDIA).toInstant();
    }

    private int count(String tableAndWhere) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + tableAndWhere, Integer.class);
        return n == null ? 0 : n;
    }

    private String token(UUID who) {
        boolean admin = !"NONE".equals(jdbc.queryForObject("SELECT admin_role FROM users WHERE id = ?", String.class,
                who));
        return jwt.issue(new AuthPrincipal(who, null, null, admin), sessions.currentGeneration(who)).token();
    }

    private Reply get(String path, UUID who) {
        return call(HttpMethod.GET, path, null, who, null);
    }

    private Reply post(String path, Object body, UUID who) {
        return call(HttpMethod.POST, path, body, who, null);
    }

    private Reply put(String path, Object body, UUID who) {
        return call(HttpMethod.PUT, path, body, who, null);
    }

    private Reply call(HttpMethod method, String path, Object body, UUID who, UUID key) {
        if (path.startsWith("/shops/me") && method != HttpMethod.GET) {
            TestData.unlockEditing(jdbc, redis);   // the code before editing a verified shop: tested in AdminIT
        }
        RestClient.RequestBodySpec spec = http.method(method).uri(path).headers(h -> {
            if (who != null) {
                h.setBearerAuth(token(who));
            }
            if (key != null) {
                h.set("Idempotency-Key", key.toString());
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

    private static Map<?, ?> byId(Object list, UUID id) {
        return ((List<?>) list).stream().map(x -> (Map<?, ?>) x)
                .filter(x -> id.toString().equals(x.get("id"))).findFirst().orElseThrow();
    }

    private static Map<?, ?> byService(Object list, UUID serviceId) {
        return ((List<?>) list).stream().map(x -> (Map<?, ?>) x)
                .filter(x -> serviceId.toString().equals(x.get("serviceId"))).findFirst().orElseThrow();
    }

    private static Object field(Object json, String name) {
        return ((Map<?, ?>) json).get(name);
    }

    private static UUID id(Reply r) {
        assertThat(r.status()).as(r.text()).isBetween(200, 201);
        return UUID.fromString((String) field(r.body(), "id"));
    }

    private record Reply(int status, Object body, String text) {
    }
}
