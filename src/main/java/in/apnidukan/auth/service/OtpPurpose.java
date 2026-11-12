package in.apnidukan.auth.service;

import java.time.Duration;

/** What a one-time code is for, and how long it lives. Each purpose has its own codes and limits in Redis. */
public enum OtpPurpose {
    LOGIN(Duration.ofMinutes(5)),
    EMAIL_VERIFY(Duration.ofMinutes(10)),
    RECOVER_EMAIL(Duration.ofMinutes(10)),
    RECOVER_PHONE(Duration.ofMinutes(10)),
    /** Code to the person's own phone, needed to replace an already verified email. */
    CHANGE_EMAIL(Duration.ofMinutes(10)),
    /** Code to the person's own phone, to confirm deleting their account. */
    DELETE_ACCOUNT(Duration.ofMinutes(10)),
    /** Signed in, changing the phone number: code to the new number (target: user id and the new number). */
    CHANGE_PHONE(Duration.ofMinutes(10)),
    /** Signed in, changing the phone number: the second code, to the verified email (target: user, email, new number). */
    CHANGE_PHONE_EMAIL(Duration.ofMinutes(10)),
    /** The second code without a verified email: to the number the account has now (target: user, it, new number). */
    CHANGE_PHONE_CURRENT(Duration.ofMinutes(10)),
    /** Code to the person's own phone, to unlock editing a verified shop's details for a while (EditUnlockStore). */
    EDIT_UNLOCK(Duration.ofMinutes(10));

    private final Duration validFor;

    OtpPurpose(Duration validFor) {
        this.validFor = validFor;
    }

    public Duration validFor() {
        return validFor;
    }
}
