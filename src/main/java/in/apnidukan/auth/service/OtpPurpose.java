package in.apnidukan.auth.service;

import java.time.Duration;

/** What a one-time code is for, and how long it lives. Each purpose has its own codes and limits in Redis. */
public enum OtpPurpose {
    LOGIN(Duration.ofMinutes(5)),
    EMAIL_VERIFY(Duration.ofMinutes(10)),
    RECOVER_EMAIL(Duration.ofMinutes(10)),
    RECOVER_PHONE(Duration.ofMinutes(10)),
    /** Code to the person's own phone, needed to replace an already verified email. */
    CHANGE_EMAIL(Duration.ofMinutes(10));

    private final Duration validFor;

    OtpPurpose(Duration validFor) {
        this.validFor = validFor;
    }

    public Duration validFor() {
        return validFor;
    }
}
