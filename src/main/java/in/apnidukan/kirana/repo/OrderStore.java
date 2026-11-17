package in.apnidukan.kirana.repo;

import in.apnidukan.kirana.domain.Unit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static in.apnidukan.kirana.repo.KiranaSettingsStore.at;

/**
 * kirana_checkouts, kirana_orders, kirana_order_parts and kirana_order_lines (V68): online orders, in plain SQL. Also
 * the reads the customer app's kirana tab needs: the shops near a customer that take orders, a shop's products, and the
 * same items in shops near it.
 */
@Repository
public class OrderStore {

    private static final String CHECKOUT = "SELECT id, customer_id, client_ref, items_paise, platform_paise, "
            + "total_paise, method_wanted, status, razorpay_order_id, razorpay_payment_id, paid_method, refunded_paise, "
            + "created_at, paid_at FROM kirana_checkouts";

    private static final String ORDER = "SELECT o.id, o.order_no, o.checkout_id, o.shop_id, o.customer_id, "
            + "o.address_line, o.latitude, o.longitude, o.status, o.cancel_reason, o.platform_paise, o.delivery_otp, "
            + "o.otp_wrong, o.otp_locked_until, o.placed_at, o.accepted_at, o.packed_at, o.out_at, o.delivered_at, "
            + "o.cancelled_at, o.rating, o.rating_comment, o.version, o.created_at FROM kirana_orders o";

    private static final String PART = "SELECT id, order_id, shop_id, role, status, accept_by, accepted_at, reason, "
            + "items_paise, version, updated_at FROM kirana_order_parts";

    /** A kirana that takes online orders now: approved, with a UPI ID, a valid FSSAI licence and its switch on. */
    private static final String READY = "s.status = 'APPROVED' AND s.type = 'KIRANA' AND o.taking_orders "
            // verified and valid, or during a renewal the earlier licence still valid (greatest skips NULLs)
            + "AND greatest(CASE WHEN o.fssai_status = 'VERIFIED' THEN o.fssai_valid_until END, "
            + "o.fssai_kept_until) >= ? "
            + "AND k.upi_id IS NOT NULL";

    private final JdbcTemplate jdbc;

    public OrderStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- records ----------

    /** @param status PENDING (waiting for the payment), PAID or EXPIRED (never paid in time) */
    public record Checkout(UUID id, UUID customerId, UUID clientRef, long itemsPaise, long platformPaise,
                           long totalPaise, String methodWanted, String status, String razorpayOrderId,
                           String razorpayPaymentId, String paidMethod, long refundedPaise, Instant createdAt,
                           Instant paidAt) {
    }

    public record Order(UUID id, long orderNo, UUID checkoutId, UUID shopId, UUID customerId, String addressLine,
                        BigDecimal latitude, BigDecimal longitude, String status, String cancelReason,
                        long platformPaise, String deliveryOtp, int otpWrong, Instant otpLockedUntil, Instant placedAt,
                        Instant acceptedAt, Instant packedAt, Instant outAt, Instant deliveredAt, Instant cancelledAt,
                        Integer rating, String ratingComment, long version, Instant createdAt) {

        public Order with(String newStatus, Instant at) {
            return new Order(id, orderNo, checkoutId, shopId, customerId, addressLine, latitude, longitude, newStatus,
                    cancelReason, platformPaise, deliveryOtp, otpWrong, otpLockedUntil,
                    "PLACED".equals(newStatus) ? at : placedAt, "ACCEPTED".equals(newStatus) ? at : acceptedAt,
                    "PACKED".equals(newStatus) ? at : packedAt, "OUT_FOR_DELIVERY".equals(newStatus) ? at : outAt,
                    "DELIVERED".equals(newStatus) ? at : deliveredAt,
                    "CANCELLED".equals(newStatus) ? at : cancelledAt, rating, ratingComment, version + 1, createdAt);
        }
    }

    /**
     * @param role   OWN (the delivering shop's items) or SUPPLIER (another shop's, handed to it)
     * @param status WAITING_PAYMENT, WAITING (to be accepted by acceptBy), ACCEPTED, PACKED, HANDED (a supplier's,
     *               given to the delivering shop), REJECTED, TIMED_OUT or CANCELLED
     */
    public record Part(UUID id, UUID orderId, UUID shopId, String role, String status, Instant acceptBy,
                       Instant acceptedAt, String reason, long itemsPaise, long version, Instant updatedAt) {

        public boolean live() {
            return switch (status) {
                case "REJECTED", "TIMED_OUT", "CANCELLED" -> false;
                default -> true;
            };
        }
    }

    public record Line(UUID partId, int lineNo, UUID productId, UUID catalogueItemId, String name, Unit unit,
                       BigDecimal quantity, long pricePaise, long amountPaise, boolean missing) {
    }

    /** A kirana near the customer that takes orders. */
    public record ShopListing(UUID id, String name, String area, UUID photoFileId, double distanceMetres,
                             long ratingSum, int ratingCount) {
    }

    /** A product of a shop as the customer app lists it. */
    public record Listed(UUID id, String name, Unit unit, long pricePaise, BigDecimal stock, UUID catalogueItemId,
                         String sortKey) {
    }

    /**
     * A product another shop has, offered for an item the customer's shop lacks.
     * @param metres how far the other shop is from the point asked about
     */
    public record Offer(UUID shopId, String shopName, UUID productId, UUID catalogueItemId, String name, Unit unit,
                        long pricePaise, BigDecimal stock, double metres) {
    }

    /** A catalogue item stocked by shops near the customer's shop (and not by it), with the lowest price. */
    public record NetworkItem(UUID catalogueItemId, String name, String brand, String size, Unit unit, long pricePaise,
                              String sortKey) {
    }

    // ---------- checkouts ----------

    /** False (nothing added) when the customer already has a checkout with this clientRef. */
    public boolean insertCheckout(Checkout c) {
        return jdbc.update("INSERT INTO kirana_checkouts (id, customer_id, client_ref, items_paise, platform_paise, "
                        + "total_paise, method_wanted, status, refunded_paise, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?) ON CONFLICT (customer_id, client_ref) DO NOTHING",
                c.id(), c.customerId(), c.clientRef(), c.itemsPaise(), c.platformPaise(), c.totalPaise(),
                c.methodWanted(), at(c.createdAt())) > 0;
    }

    public Optional<Checkout> checkoutByClientRef(UUID customerId, UUID clientRef) {
        return jdbc.query(CHECKOUT + " WHERE customer_id = ? AND client_ref = ?", OrderStore::checkout, customerId,
                clientRef).stream().findFirst();
    }

    public Optional<Checkout> checkout(UUID id) {
        return jdbc.query(CHECKOUT + " WHERE id = ?", OrderStore::checkout, id).stream().findFirst();
    }

    public Optional<Checkout> lockCheckout(UUID id) {
        return jdbc.query(CHECKOUT + " WHERE id = ? FOR NO KEY UPDATE", OrderStore::checkout, id).stream().findFirst();
    }

    /** The owners' phone numbers of these shops (how the shops of one order reach each other, and the customer them). */
    public Map<UUID, String> ownerPhones(Collection<UUID> shopIds) {
        Map<UUID, String> out = new java.util.HashMap<>();
        if (shopIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT m.shop_id, u.phone FROM shop_members m JOIN users u ON u.id = m.user_id "
                + "WHERE m.shop_id = ANY (?) AND m.role = 'OWNER'",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", shopIds.toArray())),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(rs.getObject(1, UUID.class),
                        rs.getString(2)));
        return out;
    }

    /** A customer's paid orders not delivered or cancelled yet (at most 50: a person has a few at a time). */
    public List<Order> openOrdersOf(UUID customerId) {
        return jdbc.query(ORDER + " WHERE o.customer_id = ? AND o.status IN ('PLACED', 'ACCEPTED', 'PACKED', "
                + "'OUT_FOR_DELIVERY') ORDER BY o.created_at LIMIT 50", OrderStore::order, customerId);
    }

    /** Paid orders still open that were placed before this time, oldest first (the admin website's stuck orders). */
    public List<Order> openBefore(Instant before, int limit) {
        return jdbc.query(ORDER + " WHERE o.status IN ('PLACED', 'ACCEPTED', 'PACKED', 'OUT_FOR_DELIVERY') "
                + "AND o.placed_at < ? ORDER BY o.placed_at LIMIT ?", OrderStore::order, at(before), limit);
    }

    /** Which of these shops take orders now (approved, UPI ID, FSSAI valid today, switch on). */
    public java.util.Set<UUID> readyAmong(Collection<UUID> shopIds, java.time.LocalDate today) {
        if (shopIds.isEmpty()) {
            return java.util.Set.of();
        }
        return new java.util.HashSet<>(jdbc.query("SELECT s.id FROM shops s JOIN kirana_online o ON o.shop_id = s.id "
                + "JOIN kirana_settings k ON k.shop_id = s.id WHERE s.id = ANY (?) AND " + READY, ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("uuid", shopIds.toArray()));
                    ps.setDate(2, java.sql.Date.valueOf(today));
                }, (rs, i) -> rs.getObject(1, UUID.class)));
    }

    public Optional<Checkout> checkoutByRazorpay(String razorpayOrderId) {
        return jdbc.query(CHECKOUT + " WHERE razorpay_order_id = ?", OrderStore::checkout, razorpayOrderId).stream()
                .findFirst();
    }

    public Optional<Checkout> lockCheckoutByRazorpay(String razorpayOrderId) {
        return jdbc.query(CHECKOUT + " WHERE razorpay_order_id = ? FOR NO KEY UPDATE", OrderStore::checkout,
                razorpayOrderId).stream().findFirst();
    }

    public void setRazorpayOrder(UUID checkoutId, String razorpayOrderId) {
        jdbc.update("UPDATE kirana_checkouts SET razorpay_order_id = ? WHERE id = ? AND razorpay_order_id IS NULL",
                razorpayOrderId, checkoutId);
    }

    public void markPaid(UUID checkoutId, String paymentId, String method, Instant now) {
        jdbc.update("UPDATE kirana_checkouts SET status = 'PAID', razorpay_payment_id = ?, paid_method = ?, "
                + "paid_at = ? WHERE id = ?", paymentId, method, at(now), checkoutId);
    }

    /** A payment that came after the checkout expired: kept, to be refunded. */
    public void markLatePayment(UUID checkoutId, String paymentId, String method) {
        jdbc.update("UPDATE kirana_checkouts SET razorpay_payment_id = ?, paid_method = ? WHERE id = ?", paymentId,
                method, checkoutId);
    }

    public void setPaidMethod(UUID checkoutId, String method) {
        jdbc.update("UPDATE kirana_checkouts SET paid_method = ? WHERE id = ? AND paid_method IS NULL", method,
                checkoutId);
    }

    public void expire(UUID checkoutId) {
        jdbc.update("UPDATE kirana_checkouts SET status = 'EXPIRED' WHERE id = ? AND status = 'PENDING'", checkoutId);
    }

    /** Checkouts still not paid that were made before this time. */
    public List<UUID> pendingBefore(Instant before, int limit) {
        return jdbc.queryForList("SELECT id FROM kirana_checkouts WHERE status = 'PENDING' AND created_at < ? "
                + "ORDER BY created_at LIMIT ?", UUID.class, at(before), limit);
    }

    /**
     * Sets aside this much of the checkout for a refund, never past what was paid (the caller holds its lock). Whether
     * it fitted.
     */
    public boolean addRefunded(UUID checkoutId, long amountPaise) {
        return jdbc.update("UPDATE kirana_checkouts SET refunded_paise = refunded_paise + ? WHERE id = ? "
                + "AND refunded_paise + ? <= total_paise", amountPaise, checkoutId, amountPaise) > 0;
    }

    // ---------- orders, parts, lines ----------

    /** Adds an order; returns its number. */
    public long insertOrder(Order o) {
        Long no = jdbc.queryForObject("INSERT INTO kirana_orders (id, checkout_id, shop_id, customer_id, address_line, "
                        + "latitude, longitude, status, platform_paise, delivery_otp, otp_wrong, version, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'WAITING_PAYMENT', ?, ?, 0, 0, ?) RETURNING order_no",
                Long.class, o.id(), o.checkoutId(), o.shopId(), o.customerId(), o.addressLine(), o.latitude(),
                o.longitude(), o.platformPaise(), o.deliveryOtp(), at(o.createdAt()));
        return java.util.Objects.requireNonNull(no, "order_no");
    }

    public void insertPart(Part p, List<Line> lines) {
        jdbc.update("INSERT INTO kirana_order_parts (id, order_id, shop_id, role, status, items_paise, version, "
                        + "updated_at) VALUES (?, ?, ?, ?, 'WAITING_PAYMENT', ?, 0, ?)",
                p.id(), p.orderId(), p.shopId(), p.role(), p.itemsPaise(), at(p.updatedAt()));
        jdbc.batchUpdate("INSERT INTO kirana_order_lines (part_id, line_no, product_id, catalogue_item_id, name, unit, "
                        + "quantity, price_paise, amount_paise, missing) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, false)",
                lines, lines.size(), (ps, l) -> {
                    ps.setObject(1, p.id());
                    ps.setInt(2, l.lineNo());
                    ps.setObject(3, l.productId());
                    ps.setObject(4, l.catalogueItemId());
                    ps.setString(5, l.name());
                    ps.setString(6, l.unit().name());
                    ps.setBigDecimal(7, l.quantity());
                    ps.setLong(8, l.pricePaise());
                    ps.setLong(9, l.amountPaise());
                });
    }

    public Optional<Order> order(UUID id) {
        return jdbc.query(ORDER + " WHERE o.id = ?", OrderStore::order, id).stream().findFirst();
    }

    /** These orders in one query, by id. */
    public Map<UUID, Order> orders(Collection<UUID> ids) {
        Map<UUID, Order> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.query(ORDER + " WHERE o.id = ANY (?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids.toArray())),
                (RowCallbackHandler) rs -> out.put(rs.getObject("id", UUID.class), order(rs, 0)));
        return out;
    }

    public Optional<Order> lockOrder(UUID id) {
        return jdbc.query(ORDER + " WHERE o.id = ? FOR NO KEY UPDATE", OrderStore::order, id).stream().findFirst();
    }

    /** The checkout's orders, oldest first (the first carries the platform charge). */
    public List<Order> ordersOf(UUID checkoutId) {
        return jdbc.query(ORDER + " WHERE o.checkout_id = ? ORDER BY o.order_no", OrderStore::order, checkoutId);
    }

    /** The checkout's orders, locked in order-number order (every writer locks them so: no circles). */
    public List<Order> lockOrdersOf(UUID checkoutId) {
        return jdbc.query(ORDER + " WHERE o.checkout_id = ? ORDER BY o.order_no FOR NO KEY UPDATE", OrderStore::order,
                checkoutId);
    }

    /** Saves an order's changing fields (status and its times, cancel reason, OTP tries, rating). */
    public void saveOrder(Order o) {
        if ("DELIVERED".equals(o.status()) || "CANCELLED".equals(o.status())) {
            // Nothing left for its shops to do: its parts move to their done screens
            jdbc.update("UPDATE kirana_order_parts SET board = 'DONE', updated_at = ? WHERE order_id = ? "
                    + "AND board = 'OPEN'", at(o.deliveredAt() != null ? o.deliveredAt() : o.cancelledAt()), o.id());
        }
        jdbc.update("UPDATE kirana_orders SET status = ?, cancel_reason = ?, otp_wrong = ?, otp_locked_until = ?, "
                        + "placed_at = ?, accepted_at = ?, packed_at = ?, out_at = ?, delivered_at = ?, cancelled_at = ?, "
                        + "version = ? WHERE id = ?",
                o.status(), cut(o.cancelReason()), o.otpWrong(), at(o.otpLockedUntil()), at(o.placedAt()),
                at(o.acceptedAt()), at(o.packedAt()), at(o.outAt()), at(o.deliveredAt()), at(o.cancelledAt()),
                o.version(), o.id());
    }

    /**
     * The customer corrects the address text of an order not sent out yet (the pin stays), at most {@code max} times.
     * Whether it was changed (false: the corrections are used up).
     */
    public boolean setAddress(UUID orderId, String addressLine, int max) {
        return jdbc.update("UPDATE kirana_orders SET address_line = ?, address_changes = address_changes + 1, "
                + "version = version + 1 WHERE id = ? AND address_changes < ?", addressLine, orderId, max) > 0;
    }

    /** How many times the order's address was corrected. */
    public int addressChanges(UUID orderId) {
        Integer n = jdbc.queryForObject("SELECT address_changes FROM kirana_orders WHERE id = ?", Integer.class,
                orderId);
        return n == null ? 0 : n;
    }

    /** The customer's rating of a delivered order; false when it was rated already. */
    public boolean rate(UUID orderId, int stars, String comment, Instant now) {
        return jdbc.update("UPDATE kirana_orders SET rating = ?, rating_comment = ?, rated_at = ? WHERE id = ? "
                + "AND rating IS NULL", stars, comment, at(now), orderId) > 0;
    }

    public Optional<Part> part(UUID id) {
        return jdbc.query(PART + " WHERE id = ?", OrderStore::part, id).stream().findFirst();
    }

    /** The order's parts, OWN first, then by shop. */
    public List<Part> partsOf(UUID orderId) {
        return jdbc.query(PART + " WHERE order_id = ? ORDER BY role, shop_id", OrderStore::part, orderId);
    }

    /** The order's parts, locked (after the order's own row), OWN first, then by shop. */
    public List<Part> lockPartsOf(UUID orderId) {
        return jdbc.query(PART + " WHERE order_id = ? ORDER BY role, shop_id FOR NO KEY UPDATE", OrderStore::part,
                orderId);
    }

    /** Parts of many orders in one query, by order. */
    public Map<UUID, List<Part>> partsOf(Collection<UUID> orderIds) {
        Map<UUID, List<Part>> out = new LinkedHashMap<>();
        if (orderIds.isEmpty()) {
            return out;
        }
        jdbc.query(PART + " WHERE order_id = ANY (?) ORDER BY order_id, role, shop_id",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", orderIds.toArray())),
                (RowCallbackHandler) rs -> out.computeIfAbsent(rs.getObject("order_id", UUID.class),
                        k -> new ArrayList<>()).add(part(rs, 0)));
        return out;
    }

    public void savePart(Part p, UUID acceptedBy) {
        jdbc.update("UPDATE kirana_order_parts SET status = ?, accept_by = ?, accepted_at = ?, accepted_by = "
                        + "coalesce(?, accepted_by), reason = ?, items_paise = ?, version = ?, updated_at = ?, "
                        // an unpaid order's parts (cancelled when it expires) never reach the shop's screens
                        + "board = CASE WHEN status = 'WAITING_PAYMENT' AND ? <> 'WAITING' THEN NULL ELSE ? END "
                        + "WHERE id = ?",
                p.status(), at(p.acceptBy()), at(p.acceptedAt()), acceptedBy, cut(p.reason()), p.itemsPaise(), p.version(),
                at(p.updatedAt()), p.status(), board(p.status()), p.id());
    }

    /** A reason as stored (200 letters at most: a shop's or the team's words after "The shop cancelled it: "). */
    private static String cut(String reason) {
        return reason == null || reason.length() <= 200 ? reason : reason.substring(0, 199) + "…";
    }

    /** Which of the shop's order screens a part in this status shows on: OPEN, DONE, or none (not paid yet). */
    static String board(String status) {
        return switch (status) {
            case "WAITING_PAYMENT" -> null;
            case "WAITING", "ACCEPTED", "PACKED" -> "OPEN";
            default -> "DONE";
        };
    }

    /** Lines of many parts in one query, by part, in line order. */
    public Map<UUID, List<Line>> lines(Collection<UUID> partIds) {
        Map<UUID, List<Line>> out = new LinkedHashMap<>();
        if (partIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT part_id, line_no, product_id, catalogue_item_id, name, unit, quantity, price_paise, "
                        + "amount_paise, missing FROM kirana_order_lines WHERE part_id = ANY (?) ORDER BY part_id, line_no",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", partIds.toArray())),
                (RowCallbackHandler) rs -> out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                        .add(new Line(rs.getObject(1, UUID.class), rs.getInt(2), rs.getObject(3, UUID.class),
                                rs.getObject(4, UUID.class), rs.getString(5), Unit.valueOf(rs.getString(6)),
                                rs.getBigDecimal(7), rs.getLong(8), rs.getLong(9), rs.getBoolean(10))));
        return out;
    }

    public void markMissing(UUID partId, Collection<Integer> lineNos) {
        jdbc.update("UPDATE kirana_order_lines SET missing = true WHERE part_id = ? AND line_no = ANY (?)",
                ps -> {
                    ps.setObject(1, partId);
                    ps.setArray(2, ps.getConnection().createArrayOf("smallint", lineNos.toArray()));
                });
    }

    /** Parts still waiting to be accepted after their time ran out. */
    public List<UUID> overdueParts(Instant now, int limit) {
        return jdbc.queryForList("SELECT id FROM kirana_order_parts WHERE status = 'WAITING' AND accept_by < ? "
                + "ORDER BY accept_by LIMIT ?", UUID.class, at(now), limit);
    }

    /** The customer's orders, newest first, older than (beforeAt, beforeId) when given; one more than limit. */
    public List<Order> customerOrders(UUID customerId, Instant beforeAt, UUID beforeId, int limit) {
        if (beforeAt == null) {
            return jdbc.query(ORDER + " WHERE o.customer_id = ? AND o.status <> 'WAITING_PAYMENT' "
                    + "ORDER BY o.created_at DESC, o.id DESC LIMIT ?", OrderStore::order, customerId, limit + 1);
        }
        return jdbc.query(ORDER + " WHERE o.customer_id = ? AND o.status <> 'WAITING_PAYMENT' "
                        + "AND (o.created_at, o.id) < (?, ?) ORDER BY o.created_at DESC, o.id DESC LIMIT ?",
                OrderStore::order, customerId, at(beforeAt), beforeId, limit + 1);
    }

    /**
     * A shop's order parts, newest first: open ones (to accept, pack, hand over or deliver) or done ones, older than
     * (beforeAt, beforeId) when given; one more than limit.
     */
    public List<Part> shopParts(UUID shopId, boolean open, Instant beforeAt, UUID beforeId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT p.id, p.order_id, p.shop_id, p.role, p.status, p.accept_by, "
                + "p.accepted_at, p.reason, p.items_paise, p.version, p.updated_at FROM kirana_order_parts p "
                + "WHERE p.shop_id = ?");
        if (open) {
            // Waiting, being packed, or (the delivering shop's own) packed and out until delivered or cancelled
            sql.append(" AND p.board = 'OPEN'");
        } else {
            sql.append(" AND p.board = 'DONE'");
        }
        List<Object> args = new ArrayList<>(List.of(shopId));
        if (beforeAt != null) {
            sql.append(" AND (p.updated_at, p.id) < (?, ?)");
            args.add(at(beforeAt));
            args.add(beforeId);
        }
        sql.append(" ORDER BY p.updated_at DESC, p.id DESC LIMIT ?");
        args.add(limit + 1);
        return jdbc.query(sql.toString(), OrderStore::part, args.toArray());
    }

    // ---------- the customer app's kirana tab ----------

    /** Kirana shops within {@code metres} of the point that take orders now, nearest first; one more than limit. */
    public List<ShopListing> nearby(BigDecimal lat, BigDecimal lng, int metres, java.time.LocalDate today,
                                    Double afterMetres, UUID afterId, int limit) {
        StringBuilder sql = new StringBuilder("WITH p AS (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography AS g) "
                + "SELECT s.id, s.name, s.area, s.shop_photo_file_id, ST_Distance(s.location, p.g, false) AS metres, "
                + "o.rating_sum, o.rating_count FROM shops s JOIN kirana_online o ON o.shop_id = s.id "
                + "JOIN kirana_settings k ON k.shop_id = s.id, p WHERE " + READY
                + " AND ST_DWithin(s.location, p.g, ?, false)");
        List<Object> args = new ArrayList<>(List.of(lng, lat, java.sql.Date.valueOf(today), metres));
        if (afterMetres != null) {
            sql.append(" AND (ST_Distance(s.location, p.g, false), s.id) > (?, ?)");
            args.add(afterMetres);
            args.add(afterId);
        }
        sql.append(" ORDER BY metres, s.id LIMIT ?");
        args.add(limit + 1);
        return jdbc.query(sql.toString(), (rs, i) -> new ShopListing(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getObject(4, UUID.class), rs.getDouble(5), rs.getLong(6), rs.getInt(7)),
                args.toArray());
    }

    /**
     * Whether the shop takes orders now, and how far it is from the point: empty when it doesn't (not approved, no UPI
     * ID, no valid FSSAI licence, switched off, or not a kirana).
     */
    public Optional<Double> readyShopMetres(UUID shopId, BigDecimal lat, BigDecimal lng, java.time.LocalDate today) {
        return jdbc.query("WITH p AS (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography AS g) "
                        + "SELECT ST_Distance(s.location, p.g, false) FROM shops s JOIN kirana_online o ON o.shop_id = s.id "
                        + "JOIN kirana_settings k ON k.shop_id = s.id, p WHERE s.id = ? AND " + READY,
                (rs, i) -> rs.getDouble(1), lng, lat, shopId, java.sql.Date.valueOf(today)).stream().findFirst();
    }

    /**
     * The shop's active products for the customer, in name order, after (afterName, afterId) when given; with
     * {@code words}: names containing it. One more than limit.
     */
    public List<Listed> listed(UUID shopId, String words, String afterName, UUID afterId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT id, name, unit, price_paise, stock, catalogue_item_id, "
                + "lower(name) AS sort_key FROM kirana_products WHERE shop_id = ? AND active");
        List<Object> args = new ArrayList<>(List.of(shopId));
        if (words != null) {
            sql.append(" AND lower(name) LIKE ? ESCAPE '\\'");
            args.add("%" + words + "%");
        }
        if (afterName != null) {
            sql.append(" AND (lower(name) COLLATE \"C\", id) > (?, ?)");
            args.add(afterName);
            args.add(afterId);
        }
        sql.append(" ORDER BY lower(name) COLLATE \"C\", id LIMIT ?");
        args.add(limit + 1);
        return jdbc.query(sql.toString(), (rs, i) -> new Listed(rs.getObject(1, UUID.class), rs.getString(2),
                Unit.valueOf(rs.getString(3)), rs.getLong(4), rs.getBigDecimal(5), rs.getObject(6, UUID.class),
                rs.getString(7)), args.toArray());
    }

    /** These active products of the shop (others are left out). */
    public Map<UUID, Listed> products(UUID shopId, Collection<UUID> ids) {
        Map<UUID, Listed> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT id, name, unit, price_paise, stock, catalogue_item_id, lower(name) FROM kirana_products "
                        + "WHERE shop_id = ? AND active AND id = ANY (?)",
                ps -> {
                    ps.setObject(1, shopId);
                    ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids.toArray()));
                },
                (RowCallbackHandler) rs -> out.put(rs.getObject(1, UUID.class), new Listed(rs.getObject(1, UUID.class),
                        rs.getString(2), Unit.valueOf(rs.getString(3)), rs.getLong(4), rs.getBigDecimal(5),
                        rs.getObject(6, UUID.class), rs.getString(7))));
        return out;
    }

    /**
     * Products for these catalogue items in other shops that take orders, in stock, within {@code metres} of the
     * point, nearest first (a shop's own products are left out with {@code exceptShop}).
     */
    public List<Offer> offers(Collection<UUID> catalogueItemIds, UUID exceptShop, BigDecimal lat, BigDecimal lng,
                              int metres, java.time.LocalDate today) {
        if (catalogueItemIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("WITH p AS (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography AS g) "
                        + "SELECT s.id, s.name, pr.id, pr.catalogue_item_id, pr.name, pr.unit, pr.price_paise, pr.stock, "
                        + "ST_Distance(s.location, p.g, false) AS metres FROM kirana_products pr "
                        + "JOIN shops s ON s.id = pr.shop_id JOIN kirana_online o ON o.shop_id = s.id "
                        + "JOIN kirana_settings k ON k.shop_id = s.id, p "
                        + "WHERE pr.catalogue_item_id = ANY (?) AND pr.active AND pr.stock > 0 AND s.id <> ? AND " + READY
                        + " AND ST_DWithin(s.location, p.g, ?, false) ORDER BY metres, s.id, pr.id",
                ps -> {
                    ps.setObject(1, lng);
                    ps.setObject(2, lat);
                    ps.setArray(3, ps.getConnection().createArrayOf("uuid", catalogueItemIds.toArray()));
                    ps.setObject(4, exceptShop);
                    ps.setDate(5, java.sql.Date.valueOf(today));
                    ps.setInt(6, metres);
                },
                (rs, i) -> new Offer(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getString(5), Unit.valueOf(rs.getString(6)), rs.getLong(7),
                        rs.getBigDecimal(8), rs.getDouble(9)));
    }

    /**
     * Catalogue items that shops taking orders within {@code metres} of the shop have in stock and the shop itself
     * doesn't list, in name order with the lowest price, after (afterName, afterId) when given; one more than limit.
     */
    public List<NetworkItem> networkItems(UUID shopId, BigDecimal lat, BigDecimal lng, int metres,
                                          java.time.LocalDate today, String words, String afterName, UUID afterId,
                                          int limit) {
        StringBuilder sql = new StringBuilder("WITH p AS (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography AS g) "
                + "SELECT c.id, c.name, c.brand, c.size, c.unit, min(pr.price_paise), lower(c.name) AS sort_key "
                + "FROM kirana_products pr JOIN kirana_catalogue_items c ON c.id = pr.catalogue_item_id "
                + "JOIN shops s ON s.id = pr.shop_id JOIN kirana_online o ON o.shop_id = s.id "
                + "JOIN kirana_settings k ON k.shop_id = s.id, p "
                + "WHERE pr.active AND pr.stock > 0 AND s.id <> ? AND " + READY
                + " AND ST_DWithin(s.location, p.g, ?, false) AND NOT EXISTS (SELECT 1 FROM kirana_products mine "
                + "WHERE mine.shop_id = ? AND mine.active AND mine.catalogue_item_id = c.id)");
        List<Object> args = new ArrayList<>(List.of(lng, lat, shopId, java.sql.Date.valueOf(today), metres, shopId));
        if (words != null) {
            sql.append(" AND lower(c.name) LIKE ? ESCAPE '\\'");
            args.add("%" + words + "%");
        }
        if (afterName != null) {
            sql.append(" AND (lower(c.name) COLLATE \"C\", c.id) > (?, ?)");
            args.add(afterName);
            args.add(afterId);
        }
        sql.append(" GROUP BY c.id, c.name, c.brand, c.size, c.unit ORDER BY lower(c.name) COLLATE \"C\", c.id LIMIT ?");
        args.add(limit + 1);
        return jdbc.query(sql.toString(), (rs, i) -> new NetworkItem(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getString(4), Unit.valueOf(rs.getString(5)), rs.getLong(6), rs.getString(7)),
                args.toArray());
    }

    /** The shop's location. */
    public Optional<BigDecimal[]> location(UUID shopId) {
        return jdbc.query("SELECT latitude, longitude FROM shops WHERE id = ?",
                (rs, i) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)}, shopId).stream().findFirst();
    }

    // ---------- mapping ----------

    private static Checkout checkout(ResultSet rs, int i) throws SQLException {
        OffsetDateTime paid = rs.getObject("paid_at", OffsetDateTime.class);
        return new Checkout(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getObject("client_ref", UUID.class), rs.getLong("items_paise"), rs.getLong("platform_paise"),
                rs.getLong("total_paise"), rs.getString("method_wanted"), rs.getString("status"),
                rs.getString("razorpay_order_id"), rs.getString("razorpay_payment_id"), rs.getString("paid_method"),
                rs.getLong("refunded_paise"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                paid == null ? null : paid.toInstant());
    }

    private static Order order(ResultSet rs, int i) throws SQLException {
        return new Order(rs.getObject("id", UUID.class), rs.getLong("order_no"), rs.getObject("checkout_id", UUID.class),
                rs.getObject("shop_id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getString("address_line"), rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
                rs.getString("status"), rs.getString("cancel_reason"), rs.getLong("platform_paise"),
                rs.getString("delivery_otp"), rs.getInt("otp_wrong"), time(rs, "otp_locked_until"),
                time(rs, "placed_at"), time(rs, "accepted_at"), time(rs, "packed_at"), time(rs, "out_at"),
                time(rs, "delivered_at"), time(rs, "cancelled_at"), (Integer) toInt(rs.getObject("rating")),
                rs.getString("rating_comment"), rs.getLong("version"), time(rs, "created_at"));
    }

    private static Part part(ResultSet rs, int i) throws SQLException {
        return new Part(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                rs.getObject("shop_id", UUID.class), rs.getString("role"), rs.getString("status"),
                time(rs, "accept_by"), time(rs, "accepted_at"), rs.getString("reason"), rs.getLong("items_paise"),
                rs.getLong("version"), time(rs, "updated_at"));
    }

    private static Instant time(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static Integer toInt(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }
}
