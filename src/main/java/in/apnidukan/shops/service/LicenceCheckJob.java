package in.apnidukan.shops.service;

import in.apnidukan.common.files.FileRefs;
import in.apnidukan.common.jobs.JobLeases;
import in.apnidukan.common.notify.Notifications;
import in.apnidukan.common.notify.Notifications.Notice;
import in.apnidukan.shops.domain.LicenceCheckReason;
import in.apnidukan.shops.domain.ShopEventType;
import in.apnidukan.shops.repo.LicenceCheckStore;
import in.apnidukan.shops.repo.ShopReviewStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Checks salons' trade licences in the background: every 20 seconds, on one server at a time (JobLeases), it takes
 * the checks due (a document just added, or one to try again), reads each with the {@link LicenceReader} (the AI) with
 * no transaction open, decides with {@link LicenceRules}, and writes PASSED or FAILED, a timeline note and a message
 * to the owner, all in one short transaction, only if the same document is still there.
 *
 * Under load: one document read at a time for the whole app (the lease), at most {@value #BATCH} taken per slice and
 * the round stops after {@link #MAX_ROUND}, so a flood of uploads only makes the queue longer; uploads themselves
 * never wait for the AI. Without ANTHROPIC_API_KEY there is no reader: checks stay PENDING ("Checking") and are read
 * as soon as a key is set.
 *
 * When the AI can't answer (network, overloaded, out of credit, a wrong key), the check is tried again later, waiting
 * longer each time (1, 2, 4 ... minutes, at most {@link #MAX_WAIT}); the owner sees "Checking", and after half an hour
 * the super admins' follow-up list shows it. An answer we can't use is tried {@value #MAX_UNUSABLE} times (counted
 * apart), then FAILED NOT_READ (the owner can upload a clearer one). Every answer is written only for the check it
 * was about (LicenceCheckStore: the same file and try), so one about an older licence or older details is dropped.
 */
@Component
public class LicenceCheckJob {

    private static final Logger log = LoggerFactory.getLogger(LicenceCheckJob.class);
    static final int BATCH = 5;
    static final int MAX_UNUSABLE = 3;
    /** The largest file read (uploads are at most 10 MB). */
    static final long MAX_BYTES = 10L * 1024 * 1024;
    static final Duration MAX_ROUND = Duration.ofMinutes(10);
    /** A taken check comes back after this if the server dies while reading it. */
    static final Duration CLAIM_FOR = Duration.ofMinutes(15);
    static final Duration MAX_WAIT = Duration.ofHours(6);
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final String PASSED_TYPE = "SHOP_LICENCE_PASSED";
    static final String FAILED_TYPE = "SHOP_LICENCE_FAILED";
    static final String URL = "/setup/document";
    static final String DRAFT_URL = "/register/photos";
    static final java.time.format.DateTimeFormatter DAY =
            java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final LicenceCheckStore store;
    private final ObjectProvider<LicenceReader> reader;
    private final FileRefs files;
    private final ShopReviewStore review;
    private final Notifications notifications;
    private final JobLeases leases;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final LicenceAiWatch watch;
    private final boolean enabled;

    public LicenceCheckJob(LicenceCheckStore store, ObjectProvider<LicenceReader> reader, FileRefs files,
                           ShopReviewStore review, Notifications notifications, JobLeases leases,
                           PlatformTransactionManager transactions, Clock clock, LicenceAiWatch watch,
                           @Value("${app.jobs.enabled:true}") boolean enabled) {
        this.store = store;
        this.reader = reader;
        this.files = files;
        this.review = review;
        this.notifications = notifications;
        this.leases = leases;
        this.transactions = new TransactionTemplate(transactions);
        this.clock = clock;
        this.watch = watch;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 20_000, initialDelay = 40_000)
    public void scheduled() {
        if (enabled && reader.getIfAvailable() != null) {
            // The lease outlasts the longest round: the last slice may start just before MAX_ROUND and take
            // BATCH reads of up to 95 s each
            leases.runExclusively("licence-checks", MAX_ROUND.plusMinutes(10), this::runOnce);
        }
    }

    /** One round: slices of {@value #BATCH} until none is due or the round is long enough. Returns how many were read. */
    public int runOnce() {
        LicenceReader ai = reader.getIfAvailable();
        if (ai == null) {
            return 0;
        }
        try {
            watch.retryOwed();   // a "working again" email that reached nobody before
        } catch (RuntimeException e) {
            log.warn("Licence AI email not retried: {}", e.getClass().getSimpleName());
        }
        Instant started = clock.instant();
        int done = 0;
        List<LicenceCheckStore.Due> due;
        do {
            Instant now = now();
            due = transactions.execute(s -> store.claim(now, now.plus(CLAIM_FOR), BATCH));
            for (LicenceCheckStore.Due check : due) {
                check(ai, check);
                done++;
            }
        } while (due.size() == BATCH && Duration.between(started, clock.instant()).compareTo(MAX_ROUND) < 0);
        return done;
    }

    private void check(LicenceReader ai, LicenceCheckStore.Due check) {
        LicenceExtract read;
        try {
            String type = files.find(check.fileId()).map(FileRefs.FileRef::contentType).orElse(null);
            byte[] bytes = type == null ? null : files.read(check.fileId(), MAX_BYTES).orElse(null);
            if (bytes == null) {   // can't happen while the shop points at it (the file is in use), but never loop
                fail(check, LicenceCheckReason.NOT_READ, null);
                return;
            }
            read = ai.read(bytes, type);
            answered();
        } catch (LicenceReader.UnusableAnswerException e) {
            if (e.answered()) {
                answered();   // it answered (and billed): the account works; a refused file says nothing about it
            }
            // Counted apart from the times the AI didn't answer: only answers it gave and we couldn't use fail it
            if (check.unusable() + 1 >= MAX_UNUSABLE) {
                fail(check, LicenceCheckReason.NOT_READ, null);
            } else {
                store.unusable(check, now().plus(Duration.ofMinutes(5L * (check.unusable() + 1))));
            }
            return;
        } catch (RuntimeException e) {   // the AI or storage didn't answer: never the owner's fault, try later
            if (!(e instanceof LicenceReader.ReaderUnavailableException unavailable)) {
                log.warn("Licence check of shop {} failed: {}", check.shopId(), e.getClass().getSimpleName());
            } else if (unavailable.problem() != null) {
                alert(unavailable.problem());   // out of credit or a wrong key: the super admins are told
            }
            store.retryAt(check, now().plus(wait(check.attempt())));
            return;
        }
        LocalDate today = LocalDate.now(clock.withZone(INDIA));
        LicenceRules.Outcome outcome = LicenceRules.decide(read, new LicenceRules.Salon(check.name(),
                check.ownerName(), check.addressLine(), check.area(), check.city(), check.pincode()), today);
        String extract = JSON.writeValueAsString(read);
        if (!outcome.passed()) {
            fail(check, outcome.reason(), extract);
            return;
        }
        try {
            Boolean wrote = transactions.execute(s -> {
                if (store.numberTaken(outcome.licenceNumber(), check.shopId())) {
                    throw new DuplicateKeyException("licence number taken");
                }
                Instant now = now();
                if (!store.pass(check, outcome.licenceNumber(), outcome.validUntil(), extract, now)) {
                    return false;   // replaced, removed or its details changed meanwhile: its own check decides
                }
                review.addEvent(check.shopId(), now, null, true, ShopEventType.NOTE, null,
                        "Trade licence checked automatically: " + outcome.licenceNumber() + ", valid until "
                                + day(outcome.validUntil()), List.of());
                tell(check.shopId(), PASSED_TYPE, "Trade licence checked",
                        "Customers now see \"Licensed\" on your salon, until " + day(outcome.validUntil()) + ".",
                        url(check.draft()), now);
                return true;
            });
            if (Boolean.TRUE.equals(wrote)) {
                log.info("Licence of shop {} passed", check.shopId());
            }
        } catch (DuplicateKeyException taken) {   // another salon has it (or passed with it at this very moment)
            fail(check, LicenceCheckReason.DUPLICATE, extract);
        }
    }

    /** The AI answered: a refusal emailed about is over (LicenceAiWatch says so once). */
    private void answered() {
        try {
            watch.working();
        } catch (RuntimeException e) {   // the alert's bookkeeping never stops a check
            log.warn("Licence AI alert not updated: {}", e.getClass().getSimpleName());
        }
    }

    private void alert(LicenceAiWatch.Problem problem) {
        try {
            watch.problem(problem);
        } catch (RuntimeException e) {
            log.warn("Licence AI alert not sent: {}", e.getClass().getSimpleName());
        }
    }

    private void fail(LicenceCheckStore.Due check, LicenceCheckReason reason, String extract) {
        Boolean wrote = transactions.execute(s -> {
            if (!store.fail(check, reason, extract)) {
                return false;   // replaced, removed or its details changed meanwhile: its own check decides
            }
            Instant now = now();
            review.addEvent(check.shopId(), now, null, true, ShopEventType.NOTE, null,
                    "Trade licence not accepted automatically: " + describe(reason), List.of());
            tell(check.shopId(), FAILED_TYPE, "Trade licence not accepted",
                    explain(reason) + " Open Trade licence to see what to do.", url(check.draft()), now);
            return true;
        });
        if (Boolean.TRUE.equals(wrote)) {
            log.info("Licence of shop {} failed: {}", check.shopId(), reason);
        }
    }

    private void tell(UUID shopId, String type, String title, String body, String url, Instant now) {
        for (UUID owner : store.owners(shopId)) {
            notifications.queue(new Notice(owner, type, null, now, title, body, url, Map.of()));
        }
    }

    /** The owner's page about the licence: the registration step while the salon is a draft, else the Salon tab's. */
    static String url(boolean draft) {
        return draft ? DRAFT_URL : URL;
    }

    /** "31 Mar 2027". */
    static String day(LocalDate date) {
        return date.format(DAY);
    }

    /** What the owner is told, in a sentence (the partner app's licenceReasonText says the same, with more). */
    static String explain(LicenceCheckReason reason) {
        return switch (reason) {
            case UNREADABLE -> "We couldn't read it: take a clear, well-lit photo of the whole licence.";
            case NOT_TRADE_LICENCE -> "It isn't a municipal trade licence.";
            case LOOKS_EDITED -> "It looks edited: upload it exactly as you got it.";
            case NO_NUMBER -> "We couldn't find the licence number on it.";
            case NOT_A_SALON_TRADE -> "It is a licence for another kind of business, not a salon.";
            case NO_VALIDITY -> "We couldn't find until when it is valid.";
            case EXPIRED -> "It has expired: add the renewed licence.";
            case NAME_MISMATCH -> "The name on it doesn't match your salon's name or the owner's name.";
            case ADDRESS_MISMATCH -> "The address on it doesn't match your salon's address.";
            case DUPLICATE -> "This licence is already used by another salon. If it is yours, write to support.";
            case NOT_READ -> "We couldn't read it: try a clearer photo, or the PDF.";
        };
    }

    /** The reason in words, for the field team's timeline. */
    static String describe(LicenceCheckReason reason) {
        return switch (reason) {
            case UNREADABLE -> "too blurred or cut off to read";
            case NOT_TRADE_LICENCE -> "not a municipal trade licence";
            case LOOKS_EDITED -> "looks edited";
            case NO_NUMBER -> "no licence number found";
            case NOT_A_SALON_TRADE -> "a licence for another trade";
            case NO_VALIDITY -> "no valid-up-to date found";
            case EXPIRED -> "expired";
            case NAME_MISMATCH -> "neither the business nor the holder's name matches the salon";
            case ADDRESS_MISMATCH -> "the address doesn't match the salon's";
            case DUPLICATE -> "another salon already has this licence";
            case NOT_READ -> "no usable answer from the AI";
        };
    }

    /** 1, 2, 4, 8 ... minutes after the n-th try, at most {@link #MAX_WAIT}. */
    static Duration wait(int attempt) {
        int doublings = Math.min(Math.max(attempt - 1, 0), 20);
        Duration d = Duration.ofMinutes(1L << doublings);
        return d.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : d;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
