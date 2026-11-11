package in.apnidukan.common.context;

import java.util.UUID;

/**
 * Told by the auth module, inside its transaction and under the person's row lock, when a signed-in person moves their
 * account to a new phone number. Whatever waits for the old number (an invitation to join a shop, a request to take
 * one over) is ended, so it never reaches whoever gets the old number next.
 */
public interface PhoneChangeListener {
    void phoneChanged(UUID userId, String oldPhone, String newPhone);
}
