package in.apnidukan.notifications.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.notifications.domain.InboxPage;
import in.apnidukan.notifications.service.InboxService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The signed-in person's message list in the app. `app` = customer (default) or partner. */
@RestController
public class InboxController {

    private final InboxService inbox;
    private final CurrentUserProvider currentUser;

    public InboxController(InboxService inbox, CurrentUserProvider currentUser) {
        this.inbox = inbox;
        this.currentUser = currentUser;
    }

    /**
     * Newest first, 30 a page; pass the reply's `next` as `cursor` for the next page. The first page carries an ETag
     * of its own: sent back as If-None-Match while nothing changed, 304 without reading the database (InboxService).
     */
    @GetMapping("/api/v1/me/notifications")
    public ResponseEntity<InboxPage> list(@RequestParam(required = false) String app,
                                          @RequestParam(required = false) String cursor,
                                          HttpServletRequest request) {
        UUID me = me();
        if (cursor == null || cursor.isBlank()) {
            Optional<String> same = inbox.unchanged(me, app, InboxService.LIST, sent(request));
            if (same.isPresent()) {
                return notModified(request, same.get());
            }
        }
        return tagged(request, inbox.listTagged(me, app, cursor));
    }

    /** The bell's number, with an ETag of its own (304 without a database read while nothing changed). */
    @GetMapping("/api/v1/me/notifications/unread-count")
    public ResponseEntity<Unread> unread(@RequestParam(required = false) String app, HttpServletRequest request) {
        UUID me = me();
        Optional<String> same = inbox.unchanged(me, app, InboxService.UNREAD, sent(request));
        if (same.isPresent()) {
            return notModified(request, same.get());
        }
        InboxService.Tagged<Long> count = inbox.unreadTagged(me, app);
        return tagged(request, new InboxService.Tagged<>(new Unread(count.body()), count.etag()));
    }

    /** The If-None-Match values the app sent. */
    private static List<String> sent(HttpServletRequest request) {
        return Collections.list(request.getHeaders(HttpHeaders.IF_NONE_MATCH));
    }

    /** 304 with the tag; EtagConfig's own fingerprint is skipped (there is no body to fingerprint). */
    private static <T> ResponseEntity<T> notModified(HttpServletRequest request, String etag) {
        ShallowEtagHeaderFilter.disableContentCaching(request);
        return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
    }

    /** 200 with our own tag; without one (Redis didn't answer), EtagConfig fingerprints the body as for every GET. */
    private static <T> ResponseEntity<T> tagged(HttpServletRequest request, InboxService.Tagged<T> answer) {
        if (answer.etag() == null) {
            return ResponseEntity.ok(answer.body());
        }
        ShallowEtagHeaderFilter.disableContentCaching(request);
        return ResponseEntity.ok().eTag(answer.etag()).body(answer.body());
    }

    /** Marks these read; with no ids, everything in this app's list. */
    @PostMapping("/api/v1/me/notifications/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@RequestParam(required = false) String app, @RequestBody(required = false) MarkRead body) {
        inbox.markRead(me(), app, body == null ? null : body.ids());
    }

    /** @param unread unread messages in this app's list, counted up to 100: the apps show "99+" from 100 on */
    public record Unread(long unread) {
    }

    /** At most 100 ids (InboxService.MAX_MARK). */
    public record MarkRead(List<UUID> ids) {
    }

    private UUID me() {
        return currentUser.get().userId();
    }
}
