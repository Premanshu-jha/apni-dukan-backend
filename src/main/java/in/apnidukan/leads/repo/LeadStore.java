package in.apnidukan.leads.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Leads page's tables (leads, lead_events, mapbox_usage) and what it reads of the shops: the Clozest salons in an
 * area (with the owner's phone, for matching only) and the pincodes a field agent covers. Every list is bounded or
 * paged; every query has its index (V97, V98).
 */
@Repository
public class LeadStore {

    /** One lead (see V97 for what each column means). */
    public record Lead(String mapboxId, String pincode, String status, String name, String phone, Double latitude,
                       Double longitude, String note, UUID assignedTo, LocalDate followUpOn, Instant contactedAt,
                       boolean whatsappOk,
                       Instant whatsappOkAt, UUID whatsappOkBy, UUID shopId, Instant signedUpAt, UUID creditedTo,
                       int version, Instant updatedAt, UUID updatedBy) {
    }

    /** A Clozest salon: its name and status, where it is, and its owner's phone (used for matching, never shown). */
    public record Salon(UUID shopId, String name, String status, String pincode, double latitude, double longitude,
                        String ownerPhone, Instant createdAt) {
    }

    /** One area's numbers: leads contacted, interested, signed up, and Clozest salons there. */
    public record AreaCount(String pincode, int contacted, int interested, int signedUp, int salons) {
    }

    public record Event(Instant at, UUID byUser, String field, String oldValue, String newValue) {
    }

    /** One area's funnel: leads touched (any saved), contacted, interested, not interested, signed up. */
    public record Funnel(String pincode, int touched, int contacted, int interested, int notInterested, int signedUp) {
    }

    /** One person's numbers (by who is assigned, and signups by who is credited). */
    public record PersonFunnel(UUID userId, int assigned, int contacted, int interested, int signedUp) {
    }

    private static final String LEAD_COLUMNS = "mapbox_id, pincode, status, name, phone, latitude, longitude, note, "
            + "assigned_to, follow_up_on, contacted_at, whatsapp_ok, whatsapp_ok_at, whatsapp_ok_by, shop_id, signed_up_at, "
            + "credited_to, version, updated_at, updated_by";

    private static final RowMapper<Lead> LEAD = (rs, i) -> new Lead(
            rs.getString("mapbox_id"), rs.getString("pincode"), rs.getString("status"), rs.getString("name"),
            rs.getString("phone"), dbl(rs.getBigDecimal("latitude")), dbl(rs.getBigDecimal("longitude")),
            rs.getString("note"), rs.getObject("assigned_to", UUID.class), day(rs.getDate("follow_up_on")),
            instant(rs.getTimestamp("contacted_at")), rs.getBoolean("whatsapp_ok"), instant(rs.getTimestamp("whatsapp_ok_at")),
            rs.getObject("whatsapp_ok_by", UUID.class), rs.getObject("shop_id", UUID.class),
            instant(rs.getTimestamp("signed_up_at")), rs.getObject("credited_to", UUID.class), rs.getInt("version"),
            instant(rs.getTimestamp("updated_at")), rs.getObject("updated_by", UUID.class));

    private static final RowMapper<Salon> SALON = (rs, i) -> new Salon(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("status"), rs.getString("pincode"),
            rs.getBigDecimal("latitude").doubleValue(), rs.getBigDecimal("longitude").doubleValue(),
            rs.getString("owner_phone"), instant(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public LeadStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- Leads --------------------------------------------------------------------------------------------------

    /** The saved leads among these Mapbox ids (one query, by primary key). */
    public Map<String, Lead> leads(Collection<String> mapboxIds) {
        Map<String, Lead> out = new LinkedHashMap<>();
        if (mapboxIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT " + LEAD_COLUMNS + " FROM leads WHERE mapbox_id = ANY (?)",
                ps -> ps.setArray(1, texts(ps.getConnection(), mapboxIds)),
                rs -> {
                    Lead l = LEAD.mapRow(rs, 0);
                    out.put(l.mapboxId(), l);
                });
        return out;
    }

    /** The saved leads in these areas that someone contacted (shown even when Mapbox no longer lists them), at most 2000. */
    public List<Lead> contactedIn(Collection<String> pincodes) {
        return jdbc.query("SELECT " + LEAD_COLUMNS + " FROM leads WHERE pincode = ANY (?) AND status <> 'NEW' "
                        + "ORDER BY updated_at DESC LIMIT 2000",
                ps -> ps.setArray(1, texts(ps.getConnection(), pincodes)), LEAD);
    }

    /** The lead, locked for a change (in the caller's transaction). */
    public Optional<Lead> lock(String mapboxId) {
        return jdbc.query("SELECT " + LEAD_COLUMNS + " FROM leads WHERE mapbox_id = ? FOR UPDATE", LEAD, mapboxId)
                .stream().findFirst();
    }

    /** A new lead with version 0; false if another request created it meanwhile (the caller answers 409). */
    public boolean insert(Lead l, Instant now) {
        return jdbc.update("INSERT INTO leads (" + LEAD_COLUMNS + ", created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, 0, ?, ?, ?) "
                        + "ON CONFLICT (mapbox_id) DO NOTHING",
                l.mapboxId(), l.pincode(), l.status(), l.name(), l.phone(), l.latitude(), l.longitude(), l.note(),
                l.assignedTo(), date(l.followUpOn()), ts(l.contactedAt()), l.whatsappOk(), ts(l.whatsappOkAt()), l.whatsappOkBy(),
                ts(now), l.updatedBy(), ts(now)) == 1;
    }

    /** Saves the team's fields of a locked lead and moves its version on by one. */
    public void update(Lead l, Instant now) {
        jdbc.update("UPDATE leads SET pincode = ?, status = ?, name = ?, phone = ?, latitude = ?, longitude = ?, "
                        + "note = ?, assigned_to = ?, follow_up_on = ?, contacted_at = ?, whatsapp_ok = ?, whatsapp_ok_at = ?, "
                        + "whatsapp_ok_by = ?, version = version + 1, updated_at = ?, updated_by = ? WHERE mapbox_id = ?",
                l.pincode(), l.status(), l.name(), l.phone(), l.latitude(), l.longitude(), l.note(), l.assignedTo(),
                date(l.followUpOn()), ts(l.contactedAt()), l.whatsappOk(), ts(l.whatsappOkAt()), l.whatsappOkBy(), ts(now),
                l.updatedBy(), l.mapboxId());
    }

    public void event(String mapboxId, UUID by, String field, String oldValue, String newValue, Instant at) {
        jdbc.update("INSERT INTO lead_events (mapbox_id, at, by_user, field, old_value, new_value) VALUES (?, ?, ?, ?, ?, ?)",
                mapboxId, ts(at), by, field, clip(oldValue), clip(newValue));
    }

    /** A lead's history, newest first, at most 50. */
    public List<Event> events(String mapboxId) {
        return jdbc.query("SELECT at, by_user, field, old_value, new_value FROM lead_events WHERE mapbox_id = ? "
                        + "ORDER BY at DESC, id DESC LIMIT 50",
                (rs, i) -> new Event(instant(rs.getTimestamp("at")), rs.getObject("by_user", UUID.class),
                        rs.getString("field"), rs.getString("old_value"), rs.getString("new_value")), mapboxId);
    }

    /**
     * Ties a lead to the salon it became, once, crediting the person assigned (else the last to work on it): only a
     * contacted lead (not NEW), a salon that registered after the team first tried to reach it (a salon already on
     * Clozest is never a signup from a lead), and not a closed one. False when any of that doesn't hold, the lead
     * already has a salon, or the salon already has a lead; also when another lead took the salon at the same moment
     * (ux_leads_shop), so callers never fail on a race. Called outside transactions (the scan, the job).
     */
    public boolean link(String mapboxId, UUID shopId, Instant now) {
        try {
            return jdbc.update("UPDATE leads l SET shop_id = s.id, signed_up_at = s.created_at, "
                            + "credited_to = COALESCE(l.assigned_to, l.updated_by), version = l.version + 1, updated_at = ? "
                            + "FROM shops s WHERE s.id = ? AND l.mapbox_id = ? AND l.shop_id IS NULL AND l.status <> 'NEW' "
                            + "AND l.contacted_at IS NOT NULL AND s.created_at >= l.contacted_at AND s.status <> 'CLOSED' "
                            + "AND NOT EXISTS (SELECT 1 FROM leads o WHERE o.shop_id = s.id)",
                    ts(now), shopId, mapboxId) == 1;
        } catch (DuplicateKeyException race) {
            return false;
        }
    }

    /** Contacted leads not tied to a salon yet, in pincode order after {@code afterId} (the matching job's work). */
    public List<Lead> unmatched(String afterId, int max) {
        return jdbc.query("SELECT " + LEAD_COLUMNS + " FROM leads WHERE shop_id IS NULL AND status <> 'NEW' "
                + "AND mapbox_id > ? ORDER BY mapbox_id LIMIT ?", LEAD, afterId, max);
    }

    /**
     * Leads with a follow-up on or before {@code until}: one person's (assignedTo), or everyone's when null; soonest
     * first, at most 200.
     */
    public List<Lead> due(UUID assignedTo, LocalDate until) {
        return assignedTo == null
                ? jdbc.query("SELECT " + LEAD_COLUMNS + " FROM leads WHERE follow_up_on <= ? AND shop_id IS NULL "
                + "ORDER BY follow_up_on, mapbox_id LIMIT 200", LEAD, date(until))
                : jdbc.query("SELECT " + LEAD_COLUMNS + " FROM leads WHERE assigned_to = ? AND follow_up_on <= ? "
                + "AND shop_id IS NULL ORDER BY follow_up_on, mapbox_id LIMIT 200", LEAD, assignedTo, date(until));
    }

    /**
     * Leads someone tried to reach, not signed up: one person's (assignedTo), or everyone's when null; most recently
     * worked first, at most 300 (My leads).
     */
    public List<Lead> worked(UUID assignedTo) {
        return assignedTo == null
                ? jdbc.query("SELECT " + LEAD_COLUMNS + " FROM leads WHERE status <> 'NEW' AND shop_id IS NULL "
                + "ORDER BY updated_at DESC LIMIT 300", LEAD)
                : jdbc.query("SELECT " + LEAD_COLUMNS + " FROM leads WHERE assigned_to = ? AND status <> 'NEW' "
                + "AND shop_id IS NULL ORDER BY updated_at DESC LIMIT 300", LEAD, assignedTo);
    }

    // ---- Areas and the funnel -----------------------------------------------------------------------------------

    /** Each area's numbers (only areas with something to count are in the map). */
    public Map<String, AreaCount> areaCounts(Collection<String> pincodes) {
        Map<String, AreaCount> out = new LinkedHashMap<>();
        if (pincodes.isEmpty()) {
            return out;
        }
        jdbc.query("""
                        SELECT p.pincode,
                               COALESCE(l.contacted, 0) AS contacted, COALESCE(l.interested, 0) AS interested,
                               COALESCE(l.signed, 0) AS signed, COALESCE(s.salons, 0) AS salons
                        FROM unnest(?::varchar[]) AS p (pincode)
                        LEFT JOIN (SELECT pincode, count(*) FILTER (WHERE status NOT IN ('NEW', 'NO_ANSWER')) AS contacted,
                                          count(*) FILTER (WHERE status = 'INTERESTED') AS interested,
                                          count(*) FILTER (WHERE shop_id IS NOT NULL) AS signed
                                   FROM leads WHERE pincode = ANY (?) GROUP BY pincode) l ON l.pincode = p.pincode
                        LEFT JOIN (SELECT pincode, count(*) AS salons FROM shops
                                   WHERE type = 'SALON' AND pincode = ANY (?) GROUP BY pincode) s ON s.pincode = p.pincode
                        WHERE l.pincode IS NOT NULL OR s.pincode IS NOT NULL
                        """,
                ps -> {
                    Array a = texts(ps.getConnection(), pincodes);
                    ps.setArray(1, a);
                    ps.setArray(2, a);
                    ps.setArray(3, a);
                },
                rs -> {
                    out.put(rs.getString("pincode"), new AreaCount(rs.getString("pincode"), rs.getInt("contacted"),
                            rs.getInt("interested"), rs.getInt("signed"), rs.getInt("salons")));
                });
        return out;
    }

    /** Each area's funnel (areas with saved leads only). */
    public List<Funnel> funnel(Collection<String> pincodes) {
        if (pincodes.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT pincode, count(*) AS touched, count(*) FILTER (WHERE status NOT IN ('NEW', 'NO_ANSWER')) AS contacted, "
                        + "count(*) FILTER (WHERE status = 'INTERESTED') AS interested, "
                        + "count(*) FILTER (WHERE status = 'NOT_INTERESTED') AS not_interested, "
                        + "count(*) FILTER (WHERE shop_id IS NOT NULL) AS signed "
                        + "FROM leads WHERE pincode = ANY (?) GROUP BY pincode ORDER BY pincode",
                ps -> ps.setArray(1, texts(ps.getConnection(), pincodes)),
                (rs, i) -> new Funnel(rs.getString("pincode"), rs.getInt("touched"), rs.getInt("contacted"),
                        rs.getInt("interested"), rs.getInt("not_interested"), rs.getInt("signed")));
    }

    /** Each person's numbers in these areas: leads assigned to them, and signups credited to them. */
    public List<PersonFunnel> people(Collection<String> pincodes) {
        if (pincodes.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                        SELECT who, sum(assigned) AS assigned, sum(contacted) AS contacted,
                               sum(interested) AS interested, sum(signed) AS signed
                        FROM (SELECT assigned_to AS who, 1 AS assigned, (status NOT IN ('NEW', 'NO_ANSWER'))::int AS contacted,
                                     (status = 'INTERESTED')::int AS interested, 0 AS signed
                              FROM leads WHERE pincode = ANY (?) AND assigned_to IS NOT NULL
                              UNION ALL
                              SELECT credited_to, 0, 0, 0, 1 FROM leads
                              WHERE pincode = ANY (?) AND credited_to IS NOT NULL AND shop_id IS NOT NULL) x
                        GROUP BY who ORDER BY sum(signed) DESC, sum(assigned) DESC LIMIT 100
                        """,
                ps -> {
                    Array a = texts(ps.getConnection(), pincodes);
                    ps.setArray(1, a);
                    ps.setArray(2, a);
                },
                (rs, i) -> new PersonFunnel(rs.getObject("who", UUID.class), rs.getInt("assigned"),
                        rs.getInt("contacted"), rs.getInt("interested"), rs.getInt("signed")));
    }

    // ---- What the page reads of the shops --------------------------------------------------------------------------

    /** The Clozest salons in these pincodes (any status), with the owner's phone. Callers pass a few pincodes at a time. */
    public List<Salon> salonsIn(Collection<String> pincodes) {
        if (pincodes.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT s.id, s.name, s.status, s.pincode, s.latitude, s.longitude, s.created_at, "
                        + "u.phone AS owner_phone FROM shops s "
                        + "LEFT JOIN shop_members m ON m.shop_id = s.id AND m.role = 'OWNER' "
                        + "LEFT JOIN users u ON u.id = m.user_id "
                        + "WHERE s.type = 'SALON' AND s.pincode = ANY (?) ORDER BY s.pincode, s.id",
                ps -> ps.setArray(1, texts(ps.getConnection(), pincodes)), SALON);
    }

    /**
     * For each area, the time of the last change there after that area's own time: a lead saved or tied to a salon
     * (leads.updated_at), or a Clozest salon registered or changed (shops.updated_at, which every status change sets).
     * Areas with nothing newer aren't in the map. One query, one range of ix_leads_pincode and of
     * ix_shops_salon_pincode per area; callers pass at most a few hundred areas.
     */
    public Map<String, Instant> changedSince(Map<String, Instant> since) {
        Map<String, Instant> out = new LinkedHashMap<>();
        if (since.isEmpty()) {
            return out;
        }
        List<String> pins = List.copyOf(since.keySet());
        Timestamp[] times = pins.stream().map(p -> ts(since.get(p))).toArray(Timestamp[]::new);
        jdbc.query("SELECT a.pincode, GREATEST("
                        + "(SELECT max(l.updated_at) FROM leads l WHERE l.pincode = a.pincode AND l.updated_at > a.since), "
                        + "(SELECT max(s.updated_at) FROM shops s WHERE s.type = 'SALON' AND s.pincode = a.pincode "
                        + "AND s.updated_at > a.since)) AS at "
                        + "FROM unnest(?::varchar[], ?::timestamptz[]) AS a (pincode, since) ORDER BY a.pincode",
                ps -> {
                    ps.setArray(1, texts(ps.getConnection(), pins));
                    ps.setArray(2, ps.getConnection().createArrayOf("timestamptz", times));
                },
                rs -> {
                    Timestamp at = rs.getTimestamp("at");
                    if (at != null) {
                        out.put(rs.getString("pincode"), at.toInstant());
                    }
                });
        return out;
    }

    /** The pincodes this person covers as a field agent. */
    public List<String> coveredBy(UUID userId) {
        return jdbc.queryForList("SELECT pincode FROM admin_pincodes WHERE user_id = ? ORDER BY pincode", String.class,
                userId);
    }

    // ---- Mapbox requests this month -------------------------------------------------------------------------------

    /**
     * Counts {@code n} requests for this month if that keeps the month at or under {@code cap}; false (nothing counted)
     * if it wouldn't. One statement: requests counted at the same time on several servers never pass the cap.
     */
    public boolean reserve(String month, int n, long cap) {
        Long after = jdbc.query("INSERT INTO mapbox_usage (month, requests) SELECT ?, ? WHERE ? <= ? "
                        + "ON CONFLICT (month) DO UPDATE SET requests = mapbox_usage.requests + EXCLUDED.requests "
                        + "WHERE mapbox_usage.requests + EXCLUDED.requests <= ? RETURNING requests",
                rs -> rs.next() ? rs.getLong(1) : null, month, n, (long) n, cap, cap);
        return after != null;
    }

    public long used(String month) {
        List<Long> r = jdbc.queryForList("SELECT requests FROM mapbox_usage WHERE month = ?", Long.class, month);
        return r.isEmpty() ? 0 : r.getFirst();
    }

    // ---- Helpers --------------------------------------------------------------------------------------------------

    private static Array texts(java.sql.Connection c, Collection<String> values) throws java.sql.SQLException {
        return c.createArrayOf("varchar", values.toArray());
    }

    private static Double dbl(BigDecimal d) {
        return d == null ? null : d.doubleValue();
    }

    private static LocalDate day(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    private static Date date(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static String clip(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }
}
