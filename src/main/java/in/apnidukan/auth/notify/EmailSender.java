package in.apnidukan.auth.notify;

/** Sends a plain-text email. */
public interface EmailSender {

    void send(String to, String subject, String body);
}
