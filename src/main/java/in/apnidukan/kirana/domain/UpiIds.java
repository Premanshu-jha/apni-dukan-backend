package in.apnidukan.kirana.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * UPI IDs (VPAs) as people type them: "sharmastore@okhdfcbank", "9848012345@ybl". NPCI's form is a handle (letters,
 * digits, dot, dash, underscore) then "@" and the bank's or app's name (letters). Stored lower case and without
 * spaces, as UPI apps treat them.
 */
public final class UpiIds {

    private static final Pattern VPA = Pattern.compile("^[a-z0-9._-]{2,64}@[a-z][a-z0-9]{1,31}$");

    private UpiIds() {
    }

    /** The UPI ID as stored: spaces removed, lower case. */
    public static String clean(String typed) {
        return typed == null ? "" : typed.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** Whether a cleaned UPI ID has the right form (the bank checks that it exists). */
    public static boolean valid(String cleaned) {
        return VPA.matcher(cleaned).matches();
    }
}
