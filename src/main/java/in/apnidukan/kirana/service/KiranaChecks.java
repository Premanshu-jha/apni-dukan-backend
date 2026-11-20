package in.apnidukan.kirana.service;

import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.text.TextCleaner;
import in.apnidukan.common.text.TextCleaner.TextField;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Checks and small helpers the kirana services share. */
final class KiranaChecks {

    /** India time: the shop's day runs midnight to midnight here. */
    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    static final TextField PRODUCT_NAME = new TextField("name", 2, 100);
    static final TextField CUSTOMER_NAME = new TextField("name", 2, 80);
    static final TextField CUSTOMER_NOTE = new TextField("note", 2, 100);
    static final TextField NOTE = new TextField("note", 2, 200);
    static final TextField VOID_REASON = new TextField("reason", 2, 200);

    /** As printed on packs: EAN-13, EAN-8, UPC-A, or a shop's own code (letters, digits, dash). */
    private static final Pattern BARCODE = Pattern.compile("[A-Z0-9-]{4,32}");

    /** How many rows one page of a list carries. */
    static final int PAGE = 50;

    private KiranaChecks() {
    }

    static ApiException invalid(String field, String problem) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                Map.of(field, problem));
    }

    /** A barcode as stored: spaces removed, upper case; null for none. 400 naming {@code barcode} if malformed. */
    static String barcode(String typed) {
        return barcode("barcode", typed);
    }

    /** As {@link #barcode(String)}, the 400 naming {@code field}. */
    static String barcode(String field, String typed) {
        if (typed == null) {
            return null;
        }
        String b = typed.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (b.isEmpty()) {
            return null;
        }
        if (!BARCODE.matcher(b).matches()) {
            throw invalid(field, "A barcode is 4 to 32 letters, digits or dashes, as printed");
        }
        return b;
    }

    /**
     * Whether a numeric barcode's last digit fits the others (EAN-8, UPC-A, EAN-13, GTIN-14: the standard check digit);
     * a misread scan usually fails it. Codes of other lengths, or with letters (the shop's own), always pass.
     */
    static boolean checkDigitOk(String code) {
        int n = code.length();
        if ((n != 8 && n != 12 && n != 13 && n != 14) || !code.chars().allMatch(Character::isDigit)) {
            return true;
        }
        int sum = 0;
        for (int i = n - 2, w = 3; i >= 0; i--, w = 4 - w) {
            sum += (code.charAt(i) - '0') * w;
        }
        return (10 - sum % 10) % 10 == code.charAt(n - 1) - '0';
    }

    /**
     * A label a shop's scale printed for loose goods: an EAN-13 starting 20 to 23. Its first 7 digits name the item
     * (linked to a product as that barcode); the next 5 are the weight in grams (20, 21) or the price in paise (22, 23).
     * @param byWeight the value is grams; else paise
     */
    record LooseLabel(String itemCode, boolean byWeight, int value) {
    }

    static java.util.Optional<LooseLabel> looseLabel(String code) {
        if (code.length() != 13 || !code.chars().allMatch(Character::isDigit) || code.charAt(0) != '2'
                || code.charAt(1) > '3' || !checkDigitOk(code)) {
            return java.util.Optional.empty();
        }
        int value = Integer.parseInt(code.substring(7, 12));
        if (value == 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new LooseLabel(code.substring(0, 7), code.charAt(1) <= '1', value));
    }

    /** Optional free text: null or blank → null, else cleaned (400 naming the field if not acceptable). */
    static String optional(TextField field, String value) {
        return value == null || value.isBlank() ? null : TextCleaner.clean(field, value);
    }

    /**
     * A search's text, lower case, with LIKE's wildcards escaped (the stores match it anywhere in a name); null when
     * nothing usable was typed (then the list isn't filtered).
     */
    static String prefix(String q) {
        if (q == null) {
            return null;
        }
        String t = q.strip().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return null;
        }
        if (t.length() > 100) {
            t = t.substring(0, 100);
        }
        return t.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** People's names for "by" columns, one query for the lot; missing accounts are left out. */
    static Map<UUID, String> names(UserDirectory users, Collection<UUID> ids) {
        Map<UUID, String> out = new HashMap<>();
        if (!ids.isEmpty()) {
            users.find(ids).forEach((id, p) -> out.put(id, p.name()));
        }
        return out;
    }

    /** Now, to the microsecond, as Postgres stores it (so the answer to a save matches a later read). */
    static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * A khata customer's phone as people type or paste it ("+91 98480-12345", "098480 12345"): spaces, dashes, dots
     * and brackets removed, then a leading +91 / 91 (12 digits) or 0 (11 digits). Null or blank → null; "" stays ""
     * (an edit removing it). 400 naming {@code phone} if what's left isn't a 10-digit Indian mobile number.
     */
    static String phone(String typed) {
        if (typed == null) {
            return null;
        }
        String d = typed.replaceAll("[\\s().-]", "");
        if (d.isEmpty()) {
            return "";
        }
        if (d.startsWith("+")) {
            d = d.substring(1);
        }
        if (d.length() == 12 && d.startsWith("91")) {
            d = d.substring(2);
        } else if (d.length() == 11 && d.startsWith("0")) {
            d = d.substring(1);
        }
        if (!d.matches("[6-9][0-9]{9}")) {
            throw invalid("phone", "Enter a 10-digit mobile number, like 98480 12345");
        }
        return d;
    }

    /** Digits typed in a search (4 or more), for matching phone numbers; null otherwise. */
    static String digits(String q) {
        if (q == null) {
            return null;
        }
        String d = q.replaceAll("[^0-9]", "");
        return d.length() >= 4 && q.strip().matches("[0-9 +()-]+") ? d : null;
    }

    /** A day as people read it: "31 Mar 2026". */
    static String day(LocalDate d) {
        return d.format(java.time.format.DateTimeFormatter.ofPattern("d MMM uuuu", java.util.Locale.ENGLISH));
    }

    static LocalDate today(Instant now) {
        return LocalDate.ofInstant(now, ZONE);
    }

    static Instant dayStart(LocalDate day) {
        return day.atStartOfDay(ZONE).toInstant();
    }
}
