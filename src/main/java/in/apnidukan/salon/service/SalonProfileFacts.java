package in.apnidukan.salon.service;

import in.apnidukan.common.context.ProfileFacts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** A customer's latest salon bookings, for a support ticket: number, salon, when and what happened. */
@Component
public class SalonProfileFacts implements ProfileFacts {

    /** Latest bookings shown. */
    static final int LATEST = 5;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)
            .withZone(ZoneId.of("Asia/Kolkata"));

    private final JdbcTemplate jdbc;

    public SalonProfileFacts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Section about(UUID userId) {
        List<String> lines = jdbc.query("SELECT a.booking_no, s.name, a.starts_at, a.status FROM appointments a "
                        + "JOIN shops s ON s.id = a.shop_id WHERE a.customer_id = ? AND a.kind = 'BOOKING' "
                        + "ORDER BY a.starts_at DESC, a.id LIMIT " + LATEST,
                (rs, i) -> rs.getString("booking_no") + " at " + rs.getString("name") + ", "
                        + WHEN.format(rs.getTimestamp("starts_at").toInstant()) + ": " + status(rs.getString("status")),
                userId);
        return lines.isEmpty() ? null : new Section(30, "Latest bookings", lines);
    }

    private static String status(String status) {
        return switch (status) {
            case "CONFIRMED" -> "booked";
            case "IN_SERVICE" -> "in the chair";
            case "COMPLETED" -> "done";
            case "CANCELLED_BY_CUSTOMER" -> "cancelled by the customer";
            case "CANCELLED_BY_SALON" -> "cancelled by the salon";
            case "NO_SHOW" -> "no-show";
            default -> status.toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }
}
