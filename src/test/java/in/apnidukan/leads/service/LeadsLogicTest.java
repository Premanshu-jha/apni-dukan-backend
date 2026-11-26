package in.apnidukan.leads.service;

import in.apnidukan.leads.repo.LeadStore.Salon;
import in.apnidukan.leads.service.ClozestMatch.Candidate;
import in.apnidukan.leads.service.PoiKinds.Kind;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The Leads page's pure logic: what a business is (PoiKinds), Clozest matching, and reading Mapbox's answers. */
class LeadsLogicTest {

    private static Poi poi(String name, String... categories) {
        return new Poi("id-" + name, name, "Road 1", 17.49, 78.40, "500072", List.of(categories), null, null, null);
    }

    private static Poi with(Poi p, String phone, String website, Poi.Hours hours) {
        return new Poi(p.mapboxId(), p.name(), p.address(), p.latitude(), p.longitude(), p.postcode(),
                p.categories(), phone, website, hours);
    }

    // ---- What a business is ------------------------------------------------------------------------------------

    @Test
    void businessesNotFiledAsSalonsAreStillFoundByTheirName() {
        assertThat(PoiKinds.relevant(poi("Arifa mehandi artist", "services"))).isTrue();
        assertThat(PoiKinds.relevant(poi("Glow threading point", "services"))).isTrue();
        assertThat(PoiKinds.relevant(poi("Tia's Beauty Parlour", "cosmetics shop", "salon"))).isTrue();
        // Words matched an artist who isn't a beauty business: left out
        assertThat(PoiKinds.relevant(poi("saida chary artist", "art", "arts center"))).isFalse();
        assertThat(PoiKinds.relevant(poi("Sri Sai Kirana", "grocery"))).isFalse();
    }

    @Test
    void eachBusinessGetsTheRightKind() {
        assertThat(PoiKinds.kind(poi("Tia's Beauty Parlour", "salon"))).isEqualTo(Kind.LADIES_PARLOUR);
        assertThat(PoiKinds.kind(poi("Aniq studio Unisex Saloon", "salon"))).isEqualTo(Kind.UNISEX_SALON);
        assertThat(PoiKinds.kind(poi("New O.K. Hair Saloon and Men's Saloon", "barber"))).isEqualTo(Kind.MENS_SALON);
        assertThat(PoiKinds.kind(poi("Jayam Hair Style", "barber", "salon"))).isEqualTo(Kind.MENS_SALON);
        assertThat(PoiKinds.kind(poi("Navya Beauty & Makeup Studio", "salon"))).isEqualTo(Kind.BRIDAL_MAKEUP);
        assertThat(PoiKinds.kind(poi("Arifa mehandi artist", "services"))).isEqualTo(Kind.MEHENDI);
        assertThat(PoiKinds.kind(poi("Haven Beauty Salon and Spa", "salon", "spa"))).isEqualTo(Kind.LADIES_PARLOUR);
        assertThat(PoiKinds.kind(poi("Coolstones Beauty Spa", "spa", "massage"))).isEqualTo(Kind.SPA_MASSAGE);
        assertThat(PoiKinds.kind(poi("Nail Tales Hyderabad", "nail salon"))).isEqualTo(Kind.NAIL_LASH);
        assertThat(PoiKinds.kind(poi("Sweta Skin Laser Clinic", "salon"))).isEqualTo(Kind.SKIN_HAIR_CLINIC);
        assertThat(PoiKinds.kind(poi("Ink Studio Tattoos", "tattoo_parlour"))).isEqualTo(Kind.TATTOO);
        assertThat(PoiKinds.kind(poi("Lakme Salon", "salon"))).isEqualTo(Kind.UNISEX_SALON);
        assertThat(PoiKinds.kind(poi("Glamour Cosmetics", "beauty_store"))).isEqualTo(Kind.COSMETICS_SHOP);
    }

    @Test
    void chainsAreRecognisedAndScoreBelowLocalShops() {
        Poi chain = poi("Lakme Salon Kukatpally", "salon");
        Poi local = poi("Sri Swathi Beauty Parlour", "salon");
        assertThat(PoiKinds.chain(chain)).isTrue();
        assertThat(PoiKinds.chain(local)).isFalse();
        assertThat(PoiKinds.score(chain)).isLessThan(PoiKinds.score(local));
    }

    @Test
    void theScoreAddsUpAndNeverPassesAHundred() {
        Poi bare = poi("Sri Swathi Beauty Parlour", "salon");
        // local 40 + no website 10 + core kind 10
        assertThat(PoiKinds.score(bare)).isEqualTo(60);
        Poi best = with(bare, "+919133576278", null, new Poi.Hours(7, "1000", "2100", 11, true));
        // + phone 25 + 7 days 10 + long hours 5 = 100
        assertThat(PoiKinds.score(best)).isEqualTo(100);
        assertThat(PoiKinds.score(with(bare, null, "https://x.in", new Poi.Hours(6, "1000", "1800", 8, false))))
                .isEqualTo(40 + 6 + 10);
        assertThat(LeadService.priority(100)).isEqualTo("HIGH");
        assertThat(LeadService.priority(60)).isEqualTo("MEDIUM");
        assertThat(LeadService.priority(49)).isEqualTo("LOW");
    }

    // ---- Matching with Clozest salons --------------------------------------------------------------------------

    private static Salon salon(String name, double lat, double lng) {
        return salon(name, lat, lng, null);
    }

    private static Salon salon(String name, double lat, double lng, String phone) {
        return new Salon(UUID.nameUUIDFromBytes(name.getBytes()), name, "APPROVED", "500072", lat, lng, phone,
                Instant.parse("2026-11-01T00:00:00Z"));
    }

    @Test
    void theSamePhoneIsTheSameSalonWhereverItIs() {
        Salon s = salon("Totally Different Name", 17.40, 78.30, "9133576278");
        Map<String, Salon> m = ClozestMatch.match(List.of(new Candidate("a", "Tia's Beauty Parlour", 17.49, 78.40,
                "+91 91335 76278")), List.of(s));
        assertThat(m).containsEntry("a", s);
    }

    @Test
    void aSimilarNameCloseByIsTheSameSalonButNotFarAway() {
        Salon s = salon("Tia Beauty Salon", 17.4847, 78.4132);
        Candidate near = new Candidate("near", "Tia's Beauty Parlour", 17.4848, 78.4133, null);
        Candidate far = new Candidate("far", "Tia's Beauty Parlour", 17.4900, 78.4200, null);
        assertThat(ClozestMatch.match(List.of(near), List.of(s))).containsKey("near");
        assertThat(ClozestMatch.match(List.of(far), List.of(s))).isEmpty();
        // Different names next door: not the same
        assertThat(ClozestMatch.match(List.of(new Candidate("x", "Jayam Hair Style", 17.4848, 78.4133, null)),
                List.of(s))).isEmpty();
    }

    @Test
    void oneSalonIsTheMatchOfOneBusinessOnlyAPhoneFirst() {
        Salon s = salon("Rich Look", 17.4847, 78.4132, "9000000001");
        Candidate byName = new Candidate("name", "Rich Look Unisex", 17.4847, 78.4132, null);
        Candidate byPhone = new Candidate("phone", "Rich Look", 17.4850, 78.4135, "9000000001");
        Map<String, Salon> m = ClozestMatch.match(List.of(byName, byPhone), List.of(s));
        assertThat(m).containsOnlyKeys("phone");
    }

    @Test
    void phonesAreReadInAnyForm() {
        assertThat(ClozestMatch.phone10("+919133576278")).isEqualTo("9133576278");
        assertThat(ClozestMatch.phone10("09133576278")).isEqualTo("9133576278");
        assertThat(ClozestMatch.phone10("040 2354 4236")).isNull();   // a landline: never a match
        assertThat(ClozestMatch.phone10(null)).isNull();
        assertThat(ClozestMatch.similar("Mirrors Luxury Salons", "Mirrors Salon")).isTrue();
        assertThat(ClozestMatch.similar("Beauty Salon", "Beauty Parlour")).isFalse();
    }

    // ---- Reading Mapbox --------------------------------------------------------------------------------------------

    @Test
    void mapboxAnswersAreReadWithPhoneHoursAndPincode() throws Exception {
        String answer = """
                {"features":[
                 {"properties":{"mapbox_id":"m1","name":"Tia's Beauty Parlour","full_address":"Pillar 812, Hyderabad",
                   "coordinates":{"latitude":17.4846,"longitude":78.4132},"context":{"postcode":{"name":"500072"}},
                   "poi_category":["cosmetics shop","salon"],"poi_category_ids":["beauty_store","salon"],
                   "metadata":{"phone":"+919133576278","website":"https://tias.example",
                     "open_hours":{"periods":[{"open":{"day":0,"time":"1000"},"close":{"day":0,"time":"2000"}},
                       {"open":{"day":1,"time":"1000"},"close":{"day":1,"time":"2130"}}]}}}},
                 {"properties":{"name":"no id","coordinates":{"latitude":1,"longitude":2}}},
                 {"properties":{"mapbox_id":"m2","name":"Plain","coordinates":{"latitude":17.5,"longitude":78.4},
                   "metadata":{"website":"http://not-https.example"}}}
                ]}""";
        List<Poi> pois = MapboxSearch.pois(JsonMapper.builder().build().readTree(answer));
        assertThat(pois).hasSize(2);
        Poi tia = pois.getFirst();
        assertThat(tia.postcode()).isEqualTo("500072");
        assertThat(tia.phone()).isEqualTo("+919133576278");
        assertThat(tia.categories()).contains("salon", "beauty_store");
        assertThat(tia.hours().days()).isEqualTo(2);
        assertThat(tia.hours().opens()).isEqualTo("1000");
        assertThat(tia.hours().closes()).isEqualTo("2130");
        assertThat(tia.hours().late()).isTrue();
        assertThat(tia.hours().averageHours()).isEqualTo(10.75);
        // Only https websites are kept (the page links to them)
        assertThat(pois.get(1).website()).isNull();
        assertThat(pois.get(1).postcode()).isNull();
    }

    @Test
    void aFullBoxSplitsIntoFourQuartersThatCoverIt() {
        MapboxSearch.Box b = new MapboxSearch.Box(78.0, 17.0, 78.4, 17.2);
        List<MapboxSearch.Box> q = b.quarters();
        assertThat(q).hasSize(4);
        assertThat(q).allSatisfy(x -> {
            assertThat(x.east() - x.west()).isEqualTo(0.2, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(x.north() - x.south()).isEqualTo(0.1, org.assertj.core.data.Offset.offset(1e-9));
        });
        assertThat(MapboxSearch.Box.around(17.0, 78.0, 1113).north()).isEqualTo(17.01, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    void mapboxLocalitiesKeepOnlyAnswersWithAnIndianPincode() {
        tools.jackson.databind.JsonNode answer = JsonMapper.builder().build().readTree("""
                {"features":[
                  {"properties":{"name":"Yapral","feature_type":"neighborhood","context":{"postcode":{"name":"500087"}}}},
                  {"properties":{"name":"500072","feature_type":"postcode"}},
                  {"properties":{"name":"Somewhere","feature_type":"place","context":{}}},
                  {"properties":{"name":"Bad","feature_type":"locality","context":{"postcode":{"name":"SW1A"}}}}
                ]}""");
        assertThat(MapboxSearch.localities(answer)).containsExactly(new MapboxSearch.Locality("Yapral", "500087"),
                new MapboxSearch.Locality("500072", "500072"));
    }

    @Test
    void theCsvReaderHandlesQuotesCommasAndLineBreaks() {
        assertThat(db.migration.R__India_places.parse("a,b\n\"x, y\",\"say \"\"hi\"\"\"\r\n\"multi\nline\",z"))
                .containsExactly(new String[] {"a", "b"}, new String[] {"x, y", "say \"hi\""},
                        new String[] {"multi\nline", "z"});
        // The real files: every row has its columns
        assertThat(db.migration.R__India_places.rows(db.migration.R__India_places.PINCODES, 8)).hasSize(19300);
        assertThat(db.migration.R__India_places.rows(db.migration.R__India_places.CITIES, 4)).hasSizeGreaterThan(60);
    }
}
