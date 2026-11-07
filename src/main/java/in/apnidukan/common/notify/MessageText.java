package in.apnidukan.common.notify;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * How a message's text is fitted to what is stored and sent: the notifications module uses it for every message, and
 * the salon module for the reminders it rewrites in one SQL statement when a salon moves (so both write exactly the
 * same text).
 */
public final class MessageText {

    /** Longest title and body stored (the columns' sizes). */
    public static final int MAX_TITLE = 100;
    public static final int MAX_BODY = 500;
    /** DLT's limit for one SMS template variable. */
    public static final int MAX_SMS_VARIABLE = 30;

    private MessageText() {
    }

    /** Shortens to max characters, never splitting an emoji (a surrogate pair) in two; ends with "…" when cut. */
    public static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = max - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }

    /** Like cut, without the "…" (an SMS variable: "…" isn't a GSM character and would make the SMS Unicode). */
    public static String cutPlain(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }

    /** SMS variables as a query string sorted by name (SALON=Sharma+Hair&TIME=6%3A30+PM), kept in one text column. */
    public static String smsQuery(Map<String, String> variables) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(variables).forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }
}
