package in.apnidukan.shops.service;

import in.apnidukan.common.jobs.JobLeases;
import in.apnidukan.common.notify.Notifications;
import in.apnidukan.common.notify.Notifications.Notice;
import in.apnidukan.shops.repo.LicenceCheckStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tells salon owners about their trade licence's last day, so "Licensed" doesn't go without a word: a month before it
 * ({@value #REMIND_DAYS} days: "add the renewed one"), and the day after it ("customers no longer see Licensed").
 * Every hour between 9:00 and 21:00 India time, on one server at a time; each message once per licence
 * (licence_expiry_notice, written in the same transaction as the message). A licence passed with less than a month
 * left gets only the reminder; one passed and gone between two rounds gets only "expired".
 */
@Component
public class LicenceExpiryJob {

    static final int REMIND_DAYS = 30;
    static final int BATCH = 200;
    static final String EXPIRING_TYPE = "SHOP_LICENCE_EXPIRING";
    static final String EXPIRED_TYPE = "SHOP_LICENCE_EXPIRED";
    private static final LocalTime FROM = LocalTime.of(9, 0);
    private static final LocalTime UNTIL = LocalTime.of(21, 0);

    private final LicenceCheckStore store;
    private final Notifications notifications;
    private final JobLeases leases;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final boolean enabled;

    public LicenceExpiryJob(LicenceCheckStore store, Notifications notifications, JobLeases leases,
                            PlatformTransactionManager transactions, Clock clock,
                            @Value("${app.jobs.enabled:true}") boolean enabled) {
        this.store = store;
        this.notifications = notifications;
        this.leases = leases;
        this.transactions = new TransactionTemplate(transactions);
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void scheduled() {
        if (enabled) {
            leases.runExclusively("licence-expiry", Duration.ofMinutes(10), this::runOnce);
        }
    }

    /** One round (nothing outside 9:00–21:00 India time); returns how many messages it queued. Called by tests too. */
    public int runOnce() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        LocalTime time = LocalTime.ofInstant(now, LicenceCheckJob.INDIA);
        if (time.isBefore(FROM) || !time.isBefore(UNTIL)) {
            return 0;
        }
        LocalDate today = LocalDate.ofInstant(now, LicenceCheckJob.INDIA);
        int sent = 0;
        List<LicenceCheckStore.Expiring> due;
        int before;
        do {
            before = sent;
            due = store.expiring(today, today.plusDays(REMIND_DAYS), BATCH);
            for (LicenceCheckStore.Expiring e : due) {
                boolean expired = e.validUntil().isBefore(today);
                Boolean told = transactions.execute(s -> {
                    if (!store.noticeSent(e.shopId(), e.validUntil(), expired ? 2 : 1)) {
                        return false;   // another server, or the licence changed meanwhile
                    }
                    String day = LicenceCheckJob.day(e.validUntil());
                    for (UUID owner : store.owners(e.shopId())) {
                        notifications.queue(expired
                                ? new Notice(owner, EXPIRED_TYPE, null, now, "Trade licence expired",
                                        "Your trade licence expired on " + day + ": customers no longer see "
                                                + "\"Licensed\". Upload the renewed licence to show it again.",
                                        LicenceCheckJob.url(e.draft()), Map.of())
                                : new Notice(owner, EXPIRING_TYPE, null, now, "Trade licence ends soon",
                                        "Your trade licence is valid until " + day + ". Upload the renewed licence "
                                                + "before then to keep \"Licensed\" on your salon.",
                                        LicenceCheckJob.url(e.draft()), Map.of()));
                    }
                    return true;
                });
                if (Boolean.TRUE.equals(told)) {
                    sent++;
                }
            }
        } while (due.size() == BATCH && sent > before);   // a full batch none of which changed: stop, never loop
        return sent;
    }
}
