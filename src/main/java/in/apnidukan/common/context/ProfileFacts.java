package in.apnidukan.common.context;

import java.util.List;
import java.util.UUID;

/**
 * What a module knows about a person, shown to support on a ticket so they can act without searching (the
 * helpdesk module asks every implementation). Each module answers only from its own tables: auth the account,
 * shops the businesses they are in, salon their latest bookings.
 */
public interface ProfileFacts {

    /** This module's section about the person; null when it has nothing to say (or the person is unknown). */
    Section about(UUID userId);

    /**
     * @param order where the section goes (lowest first: 10 account, 20 businesses, 30 bookings)
     * @param title e.g. "Businesses"
     * @param lines one fact each, e.g. "Owner of Rahul Salon (code 1042), approved"; at most 20
     */
    record Section(int order, String title, List<String> lines) {
        public Section {
            lines = List.copyOf(lines);
        }
    }
}
