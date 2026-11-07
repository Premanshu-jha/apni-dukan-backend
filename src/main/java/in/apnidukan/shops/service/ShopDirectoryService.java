package in.apnidukan.shops.service;

import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.shops.domain.Shop;
import in.apnidukan.shops.domain.ShopDetails;
import in.apnidukan.shops.repo.ShopRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Public shop facts for customer pages (see {@link ShopDirectory}). */
@Service
public class ShopDirectoryService implements ShopDirectory {

    /**
     * Shops near a point, in the database (PostGIS, V12): ST_DWithin finds the shops within the radius through the
     * map index on shops.location (approved shops only), ST_Distance measures each in metres, and the database sorts
     * and returns one page. Distances are on a sphere (use_spheroid = false): within 0.3% of the exact earth, the
     * same function for the filter, the sort and the page cursor, and cheaper.
     */
    private static final String NEARBY_SQL = "WITH p AS (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography AS g) "
            + "SELECT s.id, s.name, s.address_line, s.area, s.city, s.pincode, s.latitude, s.longitude, s.status, "
            + "s.type, s.shop_photo_file_id, ST_Distance(s.location, p.g, false) AS metres "
            + "FROM shops s, p "
            + "WHERE s.status = 'APPROVED' AND s.type = ? AND ST_DWithin(s.location, p.g, ?, false)";

    /** Salons that paused online bookings (a salon-module table, read here so a page is never cut short by them). */
    private static final String NOT_PAUSED = " AND NOT EXISTS (SELECT 1 FROM salon_pauses sp "
            + "WHERE sp.shop_id = s.id AND sp.paused_until > ?)";

    private static final String AFTER = " AND (ST_Distance(s.location, p.g, false), s.id) > (?, ?)";

    private final ShopRepository shops;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ShopDirectoryService(ShopRepository shops, JdbcTemplate jdbc, Clock clock) {
        this.shops = shops;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShopCard> find(UUID shopId) {
        return shops.findById(shopId).map(ShopDirectoryService::card);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ShopCard> findAll(Collection<UUID> shopIds) {
        Map<UUID, ShopCard> out = new HashMap<>();
        if (!shopIds.isEmpty()) {
            shops.findAllById(new HashSet<>(shopIds)).forEach(s -> out.put(s.getId(), card(s)));
        }
        return out;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ShopCard> findForBooking(UUID shopId) {
        return shops.findForShare(shopId).map(ShopDirectoryService::card);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void holdAgainstBookings(UUID shopId) {
        shops.holdAgainstBookings(shopId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShopCard> nearby(String type, java.math.BigDecimal latitude, java.math.BigDecimal longitude,
                                 int radiusMetres, int limit, Cursor after, boolean skipPausedSalons) {
        StringBuilder sql = new StringBuilder(NEARBY_SQL);
        List<Object> args = new ArrayList<>(List.of(longitude.doubleValue(), latitude.doubleValue(), type,
                (double) radiusMetres));
        if (skipPausedSalons) {
            sql.append(NOT_PAUSED);
            args.add(Timestamp.from(Instant.now(clock)));
        }
        if (after != null) {
            sql.append(AFTER);
            args.add(after.distanceMetres());
            args.add(after.id());
        }
        sql.append(" ORDER BY metres, s.id LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, i) -> {
            double metres = rs.getDouble("metres");
            return new ShopCard(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("address_line"),
                    rs.getString("area"), rs.getString("city"), rs.getString("pincode"),
                    rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"), rs.getString("status"),
                    rs.getString("type"), rs.getObject("shop_photo_file_id", UUID.class),
                    Math.round(metres / 100.0) / 10.0, metres);
        }, args.toArray());
    }

    private static ShopCard card(Shop s) {
        ShopDetails d = s.details();
        return new ShopCard(s.getId(), d.name(), d.addressLine(), d.area(), d.city(), d.pincode(), d.latitude(),
                d.longitude(), s.getStatus().name(), s.getType().name(), s.getShopPhotoFileId(), null, null);
    }
}
