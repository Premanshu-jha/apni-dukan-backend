package in.apnidukan.shops.service;

import in.apnidukan.common.context.ProfileFacts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** The business a person is in (one per person), for a support ticket: their role, its name, code and status. */
@Component
public class ShopProfileFacts implements ProfileFacts {

    private final JdbcTemplate jdbc;

    public ShopProfileFacts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Section about(UUID userId) {
        List<String> lines = jdbc.query("SELECT m.role, s.name, s.shop_code, s.type, s.status, s.area, s.city "
                        + "FROM shop_members m JOIN shops s ON s.id = m.shop_id WHERE m.user_id = ?",
                (rs, i) -> role(rs.getString("role")) + " of " + rs.getString("name") + " (code "
                        + rs.getLong("shop_code") + ", " + rs.getString("type").toLowerCase(Locale.ROOT) + ", "
                        + rs.getString("area") + ", " + rs.getString("city") + "): "
                        + status(rs.getString("status")), userId);
        return lines.isEmpty() ? null : new Section(20, "Business", lines);
    }

    private static String role(String role) {
        return switch (role) {
            case "OWNER" -> "Owner";
            case "BARBER" -> "Barber";
            default -> "Helper";
        };
    }

    private static String status(String status) {
        return switch (status) {
            case "DRAFT" -> "registration not finished";
            case "PENDING_VISIT" -> "waiting for the field team's visit";
            case "APPROVED" -> "approved";
            case "SUSPENDED" -> "suspended";
            case "CLOSED" -> "closed";
            default -> status.toLowerCase(Locale.ROOT);
        };
    }
}
