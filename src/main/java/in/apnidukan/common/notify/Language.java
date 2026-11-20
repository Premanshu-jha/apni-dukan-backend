package in.apnidukan.common.notify;

import java.util.Locale;
import java.util.Optional;

/**
 * The languages the apps speak, by the code a person's choice is saved with (users.language, V83). A person who never
 * chose one reads English.
 */
public enum Language {
    EN("en"),
    HI("hi"),
    TE("te"),
    MR("mr"),
    TA("ta"),
    KN("kn");

    private final String code;
    private final Locale locale;

    Language(String code) {
        this.code = code;
        // Day and month names in the language; digits stay 0-9 (java.time's formatters keep them unless asked)
        this.locale = "en".equals(code) ? Locale.ENGLISH : Locale.forLanguageTag(code + "-IN");
    }

    /** "hi": what is saved and what the apps send. */
    public String code() {
        return code;
    }

    /** For day and month names. */
    public Locale locale() {
        return locale;
    }

    /** The language with this exact code ("hi"), if it is one of ours. */
    public static Optional<Language> of(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (Language l : values()) {
            if (l.code.equals(code)) {
                return Optional.of(l);
            }
        }
        return Optional.empty();
    }

    /** The saved code's language; English for none (null) or one no longer known. */
    public static Language orEnglish(String code) {
        return of(code).orElse(EN);
    }
}
