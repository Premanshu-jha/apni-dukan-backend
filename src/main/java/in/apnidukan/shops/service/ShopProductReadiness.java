package in.apnidukan.shops.service;

import in.apnidukan.common.context.ProductReadiness;
import in.apnidukan.shops.domain.ShopEventType;
import in.apnidukan.shops.repo.ShopReviewStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A kirana's products set up (see {@link ProductReadiness}). The shared team lock first, then the shop's row, as every
 * change that may assign a business does (ShopAssignments), so an agent's pincodes changing at the same moment waits.
 */
@Service
public class ShopProductReadiness implements ProductReadiness {

    private final JdbcTemplate jdbc;
    private final ShopAssignments assignments;
    private final ShopReviewStore review;
    private final DashboardCounts dashboardCounts;
    private final Clock clock;

    public ShopProductReadiness(JdbcTemplate jdbc, ShopAssignments assignments, ShopReviewStore review,
                                DashboardCounts dashboardCounts, Clock clock) {
        this.jdbc = jdbc;
        this.assignments = assignments;
        this.review = review;
        this.dashboardCounts = dashboardCounts;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> productsReadyAt(UUID shopId) {
        List<Timestamp> at = jdbc.queryForList("SELECT products_ready_at FROM shops WHERE id = ?", Timestamp.class,
                shopId);
        return at.isEmpty() || at.get(0) == null ? Optional.empty() : Optional.of(at.get(0).toInstant());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean everApproved(UUID shopId) {
        return Boolean.TRUE.equals(jdbc.query("SELECT ever_approved FROM shops WHERE id = ?",
                (rs, i) -> rs.getBoolean(1), shopId).stream().findFirst().orElse(false));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Instant markProductsReady(UUID shopId, UUID by, long products) {
        assignments.lockForAssignment();
        List<Object[]> was = jdbc.query("SELECT products_ready_at, ever_approved FROM shops WHERE id = ? FOR UPDATE",
                (rs, i) -> new Object[] {rs.getTimestamp(1), rs.getBoolean(2)}, shopId);
        if (was.isEmpty()) {
            throw new IllegalStateException("No shop " + shopId);
        }
        if (was.get(0)[0] instanceof Timestamp at) {
            return at.toInstant();   // said before: the visit was booked then
        }
        Instant now = clock.instant();
        jdbc.update("UPDATE shops SET products_ready_at = ? WHERE id = ?", Timestamp.from(now), shopId);
        review.addEvent(shopId, now, by, false, ShopEventType.NOTE, null, "Owner finished adding the shop's products ("
                + products + ", each counted): the visit can be booked", List.of());
        // Submitted and waiting for its first visit: booked now (a draft is booked when it is submitted). A shop
        // approved before was never held back: its visits go on as they were.
        if (!Boolean.TRUE.equals(was.get(0)[1])) {
            assignments.startedWaiting(shopId, "SUBMITTED");
        }
        dashboardCounts.clearAfterCommit();
        return now;
    }
}
