package in.apnidukan.appupdates.domain;

import java.util.Optional;

/** The two Play Store apps (app_releases.app). */
public enum AppName {
    PARTNER("partner"),
    CUSTOMER("customer");

    private final String value;

    AppName(String value) {
        this.value = value;
    }

    /** As stored and as it appears in the addresses (/app-updates/partner). */
    public String value() {
        return value;
    }

    public static Optional<AppName> of(String value) {
        for (AppName app : values()) {
            if (app.value.equals(value)) {
                return Optional.of(app);
            }
        }
        return Optional.empty();
    }
}
