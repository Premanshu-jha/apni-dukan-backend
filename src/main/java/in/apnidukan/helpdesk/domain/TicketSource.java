package in.apnidukan.helpdesk.domain;

/** Where a ticket came from: an email to the support address, or "Need help" in the customer or partner app. */
public enum TicketSource {
    EMAIL,
    CUSTOMER,
    PARTNER
}
