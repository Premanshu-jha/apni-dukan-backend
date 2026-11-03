package in.apnidukan.shops.service;

import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.shops.domain.Shop;
import in.apnidukan.shops.domain.ShopDetails;
import in.apnidukan.shops.domain.ShopStatus;
import in.apnidukan.shops.domain.ShopType;
import in.apnidukan.shops.repo.ShopRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Public shop facts for customer pages (see {@link ShopDirectory}). */
@Service
public class ShopDirectoryService implements ShopDirectory {

    /** Mean radius of the Earth in km. */
    private static final double EARTH_KM = 6371.0;
    /** About 111 km per degree of latitude everywhere. */
    private static final double KM_PER_DEGREE = 111.0;

    private final ShopRepository shops;

    public ShopDirectoryService(ShopRepository shops) {
        this.shops = shops;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShopCard> find(UUID shopId) {
        return shops.findById(shopId).map(s -> card(s, null));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ShopCard> findForBooking(UUID shopId) {
        return shops.findForShare(shopId).map(s -> card(s, null));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShopCard> nearby(String type, BigDecimal latitude, BigDecimal longitude, double radiusKm, int limit) {
        double lat = latitude.doubleValue();
        double lng = longitude.doubleValue();
        double latSpan = radiusKm / KM_PER_DEGREE;
        // A degree of longitude gets shorter away from the equator (India is 6° to 37° N)
        double lngSpan = radiusKm / (KM_PER_DEGREE * Math.cos(Math.toRadians(lat)));
        return shops.findInBox(ShopType.valueOf(type), ShopStatus.APPROVED,
                        degrees(lat - latSpan), degrees(lat + latSpan), degrees(lng - lngSpan), degrees(lng + lngSpan))
                .stream()
                .map(s -> card(s, distanceKm(lat, lng, s.details().latitude().doubleValue(),
                        s.details().longitude().doubleValue())))
                .filter(c -> c.distanceKm() <= radiusKm)
                .sorted(Comparator.comparingDouble(ShopCard::distanceKm).thenComparing(ShopCard::id))
                .limit(limit)
                .toList();
    }

    /** Straight-line distance over the Earth's surface (haversine formula), rounded to 0.1 km. */
    static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double km = 2 * EARTH_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
        return Math.round(km * 10) / 10.0;
    }

    private static BigDecimal degrees(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private static ShopCard card(Shop s, Double distanceKm) {
        ShopDetails d = s.details();
        return new ShopCard(s.getId(), d.name(), d.addressLine(), d.area(), d.city(), d.pincode(), d.latitude(),
                d.longitude(), s.getStatus().name(), s.getType().name(), s.getShopPhotoFileId(), distanceKm);
    }
}
