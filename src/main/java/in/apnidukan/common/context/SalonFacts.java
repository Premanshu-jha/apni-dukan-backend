package in.apnidukan.common.context;

import java.util.UUID;

/** A salon's size, for the field team's shop page. Implemented by the salon module. */
public interface SalonFacts {

    /** Active barbers and services of this salon (zeros for a shop that has none). */
    Size size(UUID shopId);

    record Size(int barbers, int services) {
    }
}
