package in.apnidukan.notifications.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.notifications.domain.InboxPage;
import in.apnidukan.notifications.service.InboxService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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

    /** Newest first, 30 a page; pass the reply's `next` as `cursor` for the next page. */
    @GetMapping("/api/v1/me/notifications")
    public InboxPage list(@RequestParam(required = false) String app,
                                  @RequestParam(required = false) String cursor) {
        return inbox.list(me(), app, cursor);
    }

    @GetMapping("/api/v1/me/notifications/unread-count")
    public Unread unread(@RequestParam(required = false) String app) {
        return new Unread(inbox.unread(me(), app));
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
