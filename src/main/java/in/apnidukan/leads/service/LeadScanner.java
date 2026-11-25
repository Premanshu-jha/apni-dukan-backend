package in.apnidukan.leads.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.leads.repo.LeadStore;
import in.apnidukan.leads.repo.PincodeDirectory.Pin;
import in.apnidukan.leads.service.MapboxSearch.Box;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Finds the salon and beauty businesses of one pincode on Mapbox, live (results are used for the answer and never
 * stored, as Mapbox's terms require).
 * <ol>
 *   <li>Where the pincode is: Mapbox's own postcode (its box when it gives one, else 2 km or the directory's spread
 *       around its middle); India Post's point when Mapbox doesn't know the pincode; 404 AREA_NOT_ON_MAP when neither
 *       does.</li>
 *   <li>Each of {@link PoiKinds#CATEGORIES} in the box; a box that comes back full (25) is searched again as four
 *       quarters, at most {@link #MAX_DEPTH} times, so busy areas are covered too.</li>
 *   <li>Each of {@link PoiKinds#SERVICE_WORDS} in the box (businesses filed under no beauty category).</li>
 * </ol>
 * Businesses Mapbox puts in another pincode are left out (they belong to that area's scan); those with no pincode
 * stay. Only {@link PoiKinds#relevant} ones are kept.
 *
 * <p>Limits, so many people scanning at once can't overload the server or run up the Mapbox bill:
 * <ul>
 *   <li>every request waits for its turn, then is counted in mapbox_usage before it is sent, and none is sent past the month's cap
 *       (MAPBOX_MONTHLY_CAP): a scan cut short says so ({@link Found#capReached}); one that can't start answers 429
 *       MAPBOX_CAP_REACHED;</li>
 *   <li>at most {@code app.leads.scans-at-once} scans at a time on this server (503 SCAN_BUSY with Retry-After past
 *       that) and {@code app.leads.requests-at-once} Mapbox requests at a time across them;</li>
 *   <li>a scan stops waiting after {@link #SCAN_TIMEOUT}; what came back by then is answered, marked partial.</li>
 * </ul>
 * Without MAPBOX_TOKEN every scan answers 503 MAPBOX_OFF; a token Mapbox refuses answers 503 MAPBOX_REFUSED; Mapbox
 * not answering where the pincode is (and India Post having no point for it) answers 503 MAPBOX_UNAVAILABLE.
 */
@Service
public class LeadScanner {

    private static final Logger log = LoggerFactory.getLogger(LeadScanner.class);
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    /** A full box is split into quarters at most this many times (up to 1 + 4 + 16 + 64 requests a category). */
    static final int MAX_DEPTH = 3;
    static final int PAGE = 25;
    static final Duration SCAN_TIMEOUT = Duration.ofSeconds(60);
    /** Mapbox's postcode boxes bigger than this (about 30 km) are replaced by 8 km around the middle. */
    static final double MAX_BOX_DEGREES = 0.27;

    /**
     * What a scan found.
     *
     * @param pois       the relevant businesses, each once
     * @param requests   Mapbox requests made
     * @param failed     requests Mapbox didn't answer (each leaves a small gap)
     * @param capReached the month's cap stopped the scan before it finished
     * @param timedOut   the scan ran out of time before it finished
     */
    public record Found(List<Poi> pois, int requests, int failed, boolean capReached, boolean timedOut,
                        boolean truncated) {
        public Found {
            pois = List.copyOf(pois);
        }

        /** Some of the search didn't finish: the list may miss businesses (scanning again later fills the gaps). */
        public boolean partial() {
            return failed > 0 || capReached || timedOut || truncated;
        }
    }

    private final LeadStore store;
    private final Clock clock;
    private final MapboxSearch mapbox;
    private final long monthlyCap;
    private final Semaphore scans;
    private final Semaphore requests;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public LeadScanner(LeadStore store, Clock clock,
                       @Value("${app.leads.mapbox-token:}") String token,
                       @Value("${app.leads.mapbox-url:}") String url,
                       @Value("${app.leads.mapbox-monthly-cap:50000}") long monthlyCap,
                       @Value("${app.leads.scans-at-once:4}") int scansAtOnce,
                       @Value("${app.leads.requests-at-once:8}") int requestsAtOnce) {
        this(store, clock, token.isBlank() ? null
                        : new MapboxSearch(token.strip(), url.isBlank() ? MapboxSearch.MAPBOX : URI.create(url.strip())),
                monthlyCap, scansAtOnce, requestsAtOnce);
    }

    LeadScanner(LeadStore store, Clock clock, MapboxSearch mapbox, long monthlyCap, int scansAtOnce,
                int requestsAtOnce) {
        this.store = store;
        this.clock = clock;
        this.mapbox = mapbox;
        this.monthlyCap = monthlyCap;
        this.scans = new Semaphore(Math.max(1, scansAtOnce), true);
        this.requests = new Semaphore(Math.max(1, requestsAtOnce), true);
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        pool.shutdownNow();
    }

    public boolean on() {
        return mapbox != null;
    }

    public long monthlyCap() {
        return monthlyCap;
    }

    /** This month's requests so far, in India's calendar (yyyy-MM). */
    public String month() {
        return clock.instant().atZone(INDIA).format(MONTH);
    }

    public Found scan(Pin pin) {
        if (mapbox == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MAPBOX_OFF",
                    "Searching the map isn't set up yet (MAPBOX_TOKEN).");
        }
        if (!scans.tryAcquire()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SCAN_BUSY",
                    "Many areas are being searched right now. Trying again in a few seconds.").retryAfter(5);
        }
        try {
            return run(pin);
        } finally {
            scans.release();
        }
    }

    private Found run(Pin pin) {
        String month = month();
        long deadline = System.nanoTime() + SCAN_TIMEOUT.toNanos();
        Run r = new Run(month, deadline);
        Box box = area(pin, r);

        Map<String, Poi> found = new ConcurrentHashMap<>();
        List<Callable<List<Split>>> level = new ArrayList<>();
        for (String category : PoiKinds.CATEGORIES) {
            level.add(() -> category(r, new Split(category, box), found));
        }
        for (String words : PoiKinds.SERVICE_WORDS) {
            level.add(() -> {
                r.call(() -> mapbox.words(words, box)).ifPresent(list -> add(found, list));
                return List.of();
            });
        }
        for (int depth = 0; !level.isEmpty() && !r.stopped(); depth++) {
            List<Callable<List<Split>>> next = new ArrayList<>();
            for (List<Split> splits : runAll(level, deadline, r)) {
                if (depth < MAX_DEPTH) {
                    for (Split s : splits) {
                        next.add(() -> category(r, s, found));
                    }
                } else if (!splits.isEmpty()) {
                    // Still full at the smallest box: some businesses there weren't listed
                    r.truncated.set(true);
                }
            }
            level = next;
        }
        if (r.refused.get()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MAPBOX_REFUSED",
                    "Mapbox refused the access token. Check MAPBOX_TOKEN.");
        }
        List<Poi> kept = new ArrayList<>();
        for (Poi p : found.values()) {
            if ((p.postcode() == null || p.postcode().equals(pin.pincode())) && PoiKinds.relevant(p)) {
                kept.add(p);
            }
        }
        return new Found(List.copyOf(kept), r.made.get(), r.failed.get(), r.capReached.get(), r.timedOut.get(),
                r.truncated.get());
    }

    /** The box to search: see the class comment, step 1. */
    private Box area(Pin pin, Run r) {
        Optional<MapboxSearch.Postcode> postcode = r.call(() -> mapbox.postcode(pin.pincode())).flatMap(o -> o);
        if (r.refused.get()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MAPBOX_REFUSED",
                    "Mapbox refused the access token. Check MAPBOX_TOKEN.");
        }
        if (r.capReached.get()) {
            throw capReached();
        }
        if (postcode.isPresent()) {
            MapboxSearch.Postcode p = postcode.get();
            Box b = p.box();
            boolean usable = b != null && b.east() > b.west() && b.north() > b.south();
            if (usable && b.east() - b.west() <= MAX_BOX_DEGREES && b.north() - b.south() <= MAX_BOX_DEGREES) {
                return b;
            }
            // No box (or a point): 2 km or India Post's spread around the middle; a huge one: 8 km around it
            return Box.around(p.latitude(), p.longitude(), usable ? 8000 : Math.max(2000, pin.radiusM()));
        }
        if (pin.latitude() != null) {
            return Box.around(pin.latitude(), pin.longitude(), pin.radiusM());
        }
        if (r.failed.get() > 0) {
            // Mapbox didn't answer: that says nothing about the pincode
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MAPBOX_UNAVAILABLE",
                    "The map isn't answering right now. Try this area again in a minute.").retryAfter(60);
        }
        throw new ApiException(HttpStatus.NOT_FOUND, "AREA_NOT_ON_MAP",
                "This pincode isn't on the map, so it can't be searched.");
    }

    /** One category in one box; the box's quarters to search next when it came back full. */
    private List<Split> category(Run r, Split split, Map<String, Poi> found) {
        Optional<List<Poi>> answer = r.call(() -> mapbox.category(split.category(), split.box(), PAGE));
        if (answer.isEmpty()) {
            return List.of();
        }
        add(found, answer.get());
        return answer.get().size() >= PAGE
                ? split.box().quarters().stream().map(q -> new Split(split.category(), q)).toList()
                : List.of();
    }

    private List<List<Split>> runAll(List<Callable<List<Split>>> tasks, long deadline, Run r) {
        List<Future<List<Split>>> futures = new ArrayList<>();
        for (Callable<List<Split>> t : tasks) {
            futures.add(pool.submit(t));
        }
        List<List<Split>> out = new ArrayList<>();
        for (Future<List<Split>> f : futures) {
            long left = deadline - System.nanoTime();
            try {
                out.add(left <= 0 ? timeout(f, r) : f.get(left, TimeUnit.NANOSECONDS));
            } catch (java.util.concurrent.TimeoutException e) {
                out.add(timeout(f, r));
            } catch (ExecutionException e) {
                log.warn("Leads scan task failed: {}", e.getCause().getClass().getSimpleName());
                r.failed.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                r.timedOut.set(true);
                break;
            }
        }
        return out;
    }

    private static List<Split> timeout(Future<List<Split>> f, Run r) {
        f.cancel(true);
        r.timedOut.set(true);
        return List.of();
    }

    private static void add(Map<String, Poi> found, Collection<Poi> pois) {
        for (Poi p : pois) {
            found.merge(p.mapboxId(), p, Poi::fill);
        }
    }

    private ApiException capReached() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "MAPBOX_CAP_REACHED",
                "This month's map searches are used up (" + monthlyCap + "). Raise MAPBOX_MONTHLY_CAP or wait for next month.");
    }

    /** A box still to search for one category (the whole area first, then quarters of a full one). */
    private record Split(String category, Box box) {
    }

    /** One scan's counts and how it ended. */
    private final class Run {
        final String month;
        final AtomicInteger made = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicBoolean capReached = new AtomicBoolean();
        final AtomicBoolean refused = new AtomicBoolean();
        final AtomicBoolean timedOut = new AtomicBoolean();
        final AtomicBoolean truncated = new AtomicBoolean();
        final long deadline;

        Run(String month, long deadline) {
            this.month = month;
            this.deadline = deadline;
        }

        boolean stopped() {
            return capReached.get() || refused.get() || timedOut.get();
        }

        /**
         * Waits for a turn (until the scan's deadline), then counts the request against the month's cap and sends it;
         * empty if it wasn't sent or failed. The turn comes first, so requests waiting never hold a database
         * connection; a turn that never came counts as a failure (the answer is marked partial).
         */
        <T> Optional<T> call(java.util.function.Supplier<T> request) {
            if (stopped()) {
                return Optional.empty();
            }
            boolean turn = false;
            try {
                long left = deadline - System.nanoTime();
                turn = left > 0 && requests.tryAcquire(left, TimeUnit.NANOSECONDS);
                if (!turn) {
                    failed.incrementAndGet();
                    return Optional.empty();
                }
                if (stopped()) {
                    return Optional.empty();
                }
                if (!store.reserve(month, 1, monthlyCap)) {
                    capReached.set(true);
                    return Optional.empty();
                }
                made.incrementAndGet();
                return Optional.ofNullable(request.get());
            } catch (MapboxSearch.Refused e) {
                refused.set(true);
                return Optional.empty();
            } catch (MapboxSearch.Unavailable e) {
                failed.incrementAndGet();
                return Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } finally {
                if (turn) {
                    requests.release();
                }
            }
        }
    }
}
