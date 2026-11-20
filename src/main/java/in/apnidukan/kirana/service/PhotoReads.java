package in.apnidukan.kirana.service;

import in.apnidukan.kirana.domain.ReadFailure;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.files.FileRefs;
import in.apnidukan.kirana.api.StockResponses.ReadPage;
import in.apnidukan.kirana.api.StockResponses.ReadView;
import in.apnidukan.kirana.domain.Cursors;
import in.apnidukan.kirana.repo.PurchaseStore;
import in.apnidukan.kirana.repo.ReadStore;
import in.apnidukan.kirana.repo.ReadStore.Read;
import in.apnidukan.kirana.service.KiranaAccess.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static in.apnidukan.kirana.service.KiranaChecks.PAGE;
import static in.apnidukan.kirana.service.KiranaChecks.invalid;

/**
 * Photos a shop sends to be read by AI (owner or helper): a distributor's bill, a paper bill, a pack's label. Saved at
 * once and read in the background within a minute ({@link ReadJob}); the app shows "Reading…" meanwhile. The same
 * clientRef again (a retry after a lost answer) gives the same upload.
 */
@Service
public class PhotoReads {

    /** Pages a bill may have. */
    static final int MAX_PAGES = 3;

    private final KiranaAccess access;
    private final ReadStore reads;
    private final PurchaseStore purchases;
    private final FileRefs files;
    private final KiranaFiles kiranaFiles;
    private final BillShelving shelving;
    private final Clock clock;

    public PhotoReads(KiranaAccess access, ReadStore reads, PurchaseStore purchases, FileRefs files,
                      KiranaFiles kiranaFiles, BillShelving shelving, Clock clock) {
        this.access = access;
        this.reads = reads;
        this.purchases = purchases;
        this.files = files;
        this.kiranaFiles = kiranaFiles;
        this.shelving = shelving;
        this.clock = clock;
    }

    /**
     * Queues a photo to read. {@code fileIds}: uploaded first (POST /files) by this shop, BILL photos or PDFs for a bill
     * (1 to 3 pages, in order), one PRODUCT_PHOTO for a label, one BILL photo (or file) for a page of the shop's list of
     * products (STOCK_LIST: one page each, so a bad page is retaken alone; at most
     * {@value ProductSetupService#MAX_LIST_PAGES} kept a shop, 409 TOO_MANY_PAGES; at most
     * {@value ProductSetupService#LIST_PAGES_PER_DAY} sent a day, 409 TOO_MANY_PAGES_TODAY). 400 naming fileIds otherwise.
     */
    @Transactional
    public ReadView upload(UUID userId, String kind, UUID clientRef, List<UUID> fileIds, boolean recount) {
        Caller c = access.caller(userId);
        if (recount && !"STOCK_LIST".equals(kind)) {
            throw invalid("recount", "Only a page of your list can be a recount");
        }
        Read r = queue(c.shopId(), userId, kind, clientRef, fileIds, null, recount, KiranaChecks.now(clock));
        return view(r);
    }

    /** As {@link #upload} for the caller's own services (a scan's label, a product's photo); the read. */
    Read queue(UUID shopId, UUID userId, String kind, UUID clientRef, List<UUID> fileIds, UUID productId,
               Instant now) {
        return queue(shopId, userId, kind, clientRef, fileIds, productId, false, now);
    }

    private Read queue(UUID shopId, UUID userId, String kind, UUID clientRef, List<UUID> fileIds, UUID productId,
                       boolean recount, Instant now) {
        if (clientRef == null) {
            throw invalid("clientRef", "is required");
        }
        Optional<Read> earlier = reads.byClientRef(shopId, clientRef);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        String purpose = "LABEL".equals(kind) ? "PRODUCT_PHOTO" : "BILL";
        // A product's photo may come with a second one, of the side where its size is printed
        int max = "LABEL".equals(kind) ? (productId != null ? 2 : 1) : "STOCK_LIST".equals(kind) ? 1 : MAX_PAGES;
        List<UUID> ids = fileIds == null ? List.of() : fileIds.stream().distinct().toList();
        if (ids.isEmpty() || ids.size() > max) {
            throw invalid("fileIds", max == 1 ? "Send one photo" : "Send 1 to " + max + " pages");
        }
        for (UUID id : ids) {
            FileRefs.FileRef f = files.find(id).orElse(null);
            if (f == null || !shopId.equals(f.shopId()) || !purpose.equals(f.purpose())) {
                throw invalid("fileIds", "Upload the photo first (as " + purpose + ")");
            }
        }
        if ("STOCK_LIST".equals(kind)) {
            // Counted under a lock held to the end of this transaction: pages sent at the same moment wait their turn
            reads.lockListPages(shopId);
            Optional<Read> meanwhile = reads.byClientRef(shopId, clientRef);
            if (meanwhile.isPresent()) {
                return meanwhile.get();
            }
            if (!recount && reads.listPages(shopId) >= ProductSetupService.MAX_LIST_PAGES) {
                throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_PAGES", "You have "
                        + ProductSetupService.MAX_LIST_PAGES + " pages of your list already. Add the rest of your "
                        + "products one by one, or delete a page you don't need.",
                        Map.of("max", ProductSetupService.MAX_LIST_PAGES));
            }
            // Pages deleted or that failed are counted for the day too: each was read by AI
            Instant dayStart = KiranaChecks.today(now).atStartOfDay(KiranaChecks.ZONE).toInstant();
            if (reads.listPagesSince(shopId, dayStart) >= ProductSetupService.LIST_PAGES_PER_DAY) {
                throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_PAGES_TODAY", "You've sent "
                        + ProductSetupService.LIST_PAGES_PER_DAY + " pages today. Send more tomorrow, or add the "
                        + "rest of your products one by one.", Map.of("max", ProductSetupService.LIST_PAGES_PER_DAY));
            }
        }
        ids.forEach(files::keep);
        Read r = new Read(UUID.randomUUID(), shopId, kind, "READING", ids, clientRef, 0, now, null, null, null,
                productId, userId, now, now, recount);
        if (!reads.insert(r)) {
            // The same clientRef at the same moment: the other request's upload
            return reads.byClientRef(shopId, clientRef).orElseThrow();
        }
        return r;
    }

    /** One upload with how its reading went. 404 READ_NOT_FOUND if not this shop's. */
    @Transactional(readOnly = true)
    public ReadView get(UUID userId, UUID readId) {
        Caller c = access.caller(userId);
        return view(reads.find(c.shopId(), readId).orElseThrow(PhotoReads::notFound));
    }

    /** The shop's uploads of one kind (PURCHASE, PAPER_BILL or STOCK_LIST), newest first, 50 a page. */
    @Transactional(readOnly = true)
    public ReadPage list(UUID userId, String kind, String cursor) {
        Caller c = access.caller(userId);
        Optional<String[]> before = Cursors.decode(cursor);
        Instant beforeAt = before.flatMap(b -> instant(b[0])).orElse(null);
        List<Read> rows = reads.page(c.shopId(), kind, beforeAt,
                beforeAt == null ? null : ProductService.uuid(before.get()[1]), PAGE);
        boolean more = rows.size() > PAGE;
        List<Read> page = more ? rows.subList(0, PAGE) : rows;
        Map<UUID, UUID> made = purchases.byReads(page.stream().filter(r -> "PURCHASE".equals(r.kind()))
                .map(Read::id).toList());
        List<ReadView> out = new ArrayList<>();
        for (Read r : page) {
            out.add(view(r, made.get(r.id())));
        }
        Read last = page.isEmpty() ? null : page.get(page.size() - 1);
        return new ReadPage(out, more ? Cursors.encode(last.createdAt().toString(), last.id().toString()) : null);
    }

    /** The shop's read sent with this clientRef, if any (a retry). */
    Optional<Read> earlier(UUID shopId, UUID clientRef) {
        return clientRef == null ? Optional.empty() : reads.byClientRef(shopId, clientRef);
    }

    /** Holds the shop's photo checks to the end of the transaction (ProductService.setPhoto counts under it). */
    void lockPhotoChecks(UUID shopId) {
        reads.lockPhotoChecks(shopId);
    }

    /** Products' photos the shop sent to be checked since then. */
    int photoChecksSince(UUID shopId, Instant since) {
        return reads.photoChecksSince(shopId, since);
    }

    /** Throws an upload away (one still being read, read, or failed). 409 READ_USED once used. */
    @Transactional
    public ReadView discard(UUID userId, UUID readId) {
        Caller c = access.caller(userId);
        Read seen = reads.find(c.shopId(), readId).filter(r -> !"LABEL".equals(r.kind()))
                .orElseThrow(PhotoReads::notFound);
        // The bill first, then the read (the order adding or throwing away a bill takes)
        Optional<PurchaseStore.Purchase> draft = purchases.byRead(seen.id())
                .flatMap(p -> purchases.lock(c.shopId(), p.id()));
        Read r = reads.lock(c.shopId(), readId).orElseThrow(PhotoReads::notFound);
        if (draft.isEmpty() && purchases.byRead(r.id()).isPresent()) {
            // Its bill was made from it at this very moment: the bill must be locked first, so the shop tries again
            throw new ApiException(HttpStatus.CONFLICT, "READ_CHANGED", "This bill was just read. Open it and throw "
                    + "it away from there, or try again.", Map.of());
        }
        if ("DONE".equals(r.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "READ_USED", "This was already used: it can't be thrown away.");
        }
        if (!"DISCARDED".equals(r.status())) {
            Instant now = KiranaChecks.now(clock);
            if (draft.isPresent() && "DRAFT".equals(draft.get().status())) {
                shelving.throwAway(c.shopId(), draft.get(), userId, now);
            } else {
                reads.setStatus(r.id(), "DISCARDED", null, now);
                kiranaFiles.releaseIfUnused(r.fileIds(), r.id());
            }
        }
        return view(reads.find(r.id()).orElseThrow());
    }

    ReadView view(Read r) {
        return view(r, "PURCHASE".equals(r.kind()) ? purchases.byRead(r.id()).map(PurchaseStore.Purchase::id)
                .orElse(null) : null);
    }

    private static ReadView view(Read r, UUID purchaseId) {
        return new ReadView(r.id(), r.kind(), r.status(), r.fileIds(), r.failure(), purchaseId, r.billId(),
                r.createdAt(), r.updatedAt(), r.recount(), ReadFailure.code(r.failure()));
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "READ_NOT_FOUND", "No such photo.", Map.of());
    }

    static Optional<Instant> instant(String s) {
        try {
            return Optional.of(Instant.parse(s));
        } catch (java.time.format.DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
