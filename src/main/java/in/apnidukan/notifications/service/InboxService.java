package in.apnidukan.notifications.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.notifications.domain.Audience;
import in.apnidukan.notifications.domain.InboxItem;
import in.apnidukan.notifications.domain.InboxPage;
import in.apnidukan.notifications.repo.NotificationStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The in-app message list. It shows the same messages that go out as notifications (kept 90 days, see
 * MessageCleanupJob), so people who block notifications or miss one can still read it in the app.
 *
 * The bell asks for the unread count every 30 to 60 seconds, and the list's first page when opened. Both carry an ETag
 * of their own: the person's inbox version (InboxVersions, moved after every write that changes what they show), the
 * app and the answer asked for, and until when time alone can't change it ({@code validUntil}: the next message of
 * theirs waiting for its time, at most 15 minutes ahead). Sent back as If-None-Match while that holds, the answer is
 * 304 after one Redis read and no database read at all.
 */
@Service
public class InboxService {

    static final int PAGE = 30;
    static final int MAX_MARK = 100;
    /** A tag stands at most this long, whatever else (a message deleted by the 90-day cleanup moves no version). */
    static final Duration MAX_TAG_LIFE = Duration.ofMinutes(15);
    public static final String UNREAD = "unread";
    public static final String LIST = "list";

    /** "n1-<validUntil, epoch seconds>-<fingerprint>", quoted as HTTP wants. */
    private static final Pattern OURS = Pattern.compile("\"n1-(\\d{1,12})-([0-9a-f]{32})\"");
    /** At most this many tags of one If-None-Match header are looked at. */
    private static final int MAX_TAGS = 10;

    /** An answer and its ETag (null: no tag of our own, Redis didn't answer). */
    public record Tagged<T>(T body, String etag) {
    }

    private final NotificationStore store;
    private final InboxVersions versions;
    private final Clock clock;
    /** Messages marked read per statement by "mark all read" (app.notifications.mark-all-slice, default 5,000). */
    private final int markAllSlice;

    public InboxService(NotificationStore store, InboxVersions versions, Clock clock,
                        @Value("${app.notifications.mark-all-slice:5000}") int markAllSlice) {
        if (markAllSlice < 1) {   // 0 would loop forever (every slice "full")
            throw new IllegalStateException("app.notifications.mark-all-slice must be at least 1");
        }
        this.store = store;
        this.versions = versions;
        this.clock = clock;
        this.markAllSlice = markAllSlice;
    }

    @Transactional(readOnly = true)
    public InboxPage list(UUID userId, String app, String cursor) {
        return listTagged(userId, app, cursor).body();
    }

    /** A page of the list; the first page (no cursor) with its tag. */
    @Transactional(readOnly = true)
    public Tagged<InboxPage> listTagged(UUID userId, String app, String cursor) {
        Audience audience = audience(app);
        Optional<String> version = cursor == null || cursor.isBlank() ? versions.current(userId) : Optional.empty();
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
        NotificationStore.Unread unread = store.unread(userId, audience, now);
        InboxPage page = new InboxPage(items, unread.count(), next);
        return new Tagged<>(page, version.map(v -> tag(LIST, userId, audience, v, validUntil(now, unread)))
                .orElse(null));
    }

    @Transactional(readOnly = true)
    public long unread(UUID userId, String app) {
        return unreadTagged(userId, app).body();
    }

    /** The unread count with its tag. The version is read before the count, so a newer count never gets an older tag. */
    @Transactional(readOnly = true)
    public Tagged<Long> unreadTagged(UUID userId, String app) {
        Audience audience = audience(app);
        Optional<String> version = versions.current(userId);
        Instant now = clock.instant();
        NotificationStore.Unread unread = store.unread(userId, audience, now);
        return new Tagged<>(unread.count(), version.map(v -> tag(UNREAD, userId, audience, v,
                validUntil(now, unread))).orElse(null));
    }

    /**
     * The tag the app sent back (If-None-Match) that still describes the answer, if one does: same person, app,
     * answer and inbox version, and not past its validUntil. Tags that aren't ours (or have run out) cost nothing.
     */
    public Optional<String> unchanged(UUID userId, String app, String kind, List<String> ifNoneMatch) {
        Audience audience = audience(app);
        long now = clock.instant().getEpochSecond();
        List<String[]> sent = ifNoneMatch.stream()
                .flatMap(header -> OURS.matcher(header).results().map(r -> new String[] {r.group(), r.group(1)}))
                .filter(t -> now < Long.parseLong(t[1]))
                .limit(MAX_TAGS)
                .toList();
        if (sent.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> version = versions.current(userId);
        if (version.isEmpty()) {
            return Optional.empty();
        }
        for (String[] t : sent) {
            if (tag(kind, userId, audience, version.get(), Instant.ofEpochSecond(Long.parseLong(t[1]))).equals(t[0])) {
                return Optional.of(t[0]);
            }
        }
        return Optional.empty();
    }

    /** Until when time alone can't change the answer: the next message waiting for its time, at most 15 minutes. */
    private static Instant validUntil(Instant now, NotificationStore.Unread unread) {
        Instant cap = now.plus(MAX_TAG_LIFE);
        return unread.nextDue() != null && unread.nextDue().isBefore(cap) ? unread.nextDue() : cap;
    }

    private static String tag(String kind, UUID userId, Audience audience, String version, Instant validUntil) {
        long until = validUntil.getEpochSecond();
        String what = String.join("|", kind, userId.toString(), audience.name(), version,
                Long.toString(until));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(what.getBytes(StandardCharsets.UTF_8));
            return "\"n1-" + until + "-" + HexFormat.of().formatHex(hash, 0, 16) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in Java", e);
        }
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
            versions.bumpAfterCommit(List.of(userId));
            return;
        }
        if (ids.contains(null)) {
            throw ApiException.badRequest("VALIDATION_FAILED", "ids must not contain empty values");
        }
        if (ids.size() > MAX_MARK) {
            throw ApiException.badRequest("TOO_MANY", "Mark at most " + MAX_MARK + " messages at once");
        }
        if (store.markRead(userId, ids.stream().distinct().toList(), clock.instant()) > 0) {
            versions.bumpAfterCommit(List.of(userId));
        }
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
