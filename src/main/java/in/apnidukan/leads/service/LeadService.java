package in.apnidukan.leads.service;

import in.apnidukan.common.context.TeamRole;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.security.RateLimiter;
import in.apnidukan.leads.repo.LeadStore;
import in.apnidukan.leads.repo.LeadStore.Lead;
import in.apnidukan.leads.repo.LeadStore.Salon;
import in.apnidukan.leads.repo.PincodeDirectory;
import in.apnidukan.leads.repo.PincodeDirectory.Pin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The admin website's Leads page, for the field team (field agents and super admins; 404 for anyone else, as for the
 * other field-team pages): districts and their areas (pincodes), a live scan of one area ({@link LeadScanner}), the
 * team's work on each lead, follow-ups due, and the funnel.
 *
 * <p>Who may change what: anyone on the field team may update any lead's status, note, follow-up and WhatsApp
 * consent (they work an area together). A field agent may take an unassigned lead or let go of their own; only a
 * super admin assigns a lead to someone else or takes it from them (403 ASSIGNED_TO_SOMEONE_ELSE).
 *
 * <p>What a field agent sees of a Clozest salon is its name and status (so nobody pitches a salon that's already on
 * Clozest); the salon's id, for opening its page, only goes to super admins (agents see only their assigned salons'
 * pages, as elsewhere). An owner's phone is used for matching and never sent.
 */
@Service
public class LeadService {

    private static final Logger log = LoggerFactory.getLogger(LeadService.class);

    public static final Set<String> STATUSES = Set.of("NEW", "NO_ANSWER", "CALLED", "VISITED", "INTERESTED",
            "NOT_INTERESTED");

    // ---- Answers ---------------------------------------------------------------------------------------------------

    public record PersonView(UUID id, String name) {
    }

    /**
     * A city or district the search found. {@code district} is its name (a city's too: "Hyderabad");
     * {@code districts}: a city's districts; {@code matched}: what matched when not its name ("Bangalore",
     * "500087 · JJ Nagar Colony", "Yapral (500087)"); {@code pincode}: the area to tick when it's picked.
     */
    public record DistrictView(PincodeDirectory.Kind kind, String district, String state, int pincodes,
                               List<String> districts, String matched, String pincode) {
        public DistrictView {
            districts = List.copyOf(districts);
        }
    }

    /**
     * One area. {@code mine}: the caller covers it as a field agent. {@code onMap}: India Post has a point for it (an
     * area without one may still be scannable: Mapbox may know it). The rest are counts of saved leads and of Clozest
     * salons there.
     */
    public record AreaView(String pincode, String places, String district, boolean mine, boolean onMap, int contacted,
                           int interested, int signedUp, int onClozest) {
    }

    public record AreasView(PincodeDirectory.Kind kind, String district, String state, List<String> districts,
                            List<AreaView> areas) {
        public AreasView {
            districts = List.copyOf(districts);
            areas = List.copyOf(areas);
        }
    }

    public record HoursView(int days, String opens, String closes, boolean late) {
    }

    public record LeadView(String status, String note, PersonView assignedTo, LocalDate followUpOn,
                           boolean whatsappOk, Instant whatsappOkAt, PersonView whatsappOkBy, Instant signedUpAt,
                           PersonView creditedTo, int version, Instant updatedAt, PersonView updatedBy) {
    }

    /** A Clozest salon. {@code shopId} only for super admins (see the class comment). */
    public record ClozestView(UUID shopId, String name, String status) {
    }

    /**
     * One business in an area.
     *
     * @param onMapbox  false for a lead the team contacted that Mapbox didn't list this time (its saved details shown)
     * @param kind      {@link PoiKinds.Kind}
     * @param priority  HIGH (75+), MEDIUM (50+) or LOW, from {@code score}
     * @param phone     10 digits when it is an Indian mobile number, else as Mapbox gives it; null if none
     * @param lead      the team's work on it, null if none yet
     * @param clozest   the Clozest salon it is, null if it isn't on Clozest
     */
    public record ShopView(String mapboxId, String name, String address, double latitude, double longitude,
                           String pincode, boolean onMapbox, String kind, boolean chain, int score, String priority,
                           String phone, String website, HoursView hours, LeadView lead, ClozestView clozest) {
    }

    /** A Clozest salon in the area that no business found on the map matched (so "On Clozest" lists every salon). */
    public record SalonView(UUID shopId, String name, String status, double latitude, double longitude) {
    }

    public record UsageView(String month, long used, long cap) {
    }

    /**
     * One area's scan.
     *
     * @param partial some of the search didn't finish (Mapbox failed a request, the month's cap, or time): the list
     *                may miss businesses; scanning again later fills the gaps
     */
    public record ScanView(String pincode, String places, String district, String state, List<ShopView> shops,
                           List<SalonView> clozestOnly, int requests, boolean partial, boolean capReached,
                           UsageView usage) {
        public ScanView {
            shops = List.copyOf(shops);
            clozestOnly = List.copyOf(clozestOnly);
        }
    }

    public record EventView(Instant at, PersonView by, String field, String oldValue, String newValue) {
    }

    /**
     * A lead with a follow-up due: its saved details (name, phone and point only once contacted) and what the page needs
     * to change it from the list (the version, the WhatsApp consent).
     */
    public record DueView(String mapboxId, String name, String phone, Double latitude, Double longitude,
                          String pincode, String places, String status, LocalDate followUpOn, String note,
                          PersonView assignedTo, boolean whatsappOk, int version) {
    }

    public record FunnelArea(String pincode, String places, int touched, int contacted, int interested,
                             int notInterested, int signedUp) {
    }

    public record FunnelPerson(PersonView person, int assigned, int contacted, int interested, int signedUp) {
    }

    public record FunnelView(PincodeDirectory.Kind kind, String district, String state, List<FunnelArea> areas,
                             List<FunnelPerson> people) {
        public FunnelView {
            areas = List.copyOf(areas);
            people = List.copyOf(people);
        }
    }

    /**
     * A change to a lead: the team's fields as the page now shows them, the business's details from the scan (kept
     * only once it's been contacted), and the version the page last saw (null for a lead not saved yet).
     */
    public record SaveLead(String status, String note, UUID assignedTo, LocalDate followUpOn, Boolean whatsappOk,
                           String pincode, String name, String phone, Double latitude, Double longitude,
                           Integer version) {
    }

    // ---- Wiring ----------------------------------------------------------------------------------------------------

    private final PincodeDirectory pincodes;
    private final LeadStore store;
    private final LeadScanner scanner;
    private final UserDirectory users;
    private final RateLimiter limits;
    private final Clock clock;
    private final int scansPerHour;
    private final int lookupsPerHour;

    public LeadService(PincodeDirectory pincodes, LeadStore store, LeadScanner scanner, UserDirectory users,
                       RateLimiter limits, Clock clock,
                       @Value("${app.leads.scans-per-hour:60}") int scansPerHour,
                       @Value("${app.leads.lookups-per-hour:30}") int lookupsPerHour) {
        this.pincodes = pincodes;
        this.store = store;
        this.scanner = scanner;
        this.users = users;
        this.limits = limits;
        this.clock = clock;
        this.scansPerHour = scansPerHour;
        this.lookupsPerHour = lookupsPerHour;
    }

    // ---- Districts and areas ---------------------------------------------------------------------------------------

    /**
     * Cities and districts for what was typed (2 to 60 characters; shorter answers an empty list), at most 15: by
     * name, another name, a pincode or a post office (see {@link PincodeDirectory#search}). Free: our own list.
     */
    public List<DistrictView> districts(UUID caller, String typed, boolean cities) {
        requireTeam(caller);
        if (typed == null || typed.strip().length() > 60) {
            throw invalid("q");
        }
        return pincodes.search(typed, 15, cities).stream().map(LeadService::view).toList();
    }

    /**
     * What people call a place ("Yapral", "HITEC City"), when our own list has no match: Mapbox's localities for it
     * (one map request, counted against the month's cap), each offered as the city or district its pincode is in, with
     * that area to tick. 3 to 60 characters with a letter (400 q). At most app.leads.lookups-per-hour (30) per person (429
     * TOO_MANY_REQUESTS); the scanner's MAPBOX_* answers. Nothing Mapbox answers is kept.
     */
    public List<DistrictView> places(UUID caller, String typed) {
        requireTeam(caller);
        String q = typed == null ? "" : typed.strip();
        if (q.length() < 3 || q.length() > 60 || q.chars().noneMatch(Character::isLetter)) {
            throw invalid("q");
        }
        String key = "lead-lookups:" + caller;
        try {
            if (!limits.tryHit(key, lookupsPerHour, Duration.ofHours(1))) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                        "Too many place look-ups this hour. Wait a while and try again.")
                        .retryAfter(limits.secondsLeft(key));
            }
        } catch (DataAccessException redisDown) {
            log.warn("Redis didn't answer the look-up count ({}); letting it through",
                    redisDown.getClass().getSimpleName());
        }
        List<MapboxSearch.Locality> found;
        try {
            found = scanner.localities(q);
        } catch (ApiException refused) {
            // Not looked up (map off, cap, busy): it doesn't use the hourly allowance
            try {
                limits.giveBack(key);
            } catch (DataAccessException redisDown) {
                log.warn("Redis didn't take the look-up back ({})", redisDown.getClass().getSimpleName());
            }
            throw refused;
        }
        Map<String, DistrictView> out = new LinkedHashMap<>();
        for (MapboxSearch.Locality l : found) {
            pincodes.pin(l.pincode()).ifPresent(pin -> pincodes.placeOf(pin).ifPresent(place ->
                    out.putIfAbsent(pin.pincode(), view(new PincodeDirectory.Hit(place,
                            l.name() + " (" + pin.pincode() + ")", pin.pincode())))));
        }
        return List.copyOf(out.values());
    }

    private static DistrictView view(PincodeDirectory.Hit h) {
        PincodeDirectory.Place p = h.place();
        return new DistrictView(p.kind(), p.name(), p.state(), p.pins().size(), p.districts(), h.matched(),
                h.pincode());
    }

    /**
     * A city's or district's areas with each area's counts: a district's in pincode order; a city's district by
     * district, its own first. 404 DISTRICT_NOT_FOUND.
     */
    @Transactional(readOnly = true)
    public AreasView areas(UUID caller, String kind, String district, String state) {
        requireTeam(caller);
        PincodeDirectory.Place d = place(kind, district, state);
        Set<String> mine = new HashSet<>(store.coveredBy(caller));
        List<String> codes = d.pins().stream().map(Pin::pincode).toList();
        Map<String, LeadStore.AreaCount> counts = store.areaCounts(codes);
        List<AreaView> areas = new ArrayList<>();
        for (Pin p : d.pins()) {
            LeadStore.AreaCount c = counts.get(p.pincode());
            areas.add(new AreaView(p.pincode(), p.places(), p.district(), mine.contains(p.pincode()),
                    p.latitude() != null, c == null ? 0 : c.contacted(), c == null ? 0 : c.interested(),
                    c == null ? 0 : c.signedUp(), c == null ? 0 : c.salons()));
        }
        return new AreasView(d.kind(), d.name(), d.state(), d.districts(), areas);
    }

    /** A city or district (kind CITY or DISTRICT, the default): 400 kind for anything else, 404 DISTRICT_NOT_FOUND. */
    private PincodeDirectory.Place place(String kind, String name, String state) {
        PincodeDirectory.Kind k;
        try {
            k = kind == null || kind.isBlank() ? PincodeDirectory.Kind.DISTRICT
                    : PincodeDirectory.Kind.valueOf(kind.strip());
        } catch (IllegalArgumentException e) {
            throw invalid("kind");
        }
        return pincodes.place(k, text(name), text(state))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DISTRICT_NOT_FOUND",
                        "That city or district isn't in the list."));
    }

    // ---- Scanning --------------------------------------------------------------------------------------------------

    /**
     * Searches one area live (see {@link LeadScanner}) and joins in the team's leads and the Clozest salons there.
     * 404 AREA_NOT_FOUND for a pincode not in India Post's list; 429 TOO_MANY_REQUESTS past app.leads.scans-per-hour
     * scans by one person; the scanner's own answers (MAPBOX_OFF, SCAN_BUSY, MAPBOX_CAP_REACHED, AREA_NOT_ON_MAP,
     * MAPBOX_REFUSED). Contacted leads Mapbox didn't list are shown from their saved details; a business that turns
     * out to be a Clozest salon now gets its lead tied to that salon (signed up).
     */
    public ScanView scan(UUID caller, String pincode) {
        TeamRole role = requireTeam(caller);
        Pin pin = pincodes.pin(text(pincode)).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "AREA_NOT_FOUND", "That pincode isn't in the list."));
        requireScansLeft(caller);
        LeadScanner.Found found;
        try {
            found = scanner.scan(pin);
        } catch (ApiException refused) {
            // Refused before searching (busy, cap, not set up, not on the map): it doesn't use the hourly allowance
            giveBackScan(caller);
            throw refused;
        }

        List<Poi> pois = found.pois();
        Map<String, Lead> leads = store.leads(pois.stream().map(Poi::mapboxId).toList());
        List<Lead> saved = store.contactedIn(List.of(pin.pincode())).stream()
                .filter(l -> !leads.containsKey(l.mapboxId()))
                .toList();
        List<Salon> salons = store.salonsIn(List.of(pin.pincode()));

        List<ClozestMatch.Candidate> candidates = new ArrayList<>();
        for (Poi p : pois) {
            candidates.add(new ClozestMatch.Candidate(p.mapboxId(), p.name(), p.latitude(), p.longitude(), p.phone()));
        }
        for (Lead l : saved) {
            if (l.latitude() != null) {
                candidates.add(new ClozestMatch.Candidate(l.mapboxId(), l.name(), l.latitude(), l.longitude(),
                        l.phone()));
            }
        }
        // A lead already tied to a salon keeps that salon; the rest are matched against the salons still free
        Map<UUID, Salon> byId = new HashMap<>();
        salons.forEach(s -> byId.put(s.shopId(), s));
        Map<String, Salon> matched = new HashMap<>();
        Set<UUID> tied = new HashSet<>();
        for (Lead l : concat(leads.values(), saved)) {
            if (l.shopId() != null && byId.containsKey(l.shopId())) {
                matched.put(l.mapboxId(), byId.get(l.shopId()));
                tied.add(l.shopId());
            }
        }
        List<Salon> free = salons.stream().filter(s -> !tied.contains(s.shopId())).toList();
        List<ClozestMatch.Candidate> open = candidates.stream().filter(c -> !matched.containsKey(c.id())).toList();
        ClozestMatch.match(open, free).forEach(matched::put);
        linkContacted(concat(leads.values(), saved), matched);

        Map<String, Lead> fresh = refreshed(leads, saved, matched);
        Map<UUID, PersonView> people = people(fresh.values());
        boolean admin = role == TeamRole.SUPER_ADMIN;

        List<ShopView> shops = new ArrayList<>();
        for (Poi p : pois) {
            shops.add(shop(p, fresh.get(p.mapboxId()), matched.get(p.mapboxId()), people, admin, pin.pincode()));
        }
        for (Lead l : saved) {
            shops.add(savedShop(fresh.getOrDefault(l.mapboxId(), l), matched.get(l.mapboxId()), people, admin));
        }
        shops.sort(Comparator.comparingInt(ShopView::score).reversed().thenComparing(ShopView::name));
        Set<UUID> shown = new HashSet<>();
        matched.values().forEach(s -> shown.add(s.shopId()));
        List<SalonView> clozestOnly = salons.stream()
                .filter(s -> !shown.contains(s.shopId()))
                .map(s -> new SalonView(admin ? s.shopId() : null, s.name(), s.status(), s.latitude(), s.longitude()))
                .toList();
        return new ScanView(pin.pincode(), pin.places(), pin.district(), pin.state(), shops, clozestOnly,
                found.requests(), found.partial(), found.capReached(), usageView());
    }

    /** Contacted leads matched to a salon for the first time are tied to it (once; LeadStore.link has the rules). */
    private void linkContacted(Collection<Lead> all, Map<String, Salon> matched) {
        Instant now = clock.instant();
        for (Lead l : all) {
            Salon s = matched.get(l.mapboxId());
            if (s != null && l.shopId() == null && !"NEW".equals(l.status()) && store.link(l.mapboxId(), s.shopId(), now)) {
                store.event(l.mapboxId(), null, "SIGNED_UP", null, s.name(), now);
            }
        }
    }

    /** The leads again where linking changed them (so the page shows "signed up" and the new version). */
    private Map<String, Lead> refreshed(Map<String, Lead> leads, List<Lead> saved, Map<String, Salon> matched) {
        Map<String, Lead> all = new LinkedHashMap<>(leads);
        saved.forEach(l -> all.put(l.mapboxId(), l));
        List<String> changed = all.values().stream()
                .filter(l -> l.shopId() == null && !"NEW".equals(l.status()) && matched.containsKey(l.mapboxId()))
                .map(Lead::mapboxId)
                .toList();
        if (!changed.isEmpty()) {
            all.putAll(store.leads(changed));
        }
        return all;
    }

    private ShopView shop(Poi p, Lead lead, Salon salon, Map<UUID, PersonView> people, boolean admin,
                          String pincode) {
        int score = PoiKinds.score(p);
        String phone = ClozestMatch.phone10(p.phone());
        Poi.Hours h = p.hours();
        return new ShopView(p.mapboxId(), p.name(), p.address(), p.latitude(), p.longitude(),
                p.postcode() == null ? pincode : p.postcode(), true, PoiKinds.kind(p).name(), PoiKinds.chain(p),
                score, priority(score), phone != null ? phone : p.phone(), p.website(),
                h == null ? null : new HoursView(h.days(), h.opens(), h.closes(), h.late()),
                lead == null ? null : leadView(lead, people), clozest(salon, admin));
    }

    private ShopView savedShop(Lead l, Salon salon, Map<UUID, PersonView> people, boolean admin) {
        Poi p = new Poi(l.mapboxId(), l.name() == null ? "" : l.name(), null,
                l.latitude() == null ? 0 : l.latitude(), l.longitude() == null ? 0 : l.longitude(), l.pincode(),
                List.of(), l.phone(), null, null);
        int score = PoiKinds.score(p);
        return new ShopView(l.mapboxId(), p.name(), null, p.latitude(), p.longitude(), l.pincode(), false,
                PoiKinds.kind(p).name(), PoiKinds.chain(p), score, priority(score), l.phone(), null, null,
                leadView(l, people), clozest(salon, admin));
    }

    private static ClozestView clozest(Salon s, boolean admin) {
        return s == null ? null : new ClozestView(admin ? s.shopId() : null, s.name(), s.status());
    }

    static String priority(int score) {
        return score >= 75 ? "HIGH" : score >= 50 ? "MEDIUM" : "LOW";
    }

    // ---- Saving a lead ---------------------------------------------------------------------------------------------

    /**
     * Saves the team's work on a lead (see {@link SaveLead}). 400 VALIDATION_FAILED names the field: status (also a
     * lead someone tried to reach going back to NEW: its saved record would be lost), note (500 at most), followUpOn
     * (a new date not before today, at most a year ahead; an unchanged past date is fine), pincode, name (needed, 150
     * at most, once tried), phone, latitude/longitude, whatsappOk (only once someone spoke to the owner, and only with
     * a mobile number), assignedTo (a new assignee not on the field team). 403 ASSIGNED_TO_SOMEONE_ELSE (see the class
     * comment). 409 LEAD_SIGNED_UP: a lead that became a Clozest salon keeps its status. 409 LEAD_CHANGED when someone
     * saved it since the page loaded it (details: the lead now). A save that changes nothing answers the lead as it is.
     *
     * <p>Saving never ties a lead to a salon: the details here come from the page, so a lead becomes "signed up" only
     * from Mapbox's own details in a scan, or from the matching job ({@link LeadMatchJob}), and only for a salon that
     * registered after the team first tried to reach it.
     */
    @Transactional
    public LeadView save(UUID caller, String mapboxId, SaveLead body) {
        TeamRole role = requireTeam(caller);
        if (mapboxId == null || mapboxId.isBlank() || mapboxId.length() > 200) {
            throw ApiException.notFound("Lead");
        }
        if (body == null) {
            throw invalid("status");
        }
        Instant now = clock.instant();
        LocalDate today = now.atZone(LeadScanner.INDIA).toLocalDate();
        String status = body.status() == null ? "NEW" : body.status();
        if (!STATUSES.contains(status)) {
            throw invalid("status");
        }
        String note = body.note() == null ? "" : body.note().strip();
        if (note.length() > 500) {
            throw invalid("note");
        }
        String pincode = text(body.pincode());
        if (pincode == null || pincodes.pin(pincode).isEmpty()) {
            throw invalid("pincode");
        }
        boolean tried = !"NEW".equals(status);
        String name = text(body.name());
        String phone = body.phone() == null || body.phone().isBlank() ? null : ClozestMatch.phone10(body.phone());
        if (tried && (name == null || name.length() > 150)) {
            throw invalid("name");
        }
        if (body.phone() != null && !body.phone().isBlank() && phone == null) {
            throw invalid("phone");
        }
        Double lat = round6(body.latitude());
        Double lng = round6(body.longitude());
        if ((lat == null) != (lng == null) || (lat != null && (lat < 6 || lat > 37.6 || lng < 68 || lng > 97.5))) {
            throw invalid("latitude");
        }
        boolean whatsappOk = Boolean.TRUE.equals(body.whatsappOk());
        if (whatsappOk && (!SPOKEN.contains(status) || phone == null)) {
            throw invalid("whatsappOk");
        }

        Lead before = store.lock(mapboxId).orElse(null);
        // Saved on old data comes first: the other checks below compare with what was saved meanwhile
        if (before == null ? body.version() != null : !Objects.equals(body.version(), before.version())) {
            throw changed(before);
        }
        LocalDate followUp = body.followUpOn();
        boolean newDate = followUp != null && !followUp.equals(before == null ? null : before.followUpOn());
        if (newDate && (followUp.isBefore(today) || followUp.isAfter(today.plusYears(1)))) {
            throw invalid("followUpOn");
        }
        UUID assignee = body.assignedTo();
        UUID current = before == null ? null : before.assignedTo();
        if (assignee != null && !assignee.equals(current) && !users.teamRole(assignee).onTeam()) {
            throw invalid("assignedTo");
        }
        if (before != null && !"NEW".equals(before.status()) && !tried) {
            throw invalid("status");
        }
        if (before != null && before.shopId() != null && !before.status().equals(status)) {
            throw new ApiException(HttpStatus.CONFLICT, "LEAD_SIGNED_UP",
                    "This business has signed up on Clozest: its status stays as it was.");
        }
        if (role != TeamRole.SUPER_ADMIN && !Objects.equals(assignee, current)) {
            // A lead given to someone who has left the field team counts as free
            boolean free = current == null || !users.teamRole(current).onTeam();
            boolean takesFree = free && caller.equals(assignee);
            boolean letsGoOwn = caller.equals(current) && assignee == null;
            if (!takesFree && !letsGoOwn) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ASSIGNED_TO_SOMEONE_ELSE",
                        "Only a super admin can give this lead to someone else.");
            }
        }
        boolean wasOk = before != null && before.whatsappOk();
        // Consent is for one number: with a new (or no) phone the old consent ends; someone asks the owner again and
        // ticks it on a later save
        if (wasOk && !Objects.equals(before.phone(), phone)) {
            whatsappOk = false;
        }
        boolean keepConsent = wasOk && whatsappOk;
        Lead after = new Lead(mapboxId, pincode, status, tried ? name : null, tried ? phone : null,
                tried ? lat : null, tried ? lng : null, note, assignee, followUp,
                tried ? (before != null && before.contactedAt() != null ? before.contactedAt() : now) : null,
                whatsappOk, whatsappOk ? (keepConsent ? before.whatsappOkAt() : now) : null,
                whatsappOk ? (keepConsent ? before.whatsappOkBy() : caller) : null,
                before == null ? null : before.shopId(), before == null ? null : before.signedUpAt(),
                before == null ? null : before.creditedTo(), before == null ? 0 : before.version(), now, caller);
        if (before != null && sameWork(before, after)) {
            return leadView(before, people(List.of(before)));
        }
        if (before == null) {
            if (!store.insert(after, now)) {
                throw changed(store.lock(mapboxId).orElse(null));
            }
        } else {
            store.update(after, now);
        }
        events(mapboxId, caller, before, after, now);
        Lead saved = store.lock(mapboxId).orElseThrow();
        return leadView(saved, people(List.of(saved)));
    }

    /** Statuses where someone spoke to the owner (WhatsApp consent can be recorded). */
    static final Set<String> SPOKEN = Set.of("CALLED", "VISITED", "INTERESTED", "NOT_INTERESTED");

    private static Double round6(Double d) {
        return d == null ? null : Math.round(d * 1_000_000d) / 1_000_000d;
    }

    private static boolean sameWork(Lead a, Lead b) {
        return a.pincode().equals(b.pincode()) && a.status().equals(b.status()) && Objects.equals(a.name(), b.name())
                && Objects.equals(a.phone(), b.phone()) && Objects.equals(a.latitude(), b.latitude())
                && Objects.equals(a.longitude(), b.longitude()) && a.note().equals(b.note())
                && Objects.equals(a.assignedTo(), b.assignedTo()) && Objects.equals(a.followUpOn(), b.followUpOn())
                && a.whatsappOk() == b.whatsappOk() && Objects.equals(a.whatsappOkAt(), b.whatsappOkAt());
    }

    private void events(String id, UUID by, Lead before, Lead after, Instant now) {
        if (before == null || !before.status().equals(after.status())) {
            store.event(id, by, "STATUS", before == null ? null : before.status(), after.status(), now);
        }
        if (!(before == null ? "" : before.note()).equals(after.note())) {
            store.event(id, by, "NOTE", before == null ? null : before.note(), after.note(), now);
        }
        if (!Objects.equals(before == null ? null : before.assignedTo(), after.assignedTo())) {
            store.event(id, by, "ASSIGNED", str(before == null ? null : before.assignedTo()), str(after.assignedTo()), now);
        }
        if (!Objects.equals(before == null ? null : before.followUpOn(), after.followUpOn())) {
            store.event(id, by, "FOLLOW_UP", str(before == null ? null : before.followUpOn()), str(after.followUpOn()), now);
        }
        if (before != null && before.phone() != null && !before.phone().equals(after.phone())) {
            store.event(id, by, "PHONE", before.phone(), after.phone(), now);
        }
        if ((before != null && before.whatsappOk()) != after.whatsappOk()) {
            store.event(id, by, "WHATSAPP", String.valueOf(before != null && before.whatsappOk()),
                    String.valueOf(after.whatsappOk()), now);
        }
    }

    /** A lead's history, newest first (at most 50; none for a lead never saved). */
    @Transactional(readOnly = true)
    public List<EventView> history(UUID caller, String mapboxId) {
        requireTeam(caller);
        List<LeadStore.Event> events = store.events(mapboxId);
        Set<UUID> ids = new HashSet<>();
        for (LeadStore.Event e : events) {
            ids.add(e.byUser());
            if ("ASSIGNED".equals(e.field())) {
                ids.add(uuid(e.oldValue()));
                ids.add(uuid(e.newValue()));
            }
        }
        Map<UUID, PersonView> people = names(ids);
        return events.stream().map(e -> {
            String o = e.oldValue();
            String n = e.newValue();
            if ("ASSIGNED".equals(e.field())) {
                o = nameOf(people, uuid(o));
                n = nameOf(people, uuid(n));
            }
            return new EventView(e.at(), e.byUser() == null ? null : people.get(e.byUser()), e.field(), o, n);
        }).toList();
    }

    // ---- Follow-ups, the funnel, usage -----------------------------------------------------------------------------

    /**
     * Follow-ups due today or earlier and not signed up yet, soonest first (at most 200): the caller's own; a super
     * admin may ask for everyone's ({@code everyone}; a field agent asking gets 403 SUPER_ADMIN_ONLY).
     */
    @Transactional(readOnly = true)
    public List<DueView> due(UUID caller, boolean everyone) {
        TeamRole role = requireTeam(caller);
        if (everyone && role != TeamRole.SUPER_ADMIN) {
            throw superAdminOnly();
        }
        LocalDate today = clock.instant().atZone(LeadScanner.INDIA).toLocalDate();
        List<Lead> due = store.due(everyone ? null : caller, today);
        Map<UUID, PersonView> people = people(due);
        return due.stream().map(l -> dueView(l, people)).toList();
    }

    /**
     * My leads: the leads given to the caller that someone tried to reach and that haven't signed up, most recently
     * worked first (at most 300), from their saved details: no map search needed. A super admin may ask for everyone's
     * ({@code everyone}; a field agent asking gets 403 SUPER_ADMIN_ONLY).
     */
    @Transactional(readOnly = true)
    public List<DueView> mine(UUID caller, boolean everyone) {
        TeamRole role = requireTeam(caller);
        if (everyone && role != TeamRole.SUPER_ADMIN) {
            throw superAdminOnly();
        }
        List<Lead> leads = store.worked(everyone ? null : caller);
        Map<UUID, PersonView> people = people(leads);
        return leads.stream().map(l -> dueView(l, people)).toList();
    }

    private DueView dueView(Lead l, Map<UUID, PersonView> people) {
        return new DueView(l.mapboxId(), l.name(), l.phone(), l.latitude(), l.longitude(), l.pincode(),
                pincodes.pin(l.pincode()).map(Pin::places).orElse(l.pincode()), l.status(), l.followUpOn(), l.note(),
                person(people, l.assignedTo()), l.whatsappOk(), l.version());
    }

    /** A district's funnel per area and per person (super admins only: 403 SUPER_ADMIN_ONLY). */
    @Transactional(readOnly = true)
    public FunnelView funnel(UUID caller, String kind, String district, String state) {
        if (requireTeam(caller) != TeamRole.SUPER_ADMIN) {
            throw superAdminOnly();
        }
        PincodeDirectory.Place d = place(kind, district, state);
        List<String> codes = d.pins().stream().map(Pin::pincode).toList();
        Map<String, String> places = new HashMap<>();
        d.pins().forEach(p -> places.put(p.pincode(), p.places()));
        List<FunnelArea> areas = store.funnel(codes).stream()
                .map(f -> new FunnelArea(f.pincode(), places.get(f.pincode()), f.touched(), f.contacted(),
                        f.interested(), f.notInterested(), f.signedUp()))
                .toList();
        List<LeadStore.PersonFunnel> rows = store.people(codes);
        Map<UUID, PersonView> names = names(rows.stream().map(LeadStore.PersonFunnel::userId).toList());
        List<FunnelPerson> people = rows.stream()
                .map(r -> new FunnelPerson(names.getOrDefault(r.userId(), new PersonView(r.userId(), null)),
                        r.assigned(), r.contacted(), r.interested(), r.signedUp()))
                .toList();
        return new FunnelView(d.kind(), d.name(), d.state(), areas, people);
    }

    /** This month's Mapbox requests and the cap (anyone on the field team: the scan shows it too). */
    @Transactional(readOnly = true)
    public UsageView usage(UUID caller) {
        requireTeam(caller);
        return usageView();
    }

    private UsageView usageView() {
        String month = scanner.month();
        return new UsageView(month, store.used(month), scanner.monthlyCap());
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------------

    private TeamRole requireTeam(UUID caller) {
        TeamRole role = users.teamRole(caller);
        if (!role.onTeam()) {
            throw ApiException.notFound("Page");
        }
        return role;
    }

    /**
     * 429 TOO_MANY_REQUESTS (with Retry-After) past app.leads.scans-per-hour scans by one person in an hour (60 by
     * default: a scan costs 40 to 150 Mapbox requests, so one person can't use up the month alone). A scan refused
     * before searching gives its count back. If Redis doesn't answer, the scan goes ahead (the monthly cap still holds).
     */
    private void requireScansLeft(UUID caller) {
        String key = "lead-scans:" + caller;
        try {
            if (!limits.tryHit(key, scansPerHour, Duration.ofHours(1))) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                        "Too many areas searched this hour. Wait a while and try again.")
                        .retryAfter(limits.secondsLeft(key));
            }
        } catch (DataAccessException redisDown) {
            log.warn("Redis didn't answer the lead-scan count ({}); letting it through",
                    redisDown.getClass().getSimpleName());
        }
    }

    private void giveBackScan(UUID caller) {
        try {
            limits.giveBack("lead-scans:" + caller);
        } catch (DataAccessException redisDown) {
            log.warn("Redis didn't take back a lead-scan count ({})", redisDown.getClass().getSimpleName());
        }
    }

    private LeadView leadView(Lead l, Map<UUID, PersonView> people) {
        return new LeadView(l.status(), l.note(), person(people, l.assignedTo()), l.followUpOn(), l.whatsappOk(),
                l.whatsappOkAt(), person(people, l.whatsappOkBy()), l.signedUpAt(), person(people, l.creditedTo()),
                l.version(), l.updatedAt(), person(people, l.updatedBy()));
    }

    private Map<UUID, PersonView> people(Collection<Lead> leads) {
        Set<UUID> ids = new HashSet<>();
        for (Lead l : leads) {
            ids.add(l.assignedTo());
            ids.add(l.whatsappOkBy());
            ids.add(l.creditedTo());
            ids.add(l.updatedBy());
        }
        return names(ids);
    }

    private Map<UUID, PersonView> names(Collection<UUID> ids) {
        Set<UUID> wanted = new HashSet<>(ids);
        wanted.remove(null);
        Map<UUID, PersonView> out = new HashMap<>();
        if (!wanted.isEmpty()) {
            users.find(wanted).forEach((id, p) -> out.put(id, new PersonView(id, p.name())));
        }
        return out;
    }

    private static PersonView person(Map<UUID, PersonView> people, UUID id) {
        return id == null ? null : people.getOrDefault(id, new PersonView(id, null));
    }

    private static String nameOf(Map<UUID, PersonView> people, UUID id) {
        if (id == null) {
            return null;
        }
        PersonView p = people.get(id);
        return p == null || p.name() == null ? "Someone who left the team" : p.name();
    }

    private ApiException changed(Lead now) {
        Map<String, Object> details = new HashMap<>();
        if (now != null) {
            details.put("lead", leadView(now, people(List.of(now))));
        }
        return new ApiException(HttpStatus.CONFLICT, "LEAD_CHANGED",
                "Someone else changed this lead just now. Check it and save again.", details);
    }

    private static ApiException superAdminOnly() {
        return new ApiException(HttpStatus.FORBIDDEN, "SUPER_ADMIN_ONLY", "Only a super admin can do this.");
    }

    private static ApiException invalid(String field) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                Map.of(field, "not valid"));
    }

    private static String text(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static UUID uuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<Lead> concat(Collection<Lead> a, Collection<Lead> b) {
        List<Lead> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
