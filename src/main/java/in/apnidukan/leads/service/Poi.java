package in.apnidukan.leads.service;

import java.util.List;

/**
 * One business as Mapbox gives it (a search result: used to answer the scan, never stored).
 *
 * @param mapboxId   Mapbox's id for the place
 * @param name       its name
 * @param address    the full address
 * @param latitude   where it is
 * @param longitude  where it is
 * @param postcode   the pincode Mapbox puts it in, or null
 * @param categories Mapbox's category names and ids (e.g. "salon", "beauty_store", "barber")
 * @param phone      the phone as Mapbox has it (e.g. "+919133576278"), or null
 * @param website    an https address, or null
 * @param hours      when it is open, or null
 */
public record Poi(String mapboxId, String name, String address, double latitude, double longitude, String postcode,
                  List<String> categories, String phone, String website, Hours hours) {

    public Poi {
        categories = categories == null ? List.of() : List.copyOf(categories);
    }

    /**
     * Opening hours: on how many days of the week it opens, the earliest opening and latest closing ("1000", "2100"),
     * and its average hours a day.
     */
    public record Hours(int days, String opens, String closes, double averageHours, boolean late) {
    }

    /** The same place with any detail this one lacks taken from another answer about it. */
    Poi fill(Poi other) {
        return new Poi(mapboxId, name, address == null || address.isBlank() ? other.address : address, latitude,
                longitude, postcode != null ? postcode : other.postcode,
                categories.size() >= other.categories.size() ? categories : other.categories,
                phone != null ? phone : other.phone, website != null ? website : other.website,
                hours != null ? hours : other.hours);
    }
}
