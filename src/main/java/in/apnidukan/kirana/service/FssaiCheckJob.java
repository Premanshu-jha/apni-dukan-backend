package in.apnidukan.kirana.service;

import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.context.ShopMemberships;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.files.FileRefs;
import in.apnidukan.common.jobs.JobLeases;
import in.apnidukan.common.notify.Notifications;
import in.apnidukan.common.notify.Notifications.Notice;
import in.apnidukan.kirana.repo.OnlineStore;
import in.apnidukan.kirana.repo.OnlineStore.Checking;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reads each FSSAI certificate a kirana adds, with the kirana AI, and decides in code (the AI only reads): it must be an
 * FSSAI certificate, show the number the owner typed, not have expired, and carry the shop's or the owner's name. Runs
 * every minute on one backend at a time (JobLeases), outside any transaction while the AI reads; at most the day's cap
 * of reads (app.kirana.ai-calls-per-day). Without an AI key nothing is read and certificates wait for a super admin's
 * decision on the admin website. A read the AI couldn't make is tried again 5 times, then the owner is asked for a
 * clearer photo.
 */
@Component
public class FssaiCheckJob {

    private static final Logger log = LoggerFactory.getLogger(FssaiCheckJob.class);
    static final int MAX_TRIES = 5;
    private static final long MAX_BYTES = 15L * 1024 * 1024;
    private static final Set<String> COMMON = Set.of("the", "and", "shop", "store", "stores", "general", "kirana",
            "mart", "super", "market", "supermarket", "enterprises", "traders", "trading", "provision", "provisions",
            "m/s", "ms", "sri", "shri", "co", "pvt", "ltd");

    private final OnlineStore online;
    private final KiranaDocumentAi ai;
    private final FileRefs files;
    private final ShopDirectory shops;
    private final ShopMemberships memberships;
    private final UserDirectory users;
    private final Notifications notifications;
    private final JobLeases leases;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int callsPerDay;

    public FssaiCheckJob(OnlineStore online, KiranaDocumentAi ai, FileRefs files, ShopDirectory shops,
                         ShopMemberships memberships, UserDirectory users, Notifications notifications,
                         JobLeases leases, TransactionTemplate transactions, Clock clock,
                         @Value("${app.kirana.ai-calls-per-day:500}") int callsPerDay) {
        this.online = online;
        this.ai = ai;
        this.files = files;
        this.shops = shops;
        this.memberships = memberships;
        this.users = users;
        this.notifications = notifications;
        this.leases = leases;
        this.transactions = transactions;
        this.clock = clock;
        this.callsPerDay = callsPerDay;
    }

    @Scheduled(fixedDelayString = "${app.kirana.fssai-check-ms:60000}",
            initialDelayString = "${app.kirana.jobs-delay-ms:30000}")
    public void run() {
        if (!ai.configured()) {
            return;
        }
        leases.runExclusively("kirana-fssai-check", Duration.ofMinutes(10), () -> {
            for (Checking c : online.checking(MAX_TRIES, 20)) {
                if (!online.takeAiCall(KiranaChecks.today(clock.instant()), callsPerDay)) {
                    log.warn("The kirana AI's daily cap ({}) is reached: FSSAI checks wait for tomorrow", callsPerDay);
                    return;
                }
                try {
                    check(c);
                } catch (RuntimeException e) {
                    // One certificate's trouble never stops the others' checks
                    log.error("FSSAI check of shop {} failed", c.shopId(), e);
                    tried(c, KiranaChecks.now(clock));
                }
            }
        });
    }

    /** Reads one certificate and keeps the decision (if the shop still has that certificate). */
    void check(Checking c) {
        Instant now = KiranaChecks.now(clock);
        Optional<FileRefs.FileRef> file = files.find(c.fileId());
        Optional<byte[]> bytes = file.flatMap(f -> files.read(f.id(), MAX_BYTES));
        if (bytes.isEmpty()) {
            decide(c, "REJECTED", "We couldn't open the certificate. Upload it again (a clear photo or the PDF).",
                    null, now);
            return;
        }
        KiranaDocumentAi.FssaiFacts facts;
        try {
            facts = ai.readFssai(bytes.get(), file.get().contentType());
        } catch (KiranaDocumentAi.Unreadable e) {
            decide(c, "REJECTED", "We couldn't read the certificate. Upload a clear photo of the whole page, or the PDF.",
                    null, now);
            return;
        } catch (KiranaDocumentAi.Unavailable e) {
            tried(c, now);
            return;
        }
        LocalDate today = KiranaChecks.today(clock.instant());
        String why = problem(c, facts, today);
        decide(c, why == null ? "VERIFIED" : "REJECTED", why, why == null ? facts.validUpto() : null, now);
    }

    /** One more read tried without an answer; after the last one the owner is told a person will look at it. */
    private void tried(Checking c, Instant now) {
        transactions.executeWithoutResult(s -> {
            online.fssaiTried(c.shopId(), c.fileId(), now);
            if (c.tries() + 1 >= MAX_TRIES) {
                log.warn("FSSAI check of shop {} waits for a super admin after {} tries", c.shopId(), MAX_TRIES);
                tellOwner(c.shopId(), now, "FSSAI licence: our team will check it",
                        "We couldn't check it automatically. Our team will look at it and tell you, usually "
                                + "within a day.");
            }
        });
    }

    /** Why the certificate can't be accepted, in the owner's words; null when it can. */
    String problem(Checking c, KiranaDocumentAi.FssaiFacts f, LocalDate today) {
        if (!f.readable()) {
            return "We couldn't read the certificate. Upload a clear photo of the whole page, or the PDF.";
        }
        if (!f.fssai()) {
            return "This isn't an FSSAI licence or registration certificate.";
        }
        String printed = f.number() == null ? "" : f.number().replaceAll("[^0-9]", "");
        if (printed.isEmpty()) {
            return "We couldn't find the FSSAI number on the certificate.";
        }
        if (!printed.equals(c.fssaiNumber())) {
            return "The number on the certificate (" + printed + ") isn't the one you typed (" + c.fssaiNumber()
                    + "). Check the number.";
        }
        if (f.validUpto() != null && f.validUpto().isBefore(today)) {
            return "This licence expired on " + KiranaChecks.day(f.validUpto()) + ". Add the renewed certificate.";
        }
        if (f.validUpto() == null) {
            return "We couldn't read the date the licence is valid until. Upload a clear photo of the whole page, "
                    + "or the PDF.";
        }
        Set<String> ours = words(shops.find(c.shopId()).map(ShopDirectory.ShopCard::name).orElse(""));
        memberships.members(c.shopId()).stream().filter(m -> "OWNER".equals(m.role())).findFirst()
                .map(m -> users.find(List.of(m.userId())).get(m.userId()))
                .ifPresent(p -> ours.addAll(words(p.name())));
        Set<String> theirs = words((f.businessName() == null ? "" : f.businessName()) + " "
                + (f.operatorName() == null ? "" : f.operatorName()));
        theirs.retainAll(ours);
        if (theirs.isEmpty()) {
            return "The name on the certificate (" + (f.businessName() != null ? f.businessName() : f.operatorName())
                    + ") doesn't match your shop's name or your name. Add your own shop's certificate.";
        }
        return null;
    }

    private void decide(Checking c, String status, String reason, LocalDate validUntil, Instant now) {
        transactions.executeWithoutResult(s -> {
            // Kept only for the certificate and number read: a newer upload or a corrected number is checked afresh
            if (online.fssaiResult(c.shopId(), c.fssaiNumber(), c.fileId(), status, reason, validUntil, now)) {
                if ("VERIFIED".equals(status)) {
                    // The renewal replaces the licence kept meanwhile: its certificate is let go
                    online.endKept(c.shopId(), now).filter(f -> !f.equals(c.fileId())).ifPresent(files::release);
                }
                tellDecision(c.shopId(), status, reason, now);
            }
        });
    }

    /** Tells the owner how the certificate was decided. The caller holds a transaction. */
    void tellDecision(UUID shopId, String status, String reason, Instant now) {
        String body = reason;
        if (!"VERIFIED".equals(status)) {
            // A refused renewal: the licence verified before still holds; say until when, so no one thinks it closed
            LocalDate today = KiranaChecks.today(now);
            body = online.find(shopId).filter(o -> o.keptOn(today)).map(o -> reason + " Your current licence keeps "
                    + "your shop open until " + KiranaChecks.day(o.fssaiKeptUntil()) + ".").orElse(reason);
        }
        tellOwner(shopId, now, "VERIFIED".equals(status) ? "FSSAI licence verified" : "FSSAI certificate not accepted",
                "VERIFIED".equals(status) ? "Customers can order from your shop once online orders are on." : body);
    }

    private void tellOwner(UUID shopId, Instant now, String title, String body) {
        memberships.members(shopId).stream().filter(m -> "OWNER".equals(m.role())).forEach(m ->
                notifications.queue(new Notice(m.userId(), "KIRANA_FSSAI", shopId, now, title, body,
                        "/kirana/online", null)));
    }

    /** A name's words that tell it apart (lower case, 3+ letters, without the usual shop words). */
    static Set<String> words(String name) {
        return Arrays.stream(name.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}/]+"))
                .filter(w -> w.length() >= 3 && !COMMON.contains(w))
                .collect(Collectors.toCollection(HashSet::new));
    }
}
