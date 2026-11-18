package in.apnidukan.kirana.service;

import in.apnidukan.common.files.FileRefs;
import in.apnidukan.common.jobs.JobLeases;
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
    private final JobLeases leases;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int callsPerDay;

    public ReadJob(ReadStore reads, OnlineStore online, KiranaDocumentAi ai, FileRefs files, PurchaseService purchases,
                   ScanService scans, ScanStore scanStore, BillFiles billFiles, ProductSetupService setup,
                   JobLeases leases,
                   TransactionTemplate transactions, Clock clock,
                   @Value("${app.kirana.ai-calls-per-day:500}") int callsPerDay) {
        this.reads = reads;
        this.online = online;
        this.ai = ai;
        this.files = files;
        this.purchases = purchases;
        this.scans = scans;
        this.scanStore = scanStore;
        this.billFiles = billFiles;
        this.setup = setup;
        this.leases = leases;
        this.transactions = transactions;
        this.clock = clock;
        this.callsPerDay = callsPerDay;
    }

    @Scheduled(fixedDelayString = "${app.kirana.read-ms:15000}",
            initialDelayString = "${app.kirana.jobs-delay-ms:30000}")
    public void run() {
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
        if (!ai.configured()) {
            fail(r, "Reading photos isn't switched on yet. Enter it by hand.", now);
            return;
        }
        // The photo first (storage trouble isn't a read: no AI call is used up), then the day's calls
        List<KiranaDocumentAi.Page> pages = new ArrayList<>();
        for (UUID fileId : r.fileIds()) {
            Optional<FileRefs.FileRef> f = files.find(fileId);
            if (f.isEmpty()) {
                fail(r, "We couldn't open the photo. Take it again.", now);
                return;
            }
            Optional<byte[]> bytes;
            try {
                bytes = files.read(f.get().id(), MAX_BYTES);
            } catch (IllegalStateException e) {
                fail(r, "This photo is too large. Take it again.", now);
                return;
            } catch (RuntimeException storageDown) {
                log.warn("Kirana photo {} couldn't be downloaded: {}", r.id(), storageDown.getClass().getSimpleName());
                retryOrFail(r, now);
                return;
            }
            if (bytes.isEmpty()) {
                fail(r, "We couldn't open the photo. Take it again.", now);
                return;
            }
            pages.add(new KiranaDocumentAi.Page(bytes.get(), f.get().contentType()));
        }
        if (!"LABEL".equals(r.kind())) {
            // Spreadsheets, documents, e-invoices and PDFs with text are read in code first: no AI, or only text
            BillFiles.Prepared ready = billFiles.prepare(r.kind(), pages);
            if (ready.problem() != null) {
                // The same file checks for a page of the shop's list: told in its words
                fail(r, "STOCK_LIST".equals(r.kind()) ? ready.problem().replace("This bill", "This list")
                        .replace("the bill", "your list") : ready.problem(), now);
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
                                ? "This doesn't look like a distributor's bill. Take a photo of the bill."
                                : "We couldn't read the bill's items. Take a clear, straight photo of each page in good "
                                + "light.", now);
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
                                ? "This doesn't look like a list of products. Take a photo of one page of your list."
                                : "We couldn't read the products on this page. Take a clear, straight photo of the "
                                + "whole page in good light.", now);
                        return;
                    }
                    transactions.executeWithoutResult(s -> {
                        if (reads.read(r.id(), JSON.writeValueAsString(facts), now)) {
                            setup.fromRead(r, facts, now);
                        }
                    });
                }
                case "PAPER_BILL" -> {
                    KiranaDocumentAi.PaperFacts facts = ai.readPaperBill(pages);
                    if (!facts.readable() || !facts.bill() || facts.lines().isEmpty()) {
                        fail(r, "We couldn't read the bill. Take a clear photo of the whole paper in good light.", now);
                        return;
                    }
                    transactions.executeWithoutResult(s -> reads.read(r.id(), JSON.writeValueAsString(facts), now));
                }
                default -> {
                    KiranaDocumentAi.LabelFacts facts = ai.readLabel(pages.get(0));
                    if (!facts.readable() || !facts.product()) {
                        fail(r, "We couldn't read the pack's label. Take a photo of the front of the pack.", now);
                        return;
                    }
                    transactions.executeWithoutResult(s -> {
                        if (reads.read(r.id(), JSON.writeValueAsString(facts), now)) {
                            scans.labelRead(r, facts, now);
                        }
                    });
                }
            }
        } catch (KiranaDocumentAi.Unreadable e) {
            fail(r, "LABEL".equals(r.kind()) ? "We couldn't read the pack's label. Take a photo of the front of the "
                    + "pack." : "We couldn't read this photo. Take a clear, straight photo in good light.", now);
        } catch (KiranaDocumentAi.Unavailable e) {
            retryOrFail(r, now);
        }
    }

    /** Tried again after 1, 2, 4, 8, 16 minutes; after {@link #MAX_TRIES} tries, given up with a reason. */
    private void retryOrFail(Read r, Instant now) {
        if (r.tries() + 1 >= MAX_TRIES) {
            fail(r, "We couldn't read it just now. Send it again a little later, or enter it by hand.", now);
            return;
        }
        long minutes = Math.min(30, 1L << r.tries());
        transactions.executeWithoutResult(s -> reads.retry(r.id(), now.plus(Duration.ofMinutes(minutes)), now));
    }

    private void fail(Read r, String why, Instant now) {
        transactions.executeWithoutResult(s -> {
            if (reads.failed(r.id(), why, now)) {
                scans.labelFailed(r, now);
            }
        });
    }
}
