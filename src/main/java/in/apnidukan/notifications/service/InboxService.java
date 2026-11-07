package in.apnidukan.notifications.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.notifications.domain.Audience;
import in.apnidukan.notifications.domain.InboxItem;
import in.apnidukan.notifications.domain.InboxPage;
import in.apnidukan.notifications.repo.NotificationStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The in-app message list. It shows the same messages that go out as notifications (kept 90 days, see
 * MessageCleanupJob), so people who block notifications or miss one can still read it in the app.
 */
@Service
public class InboxService {

    static final int PAGE = 30;
    static final int MAX_MARK = 100;

    private final NotificationStore store;
    private final Clock clock;
    /** Messages marked read per statement by "mark all read" (app.notifications.mark-all-slice, default 5,000). */
    private final int markAllSlice;

    public InboxService(NotificationStore store, Clock clock,
                        @Value("${app.notifications.mark-all-slice:5000}") int markAllSlice) {
        if (markAllSlice < 1) {   // 0 would loop forever (every slice "full")
            throw new IllegalStateException("app.notifications.mark-all-slice must be at least 1");
        }
        this.store = store;
        this.clock = clock;
        this.markAllSlice = markAllSlice;
    }

    @Transactional(readOnly = true)
    public InboxPage list(UUID userId, String app, String cursor) {
        Audience audience = audience(app);
        Instant now = clock.instant();
        Instant beforeAt = null;
        UUID beforeId = null;
        if (cursor != null && !cursor.isBlank()) {
            String[] parts = cursor.split("_", 2);
            try {
                beforeAt = Instant.parse(parts[0]);
                beforeId = UUID.fromString(parts[1]);
            } catch (RuntimeException e) {
                throw ApiException.badRequest("BAD_CURSOR", "This page link is not valid. Start from the top.");
            }
        }
        List<InboxItem> rows = store.inbox(userId, audience, now, beforeAt, beforeId, PAGE + 1);
        List<InboxItem> items = rows.size() > PAGE ? rows.subList(0, PAGE) : rows;
        String next = rows.size() > PAGE ? items.get(PAGE - 1).sentAt() + "_" + items.get(PAGE - 1).id() : null;
        return new InboxPage(items, store.unreadCount(userId, audience, now), next);
    }

    @Transactional(readOnly = true)
    public long unread(UUID userId, String app) {
        return store.unreadCount(userId, audience(app), clock.instant());
    }

    /**
     * Marks these messages read, or all of this app's list when ids is null or empty: then MARK_ALL_SLICE at a time,
     * each slice its own short statement (no surrounding transaction: a slice done stays done), until none is left.
     */
    public void markRead(UUID userId, String app, List<UUID> ids) {
        Audience audience = audience(app);
        if (ids == null || ids.isEmpty()) {
            Instant now = clock.instant();
            int n;
            do {   // a full slice means there may be more
                n = store.markAllReadSlice(userId, audience, now, markAllSlice);
            } while (n == markAllSlice);
            return;
        }
        if (ids.contains(null)) {
            throw ApiException.badRequest("VALIDATION_FAILED", "ids must not contain empty values");
        }
        if (ids.size() > MAX_MARK) {
            throw ApiException.badRequest("TOO_MANY", "Mark at most " + MAX_MARK + " messages at once");
        }
        store.markRead(userId, ids.stream().distinct().toList(), clock.instant());
    }

    static Audience audience(String app) {
        if (app == null || app.isBlank()) {
            return Audience.CUSTOMER;
        }
        try {
            return Audience.valueOf(app.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("UNKNOWN_APP", "app must be customer or partner");
        }
    }
}
