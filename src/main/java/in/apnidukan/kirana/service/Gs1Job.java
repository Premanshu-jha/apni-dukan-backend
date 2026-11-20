package in.apnidukan.kirana.service;

import in.apnidukan.common.config.KiranaSwitch;
import in.apnidukan.common.files.PlatformFiles;
import in.apnidukan.common.jobs.JobLeases;
import in.apnidukan.kirana.domain.NameMatcher;
import in.apnidukan.kirana.domain.PhotoRules;
import in.apnidukan.kirana.domain.Product;
import in.apnidukan.kirana.domain.Unit;
import in.apnidukan.kirana.repo.CatalogueStore;
import in.apnidukan.kirana.repo.Gs1Store;
import in.apnidukan.kirana.repo.Gs1Store.Lookup;
import in.apnidukan.kirana.repo.OnlineStore;
import in.apnidukan.kirana.repo.ProductStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Looks products up on GS1 DataKart (only when it is set up), 10 at a time, one server at a time:
 * <ul>
 * <li>CODE, a catalogue barcode whose item has no picture: GS1's product must be ours (brand, name and size agree,
 * NameMatcher), and its picture must show it (the AI photo check, PhotoRules); then it is the item's picture, for
 * every shop selling it, and the item's missing brand and size are filled in.</li>
 * <li>NAME, a shop's product added without a barcode: found by name, only on a clear match (the same size, no
 * contradicting variant, a sure score, and no second product as likely). Its barcode goes into the shared catalogue
 * (from GS1: trusted) and the product is linked to that item, so its picture comes as above and its first scan at the
 * counter offers it in one tap ("Needs a tap"); the product's own barcode is only set by the shop.</li>
 * </ul>
 * Not found: looked up again a month later. GS1 or the AI unreachable: tried again in 30
 * minutes, for a day, then a month later. A wrong key or address: nothing is looked up (logged) until it is fixed. No AI call or picture
 * is fetched for a lookup no longer needed (the item got a picture, the product a barcode).
 */
@Service
public class Gs1Job {

    static final int BATCH = 10;
    /** Held this long when taken: longer than a run of {@link #BATCH} can take (each at most a minute or so). */
    private static final Duration HOLD = Duration.ofMinutes(30);
    /** Lookups failing this often for reasons other than GS1 or the AI being down are given up (REFUSED). */
    static final int MAX_TRIES = 8;
    /**
     * Lookups GS1 or the AI couldn't answer this often in a row (a day of runs) are left for a month (NOT_FOUND): one
     * that never answers isn't asked every 30 minutes forever.
     */
    static final int MAX_WAITS = 48;
    private static final Duration AGAIN = Duration.ofDays(30);
    private static final Duration REFUSED_WAIT = Duration.ofMinutes(15);
    private static final Logger log = LoggerFactory.getLogger(Gs1Job.class);

    private final Gs1Store lookups;
    private final DataKart gs1;
    private final CatalogueStore catalogue;
    private final ProductStore products;
    private final PlatformFiles files;
    private final KiranaDocumentAi ai;
    private final OnlineStore online;
    private final JobLeases leases;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int checksPerDay;
    private final KiranaSwitch kirana;

    public Gs1Job(Gs1Store lookups, DataKart gs1, CatalogueStore catalogue, ProductStore products,
                  PlatformFiles files, KiranaDocumentAi ai, OnlineStore online, JobLeases leases,
                  TransactionTemplate transactions, Clock clock,
                  @Value("${app.kirana.gs1.checks-per-day:1000}") int checksPerDay, KiranaSwitch kirana) {
        this.kirana = kirana;
        this.lookups = lookups;
        this.gs1 = gs1;
        this.catalogue = catalogue;
        this.products = products;
        this.files = files;
        this.ai = ai;
        this.online = online;
        this.leases = leases;
        this.transactions = transactions;
        this.clock = clock;
        this.checksPerDay = checksPerDay;
    }

    @Scheduled(fixedDelayString = "${app.kirana.gs1.every-ms:60000}",
            initialDelayString = "${app.kirana.jobs-delay-ms:50000}")
    public void run() {
        if (kirana.off()) {
            return;   // kirana switched off: nothing is asked of GS1 or the AI
        }
        if (!gs1.configured()) {
            return;   // switched off until GS1_DATAKART_URL and GS1_DATAKART_KEY are set
        }
        leases.runExclusively("kirana-gs1", Duration.ofMinutes(30), () -> {
            Instant now = KiranaChecks.now(clock);
            List<Lookup> due = transactions.execute(s -> lookups.claim(now, now.plus(HOLD), BATCH));
            due = due == null ? List.of() : due;
            for (int i = 0; i < due.size(); i++) {
                Lookup l = due.get(i);
                try {
                    if ("CODE".equals(l.kind())) {
                        picture(l);
                    } else {
                        byName(l);
                    }
                } catch (DataKart.Refused e) {
                    // The key (or the address) is wrong: every lookup would fail the same way. This one and those not
                    // reached are given back as they were (the try not counted), to be taken again in 15 minutes
                    log.error("GS1 DataKart isn't usable, nothing is looked up: {}", e.getMessage());
                    List<UUID> back = due.subList(i, due.size()).stream().map(Lookup::id).toList();
                    Instant at = KiranaChecks.now(clock);
                    transactions.executeWithoutResult(s -> lookups.giveBack(back, at.plus(REFUSED_WAIT), at));
                    return;
                } catch (DataKart.Unavailable | KiranaDocumentAi.Unavailable e) {
                    log.warn("GS1 lookup {} waits ({})", l.id(), e.getMessage());   // taken again after HOLD
                    if (l.tries() >= MAX_WAITS) {
                        notFound(l, "GS1 or the AI didn't answer for a day: " + e.getMessage(),
                                KiranaChecks.now(clock));
                    }
                } catch (RuntimeException e) {
                    log.error("GS1 lookup {} failed", l.id(), e);
                    if (l.tries() >= MAX_TRIES) {
                        end(l, "REFUSED", null, "Couldn't be looked up: " + e.getClass().getSimpleName(),
                                KiranaChecks.now(clock));
                    }
                }
            }
        });
    }

    /** A catalogue item's official picture, by its barcode. */
    void picture(Lookup l) {
        Instant now = KiranaChecks.now(clock);
        Optional<CatalogueStore.Item> found = catalogue.find(l.itemId());
        if (found.isEmpty() || !catalogue.photos(List.of(l.itemId())).isEmpty()) {
            end(l, "FOUND", null, "Nothing to do: the item has a picture, or is gone", now);
            return;
        }
        CatalogueStore.Item item = found.get();
        Optional<DataKart.Gs1Product> known = gs1.byGtin(l.code());
        if (known.isEmpty()) {
            notFound(l, "GS1 doesn't know this barcode", now);
            return;
        }
        DataKart.Gs1Product g = known.get();
        if (!sameProduct(item.name(), item.brand(), item.size(), g)) {
            end(l, "REFUSED", null, "GS1 says this barcode is " + describe(g) + ", not " + item.name(), now);
            return;
        }
        transactions.executeWithoutResult(s -> catalogue.fillIn(item.id(), g.brand(), g.netContent(), null, null,
                now));
        boolean loose = item.unit() == Unit.KG;
        for (String link : g.pictures()) {
            Optional<byte[]> bytes = gs1.picture(link);
            if (bytes.isEmpty()) {
                continue;
            }
            Optional<PlatformFiles.Stored> stored = files.storePicture(bytes.get(), l.code() + ".jpg");
            if (stored.isEmpty()) {
                continue;   // not a JPEG or PNG we can read
            }
            if (!online.takeGs1AiCall(KiranaChecks.today(now), checksPerDay)) {
                files.discard(stored.get().fileId());
                hold(l, tomorrow(now), now);   // the day's GS1 picture checks are used: tomorrow
                return;
            }
            PhotoRules.Seen seen;
            try {
                seen = ai.checkProductPhoto(List.of(new KiranaDocumentAi.Page(stored.get().jpeg(), "image/jpeg")),
                        item.name(), loose);
            } catch (KiranaDocumentAi.Unreadable e) {
                files.discard(stored.get().fileId());
                continue;   // the AI can't make this picture out: the next one, if any
            } catch (RuntimeException e) {
                files.discard(stored.get().fileId());
                throw e;
            }
            PhotoRules.Verdict v = PhotoRules.decide(item.name(), loose, seen, false);
            // GS1 said what the size is, and it agreed with ours: a picture not showing it is still this product
            if (!v.ok() && !"NEED_SIZE".equals(v.status())) {
                files.discard(stored.get().fileId());
                end(l, "REFUSED", null, "GS1's picture: " + v.reason(), now);
                return;
            }
            UUID fileId = stored.get().fileId();
            Boolean set = transactions.execute(s -> catalogue.setPhotoIfNone(item.id(), fileId));
            if (!Boolean.TRUE.equals(set)) {
                files.discard(fileId);   // a shop's checked photo got there first
            }
            end(l, "FOUND", null, null, now);
            return;
        }
        notFound(l, "GS1 has no picture of it", now);
    }

    /** A shop's product added without a barcode: its barcode, by name. */
    void byName(Lookup l) {
        Instant now = KiranaChecks.now(clock);
        Optional<Product> found = products.find(l.shopId(), l.productId());
        if (found.isEmpty() || !found.get().active() || !products.barcodes(l.productId()).isEmpty()
                || products.catalogueItemOf(l.productId()) != null) {
            end(l, "FOUND", null, "Nothing to do: the product has a barcode or a catalogue item, or is removed", now);
            return;
        }
        Product p = found.get();
        NameMatcher.Parsed want = NameMatcher.parse(p.name(), null, null);
        List<DataKart.Gs1Product> candidates = gs1.search(p.name()).stream()
                .filter(g -> g.gtin() != null && g.gtin().matches("[0-9]{8,14}")).toList();
        // The rule a one-tap match follows everywhere: sure, and clearly ahead of any other likely product
        // One per barcode (its best score): the lead is measured against the next other product
        java.util.Map<String, Scored> best = new java.util.LinkedHashMap<>();
        for (DataKart.Gs1Product g : candidates) {
            Scored x = new Scored(g, score(want, g));
            best.merge(g.gtin(), x, (a, b) -> a.score() >= b.score() ? a : b);
        }
        List<Scored> likely = best.values().stream().filter(x -> x.score() >= NameMatcher.LIKELY)
                .sorted(Comparator.comparingDouble(Scored::score).reversed()).toList();
        if (likely.isEmpty() || likely.get(0).score() < NameMatcher.SURE) {
            notFound(l, "No clear match on GS1", now);
            return;
        }
        if (likely.size() > 1 && likely.get(0).score() - likely.get(1).score() < NameMatcher.LEAD) {
            notFound(l, "More than one product on GS1 matches it", now);   // never a guess between two
            return;
        }
        DataKart.Gs1Product g = likely.get(0).g();
        transactions.executeWithoutResult(s -> {
            // The product locked first, as a rename does, then the lookup: renamed (or matched, or refused) while GS1
            // was asked, this answer is for a name it no longer has, and is dropped
            Optional<Product> current = products.lock(l.shopId(), l.productId());
            if (current.isEmpty() || !current.get().name().equals(p.name()) || current.get().unit() != p.unit()
                    || !lookups.stillOpen(l.id())) {
                return;
            }
            // The barcode's catalogue item (made from GS1's details if new), and this product linked to it
            UUID itemId = catalogue.link(g.gtin()).map(CatalogueStore.Link::itemId).orElse(null);
            if (itemId == null) {
                UUID id = UUID.randomUUID();
                catalogue.insertItem(new CatalogueStore.Item(id, itemName(g), g.brand(), g.netContent(), p.unit(),
                        null, null, 0, now), null, now);
                if (catalogue.addCode(id, g.gtin(), BigDecimal.ONE, "GS1", true, now)) {
                    itemId = id;
                } else {
                    catalogue.deleteItem(id);   // another shop's barcode won the race: its item is used
                    itemId = catalogue.link(g.gtin()).map(CatalogueStore.Link::itemId).orElse(null);
                }
            }
            if (itemId != null) {
                products.linkCatalogueIfNone(p.id(), itemId);
            }
            lookups.done(l.id(), "FOUND", g.gtin(), "Found on GS1: " + describe(g), now, now);
        });
    }

    private record Scored(DataKart.Gs1Product g, double score) {
    }

    /** How sure GS1's product is ours by name: 0 when the size or a variant contradicts, or either size is missing. */
    private static double score(NameMatcher.Parsed want, DataKart.Gs1Product g) {
        NameMatcher.Parsed seen = NameMatcher.parse(itemName(g), g.brand(), g.netContent());
        NameMatcher.Score s = NameMatcher.score(seen, want);
        return s.never() || !s.sizeKnownBoth() || PhotoRules.variantsContradict(seen, want) ? 0 : s.value();
    }

    /** GS1's product is the one we have under this barcode: the same size, no contradicting variant, likely by name. */
    static boolean sameProduct(String name, String brand, String size, DataKart.Gs1Product g) {
        NameMatcher.Parsed ours = NameMatcher.parse(name, brand, size);
        NameMatcher.Parsed theirs = NameMatcher.parse(itemName(g), g.brand(), g.netContent());
        NameMatcher.Score s = NameMatcher.score(theirs, ours);
        return !s.never() && !PhotoRules.variantsContradict(theirs, ours) && s.value() >= NameMatcher.LIKELY;
    }

    /** GS1's product named as the catalogue names items: brand, name and size. */
    private static String itemName(DataKart.Gs1Product g) {
        String name = g.name();
        if (g.brand() != null && !name.toLowerCase(java.util.Locale.ROOT)
                .contains(g.brand().toLowerCase(java.util.Locale.ROOT))) {
            name = g.brand() + " " + name;
        }
        if (g.netContent() != null && NameMatcher.parse(name, null, null).size() == null) {
            name = name + " " + g.netContent();
        }
        return name.length() > 100 ? name.substring(0, 100).strip() : name;
    }

    private static String describe(DataKart.Gs1Product g) {
        return itemName(g);
    }

    private void end(Lookup l, String status, String code, String note, Instant now) {
        transactions.executeWithoutResult(s -> lookups.done(l.id(), status, code, note, now, now));
    }

    private void notFound(Lookup l, String note, Instant now) {
        transactions.executeWithoutResult(s -> lookups.done(l.id(), "NOT_FOUND", null, note, now.plus(AGAIN), now));
    }

    private void hold(Lookup l, Instant until, Instant now) {
        transactions.executeWithoutResult(s -> lookups.done(l.id(), l.status(), null, null, until, now));
    }

    private static Instant tomorrow(Instant now) {
        LocalDate today = KiranaChecks.today(now);
        return today.plusDays(1).atTime(0, 5).atZone(KiranaChecks.ZONE).toInstant();
    }
}
