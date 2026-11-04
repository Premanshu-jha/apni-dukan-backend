package in.apnidukan.salon.domain;

import in.apnidukan.common.error.ApiException;
import org.springframework.http.HttpStatus;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Times of day in salon time. Every salon is in India, which has one time zone and no daylight saving, so a time
 * like "10:00" means the same moment for every salon on a given date.
 *
 * Weekly hours are stored as minutes after midnight: "10:00" is 600, "21:30" is 1290, and the end of the day,
 * written "24:00", is 1440. Times go in 15-minute steps, the same steps booking slots will use.
 */
public final class SalonTime {

    /** The salons' time zone: turns a date and "10:00" into an exact moment. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    public static final int STEP_MINUTES = 15;
    public static final int END_OF_DAY = 24 * 60;

    /** Exactly two digits, a colon, two digits: "09:30" passes, "9:30" and "09:30:00" don't. */
    private static final Pattern HH_MM = Pattern.compile("([0-9]{2}):([0-9]{2})");

    private SalonTime() {
    }

    /**
     * "10:15" → 615. Refuses (400, naming the field) anything that isn't "HH:mm" on a 15-minute step between
     * "00:00" and "24:00".
     */
    public static int minutes(String field, String hhmm) {
        Matcher m = hhmm == null ? null : HH_MM.matcher(hhmm);
        if (m == null || !m.matches()) {
            throw invalid(field, "must be a time like 09:30");
        }
        int hours = Integer.parseInt(m.group(1));
        int minutes = Integer.parseInt(m.group(2));
        int total = hours * 60 + minutes;
        if (minutes > 59 || total > END_OF_DAY) {
            throw invalid(field, "must be between 00:00 and 24:00");
        }
        if (total % STEP_MINUTES != 0) {
            throw invalid(field, "must be on a 15-minute step (:00, :15, :30 or :45)");
        }
        return total;
    }

    /** 615 → "10:15", 1440 → "24:00". */
    public static String format(int minutes) {
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private static ApiException invalid(String field, String problem) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                Map.of(field, problem));
    }
}
