package in.apnidukan.helpdesk.service;

import in.apnidukan.common.notify.Language;
import in.apnidukan.common.notify.NoticeTexts;
import in.apnidukan.helpdesk.domain.Ticket;

import java.util.List;
import java.util.Map;

/**
 * The texts of the emails a ticket sends, in the language of the requester's account (users.language; English when
 * they chose none or have no account): the fixed words are the email.ticket.* texts of notices/*.properties. Every
 * subject carries "[CLZ-1042]" exactly so in every language: a reply to it comes back to the same ticket
 * (InboundEmail.ticketNumber). The support person's own reply and the messages quoted back stay as they were written.
 */
final class TicketEmails {

    /** How much of the person's own message the acknowledgement quotes back. */
    static final int QUOTE = 1000;

    private static final String KEY = "email.ticket.";
    /** Every text an email may use: a language that lacks one of them gets the whole email in English. */
    private static final List<String> KEYS = List.of("ackSubject", "hi", "hiName", "ack", "yourMessage", "addMore",
            "signature", "signedBy", "resolved", "replyMore", "youWrote", "yourRequest", "files.one", "files.other");

    private TicketEmails() {
    }

    static String ackSubject(Ticket t) {
        return ackSubject(t, Language.EN);
    }

    static String ackSubject(Ticket t, Language language) {
        return "[" + t.label() + "] " + text(language, "ackSubject", Map.of());
    }

    static String ackBody(Ticket t, String name, String message) {
        return ackBody(t, name, message, Language.EN);
    }

    /**
     * "We've got it": sent once the ticket is made and given to someone.
     * @param message their message, quoted back; null for an email whose sender couldn't be proven (the address may
     *                not be theirs, so nothing they wrote is sent to it)
     */
    static String ackBody(Ticket t, String name, String message, Language language) {
        Language l = usable(language);
        String quote = message == null ? ""
                : text(l, "yourMessage", Map.of()) + "\n" + quoted(message) + "\n\n";
        return greeting(l, name)
                + text(l, "ack", Map.of("ticket", t.label())) + "\n\n"
                + quote
                + text(l, "addMore", Map.of("ticket", t.label())) + "\n\n"
                + text(l, "signature", Map.of());
    }

    private static String quoted(String message) {
        String cut = message.length() <= QUOTE ? message : message.substring(0, QUOTE) + "…";
        return cut.replaceAll("(?m)^", "> ");
    }

    static String replySubject(Ticket t) {
        return replySubject(t, Language.EN);
    }

    /** "Re: [CLZ-1042] " and their own subject, or "Your request" in their language when there is none. */
    static String replySubject(Ticket t, Language language) {
        String subject = t.subject().replaceAll("^(?i)((re|fwd?)\\s*:\\s*)+", "")
                .replace("[" + t.label() + "]", "").strip();
        return "Re: [" + t.label() + "] " + (subject.isEmpty() || subject.equals("(no subject)")
                ? text(usable(language), "yourRequest", Map.of()) : subject);
    }

    static String replyBody(Ticket t, String name, String reply, boolean resolved, String agentName,
                            String theirLast) {
        return replyBody(t, name, reply, resolved, agentName, theirLast, Language.EN);
    }

    /**
     * A support person's answer (as they typed it); when it resolves the ticket, how to open it again; their last
     * message quoted below.
     * @param agentName the support person's name (null: none; never their phone)
     */
    static String replyBody(Ticket t, String name, String reply, boolean resolved, String agentName,
                            String theirLast, Language language) {
        Language l = usable(language);
        return greeting(l, name)
                + reply + "\n\n"
                + (resolved ? text(l, "resolved", Map.of("ticket", t.label())) : text(l, "replyMore", Map.of()))
                + "\n\n"
                + (agentName == null || agentName.isBlank() ? text(l, "signature", Map.of())
                : text(l, "signedBy", Map.of("name", firstName(agentName))))
                + (theirLast == null ? "" : "\n\n" + text(l, "youWrote", Map.of()) + "\n" + quoted(theirLast));
    }

    /** "(We sent 2 files with this reply. See them in the Clozest app: ...)": under a reply that came with files. */
    static String filesNote(int count, Language language) {
        return text(usable(language), count == 1 ? "files.one" : "files.other", Map.of("count", count));
    }

    private static String greeting(Language l, String name) {
        return (name == null || name.isBlank() ? text(l, "hi", Map.of())
                : text(l, "hiName", Map.of("name", firstName(name)))) + "\n\n";
    }

    private static String firstName(String name) {
        String n = name.strip();
        int space = n.indexOf(' ');
        return space > 0 ? n.substring(0, space) : n;
    }

    /** The language, if it has every text of the emails; English otherwise (never half one, half the other). */
    static Language usable(Language language) {
        if (language == null) {
            return Language.EN;
        }
        for (String k : KEYS) {
            if (NoticeTexts.template(language, KEY + k).isEmpty()) {
                return Language.EN;
            }
        }
        return language;
    }

    /** The text in the language (usable() checked it has it), or in English for a value it can't fill. */
    private static String text(Language language, String key, Map<String, ?> values) {
        return NoticeTexts.render(language, KEY + key, values)
                .or(() -> NoticeTexts.render(Language.EN, KEY + key, values))
                .orElseThrow(() -> new IllegalStateException("notices/en.properties lacks " + KEY + key));
    }
}
