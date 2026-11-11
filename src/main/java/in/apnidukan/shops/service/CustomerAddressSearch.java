package in.apnidukan.shops.service;

import in.apnidukan.common.context.AddressSearch;
import in.apnidukan.shops.service.AddressLocator.Asker;
import in.apnidukan.shops.service.AddressLocator.Described;
import in.apnidukan.shops.service.AddressLocator.Located;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/** The customer's side of {@link AddressLocator}, for the places module (see AddressSearch). */
@Component
class CustomerAddressSearch implements AddressSearch {

    private final AddressLocator locator;

    CustomerAddressSearch(AddressLocator locator) {
        this.locator = locator;
    }

    @Override
    public Found find(UUID customerId, String addressLine, String area, String city, String pincode) {
        Located l = locator.locate(Asker.CUSTOMER, customerId, addressLine, area, city, pincode);
        return new Found(l.latitude(), l.longitude(), l.precision().name(), l.formattedAddress());
    }

    @Override
    public AddressAt describe(UUID customerId, BigDecimal latitude, BigDecimal longitude) {
        Described d = locator.describe(Asker.CUSTOMER, customerId, latitude, longitude);
        return new AddressAt(d.addressLine(), d.area(), d.city(), d.pincode(), d.formattedAddress());
    }
}
