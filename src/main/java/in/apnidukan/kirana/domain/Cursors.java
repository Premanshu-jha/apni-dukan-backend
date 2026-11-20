package in.apnidukan.kirana.domain;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * The "next page" token of the kirana lists: where the last page ended (its sort key and id), opaque to the app.
 * A token that doesn't decode is treated as no token (the first page), never as an error.
 */
public final class Cursors {

    private static final char SEP = '\u001f';

    private Cursors() {
    }

    public static String encode(String key, String id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((key + SEP + id).getBytes(StandardCharsets.UTF_8));
    }

    /** The key and id, or empty for a missing or broken token. */
    public static Optional<String[]> decode(String token) {
        if (token == null || token.isBlank() || token.length() > 400) {
            return Optional.empty();
        }
        try {
            String s = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int at = s.lastIndexOf(SEP);
            return at < 0 ? Optional.empty() : Optional.of(new String[] {s.substring(0, at), s.substring(at + 1)});
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
