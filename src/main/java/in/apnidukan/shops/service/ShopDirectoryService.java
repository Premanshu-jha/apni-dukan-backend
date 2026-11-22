package in.apnidukan.shops.service;

import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.shops.domain.Shop;
import in.apnidukan.shops.domain.ShopDetails;
import in.apnidukan.shops.repo.ShopRepository;
import in.apnidukan.common.config.ReadCopy;
import org.springframework.beans.factory.annotation.Value;
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

 /**
     * Salons whose subscription lapsed (trial or paid month over, and the 3 days' grace too: salon_subscriptions,
     * V65), while SUBSCRIPTIONS_ENFORCED is on. A salon without a row is never left out by it.
     */
    private static final String NOT_LAPSED = " AND NOT EXISTS (SELECT 1 FROM salon_subscriptions ss "
            + "WHERE ss.shop_id = s.id AND ss.bookable_until <= ?)";

    private static final String AFTER = " AND (ST_Distance(s.location, p.g, false), s.id) > (?, ?)";

    /**
     * A customer's search (ShopDirectory.Search): the salon's name, or one of its active services that an active
     * barber with weekly hours does (the same services its card counts, and someone can be booked for it: a barber
     * without hours has no free time ever, so a salon is never found by a service nobody there can do). It only
     * narrows the shops near the point: the map index still finds those (a few hundred within 2 km even in
     * the busiest area), and the radius, order and page cursor are unchanged. Each of them is then checked in the
     * same statement, never a read per salon from Java: its services from ix_salon_services_search (V85: the
     * salon's active services with their name, picture and id in the index itself, so no table rows are read), its
     * barbers by ix_salon_staff_shop, staff_services' (staff_id, service_id) key and ix_staff_hours_staff. Starting
     * from the words instead (a trigram index) would be slower for the common ones: "haircut" is in nearly every salon
     * in India. The names are matched by Search.textMatches (in part, and word by word with small slips forgiven when
     * the search allows them); the pictures come as one comma-separated text (empty: none).
     */
    private static final String SERVICE_MATCHES = " OR EXISTS (SELECT 1 FROM salon_services v WHERE v.shop_id = s.id"
            + " AND v.active AND (";
    private static final String SERVICE_DONE = " OR v.icon = ANY (string_to_array(?, ','))) AND v.id IN ("
            + "SELECT k.service_id FROM salon_staff st JOIN staff_services k ON k.staff_id = st.id"
            + " WHERE st.shop_id = s.id AND st.active"
            + " AND EXISTS (SELECT 1 FROM staff_hours sh WHERE sh.staff_id = st.id))))";

    private final ShopRepository shops;
    private final JdbcTemplate jdbc;
    /** The salon list's reads (nearby): the read copy when READ_DB_URL is set, else the main database. */
    private final ReadCopy listReads;
    private final Clock clock;
    private final boolean subscriptionsEnforced;

    public ShopDirectoryService(ShopRepository shops, JdbcTemplate jdbc, ReadCopy readCopy, Clock clock,
                                @Value("${app.subscriptions.enforce:false}") boolean subscriptionsEnforced) {
        this.shops = shops;
        this.jdbc = jdbc;
        this.listReads = readCopy;
        this.clock = clock;
        this.subscriptionsEnforced = subscriptionsEnforced;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean needsEditCode(UUID shopId) {
        return shops.needsEditCode(shopId);
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean approvedNow(UUID shopId) {
        return Boolean.TRUE.equals(jdbc.query("SELECT status = 'APPROVED' FROM shops WHERE id = ? FOR SHARE",
                (rs, i) -> rs.getBoolean(1), shopId).stream().findFirst().orElse(false));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShopCard> find(UUID shopId) {
        return shops.findById(shopId).map(ShopDirectoryService::card);
    }

    @Override
    public boolean usesFile(UUID fileId) {
        return shops.existsByOwnerPhotoFileIdOrShopPhotoFileIdOrShopVideoFileId(fileId, fileId, fileId)
                || shops.existsByRegistrationDocFileId(fileId);
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

    /** No transaction: one statement, read from the read copy when there is one (holding none of the main's). */
    @Override
    public List<ShopCard> nearby(String type, java.math.BigDecimal latitude, java.math.BigDecimal longitude,
                                 int radiusMetres, int limit, Cursor after, boolean skipPausedSalons,
                                 Search search) {
        StringBuilder sql = new StringBuilder(NEARBY_SQL);
        List<Object> args = new ArrayList<>(List.of(longitude.doubleValue(), latitude.doubleValue(), type,
                (double) radiusMetres));
        if (skipPausedSalons) {
            sql.append(NOT_PAUSED);
            args.add(Timestamp.from(Instant.now(clock)));
            if (subscriptionsEnforced) {
                sql.append(NOT_LAPSED);
                args.add(Timestamp.from(Instant.now(clock)));
            }
        }
        if (search != null) {
            sql.append(" AND (").append(search.textMatches("lower(s.name)"));
            args.addAll(search.textArgs());
            sql.append(SERVICE_MATCHES).append(search.textMatches("lower(v.name)"));
            args.addAll(search.textArgs());
            sql.append(SERVICE_DONE);
            args.add(String.join(",", search.serviceIcons()));
        }
        if (after != null) {
            sql.append(AFTER);
            args.add(after.distanceMetres());
            args.add(after.id());
        }
        sql.append(" ORDER BY metres, s.id LIMIT ?");
        args.add(limit);
        return listReads.read(read -> read.query(sql.toString(), (rs, i) -> {
            double metres = rs.getDouble("metres");
            return new ShopCard(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("address_line"),
                    rs.getString("area"), rs.getString("city"), rs.getString("pincode"),
                    rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"), rs.getString("status"),
                    rs.getString("type"), rs.getObject("shop_photo_file_id", UUID.class),
                    Math.round(metres / 100.0) / 10.0, metres);
        }, args.toArray()));
    }

    private static ShopCard card(Shop s) {
        ShopDetails d = s.details();
        return new ShopCard(s.getId(), d.name(), d.addressLine(), d.area(), d.city(), d.pincode(), d.latitude(),
                d.longitude(), s.getStatus().name(), s.getType().name(), s.getShopPhotoFileId(), null, null);
    }
}
