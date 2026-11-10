package in.apnidukan.common.context;

import java.util.List;
import java.util.UUID;

/** A salon's size and setup, for registration and the field team's shop page. Implemented by the salon module. */
public interface SalonFacts {

    /** Active barbers and services of this salon (zeros for a shop that has none). */
    Size size(UUID shopId);

    /** Whether the salon has set its opening hours (at least one open day). */
    boolean hasOpeningHours(UUID shopId);

    /** What the field team checks on a visit: the salon's services, its week and its ratings so far. */
    Profile profile(UUID shopId);

    record Size(int barbers, int services) {
    }

    /**
     * A salon's setup as the field team checks it.
     * @param services       active services in the salon's order
     * @param hours          open days, Monday first; empty until set
     * @param ratings        how many customers rated it
     * @param ratingAverage  average stars to one decimal; null with no ratings
     */
    record Profile(List<Service> services, List<Day> hours, int ratings, Double ratingAverage) {

        public Profile {
            services = List.copyOf(services);
            hours = List.copyOf(hours);
        }
    }

    /** One service: minutes, price in paise ("from" when it can vary), come-back reminder after days (0: none). */
    record Service(String name, int durationMinutes, long pricePaise, boolean priceIsFrom, int returnAfterDays) {
    }

    /** An open day: weekday 1 = Monday ... 7 = Sunday, "HH:mm" times in salon time. */
    record Day(int weekday, String open, String close) {
    }
}
