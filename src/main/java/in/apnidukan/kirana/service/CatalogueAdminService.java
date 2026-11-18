package in.apnidukan.kirana.service;

import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.kirana.api.StockResponses.ImportProblem;
import in.apnidukan.kirana.api.StockResponses.ImportResult;
import in.apnidukan.kirana.api.StockResponses.ReviewItem;
import in.apnidukan.kirana.api.StockResponses.ReviewView;
import in.apnidukan.kirana.domain.NameMatcher;
import in.apnidukan.kirana.domain.Unit;
import in.apnidukan.kirana.repo.CatalogueStore;
import in.apnidukan.kirana.repo.CatalogueStore.Item;
import in.apnidukan.kirana.repo.CatalogueStore.Review;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The shared catalogue's admin side (super admins): the review list (shops disagreeing on a barcode, a corrected
 * trusted link, an imported row that clashes), and importing a distributor's product master (CSV) so the catalogue
 * knows its barcodes before shops scan them.
 */
@Service
public class CatalogueAdminService {

    /** Rows one import may have. */
    static final int IMPORT_MAX = 5000;
    /** Bytes one import may have. */
    static final long IMPORT_MAX_BYTES = 2L * 1024 * 1024;
    private static final int REVIEWS_MAX = 100;

    private final CatalogueService catalogueService;
    private final CatalogueStore catalogue;
    private final ShopDirectory shops;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CatalogueAdminService(CatalogueService catalogueService, CatalogueStore catalogue, ShopDirectory shops,
                                 TransactionTemplate transactions, Clock clock) {
        this.catalogueService = catalogueService;
        this.catalogue = catalogue;
        this.shops = shops;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** Open reviews, oldest first, with both items and how many shops say each. */
    @Transactional(readOnly = true)
    public List<ReviewView> reviews(UUID adminId) {
        catalogueService.requireSuperAdmin(adminId);
        List<Review> open = catalogue.openReviews(REVIEWS_MAX);
        Set<UUID> ids = new HashSet<>();
        Set<UUID> shopIds = new HashSet<>();
        for (Review r : open) {
            if (r.itemId() != null) {
                ids.add(r.itemId());
            }
            if (r.otherItemId() != null) {
                ids.add(r.otherItemId());
            }
            if (r.shopId() != null) {
                shopIds.add(r.shopId());
            }
        }
        Map<UUID, Item> items = catalogue.items(ids);
        Map<UUID, ShopDirectory.ShopCard> cards = shops.findAll(shopIds);
        List<ReviewView> out = new ArrayList<>();
        for (Review r : open) {
            Map<UUID, Integer> votes = catalogue.votesByItem(r.code());
            out.add(new ReviewView(r.id(), r.code(), r.kind(), item(items.get(r.itemId()), votes),
                    item(items.get(r.otherItemId()), votes),
                    r.shopId() != null && cards.containsKey(r.shopId()) ? cards.get(r.shopId()).name() : null,
                    r.note(), r.createdAt()));
        }
        return out;
    }

    /**
     * The team decides which item the barcode is (one of the review's two, or any other catalogue item when both are
     * wrong): trusted from now on for every shop; a losing item nothing else uses goes. 404 REVIEW_NOT_FOUND; 409
     * REVIEW_DONE; 400 naming itemId for an item that doesn't exist.
     */
    @Transactional
    public void decide(UUID adminId, UUID reviewId, UUID itemId) {
        catalogueService.requireSuperAdmin(adminId);
        Review r = catalogue.lockReview(reviewId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "REVIEW_NOT_FOUND", "No such review.", Map.of()));
        if (!"OPEN".equals(r.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "REVIEW_DONE", "Someone decided this already.", Map.of());
        }
        if (itemId == null || catalogue.find(itemId).isEmpty()) {
            throw KiranaChecks.invalid("itemId", "Choose an item of the catalogue");
        }
        Instant now = KiranaChecks.now(clock);
        if (catalogue.lockLink(r.code()).isPresent()) {
            catalogue.relink(r.code(), itemId, null, "ADMIN", true);
        } else if (!catalogue.addCode(itemId, r.code(), BigDecimal.ONE.setScale(3), "ADMIN", true, now)) {
            catalogue.relink(r.code(), itemId, null, "ADMIN", true);   // a shop linked it this moment
        }
        catalogue.closeReview(r.id(), adminId, now);
        // An item made only for this review (a product master's row) that lost goes again
        for (UUID other : new UUID[] {r.itemId(), r.otherItemId()}) {
            if (other != null && !other.equals(itemId)) {
                catalogue.deleteIfUnused(other);
            }
        }
    }

    /**
     * Imports a distributor's product master: CSV with a header row naming its columns (barcode, name, brand, size,
     * mrp, category, pack, unit; barcode and name needed, the rest optional, in any order). Each good row's barcode is
     * trusted for every shop: added to the catalogue (with an item matched by name and size, else a new one), or, when
     * a shop had linked it, confirmed and its empty details filled in. A row whose barcode the catalogue has as a
     * clearly different product goes to the review list. Bad rows are listed with their line and why (nothing else
     * stops the import). 400 when there is no header with barcode and name, or more than 5,000 rows.
     */
    public ImportResult importMaster(UUID adminId, MultipartFile file) {
        catalogueService.requireSuperAdmin(adminId);
        if (file == null || file.isEmpty()) {
            throw KiranaChecks.invalid("file", "The file is empty");
        }
        if (file.getSize() > IMPORT_MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "At most 2 MB at a time: split the "
                    + "file.", Map.of());
        }
        String csv;
        try {
            csv = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw KiranaChecks.invalid("file", "The file couldn't be read");
        }
        return transactions.execute(s -> importMaster(adminId, csv));
    }

    /** As {@link #importMaster(UUID, MultipartFile)}, from the file's text, in one transaction. */
    ImportResult importMaster(UUID adminId, String csv) {
        catalogueService.requireSuperAdmin(adminId);
        List<List<String>> rows = parse(csv == null ? "" : csv);
        if (rows.isEmpty()) {
            throw KiranaChecks.invalid("file", "The file is empty");
        }
        List<String> header = rows.get(0).stream().map(h -> h.strip().toLowerCase(Locale.ROOT)).toList();
        int codeAt = column(header, "barcode", "ean", "code", "gtin");
        int nameAt = column(header, "name", "item", "item name", "description", "product");
        if (codeAt < 0 || nameAt < 0) {
            throw KiranaChecks.invalid("file", "The first row must name the columns, with at least barcode and name");
        }
        if (rows.size() - 1 > IMPORT_MAX) {
            throw KiranaChecks.invalid("file", "At most " + IMPORT_MAX + " rows at a time: split the file");
        }
        int brandAt = column(header, "brand", "company", "mfr", "manufacturer");
        int sizeAt = column(header, "size", "pack size", "weight", "net qty", "quantity");
        int mrpAt = column(header, "mrp", "mrp (rs)", "price");
        int categoryAt = column(header, "category", "group");
        int packAt = column(header, "pack", "pieces", "case qty");
        int unitAt = column(header, "unit", "uom");
        Instant now = KiranaChecks.now(clock);
        int added = 0;
        int confirmed = 0;
        int reviews = 0;
        List<ImportProblem> problems = new ArrayList<>();
        for (int n = 1; n < rows.size(); n++) {
            List<String> row = rows.get(n);
            int line = n + 1;
            String code;
            try {
                code = KiranaChecks.barcode("barcode", cell(row, codeAt));
            } catch (ApiException e) {
                code = null;
            }
            if (code == null || !KiranaChecks.checkDigitOk(code)) {
                problems.add(new ImportProblem(line, "The barcode isn't valid"));
                continue;
            }
            String name = cell(row, nameAt);
            if (name == null || name.length() < 2) {
                problems.add(new ImportProblem(line, "The name is missing"));
                continue;
            }
            name = cut(name, 100);
            String brand = cut(cell(row, brandAt), 60);
            String size = cut(cell(row, sizeAt), 30);
            String category = cut(cell(row, categoryAt), 40);
            Long mrp = rupees(cell(row, mrpAt));
            BigDecimal pack = pack(cell(row, packAt));
            Unit unit = "kg".equalsIgnoreCase(cell(row, unitAt)) ? Unit.KG : Unit.PIECE;
            Optional<CatalogueStore.Link> link = catalogue.lockLink(code);
            if (link.isEmpty()) {
                Item item = catalogue.sameItem(name, size, unit).orElse(null);
                if (item == null) {
                    item = new Item(UUID.randomUUID(), name, brand, size, unit, mrp, category, 0, now);
                    catalogue.insertItem(item, null, now);
                } else {
                    catalogue.fillIn(item.id(), brand, size, mrp, category, now);
                }
                if (catalogue.addCode(item.id(), code, pack, "MASTER", true, now)) {
                    added++;
                } else {
                    catalogue.deleteIfUnused(item.id());   // a shop linked it this moment: checked on the next import
                    problems.add(new ImportProblem(line, "A shop linked this barcode just now: import it again"));
                }
                continue;
            }
            Item current = catalogue.find(link.get().itemId()).orElseThrow();
            NameMatcher.Score score = NameMatcher.score(NameMatcher.parse(name, brand, size),
                    NameMatcher.parse(current.name(), current.brand(), current.size()));
            if (!score.never() && score.value() >= NameMatcher.SURE) {
                // The same product: trusted now, its empty details filled in
                catalogue.relink(code, current.id(), null, "MASTER", true);
                catalogue.fillIn(current.id(), brand, size, mrp, category, now);
                confirmed++;
                continue;
            }
            Item proposed = catalogue.sameItem(name, size, unit).orElse(null);
            boolean made = proposed == null;
            if (made) {
                proposed = new Item(UUID.randomUUID(), name, brand, size, unit, mrp, category, 0, now);
                catalogue.insertItem(proposed, null, now);
            }
            if (catalogue.addReview(new Review(UUID.randomUUID(), code, "IMPORT", current.id(), proposed.id(), null,
                    "A product master names this barcode differently", "OPEN", now))) {
                reviews++;
            } else {
                if (made) {
                    catalogue.deleteIfUnused(proposed.id());
                }
                problems.add(new ImportProblem(line, "This barcode is already on the review list"));
            }
        }
        return new ImportResult(rows.size() - 1, added, confirmed, reviews,
                List.copyOf(problems.size() > 200 ? problems.subList(0, 200) : problems), problems.size());
    }

    private static ReviewItem item(Item i, Map<UUID, Integer> votes) {
        return i == null ? null : new ReviewItem(i.id(), i.name(), i.brand(), i.size(), i.mrpPaise(),
                votes.getOrDefault(i.id(), 0));
    }

    // ---------- CSV ----------

    /** Rows of a CSV (commas, quotes with "" inside, CRLF or LF), blank rows left out. */
    static List<List<String>> parse(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        String text = csv.startsWith("﻿") ? csv.substring(1) : csv;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                if (row.stream().anyMatch(c -> !c.isBlank())) {
                    rows.add(row);
                }
                row = new ArrayList<>();
            } else {
                cell.append(ch);
            }
        }
        row.add(cell.toString());
        if (row.stream().anyMatch(c -> !c.isBlank())) {
            rows.add(row);
        }
        return rows;
    }

    private static int column(List<String> header, String... names) {
        for (String n : names) {
            int i = header.indexOf(n);
            if (i >= 0) {
                return i;
            }
        }
        return -1;
    }

    private static String cell(List<String> row, int at) {
        if (at < 0 || at >= row.size()) {
            return null;
        }
        String v = row.get(at).strip();
        return v.isEmpty() ? null : v;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static Long rupees(String s) {
        if (s == null) {
            return null;
        }
        try {
            BigDecimal r = new BigDecimal(s.replaceAll("[^0-9.]", ""));
            if (r.signum() <= 0 || r.compareTo(new BigDecimal("100000")) > 0) {
                return null;
            }
            return r.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    private static BigDecimal pack(String s) {
        if (s != null) {
            try {
                BigDecimal p = new BigDecimal(s.replaceAll("[^0-9.]", ""));
                if (p.signum() > 0 && p.compareTo(new BigDecimal("9999")) <= 0) {
                    return p.setScale(3, java.math.RoundingMode.HALF_UP);
                }
            } catch (NumberFormatException e) {
                // a pack that isn't a number counts as 1
            }
        }
        return BigDecimal.ONE.setScale(3);
    }
}
