package in.apnidukan.common.notify;

import in.apnidukan.common.notify.Notifications.AndList;
import in.apnidukan.common.notify.Notifications.Day;
import in.apnidukan.common.notify.Notifications.DayTime;
import in.apnidukan.common.notify.Notifications.Text;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * The words of the messages people get (push and the app's list), one file per language:
 * src/main/resources/notices/&lt;code&gt;.properties (UTF-8), "KEY.title" and "KEY.body" for a message, other keys
 * for the pieces put into them. {blanks} take the notice's values: data (names, numbers, codes, amounts, times) goes
 * in as it is; dates get the language's day and month names, digits 0-9; times stay "6:30 PM" in every language.
 *
 * en.properties says exactly what the code writes in English (the tests check every message they queue against it),
 * and is the source the other languages are translated from. A text missing in a language: that message goes in
 * English (see NotificationService).
 */
public final class NoticeTexts {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter EN_DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a",
            Locale.ENGLISH).withZone(INDIA);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
            .withZone(INDIA);

    private static final Map<Language, Map<String, String>> TEXTS = load();
    /** Day patterns' formatters by language and pattern (the code uses a few fixed patterns: made once each). */
    private static final Map<Language, Map<String, DateTimeFormatter>> DAY_PATTERNS = new EnumMap<>(Language.class);
    /** "Fri 7 Nov" in each language. */
    private static final Map<Language, DateTimeFormatter> DAY_NAMES = new EnumMap<>(Language.class);

    static {
        for (Language l : Language.values()) {
            DAY_NAMES.put(l, DateTimeFormatter.ofPattern("EEE d MMM", l.locale()).withZone(INDIA));
            DAY_PATTERNS.put(l, new java.util.concurrent.ConcurrentHashMap<>());
        }
    }

    private NoticeTexts() {
    }

    /** A message's title in this language; empty when the language has no such text (or a piece of it). */
    public static Optional<String> title(Language language, Text text) {
        return render(language, text.key() + ".title", text.values());
    }

    /** A message's body in this language; empty as for title. */
    public static Optional<String> body(Language language, Text text) {
        return render(language, text.key() + ".body", text.values());
    }

    /** The text {@code key} with its blanks filled in; empty when the language lacks it, a piece, or a value. */
    public static Optional<String> render(Language language, String key, Map<String, ?> values) {
        String template = TEXTS.get(language).get(key);
        return template == null ? Optional.empty() : fill(language, template, values);
    }

    /** A text's words (with {blanks}) filled in; empty when a blank has no value. */
    public static Optional<String> fill(Language language, String template, Map<String, ?> values) {
        StringBuilder out = new StringBuilder(template.length() + 64);
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf('{', i);
            int close = open < 0 ? -1 : template.indexOf('}', open);
            if (close < 0) {
                out.append(template, i, template.length());
                break;
            }
            out.append(template, i, open);
            String name = template.substring(open + 1, close);
            if (!values.containsKey(name)) {
                return Optional.empty();   // a translation naming a blank the code doesn't fill: English instead
            }
            Optional<String> value = value(language, values.get(name));
            if (value.isEmpty()) {
                return Optional.empty();
            }
            out.append(value.get());
            i = close + 1;
        }
        return Optional.of(out.toString());
    }

    /** The raw text (with its {blanks}), if the language has it. */
    public static Optional<String> template(Language language, String key) {
        return Optional.ofNullable(TEXTS.get(language).get(key));
    }

    /** Every key the language has (for the tests that compare the languages with English). */
    public static java.util.Set<String> keys(Language language) {
        return java.util.Collections.unmodifiableSet(TEXTS.get(language).keySet());
    }

    private static Optional<String> value(Language language, Object v) {
        if (v == null) {
            return Optional.of("");
        }
        if (v instanceof Text t) {
            return render(language, t.key(), t.values());
        }
        if (v instanceof DayTime d) {
            return Optional.of(language == Language.EN ? EN_DAY_TIME.format(d.at())
                    : DAY_NAMES.get(language).format(d.at()) + ", " + TIME.format(d.at()));
        }
        if (v instanceof Day d) {
            return Optional.of(dayFormat(language, d.pattern()).format(d.date()));
        }
        if (v instanceof AndList l) {
            return andList(language, l.items());
        }
        if (v instanceof List<?> pieces) {   // each in the language, one after the other with ", "
            StringBuilder out = new StringBuilder();
            for (Object piece : pieces) {
                Optional<String> one = value(language, piece);
                if (one.isEmpty()) {
                    return Optional.empty();
                }
                out.append(out.isEmpty() ? "" : ", ").append(one.get());
            }
            return Optional.of(out.toString());
        }
        return Optional.of(v.toString());
    }

    /** The formatter of this pattern in this language, made the first time (DateTimeFormatter is thread-safe). */
    static DateTimeFormatter dayFormat(Language language, String pattern) {
        return DAY_PATTERNS.get(language).computeIfAbsent(pattern, p -> DateTimeFormatter.ofPattern(p,
                language.locale()));
    }

    /** "A", "A and B", "A, B and C": the last joined with the language's "and" (list.and). */
    private static Optional<String> andList(Language language, List<String> items) {
        if (items.isEmpty()) {
            return Optional.of("");
        }
        if (items.size() == 1) {
            return Optional.of(items.get(0));
        }
        return render(language, "list.and", Map.of("items", String.join(", ", items.subList(0, items.size() - 1)),
                "last", items.get(items.size() - 1)));
    }

    private static Map<Language, Map<String, String>> load() {
        Map<Language, Map<String, String>> out = new EnumMap<>(Language.class);
        for (Language language : Language.values()) {
            Map<String, String> texts = new HashMap<>();
            try (InputStream in = NoticeTexts.class.getResourceAsStream("/notices/" + language.code()
                    + ".properties")) {
                if (in != null) {
                    Properties p = new Properties();
                    p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                    p.stringPropertyNames().forEach(k -> texts.put(k, p.getProperty(k)));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            out.put(language, Map.copyOf(texts));
        }
        return out;
    }
}
