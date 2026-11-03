package in.apnidukan.notifications.service;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Which message types also go out as SMS: a type does once its MSG91 template id is set, e.g.
 * SMS_TEMPLATE_BOOKING_CONFIRMED (read as app.sms.template.booking-confirmed). Nothing else changes: set the
 * variable on the server, restart, and messages queued from then on are also sent as SMS. Empty = no SMS.
 */
@Component
public class SmsTemplates {

    private final Environment environment;

    public SmsTemplates(Environment environment) {
        this.environment = environment;
    }

    /** BOOKING_CONFIRMED → the value of app.sms.template.booking-confirmed, if set. */
    public Optional<String> forType(String type) {
        String id = environment.getProperty("app.sms.template." + type.toLowerCase(Locale.ROOT).replace('_', '-'));
        return id == null || id.isBlank() ? Optional.empty() : Optional.of(id.strip());
    }
}
