package in.apnidukan.auth.notify;

/**
 * Sends text messages. In India every SMS must match a DLT-approved template, so codes and notices are separate
 * calls: each maps to its own MSG91 template.
 */
public interface SmsSender {

    /** A 6-digit sign-in or recovery code. */
    void sendOtp(String phone, String code);

    /** An informational message (e.g. "your account moved"). Skipped if no template is set up for notices yet. */
    void sendNotice(String phone, String text);
}
