package in.apnidukan.common.context;

import java.util.UUID;

/**
 * Told when a person chose another language (users.language, already saved in this transaction): modules whose
 * messages to them are still waiting to go out write those again in the new language, so a message queued before
 * the change doesn't arrive in the old one. Runs inside the change's transaction, after the person's row is locked
 * and saved: if it rolls back, the messages keep their words.
 */
public interface LanguageChangeListener {

    void languageChanged(UUID userId);
}
