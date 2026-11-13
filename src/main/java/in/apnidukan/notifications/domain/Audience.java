package in.apnidukan.notifications.domain;

import java.util.List;

/**
 * Which app is asking for the list. One account can be a customer and a shop owner, and each app shows only its
 * own messages: the customer app the booking messages, the partner app the salon and shop ones; each its own
 * "Support replied" ones.
 */
public enum Audience {
    CUSTOMER(List.of("BOOKING", "CUSTOMERHELP")),
    PARTNER(List.of("SALON", "SHOP", "PARTNERHELP"));

    /** The first word of the message types this app shows (BOOKING_CONFIRMED → BOOKING). */
    private final List<String> typeGroups;

    Audience(List<String> typeGroups) {
        this.typeGroups = typeGroups;
    }

    public List<String> typeGroups() {
        return typeGroups;
    }
}
