package in.apnidukan.kirana.service;

import in.apnidukan.common.files.FileRefs;
import in.apnidukan.kirana.api.StockResponses.CounterSuggestions;
import in.apnidukan.kirana.api.StockResponses.SuggestedProduct;
import in.apnidukan.kirana.domain.Product;
import in.apnidukan.kirana.domain.Quantities;
import in.apnidukan.kirana.repo.BillStore;
import in.apnidukan.kirana.repo.BillStore.Often;
import in.apnidukan.kirana.repo.KhataStore;
import in.apnidukan.kirana.repo.ProductStore;
import in.apnidukan.kirana.service.KiranaAccess.Caller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What to offer at the counter (owner or helper), from the shop's own bills of the last 14 days: what sells around
 * this hour, what this khata customer usually buys, and what is often on the same bill as what is on it now. Each list
 * is counted in the database once per 10 minutes and kept in Redis for every server (Redis not answering only means
 * counting again); removed products and products out of stock are left out when shown.
 */
@Service
public class SuggestionService {

    private static final Logger log = LoggerFactory.getLogger(SuggestionService.class);

    /** Bills looked at. */
    static final Duration LOOK_BACK = Duration.ofDays(14);
    /** How long a counted list is kept. */
    static final Duration KEEP = Duration.ofMinutes(10);
    /** Products in each list. */
    static final int SHOWN = 8;
    /** The latest items on the bill that "often bought together" looks at. */
    static final int WITH_MAX = 5;

    private final KiranaAccess access;
    private final BillStore bills;
    private final ProductStore products;
    private final KhataStore khata;
    private final StringRedisTemplate redis;
    private final FileRefs files;
    private final Clock clock;

    public SuggestionService(KiranaAccess access, BillStore bills, ProductStore products, KhataStore khata,
                             StringRedisTemplate redis, FileRefs files, Clock clock) {
        this.access = access;
        this.bills = bills;
        this.products = products;
        this.khata = khata;
        this.redis = redis;
        this.files = files;
        this.clock = clock;
    }

    /**
     * The three lists. {@code khataCustomerId}: the bill's khata customer, if chosen (another shop's is ignored);
     * {@code with}: products on the bill now, latest last (only the last 5 are looked at).
     */
    @Transactional(readOnly = true)
    public CounterSuggestions suggest(UUID userId, UUID khataCustomerId, List<UUID> with) {
        Caller c = access.caller(userId);
        Instant now = KiranaChecks.now(clock);
        Instant since = now.minus(LOOK_BACK);
        int hour = now.atZone(KiranaChecks.ZONE).getHour();
        UUID shop = c.shopId();
        List<Often> soldNow = cached("kirana:suggest:" + shop + ":hour:" + hour,
                () -> bills.soldAtThisHour(shop, since, hour, SHOWN * 2));
        List<Often> usual = List.of();
        if (khataCustomerId != null && khata.find(shop, khataCustomerId).isPresent()) {
            usual = cached("kirana:suggest:" + shop + ":khata:" + khataCustomerId,
                    () -> bills.usualFor(shop, khataCustomerId, since, SHOWN * 2));
        }
        List<UUID> onBill = with == null ? List.of() : with.stream().filter(java.util.Objects::nonNull).distinct()
                .toList();
        List<UUID> latest = onBill.subList(Math.max(0, onBill.size() - WITH_MAX), onBill.size()).stream().sorted()
                .toList();
        List<Often> together = latest.isEmpty() ? List.of() : cached("kirana:suggest:" + shop + ":with:"
                + String.join(",", latest.stream().map(UUID::toString).toList()),
                () -> bills.boughtWith(shop, latest, since, SHOWN * 2));
        // The products of all three lists read in one query; what is on the bill already isn't offered again
        Set<UUID> ids = new HashSet<>();
        soldNow.forEach(o -> ids.add(o.productId()));
        usual.forEach(o -> ids.add(o.productId()));
        together.forEach(o -> ids.add(o.productId()));
        Map<UUID, Product> found = new HashMap<>();
        for (Product p : products.findAll(shop, ids)) {
            found.put(p.id(), p);
        }
        Map<UUID, ProductStore.Extra> extras = products.extras(found.keySet());
        Map<UUID, String> links = files.links(extras.values().stream().map(ProductStore.Extra::photoFileId)
                .filter(java.util.Objects::nonNull).distinct().toList());
        Set<UUID> skip = new HashSet<>(onBill);
        return new CounterSuggestions(shown(soldNow, found, extras, links, skip),
                shown(usual, found, extras, links, skip), shown(together, found, extras, links, skip));
    }

    private static List<SuggestedProduct> shown(List<Often> list, Map<UUID, Product> found,
                                                Map<UUID, ProductStore.Extra> extras, Map<UUID, String> links,
                                                Set<UUID> skip) {
        List<SuggestedProduct> out = new ArrayList<>();
        for (Often o : list) {
            Product p = found.get(o.productId());
            if (p == null || !p.active() || p.stock().signum() <= 0 || skip.contains(p.id())) {
                continue;
            }
            ProductStore.Extra x = extras.get(p.id());
            UUID photo = x == null ? null : x.photoFileId();
            out.add(new SuggestedProduct(p.id(), p.name(), p.unit(), p.pricePaise(), Quantities.json(p.stock()),
                    photo, photo == null ? null : links.get(photo), o.times()));
            if (out.size() == SHOWN) {
                break;
            }
        }
        return out;
    }

    /** A list from Redis while kept, else counted and kept ("id:times,id:times"). */
    private List<Often> cached(String key, Supplier<List<Often>> count) {
        try {
            String kept = redis.opsForValue().get(key);
            if (kept != null) {
                return parse(kept);
            }
        } catch (DataAccessException redisDown) {
            log.warn("Redis didn't answer for counter suggestions ({})", redisDown.getClass().getSimpleName());
            return count.get();
        }
        List<Often> counted = count.get();
        try {
            redis.opsForValue().set(key, format(counted), KEEP);
        } catch (DataAccessException redisDown) {
            log.warn("Redis didn't keep counter suggestions ({})", redisDown.getClass().getSimpleName());
        }
        return counted;
    }

    static String format(List<Often> list) {
        StringBuilder s = new StringBuilder();
        for (Often o : list) {
            if (!s.isEmpty()) {
                s.append(',');
            }
            s.append(o.productId()).append(':').append(o.times());
        }
        return s.toString();
    }

    static List<Often> parse(String kept) {
        List<Often> out = new ArrayList<>();
        if (kept.isEmpty()) {
            return out;
        }
        for (String part : kept.split(",")) {
            int at = part.indexOf(':');
            try {
                out.add(new Often(UUID.fromString(part.substring(0, at)), Long.parseLong(part.substring(at + 1))));
            } catch (RuntimeException e) {
                return List.of();   // something else under the key: counted again next time
            }
        }
        return out;
    }
}
