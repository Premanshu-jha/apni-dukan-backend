package in.apnidukan.auth.service;

import in.apnidukan.auth.repo.UserRepository;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.security.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The spending guard on code SMS (sign-in, account deletion, email change, recovery), counted in Redis for all copies
 * of the backend together. It grows with the business instead of stopping sign-in for everyone at one fixed number:
 * <ul>
 *   <li>a daily budget, SMS_DAILY_LIMIT (default 50,000): raise it as sign-ins grow;</li>
 *   <li>an hourly burst cap, SMS_HOURLY_LIMIT (default 10,000): someone spraying codes can't use up the day's budget
 *       in an hour;</li>
 *   <li>near the cap, people who signed in within the last 30 days come first. From 80% of the daily budget, or once
 *       the hour's cap is reached, only they still get codes (and signed-in people asking for a code to delete their
 *       account or change their email); a number with no recent sign-in gets 503 SMS_PAUSED. The people already using
 *       the app keep signing in while a flood of new or made-up numbers is held off. Everyone stops only when the
 *       whole daily budget is used.</li>
 * </ul>
 * Never silently: at 80% of the daily budget a WARN "80% of the daily SMS limit used" (once a day), and each time codes
 * are refused an ERROR, once an hour for new numbers ("SMS for new numbers paused") and once a day for everyone ("Daily
 * SMS limit ... reached"). Point alerts at all three.
 *
 * Only SMS that go out are counted: a refused one never uses up the budget, so a flood of refused numbers can't stop
 * the people who come first. The counts are spending guards, not exact: two servers at the same moment can pass a
 * limit by a few. If Redis is down, codes can't be issued anyway (OtpService needs it).
 *
 * The trade-off: near the cap, whether a code is sent tells a caller whether that number signed in within 30 days.
 * Only then, and the per-network and per-phone limits still apply; recovery codes (the new phone usually has no
 * account) never look it up, so recovery never reveals it.
 */
@Component
public class SmsBudget {

    private static final Logger log = LoggerFactory.getLogger(SmsBudget.class);
    static final String DAY = "sms-day";
    static final String HOUR = "sms-hour";

    /**
     * Who a code SMS is for.
     * @param phone    the number it goes to
     * @param signedIn true when the person asking is signed in to that number's account (deleting the account,
     *                 changing the email): they count as known without a lookup
     * @param lookUp   false: never look up whether the number signed in recently (recovery), it counts as new
     */
    public record To(String phone, boolean signedIn, boolean lookUp) {

        public static To signIn(String phone) {
            return new To(phone, false, true);
        }

        public static To signedIn(String phone) {
            return new To(phone, true, false);
        }

        public static To recovery(String phone) {
            return new To(phone, false, false);
        }
    }

    private final RateLimiter limits;
    private final UserRepository users;
    private final int daily;
    private final int hourly;

    public SmsBudget(RateLimiter limits, UserRepository users, SignInLimits signInLimits,
                     @Value("${app.sms.hourly-limit:10000}") int hourly) {
        this.limits = limits;
        this.users = users;
        this.daily = signInLimits.smsDailyLimit();
        this.hourly = hourly;
    }

    /**
     * 503 SMS_PAUSED if a code to this number would be refused now; counts nothing. Called before a new code is made,
     * so a refused send never breaks the code the person already has.
     */
    public void requireRoom(To to) {
        decide(to, limits.current(DAY), limits.current(HOUR));
    }

    /**
     * Counts this SMS (call just before sending it). It never refuses: requireRoom decided before the code was made,
     * and the new code has already replaced the old one, so refusing now would leave the person with no working code
     * at all. Two servers at the same moment can pass the cap by a few (see above).
     */
    public void spend(To to) {
        long today = limits.hit(DAY, Duration.ofHours(24));
        limits.hit(HOUR, Duration.ofHours(1));
        if (today == SignInLimits.warnAt(daily)) {
            // Once a day (the counter passes each number once per 24 hours): time to look before new numbers stop
            log.warn("80% of the daily SMS limit used ({} of {}). Check for abuse now; from here only people who "
                    + "signed in within 30 days get codes, at {} everyone stops.", today, daily, daily);
        }
    }

    private void decide(To to, long today, long thisHour) {
        if (today >= daily) {
            if (limits.tryHit("sms-day-alert", 1, Duration.ofHours(24))) {
                log.error("Daily SMS limit ({}) reached: SMS paused for everyone. Check for abuse before raising "
                        + "SMS_DAILY_LIMIT.", daily);
            }
            throw paused(DAY);
        }
        boolean nearCap = today >= SignInLimits.warnAt(daily) || thisHour >= hourly;
        if (nearCap && !known(to)) {
            if (limits.tryHit("sms-new-alert", 1, Duration.ofHours(1))) {
                log.error("SMS for new numbers paused: {} of the daily {} and {} of the hourly {} used. Only people "
                        + "who signed in within 30 days get codes now; raise SMS_DAILY_LIMIT / SMS_HOURLY_LIMIT if "
                        + "this is real traffic.", today, daily, thisHour, hourly);
            }
            throw paused(thisHour >= hourly ? HOUR : DAY);
        }
    }

    private boolean known(To to) {
        return to.signedIn() || (to.lookUp() && users.signedInRecently(to.phone()));
    }

    private ApiException paused(String key) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SMS_PAUSED",
                "We can't send codes right now. Please try again later.").retryAfter(limits.secondsLeft(key));
    }
}
