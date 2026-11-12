package in.apnidukan.helpdesk.service;

import in.apnidukan.helpdesk.domain.Ticket;

/**
 * The texts of the emails a ticket sends. Every subject carries "[CLZ-1042]": a reply to it comes back to the same
 * ticket (InboundEmail.ticketNumber).
 */
final class TicketEmails {

    /** How much of the person's own message the acknowledgement quotes back. */
    static final int QUOTE = 1000;

    private TicketEmails() {
    }

    static String ackSubject(Ticket t) {
        return "[" + t.label() + "] We've got your request";
    }

    /**
     * "We've got it": sent once the ticket is made and given to someone.
     * @param message their message, quoted back; null for an email whose sender couldn't be proven (the address may
     *                not be theirs, so nothing they wrote is sent to it)
     */
    static String ackBody(Ticket t, String name, String message) {
        String quote = message == null ? ""
                : "Your message:\n" + quoted(message) + "\n\n";
        return greeting(name)
                + "We've got your request to Clozest support and created ticket " + t.label() + ". Our support team "
                + "will get back to you as soon as we can, usually within a working day.\n\n"
                + quote
                + "To add anything (a photo or screenshot too), just reply to this email (keep " + t.label()
                + " in the subject).\n\n"
                + "Clozest Support";
    }

    private static String quoted(String message) {
        String cut = message.length() <= QUOTE ? message : message.substring(0, QUOTE) + "…";
        return cut.replaceAll("(?m)^", "> ");
    }

    static String replySubject(Ticket t) {
        String subject = t.subject().replaceAll("^(?i)((re|fwd?)\\s*:\\s*)+", "")
                .replace("[" + t.label() + "]", "").strip();
        return "Re: [" + t.label() + "] " + (subject.isEmpty() || subject.equals("(no subject)") ? "Your request" : subject);
    }

    /**
     * A support person's answer; when it resolves the ticket, how to open it again; their last message quoted below.
     * @param agentName the support person's name (null: none; never their phone)
     */
    static String replyBody(Ticket t, String name, String reply, boolean resolved, String agentName,
                            String theirLast) {
        return greeting(name)
                + reply + "\n\n"
                + (resolved
                ? "We've marked ticket " + t.label() + " as resolved. If you still need help, just reply to this "
                + "email and it will open again.\n\n"
                : "Reply to this email if you have anything to add.\n\n")
                + (agentName == null || agentName.isBlank() ? "" : firstName(agentName) + ", ") + "Clozest Support"
                + (theirLast == null ? "" : "\n\nYou wrote:\n" + quoted(theirLast));
    }

    private static String greeting(String name) {
        return "Hi" + (name == null || name.isBlank() ? "" : " " + firstName(name)) + ",\n\n";
    }

    private static String firstName(String name) {
        String n = name.strip();
        int space = n.indexOf(' ');
        return space > 0 ? n.substring(0, space) : n;
    }
}
