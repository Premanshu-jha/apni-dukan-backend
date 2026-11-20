package in.apnidukan.kirana.service;

import in.apnidukan.common.config.KiranaSwitch;
import in.apnidukan.common.files.FileRefs;
import in.apnidukan.common.jobs.JobLeases;
import in.apnidukan.kirana.domain.ReadFailure;
import in.apnidukan.kirana.domain.PhotoRules;
import in.apnidukan.kirana.repo.OnlineStore;
import in.apnidukan.kirana.repo.ReadStore;
import in.apnidukan.kirana.repo.ReadStore.Read;
import in.apnidukan.kirana.repo.ScanStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static in.apnidukan.kirana.service.RazorpayHttp.JSON;

/**
 * Reads the photos shops sent (every 15 seconds, on one backend at a time): a distributor's bill becomes a draft with
 * its lines matched ({@link PurchaseService#fromRead}), a paper bill's and a label's reading is kept for the shop to
 * use, a product photo's label shares the photo once it matches the product. The AI is called outside any transaction;
 * each read is claimed first, so two runs never read it together. At most 30 reads per shop a day (stocking a new shop
 * is the busy day), within the platform's daily cap.
 */
@Component
public class ReadJob {

    private static final Logger log = LoggerFactory.getLogger(ReadJob.class);
    /** Reads per shop and day. */
    static final int SHOP_CALLS_PER_DAY = 30;
    /** A run reads at most this many (the next run takes the rest). */
    private static final int BATCH = 10;
    /** Tries before a read is given up ("try again later"). */
    static final int MAX_TRIES = 6;
    /** How long a claimed read is left to its run (one AI call takes up to 2 minutes). */
    private static final Duration CLAIM = Duration.ofMinutes(5);
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private final ReadStore reads;
    private final OnlineStore online;
    private final KiranaDocumentAi ai;
    private final FileRefs files;
    private final PurchaseService purchases;
    private final ScanService scans;
    private final ScanStore scanStore;
    private final BillFiles billFiles;
    private final ProductSetupService setup;
    private final PhotoChecks photoChecks;
    private final JobLeases leases;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int callsPerDay;
    private final int photoCallsPerDay;
    private final KiranaSwitch kirana;

    public ReadJob(ReadStore reads, OnlineStore online, KiranaDocumentAi ai, FileRefs files, PurchaseService purchases,
                   ScanService scans, ScanStore scanStore, BillFiles billFiles, ProductSetupService setup,
                   PhotoChecks photoChecks, JobLeases leases,
                   TransactionTemplate transactions, Clock clock,
                   @Value("${app.kirana.ai-calls-per-day:500}") int callsPerDay,
                   @Value("${app.kirana.photo-checks-per-day:3000}") int photoCallsPerDay,
                   KiranaSwitch kirana) {
        this.kirana = kirana;
        this.reads = reads;
        this.online = online;
        this.ai = ai;
        this.files = files;
        this.purchases = purchases;
        this.scans = scans;
        this.scanStore = scanStore;
        this.billFiles = billFiles;
        this.setup = setup;
        this.photoChecks = photoChecks;
        this.leases = leases;
        this.transactions = transactions;
        this.clock = clock;
        this.callsPerDay = callsPerDay;
        this.photoCallsPerDay = photoCallsPerDay;
    }

    @Scheduled(fixedDelayString = "${app.kirana.read-ms:15000}",
            initialDelayString = "${app.kirana.jobs-delay-ms:30000}")
    public void run() {
        if (kirana.off()) {
            return;   // kirana switched off: nothing to do
        }
        leases.runExclusively("kirana-photo-reads", Duration.ofMinutes(10), () -> {
            for (UUID id : reads.due(KiranaChecks.now(clock), BATCH)) {
                try {
                    readOne(id);
                } catch (RuntimeException e) {
                    // One photo's trouble never stops the others; it is tried again later, at most MAX_TRIES times
                    log.error("Reading kirana photo {} failed", id, e);
                    Instant now = KiranaChecks.now(clock);
                    reads.find(id).filter(r -> "READING".equals(r.status())).ifPresent(r -> retryOrFail(r, now));
                }
            }
            // Scan batches are remembered a day (a retry comes within minutes)
            Instant now = KiranaChecks.now(clock);
            transactions.executeWithoutResult(s -> scanStore.forgetBatches(now.minus(Duration.ofDays(1)), 5000));
        });
    }

    /** Reads one photo (claimed first; nothing when another run holds it or it is done). */
    void readOne(UUID id) {
        Instant now = KiranaChecks.now(clock);
        Read r = transactions.execute(s -> reads.claim(id, now.plus(CLAIM), now).orElse(null));
        if (r == null) {
            return;
        }
        boolean productPhoto = "LABEL".equals(r.kind()) && r.productId() != null;
        Optional<PhotoChecks.Expected> expected = Optional.empty();
        if (productPhoto) {
            // Still waiting for this photo? (Replaced or removed meanwhile: no AI call is spent on it)
            expected = photoChecks.expected(r);
            if (expected.isEmpty()) {
                fail(r, ReadFailure.PHOTO_NOT_WAITING.text(), now);
                return;
            }
        }
        if (!ai.configured()) {
            if (productPhoto) {
                // A product's photo isn't refused for our setup: it waits (the shop sees "checking")
                transactions.executeWithoutResult(s -> reads.retry(r.id(), now.plus(Duration.ofHours(1)), now));
                return;
            }
            fail(r, ReadFailure.READING_OFF.text(), now);
            return;
        }
        // The photo first (storage trouble isn't a read: no AI call is used up), then the day's calls
        List<KiranaDocumentAi.Page> pages = new ArrayList<>();
        for (UUID fileId : r.fileIds()) {
            Optional<FileRefs.FileRef> f = files.find(fileId);
            if (f.isEmpty()) {
                fail(r, ReadFailure.CANT_OPEN.text(), now);
                return;
            }
            Optional<byte[]> bytes;
            try {
                bytes = files.read(f.get().id(), MAX_BYTES);
            } catch (IllegalStateException e) {
                fail(r, ReadFailure.TOO_LARGE.text(), now);
                return;
            } catch (RuntimeException storageDown) {
                log.warn("Kirana photo {} couldn't be downloaded: {}", r.id(), storageDown.getClass().getSimpleName());
                retryOrFail(r, now);
                return;
            }
            if (bytes.isEmpty()) {
                fail(r, ReadFailure.CANT_OPEN.text(), now);
                return;
            }
            pages.add(new KiranaDocumentAi.Page(bytes.get(), f.get().contentType()));
        }
        if (!"LABEL".equals(r.kind())) {
            // Spreadsheets, documents, e-invoices and PDFs with text are read in code first: no AI, or only text
            BillFiles.Prepared ready = billFiles.prepare(r.kind(), pages);
            if (ready.problem() != null) {
                // The same file checks for a page of the shop's list: told in its words
                fail(r, "STOCK_LIST".equals(r.kind()) ? ReadFailure.forList(ready.problem()) : ready.problem(), now);
                return;
            }
            if (ready.facts() != null) {
                KiranaDocumentAi.PurchaseFacts facts = ready.facts();
                transactions.executeWithoutResult(s -> {
                    if (reads.read(r.id(), JSON.writeValueAsString(facts), now)) {
                        purchases.fromRead(r, facts, now);
                    }
                });
                return;
            }
            pages = ready.pages();
        }
        LocalDate today = KiranaChecks.today(now);
        Instant tomorrow = today.plusDays(1).atTime(0, 5).atZone(KiranaChecks.ZONE).toInstant();
        if (productPhoto) {
            // Photo checks have the platform's own daily allowance, so they never hold up anyone's bills
            if (!online.takePhotoAiCall(today, photoCallsPerDay)) {
                log.warn("The daily photo checks cap ({}) is reached: they wait for tomorrow", photoCallsPerDay);
                transactions.executeWithoutResult(s -> reads.defer(r.id(), tomorrow, now));
                return;
            }
            try {
                checkPhoto(r, pages, expected.get(), now);
            } catch (KiranaDocumentAi.Unreadable e) {
                fail(r, ReadFailure.PRODUCT_UNCLEAR.text(), now);
            } catch (KiranaDocumentAi.Unavailable e) {
                retryOrFail(r, now);
            }
            return;
        }
        if (!online.takeAiCall(today, callsPerDay)) {
            log.warn("The kirana AI's daily cap ({}) is reached: photo reads wait for tomorrow", callsPerDay);
            transactions.executeWithoutResult(s -> reads.defer(r.id(), tomorrow, now));
            return;
        }
        // A list page has its own allowances (ProductSetupService.MAX_LIST_PAGES kept, LIST_PAGES_PER_DAY sent, both
        // counted when sent, thrown-away pages too for the day): setting up a shop's products may take many pages
        Boolean allowed = "STOCK_LIST".equals(r.kind()) ? Boolean.TRUE
                : transactions.execute(s -> reads.takeShopCall(r.shopId(), today, SHOP_CALLS_PER_DAY));
        if (!Boolean.TRUE.equals(allowed)) {
            // The shop's 30 for today are used: read tomorrow morning (the shop sees "waiting")
            transactions.executeWithoutResult(s -> reads.defer(r.id(), tomorrow, now));
            return;
        }
        try {
            switch (r.kind()) {
                case "PURCHASE" -> {
                    KiranaDocumentAi.PurchaseFacts facts = ai.readPurchase(pages);
                    if (!facts.readable() || !facts.bill() || facts.lines().isEmpty()) {
                        fail(r, facts.readable() && !facts.bill()
                                ? ReadFailure.NOT_A_BILL.text()
                                : ReadFailure.BILL_UNREADABLE.text(), now);
                        return;
                    }
                    transactions.executeWithoutResult(s -> {
                        if (reads.read(r.id(), JSON.writeValueAsString(facts), now)) {
                            purchases.fromRead(r, facts, now);
                        }
                    });
                }
                case "STOCK_LIST" -> {
                    KiranaDocumentAi.StockListFacts facts = ai.readStockList(pages.get(0));
                    if (!facts.readable() || !facts.list() || facts.lines().isEmpty()) {
                        fail(r, facts.readable() && !facts.list()
                                ? ReadFailure.NOT_A_LIST.text()
                                : ReadFailure.LIST_UNREADABLE.text(), now);
                        return;
                    }
                    // A recount's matching first, outside the transaction (no lock is held while it works)
                    Map<Integer, UUID> clear = setup.clearCounts(r, facts);
                    transactions.executeWithoutResult(s -> {
                        if (reads.read(r.id(), JSON.writeValueAsString(facts), now)) {
                            setup.fromRead(r, facts, clear, now);
                        }
                    });
                }
                case "PAPER_BILL" -> {
                    KiranaDocumentAi.PaperFacts facts = ai.readPaperBill(pages);
                    if (!facts.readable() || !facts.bill() || facts.lines().isEmpty()) {
                        fail(r, ReadFailure.PAPER_BILL_UNREADABLE.text(), now);
                        return;
                    }
                    transactions.executeWithoutResult(s -> reads.read(r.id(), JSON.writeValueAsString(facts), now));
                }
                default -> {   // LABEL: an unknown pack's label (a product's photo is checked above)
                    KiranaDocumentAi.LabelFacts facts = ai.readLabel(pages.get(0));
                    if (!facts.readable() || !facts.product()) {
                        fail(r, ReadFailure.LABEL_UNREADABLE.text(), now);
                        return;
                    }
                    // An unknown pack's label: its suggestions are worked out from it when shown
                    transactions.executeWithoutResult(s -> reads.read(r.id(), JSON.writeValueAsString(facts), now));
                }
            }
        } catch (KiranaDocumentAi.Unreadable e) {
            fail(r, "LABEL".equals(r.kind()) ? ReadFailure.LABEL_UNREADABLE.text()
                    : ReadFailure.PHOTO_UNREADABLE.text(), now);
        } catch (KiranaDocumentAi.Unavailable e) {
            retryOrFail(r, now);
        }
    }

    /** Tried again after 1, 2, 4, 8, 16 minutes; after {@link #MAX_TRIES} tries, given up with a reason. */
    private void retryOrFail(Read r, Instant now) {
        if ("LABEL".equals(r.kind()) && r.productId() != null && r.tries() + 1 >= MAX_TRIES) {
            // A product's photo isn't refused for our trouble: it is checked when the AI is back (every 30 minutes)
            transactions.executeWithoutResult(s -> reads.retry(r.id(), now.plus(Duration.ofMinutes(30)), now));
            return;
        }
        if (r.tries() + 1 >= MAX_TRIES) {
            fail(r, ReadFailure.TRY_LATER.text(), now);
            return;
        }
        long minutes = Math.min(30, 1L << r.tries());
        transactions.executeWithoutResult(s -> reads.retry(r.id(), now.plus(Duration.ofMinutes(minutes)), now));
    }

    private void fail(Read r, String why, Instant now) {
        transactions.executeWithoutResult(s -> {
            if (reads.failed(r.id(), why, now)) {
                scans.labelFailed(r, now);
                photoChecks.failed(r, why);
            }
        });
    }

    /** A shop's photo of its product, checked against it as it was named when the check started. */
    private void checkPhoto(Read r, List<KiranaDocumentAi.Page> pages, PhotoChecks.Expected expected, Instant now) {
        PhotoRules.Seen seen = ai.checkProductPhoto(pages, expected.name(), expected.loose());
        transactions.executeWithoutResult(s -> {
            if (reads.read(r.id(), JSON.writeValueAsString(seen), now)) {
                photoChecks.checked(r, seen, expected);
            }
        });
    }
}
