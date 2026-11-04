package in.apnidukan.common.text;

import in.apnidukan.common.error.ApiException;
import org.springframework.http.HttpStatus;

import java.text.Normalizer;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Cleans a name or address typed by a person before it is saved, or refuses it with a 400 that names the field.
 * Used by every module that stores such text (shop names, salon services, barbers), so the rules are the same
 * everywhere. Example: "  Sharma   Store " becomes "Sharma Store".
 */
public final class TextCleaner {

    /** Any kind of space, in any script (Java's \\s alone only knows ASCII spaces). */
    private static final Pattern SPACES = Pattern.compile("[\\s\\p{Z}]+");

    /**
     * A text field's name (for error messages) and its allowed length after cleaning.
     * maxLength must be the column size in the database.
     */
    public record TextField(String name, int minLength, int maxLength) {
    }

    private TextCleaner() {
    }

    public static String clean(TextField field, String value) {
        String normal = toNormalLetters(value);
        refuseHiddenCharacters(field.name(), normal);   // must run before collapseSpaces (see refuseHiddenCharacters)
        String cleaned = collapseSpaces(normal);
        requireTwoLettersOrDigits(field.name(), cleaned);
        requireLength(field, cleaned);
        return cleaned;
    }

    /** Look-alike letters become normal ones: full-width "Sharma" from some keyboards becomes plain "Sharma". */
    private static String toNormalLetters(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC);
    }

    /**
     * Refuses invisible characters, such as the right-to-left override that can make a name display differently
     * from what was typed. Tab and newline are allowed: they come from pasted addresses and collapseSpaces tidies them.
     * Runs before collapseSpaces, because that would turn line separators into spaces and hide them from this check.
     */
    private static void refuseHiddenCharacters(String field, String value) {
        boolean found = value.codePoints().anyMatch(TextCleaner::isHidden);
        if (found) {
            throw invalid(field, "contains invisible or special characters");
        }
    }

    private static boolean isHidden(int c) {
        if (c == '\t' || c == '\n' || c == '\r') {
            return false;
        }
        int type = Character.getType(c);
        return type == Character.CONTROL                 // control codes, e.g. the null character
                || type == Character.FORMAT              // zero-width space, right-to-left override
                || type == Character.PRIVATE_USE         // characters with no agreed meaning
                || type == Character.UNASSIGNED          // not a real character
                || type == Character.SURROGATE           // half of a broken emoji
                || type == Character.LINE_SEPARATOR      // Unicode line separator
                || type == Character.PARAGRAPH_SEPARATOR; // Unicode paragraph separator
    }

    /** Any run of spaces, tabs or newlines becomes one space, and the ends are trimmed. */
    private static String collapseSpaces(String value) {
        return SPACES.matcher(value).replaceAll(" ").strip();
    }

    /**
     * Checks the length of the cleaned value, which is what gets saved. @Size in the request only sees what was sent:
     * collapsing spaces can make it shorter, and toNormalLetters can make it longer (one character can become 3 or
     * more), which would otherwise reach the database too long and fail with a 500. Counted like Postgres counts.
     */
    private static void requireLength(TextField field, String value) {
        int length = value.codePointCount(0, value.length());
        if (length < field.minLength() || length > field.maxLength()) {
            throw invalid(field.name(), "must be " + field.minLength() + " to " + field.maxLength() + " characters");
        }
    }

    /** Refuses values like "!!" or "--". Letters in any script count, so Hindi names pass. */
    private static void requireTwoLettersOrDigits(String field, String value) {
        long count = value.codePoints().filter(Character::isLetterOrDigit).count();
        if (count < 2) {
            throw invalid(field, "must have at least 2 letters or digits");
        }
    }

    private static ApiException invalid(String field, String problem) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                Map.of(field, problem));
    }
}
