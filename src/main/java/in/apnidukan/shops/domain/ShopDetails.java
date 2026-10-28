package in.apnidukan.shops.domain;

import java.math.BigDecimal;

/** The editable part of a shop, already checked and cleaned (see ShopService). gstin and udyamNumber may be null. */
public record ShopDetails(String name, String ownerName, String addressLine, String area, String city, String pincode,
                          BigDecimal latitude, BigDecimal longitude, String gstin, String udyamNumber) {
}
