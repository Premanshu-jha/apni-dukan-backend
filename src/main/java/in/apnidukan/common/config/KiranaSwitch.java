package in.apnidukan.common.config;

import in.apnidukan.common.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The one on/off switch for everything kirana (app.kirana.enabled, env KIRANA_ENABLED; off unless set to true). Until
 * kirana launches the server runs with it off: the kirana API answers 404 KIRANA_OFF (KiranaSwitchFilter), no shop
 * can become a kirana, the kirana jobs do nothing and nothing is sent to the AI, GS1 DataKart or RazorpayX for a
 * kirana. On: exactly as before. Salons work the same either way. Every kirana bean still exists either way (others
 * inject them); they ask this switch.
 */
@Component
public class KiranaSwitch {

    /** The error code of everything refused because the switch is off. */
    public static final String CODE = "KIRANA_OFF";
    public static final String MESSAGE = "Kirana shops aren't on Clozest yet.";

    private final boolean on;

    public KiranaSwitch(@Value("${app.kirana.enabled:false}") boolean on) {
        this.on = on;
    }

    /** Whether kirana is switched on. */
    public boolean on() {
        return on;
    }

    /** Whether kirana is switched off (the server's default until kirana launches). */
    public boolean off() {
        return !on;
    }

    /** Nothing when on; when off, {@code status} with code KIRANA_OFF. */
    public void requireOn(HttpStatus status) {
        if (!on) {
            throw refused(status);
        }
    }

    /** The error for something kirana-only asked while the switch is off. */
    public static ApiException refused(HttpStatus status) {
        return new ApiException(status, CODE, MESSAGE);
    }
}
