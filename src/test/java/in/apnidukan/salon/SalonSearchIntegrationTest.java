package in.apnidukan.salon;

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
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The customers' salon search (GET /salons?q=) against real Postgres: by the salon's name or a service's, in part and
 * in any case, small spelling slips forgiven, a service's picture, only services a working barber does, the same
 * radius, order and pages as the list without a search, the checks on q, and %, _ and \ taken as they are.
 *
 * The customer is at 17.466, 78.365; every salon is on that longitude, north of them, so a higher latitude is
 * further away. Each salon gets one barber with Monday hours, who does the services added with {@code done = true}.
 * Subscriptions are enforced (a lapsed salon is left out; one without a subscription row never is).
 *
 * The Hindi cases need the database's character type to be UTF-8 (the postgis image's en_US.utf8): pg_trgm counts
 * letters of other scripts as parts of words only then.
 */
@EnabledIf("in.apnidukan.support.TestContainers#dockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CapturingSenders.class)
class SalonSearchIntegrationTest {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
        registry.add("app.subscriptions.enforce", () -> "true");
    }

    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JwtService jwt;
    @Autowired SessionRevocations sessions;

    RestClient http;
    String base;
    UUID customer;
    int phones;

    @BeforeEach
    void setUp() {
        base = "http://localhost:" + environment.getProperty("local.server.port") + "/api/v1";
        http = RestClient.builder()
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
                .build();
        TestData.clean(jdbc);
        redis.execute((RedisCallback<Object>) (RedisConnection c) -> {
            c.serverCommands().flushAll();
            return null;
        });
        phones = 0;
        customer = person();
    }

    @Test
    void aSalonIsFoundByItsNameInPartAndInAnyCase() {
        UUID raju = salon("Raju Hair Salon", "17.467");
        UUID glow = salon("Glow Studio", "17.468");
        service(raju, "Haircut", true, null, true);
        service(glow, "Haircut", true, null, true);

        assertThat(ids(search("RAJU"))).containsExactly(raju.toString());
        assertThat(ids(search("aju h"))).containsExactly(raju.toString());   // across two words
        assertThat(ids(search("  glow  "))).containsExactly(glow.toString());   // spaces at the ends dropped
        assertThat(ids(search("studio"))).containsExactly(glow.toString());
    }

    @Test
    void aSalonIsFoundByAServiceItsBarbersDoOrItsPictureNearestFirstWithTheSameCards() {
        UUID raju = salon("Raju Hair Salon", "17.467");
        UUID glow = salon("Glow Studio", "17.468");
        UUID style = salon("Style Point", "17.469");
        service(raju, "Gold Facial", true, null, true);
        service(glow, "Face clean-up", true, "FACIAL", true);   // found by its picture
        service(style, "Haircut", true, "HAIRCUT", true);

        List<?> facial = search("facial");
        assertThat(ids(facial)).containsExactly(raju.toString(), glow.toString());
        // The same cards as the list without a search (same fields, same values)
        Map<String, Object> plain = byId(page(null, null));
        for (Object card : facial) {
            assertThat(card).isEqualTo(plain.get((String) field(card, "id")));
        }
        assertThat(ids(search("acia"))).containsExactly(raju.toString());   // in part: the name; not the picture
        // Style Point by its Haircut; never Raju Hair Salon for the "Hair" in its name (3 letters short of "haircut")
        assertThat(ids(search("HAIRCUT"))).containsExactly(style.toString());
        // Glow Studio by its "Face clean-up"; "Gold Facial" is 2 letters off "face", too far for 4 letters
        assertThat(ids(search("face"))).containsExactly(glow.toString());
    }

    @Test
    void smallSpellingSlipsAreForgivenFromFourLetters() {
        UUID raju = salon("Raju Hair Salon", "17.467");
        UUID glow = salon("Glow Studio", "17.468");
        service(raju, "Mehendi", true, null, true);
        service(glow, "Threading", true, null, true);

        assertThat(ids(search("mehndi"))).containsExactly(raju.toString());
        assertThat(ids(search("threding"))).containsExactly(glow.toString());
        assertThat(ids(search("rajoo"))).containsExactly(raju.toString());
        assertThat(search("pedicure")).isEmpty();
    }

    @Test
    void everyWordMustMatchOnItsOwnSoOneCommonWordSharedFindsNothing() {
        UUID raju = salon("Raju Hair Salon", "17.467");
        UUID ravi = salon("Ravi Hair Salon", "17.468");
        UUID style = salon("Style Salon", "17.469");
        service(raju, "Hair Spa", true, null, true);
        service(raju, "Shampoo", true, null, true);
        service(ravi, "Body Massage", true, null, true);
        service(style, "Facial", true, null, true);

        assertThat(search("haircut")).isEmpty();   // not Raju's "Hair" nor his "Hair Spa"
        assertThat(search("lakme salon")).isEmpty();   // "salon" alone is not enough
        assertThat(ids(search("raju hair salon"))).containsExactly(raju.toString());   // not Ravi Hair Salon
        assertThat(search("head massage")).isEmpty();   // not Ravi's Body Massage
        assertThat(search("shave")).isEmpty();   // not Raju's Shampoo
        // Slips are still forgiven, word by word and in any order
        assertThat(ids(search("facal"))).containsExactly(style.toString());
        assertThat(ids(search("rajoo"))).containsExactly(raju.toString());
        assertThat(ids(search("salon raju"))).containsExactly(raju.toString());
        assertThat(ids(search("rajoo saloon"))).containsExactly(raju.toString());
    }

    @Test
    void aNameIsSplitIntoWordsAtPunctuationTooSoSlipsStillMatchWordsJoinedToIt() {
        UUID apostrophe = salon("Raju's Salon", "17.467");
        UUID slash = salon("Glow Studio", "17.468");
        service(slash, "Eyebrow Threading/Shaping", true, null, true);
        service(slash, "Facial/Cleanup", true, null, true);

        assertThat(ids(search("rajoo"))).containsExactly(apostrophe.toString());
        assertThat(ids(search("threding"))).containsExactly(slash.toString());
        assertThat(ids(search("facal"))).containsExactly(slash.toString());
    }

    @Test
    void whatWasTypedIsTidiedAsSavedNamesAreAndHindiIsFound() {
        UUID raju = salon("Raju Hair Salon", "17.467");
        UUID hindi = salon("राजू हेयर सैलून", "17.468");
        // Saved as TextCleaner saves it: NFKC writes "फ़" as "फ" and its dot (U+092B U+093C)
        service(hindi, "\u092B\u093C\u0947\u0936\u093F\u092F\u0932", true, null, true);

        assertThat(ids(search("\uFF32\uFF41\uFF4A\uFF55"))).containsExactly(raju.toString());   // full-width
        assertThat(ids(search("raju\u00A0hair"))).containsExactly(raju.toString());   // a no-break space
        // Hindi: the name in part, a slip in a vowel sign (राजु for राजू), and a service typed with the
        // one-character "फ़" (U+095E), as some keyboards send it
        assertThat(ids(search("राजू"))).containsExactly(hindi.toString());
        assertThat(ids(search("हेयर सैलून"))).containsExactly(hindi.toString());
        assertThat(ids(search("राजु"))).containsExactly(hindi.toString());
        assertThat(ids(search("\u095E\u0947\u0936\u093F\u092F\u0932"))).containsExactly(hindi.toString());
        assertThat(search("मसाज")).isEmpty();
    }

    @Test
    void aServiceOnlyABarberWithoutWeeklyHoursDoesNeverFindsTheSalonButItsNameStillDoes() {
        UUID glow = salon("Glow Studio", "17.467");
        service(glow, "Pedicure", true, null, true);
        jdbc.update("DELETE FROM staff_hours WHERE staff_id IN (SELECT id FROM salon_staff WHERE shop_id = ?)", glow);

        assertThat(search("pedicure")).isEmpty();
        assertThat(ids(search("glow"))).containsExactly(glow.toString());
    }

    @Test
    void aSalonWhoseSubscriptionLapsedIsNeverFound() {
        UUID lapsed = salon("Raju Hair Salon", "17.467");
        service(lapsed, "Facial", true, null, true);
        jdbc.update("INSERT INTO salon_subscriptions (shop_id, trial_ends_at, bookable_until, created_at, updated_at) "
                + "VALUES (?, now() - interval '10 days', now() - interval '1 day', now(), now())", lapsed);
        UUID paying = salon("Glow Studio", "17.468");
        service(paying, "Facial", true, null, true);
        jdbc.update("INSERT INTO salon_subscriptions (shop_id, trial_ends_at, bookable_until, created_at, updated_at) "
                + "VALUES (?, now() - interval '10 days', now() + interval '20 days', now(), now())", paying);

        assertThat(ids(search("facial"))).containsExactly(paying.toString());
        assertThat(search("raju")).isEmpty();
    }

    @Test
    void theServicesASearchMatchedComeFirstOnTheCardThenTheOthersInTheSalonsOrder() {
        UUID raju = salon("Raju Hair Salon", "17.467");
        for (String name : List.of("Beard", "Facial", "Haircut", "Manicure", "Massage", "Threading")) {
            service(raju, name, true, null, true);
        }
        Object card = search("threading").get(0);
        assertThat(((List<?>) field(card, "topServices")).stream().map(x -> field(x, "name")).toList())
                .containsExactly("Threading", "Beard", "Facial");
        assertThat(field(card, "serviceCount")).isEqualTo(6);
        // A typo matches too, and the list without a search keeps the salon's order
        assertThat(((List<?>) field(search("threding").get(0), "topServices")).stream().map(x -> field(x, "name"))
                .toList()).containsExactly("Threading", "Beard", "Facial");
        assertThat(((List<?>) field(page(null, null).get(0), "topServices")).stream().map(x -> field(x, "name"))
                .toList()).containsExactly("Beard", "Facial", "Haircut");
        // Found by its own name: the salon's order
        assertThat(((List<?>) field(search("raju").get(0), "topServices")).stream().map(x -> field(x, "name"))
                .toList()).containsExactly("Beard", "Facial", "Haircut");
    }

    @Test
    void thirtySearchesAMinuteThenA429WithRetryAfterWhileTheListWithoutQGoesOn() {
        UUID raju = salon("Raju Hair Salon", "17.467");
        service(raju, "Haircut", true, null, true);
        for (int i = 0; i < 30; i++) {
            assertThat(get(path("raju", null)).status()).isEqualTo(200);
        }
        Reply over = get(path("raju", null));
        assertThat(over.status()).isEqualTo(429);
        assertThat(field(over.body(), "code")).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(over.retryAfter()).isNotNull();
        assertThat(Long.parseLong(over.retryAfter())).isBetween(1L, 60L);
        // The list without q is never counted, nor a refused q; the salon pages have their own count
        assertThat(get(path(null, null)).status()).isEqualTo(200);
        assertThat(get(path("", null)).status()).isEqualTo(200);
        assertThat(get("/salons/" + raju).status()).isEqualTo(200);
        // Per person: someone else still searches
        customer = person();
        assertThat(get(path("raju", null)).status()).isEqualTo(200);
    }


    @Test
    void aSwitchedOffServiceAServiceNoWorkingBarberDoesAPausedOrFarSalonAreNeverFound() {
        UUID off = salon("Raju Hair Salon", "17.467");
        service(off, "Pedicure", false, "PEDICURE", true);   // switched off
        UUID nobody = salon("Glow Studio", "17.468");
        service(nobody, "Pedicure", true, "PEDICURE", false);   // no barber does it
        UUID leftBarber = salon("Style Point", "17.469");
        service(leftBarber, "Pedicure", true, "PEDICURE", true);
        jdbc.update("UPDATE salon_staff SET active = false WHERE shop_id = ?", leftBarber);   // his barber left
        UUID paused = salon("Paused Pedicure Place", "17.470");
        service(paused, "Pedicure", true, null, true);
        jdbc.update("INSERT INTO salon_pauses (shop_id, paused_until, updated_by, updated_at) VALUES (?, now() "
                + "+ interval '1 hour', ?, now())", paused, customer);
        UUID far = salon("Far Pedicure Place", "17.496");   // about 3.3 km: outside 2 km
        service(far, "Pedicure", true, null, true);
        UUID kirana = UUID.randomUUID();   // near, but not a salon
        TestData.shop(jdbc, kirana, person());
        jdbc.update("UPDATE shops SET status = 'APPROVED', name = 'Pedicure Kirana' WHERE id = ?", kirana);

        assertThat(search("pedicure")).isEmpty();
        assertThat(search("xyzzy")).isEmpty();   // nothing matches: an empty page, not an error

        UUID works = salon("Works Salon", "17.471");
        service(works, "Pedicure", true, null, true);
        assertThat(ids(search("pedicure"))).containsExactly(works.toString());
    }

    @Test
    void searchResultsCome30APageWithTheSameCursorAndTheSameQ() {
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < 35; i++) {
            // The 30th and 31st at the same distance: the page ends between them, the cursor's id decides
            int step = i == 30 ? 29 : i;
            UUID id = salon("Salon " + i, new BigDecimal("17.467000").add(new BigDecimal("0.000300")
                    .multiply(BigDecimal.valueOf(step))).toPlainString());
            service(id, "Facial", true, null, true);
            expected.add(id.toString());
        }
        for (int i = 0; i < 5; i++) {   // nearer, without a facial: never on a page, never cut a page short
            service(salon("Cuts " + i, "17.4665"), "Haircut", true, null, true);
        }
        List<?> first = page("facial", null);
        assertThat(first).hasSize(30);
        List<?> second = page("facial", first.get(29));
        assertThat(second).hasSize(5);
        List<Object> all = new ArrayList<>(first);
        all.addAll(second);
        assertThat(ids(all)).containsExactlyInAnyOrderElementsOf(expected);   // each once: the pair split, none lost
        assertThat(field(first.get(29), "distanceMetres")).isEqualTo(field(second.get(0), "distanceMetres"));
        assertThat(all.stream().map(x -> ((Number) field(x, "distanceMetres")).doubleValue()).toList()).isSorted();
        assertThat(page("facial", second.get(4))).isEmpty();
        assertThat(page(null, null)).hasSize(30);   // without q: the first 30 of all 40
    }

    @Test
    void qIsTwoToSixtyCharactersOnceTrimmedAndABlankQIsTheListAsItIs() {
        UUID raju = salon("Raju Hair Salon", "17.467");
        service(raju, "Haircut", true, null, true);
        for (String bad : List.of("f", "  f  ", "\\", "x".repeat(61))) {
            Reply r = get(path(bad, null));
            assertThat(r.status()).as(bad).isEqualTo(400);
            assertThat(field(r.body(), "code")).isEqualTo("VALIDATION_FAILED");
            Map<?, ?> details = (Map<?, ?>) field(r.body(), "details");
            assertThat(details.size()).isEqualTo(1);
            assertThat(details.containsKey("q")).isTrue();
        }
        assertThat(get(path("x".repeat(60), null)).status()).isEqualTo(200);
        assertThat(page("", null)).isEqualTo(page(null, null));
        assertThat(page("    ", null)).isEqualTo(page(null, null));
        assertThat(ids(page(null, null))).containsExactly(raju.toString());
    }

    @Test
    void percentUnderscoreAndBackslashAreTakenAsTheyAreNotAsWildcards() {
        UUID percent = salon("100% Style", "17.467");
        salon("1000 Style", "17.468");
        UUID underscore = salon("A_B Cuts", "17.469");
        salon("AxB Cuts", "17.470");
        UUID backslash = salon("A\\B Salon", "17.471");
        salon("AB Salon", "17.472");

        assertThat(ids(search("0%"))).containsExactly(percent.toString());
        assertThat(ids(search("a_b"))).containsExactly(underscore.toString());
        assertThat(ids(search("a\\b"))).containsExactly(backslash.toString());
        assertThat(search("%%")).isEmpty();
        assertThat(search("__")).isEmpty();
    }

    // ---------- helpers ----------

    private UUID person() {
        UUID id = UUID.randomUUID();
        TestData.user(jdbc, id, "96" + String.format("%08d", ++phones));
        return id;
    }

    /** An approved salon with this name at this latitude, with one working barber (Mondays 10:00 to 20:00). */
    private UUID salon(String name, String latitude) {
        UUID id = UUID.randomUUID();
        TestData.salon(jdbc, id, person());
        jdbc.update("UPDATE shops SET status = 'APPROVED', name = ?, latitude = ?, longitude = 78.365 WHERE id = ?",
                name, new BigDecimal(latitude), id);
        UUID barber = UUID.randomUUID();
        jdbc.update("INSERT INTO salon_staff (id, shop_id, name, active, version, created_at, updated_at) "
                + "VALUES (?, ?, 'Barber', true, 0, now(), now())", barber, id);
        jdbc.update("INSERT INTO staff_hours (id, staff_id, weekday, start_min, end_min) VALUES (?, ?, 1, 600, 1200)",
                UUID.randomUUID(), barber);
        return id;
    }

    /** A service of the salon; done: its barber does it. */
    private void service(UUID shop, String name, boolean active, String icon, boolean done) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO salon_services (id, shop_id, name, duration_min, price_paise, price_is_from, active, "
                + "sort_order, version, created_at, updated_at, icon) VALUES (?, ?, ?, 30, 15000, false, ?, 0, 0, "
                + "now(), now(), ?)", id, shop, name, active, icon);
        if (done) {
            UUID barber = jdbc.queryForObject("SELECT id FROM salon_staff WHERE shop_id = ?", UUID.class, shop);
            jdbc.update("INSERT INTO staff_services (id, shop_id, staff_id, service_id) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), shop, barber, id);
        }
    }

    private List<?> search(String q) {
        return page(q, null);
    }

    private List<?> page(String q, Object after) {
        Reply r = get(path(q, after));
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        return (List<?>) r.body();
    }

    /** The list's address with q (null: none) encoded as a browser would, and the next page's cursor. */
    private static String path(String q, Object after) {
        String p = "/salons?lat=17.466&lng=78.365";
        if (q != null) {
            p += "&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8).replace("+", "%20");
        }
        if (after != null) {
            p += "&afterDistance=" + field(after, "distanceMetres") + "&afterId=" + field(after, "id");
        }
        return p;
    }

    private static List<String> ids(List<?> cards) {
        return cards.stream().map(x -> (String) field(x, "id")).toList();
    }

    private static Map<String, Object> byId(List<?> cards) {
        Map<String, Object> out = new java.util.HashMap<>();
        cards.forEach(c -> out.put((String) field(c, "id"), c));
        return out;
    }

    private String token(UUID who) {
        return jwt.issue(new AuthPrincipal(who, null, null, false), sessions.currentGeneration(who)).token();
    }

    /** A GET of this already-encoded path, as the customer (a URI, so nothing in it is encoded a second time). */
    private Reply get(String path) {
        return http.get().uri(URI.create(base + path)).headers(h -> h.setBearerAuth(token(customer)))
                .exchange((rq, res) -> {
                    int status = res.getStatusCode().value();
                    String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    return new Reply(status, text.isBlank() ? Map.of() : JSON.readValue(text, Object.class), text,
                            res.getHeaders().getFirst("Retry-After"));
                });
    }

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    private static Object field(Object json, String name) {
        return ((Map<?, ?>) json).get(name);
    }

    private record Reply(int status, Object body, String text, String retryAfter) {
    }
}
