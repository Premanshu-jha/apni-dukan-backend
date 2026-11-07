package in.apnidukan.common.context;

import java.util.UUID;

/**
 * Told when a person's name or phone is about to change (renamed, account moved to a new phone), inside that
 * transaction and before their users row is locked: modules that show the person to others (a salon's day board)
 * mark those screens changed now. Before, not after: a salon's own change takes its screen's counter first and the
 * person's row after (a message to them checks the row), so taking them in the same order here means the two can only
 * wait for each other, never deadlock. Account deletion does the same through AccountDeletionListener.
 */
public interface PersonDetailsListener {

    void detailsChanging(UUID userId);
}
