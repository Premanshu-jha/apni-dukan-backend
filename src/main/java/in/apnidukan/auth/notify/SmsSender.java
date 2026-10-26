package in.apnidukan.auth.notify;

/**
 * Sends text messages. In India every SMS must match a DLT-approved template whose text is fixed, so each kind of
 * SMS is its own method with its own MSG91 template; only the variables change.
 */
public interface SmsSender {

    /** A 6-digit sign-in or recovery code. MSG91 template variable: OTP. */
    void sendOtp(String phone, String code);

    /**
     * Tells the old phone that the account moved to a new number (account recovery), so a real owner notices if
     * someone else did it. MSG91 template variable: ENDING (last 4 digits of the new number).
     */
    void sendAccountMoved(String phone, String newPhoneEnding);
}
