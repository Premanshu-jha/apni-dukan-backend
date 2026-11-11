package in.apnidukan.places.api;

import in.apnidukan.common.context.AddressSearch;
import in.apnidukan.common.context.AddressSearch.Found;
import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.places.api.PlaceRequests.GpsPoint;
import in.apnidukan.places.api.PlaceRequests.FindAddress;
import in.apnidukan.places.api.PlaceRequests.SavePlace;
import in.apnidukan.places.service.PlaceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The customer's saved places (Home, Work, others) and the address search that sets them: the same two ways as a
 * salon's address at registration, typed then found on the map, or filled from the phone's GPS point.
 */
@RestController
@RequestMapping("/api/v1/me/places")
public class PlacesController {

    private final PlaceService places;
    private final AddressSearch addresses;
    private final CurrentUserProvider currentUser;

    public PlacesController(PlaceService places, AddressSearch addresses, CurrentUserProvider currentUser) {
        this.places = places;
        this.addresses = addresses;
        this.currentUser = currentUser;
    }

    /** My places: Home, Work, then the others as saved. */
    @GetMapping
    public List<PlaceView> list() {
        return places.list(currentUser.get().userId());
    }

    /**
     * Save a place. 201. 409 TOO_MANY_PLACES (10 already), 409 PLACE_KIND_TAKEN (a Home or Work already:
     * details.placeId), 400 VALIDATION_FAILED (an Other needs a name; the pin must be in India).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlaceView add(@Valid @RequestBody SavePlace body) {
        return places.add(currentUser.get().userId(), body);
    }

    /** Change a saved place (send the whole place). 404 PLACE_NOT_FOUND, 409 PLACE_KIND_TAKEN, 400. */
    @PutMapping("/{id}")
    public PlaceView update(@PathVariable UUID id, @Valid @RequestBody SavePlace body) {
        return places.update(currentUser.get().userId(), id, body);
    }

    /** Delete a saved place. 204, also when it was gone already. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        places.delete(currentUser.get().userId(), id);
    }

    /**
     * "Find on map": where the typed address is, for the pin the customer then drags. 200 {latitude, longitude,
     * precision: EXACT|STREET|AREA, formattedAddress}. 404 ADDRESS_NOT_FOUND, 429 TRY_LATER, 503 GEOCODING_OFF (the
     * app hides the button), 503 GEOCODING_UNAVAILABLE. The same as an owner's search (POST /shops/geocode), with
     * customers' own share of the day's lookups.
     */
    @PostMapping("/geocode")
    public Found geocode(@Valid @RequestBody FindAddress body) {
        return addresses.find(currentUser.get().userId(), body.addressLine(), body.area(), body.city(),
                body.pincode());
    }

    /**
     * "Use current location": the address at the phone's GPS point, to fill the empty fields. 200 {addressLine,
     * area, city, pincode, formattedAddress}; the same errors as /geocode, and 400 outside India.
     */
    @PostMapping("/reverse-geocode")
    public AddressSearch.AddressAt reverseGeocode(@Valid @RequestBody GpsPoint body) {
        return addresses.describe(currentUser.get().userId(), body.latitude(), body.longitude());
    }
}
