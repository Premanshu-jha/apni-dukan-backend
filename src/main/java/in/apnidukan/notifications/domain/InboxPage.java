package in.apnidukan.notifications.domain;

import java.util.List;

/**
 * A page of a person's in-app list, newest first.
 *
 * @param unread how many of this app's messages are still unread (all pages), counted up to 100 (the apps show
 *               "99+" from 100 on; see NotificationStore.UNREAD_CAP)
 * @param next   the cursor for the following page, or null at the end
 */
public record InboxPage(List<InboxItem> items, long unread, String next) {

    public InboxPage {
        items = List.copyOf(items);
    }
}
