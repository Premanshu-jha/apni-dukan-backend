package in.apnidukan.auth.service;

import in.apnidukan.common.error.ApiException;

import java.util.Locale;

public final class Emails {

    private Emails() {
    }

    /** Trims and lower-cases; rejects anything that clearly isn't an email address. */
    public static String normalize(String input) {
        String email = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !email.matches("[a-z0-9._%+-]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}")) {
            throw ApiException.badRequest("INVALID_EMAIL", "Enter a valid email address");
        }
        return email;
    }
}
