package in.apnidukan.auth.notify;

/** Sends a plain-text email. */
public interface EmailSender {

    void send(String to, String subject, String body);

    /**
     * Like send, from another address of ours (e.g. "Clozest Support &lt;support@clozest.in&gt;") and with replies
     * going to {@code replyTo}. A sender that can't change who the email is from (Gmail SMTP sends only from its
     * signed-in account) still sets the reply address; the logging one just logs.
     */
    default void send(String to, String subject, String body, String from, String replyTo) {
        send(new Email(to, subject, body, from, replyTo, subject, null));
    }

    /** The full form: see {@link Email}. A sender that can't set something leaves it out. */
    default void send(Email email) {
        send(email.to(), email.subject(), email.body());
    }

    /**
     * @param logAs          what logs may say about it instead of the subject (a support reply's subject is the
     *                       person's own words), e.g. "ticket email"
     * @param idempotencyKey the same key for every try of one email, so a provider that got it before (its answer
     *                       lost) doesn't send it twice; null for none
     */
    record Email(String to, String subject, String body, String from, String replyTo, String logAs,
                 String idempotencyKey) {
    }
}
