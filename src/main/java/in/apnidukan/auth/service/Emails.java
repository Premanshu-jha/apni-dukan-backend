package in.apnidukan.auth.service;

import in.apnidukan.common.error.ApiException;

import java.util.Locale;

public final class Emails {

    private Emails() {
    }

    private static final java.util.regex.Pattern LOCAL = java.util.regex.Pattern.compile("[a-z0-9._%+-]{1,64}");
    private static final java.util.regex.Pattern LABEL = java.util.regex.Pattern.compile("[a-z0-9-]{1,63}");
    private static final java.util.regex.Pattern TOP_LEVEL = java.util.regex.Pattern.compile("[a-z]{2,63}");

    /**
     * Trims and lower-cases; rejects anything that clearly isn't an email address.
     * Checked part by part with simple patterns (no nested repetition), so a crafted input can't make the check
     * slow (ReDoS).
     */
    public static String normalize(String input) {
        String email = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !looksValid(email)) {
            throw ApiException.badRequest("INVALID_EMAIL", "Enter a valid email address");
        }
        return email;
    }

    /** Domains reserved for testing (RFC 2606 and RFC 6761): no real person's inbox is ever there. */
    private static final java.util.List<String> TEST_DOMAINS = java.util.List.of("example.com", "example.org",
            "example.net");

    /**
     * Whether an (already normalised) address is a test address: at example.com, example.org or example.net (or a
     * subdomain of them), or under the .test top-level domain. Only such addresses can be verified without a real code,
     * by a test account in OTP test mode (TestModeService), so a tester can never take a real person's email.
     */
    public static boolean isTestAddress(String normalizedEmail) {
        if (normalizedEmail == null) {
            return false;
        }
        int at = normalizedEmail.lastIndexOf('@');
        if (at < 0) {
            return false;
        }
        String domain = normalizedEmail.substring(at + 1);
        if (domain.endsWith(".test")) {
            return true;
        }
        for (String d : TEST_DOMAINS) {
            if (domain.equals(d) || domain.endsWith("." + d)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksValid(String email) {
        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@')) {
            return false;
        }
        if (!LOCAL.matcher(email.substring(0, at)).matches()) {
            return false;
        }
        String[] labels = email.substring(at + 1).split("\\.", -1);
        if (labels.length < 2) {
            return false;
        }
        for (String label : labels) {
            if (!LABEL.matcher(label).matches() || label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
        }
        return TOP_LEVEL.matcher(labels[labels.length - 1]).matches();
    }
}
