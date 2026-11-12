package in.apnidukan.helpdesk.service;

import in.apnidukan.common.error.ApiException;
import org.springframework.http.HttpStatus;

import java.text.Normalizer;
import java.util.Map;

/**
 * Cleans text a person typed for a ticket (a question, a reply): look-alike letters made normal, invisible
 * characters dropped, line breaks kept (made \n, at most two empty lines in a row), spaces at the ends trimmed.
 * Refuses (400 VALIDATION_FAILED naming the field) text shorter or longer than allowed.
 */
final class MessageText {

    private MessageText() {
    }

    /** Several lines allowed (a message). */
    static String clean(String field, String value, int min, int max) {
        String t = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder(t.length());
        t.codePoints().filter(c -> c == '\n' || c == '\t' || !hidden(c)).forEach(out::appendCodePoint);
        String cleaned = out.toString().replaceAll("[ \\t\\u00A0]+\n", "\n").replaceAll("\n{4,}", "\n\n\n").strip();
        if (cleaned.length() < min || cleaned.length() > max) {
            throw invalid(field, "write " + min + " to " + max + " characters");
        }
        return cleaned;
    }

    /** One line (a subject): every run of spaces or line breaks becomes one space. */
    static String line(String field, String value, int min, int max) {
        String t = clean(field, value, 0, Integer.MAX_VALUE).replaceAll("\\s+", " ");
        if (t.length() < min || t.length() > max) {
            throw invalid(field, "write " + min + " to " + max + " characters");
        }
        return t;
    }

    /** The first words of a message, for a ticket from the app sent without a subject. */
    static String subjectFrom(String message, int max) {
        String first = message.replaceAll("\\s+", " ").strip();
        if (first.length() <= max) {
            return first;
        }
        int cut = first.lastIndexOf(' ', max - 1);
        return first.substring(0, cut > max / 2 ? cut : max - 1) + "…";
    }

    private static boolean hidden(int c) {
        int type = Character.getType(c);
        return type == Character.CONTROL || type == Character.FORMAT || type == Character.PRIVATE_USE
                || type == Character.SURROGATE || type == Character.UNASSIGNED
                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
    }

    private static ApiException invalid(String field, String problem) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                Map.of(field, problem));
    }
}
