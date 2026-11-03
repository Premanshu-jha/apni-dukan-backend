package in.apnidukan.salon.service;

import in.apnidukan.common.context.ShopMemberships;
import in.apnidukan.common.context.ShopMemberships.Membership;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.salon.domain.SalonStaff;
import in.apnidukan.salon.repo.SalonAccessStore;
import in.apnidukan.salon.repo.SalonStaffRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Who is asking, for the salon's daily-work screens. Looked up in the database on every call, never taken from
 * the sign-in token or the request:
 * <ul>
 *   <li>OWNER and HELPER see every barber's day; BARBER sees his own column only.</li>
 *   <li>Walk-ins: only the owner, and the people he gave walk-in access to (a helper or a barber); they see every
 *       barber's day, because choosing the barber is the point.</li>
 * </ul>
 */
@Component
public class SalonAccess {

    private final ShopMemberships memberships;
    private final SalonStaffRepository staff;
    private final SalonAccessStore access;

    public SalonAccess(ShopMemberships memberships, SalonStaffRepository staff, SalonAccessStore access) {
        this.memberships = memberships;
        this.staff = staff;
        this.access = access;
    }

    /**
     * @param staffId  his barber row when the caller is a BARBER, else null
     * @param walkIns  true for the owner and anyone he gave walk-in access to
     */
    public record Caller(UUID userId, UUID shopId, String role, UUID staffId, boolean walkIns) {

        public boolean isOwner() {
            return "OWNER".equals(role);
        }

        public boolean ownerOrHelper() {
            return "OWNER".equals(role) || "HELPER".equals(role);
        }

        public boolean isBarber() {
            return "BARBER".equals(role);
        }

        /** Sees every barber's day (owner, helper, or anyone with walk-in access). */
        public boolean seesAll() {
            return ownerOrHelper() || walkIns;
        }

        /** May act on this barber's appointments (start, no-show, breaks): any for owner/helper, else his own. */
        public boolean actsFor(UUID barberId) {
            return ownerOrHelper() || barberId.equals(staffId);
        }
    }

    /** The caller in his salon. 404 NO_SHOP without a shop; 409 NOT_A_SALON for a kirana. */
    public Caller caller(UUID userId) {
        Membership m = memberships.find(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_SHOP",
                "You haven't registered or joined a shop yet."));
        if (!"SALON".equals(m.shopType())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_A_SALON", "This is only for salons.");
        }
        UUID staffId = null;
        if ("BARBER".equals(m.role())) {
            SalonStaff me = staff.findByUserId(userId)
                    .filter(s -> s.getShopId().equals(m.shopId()) && s.isActive())
                    .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_ALLOWED",
                            "Your barber profile isn't active in this salon."));
            staffId = me.getId();
        }
        boolean walkIns = "OWNER".equals(m.role()) || access.hasWalkInAccess(m.shopId(), userId);
        return new Caller(userId, m.shopId(), m.role(), staffId, walkIns);
    }

    /** 403 OWNER_ONLY unless the caller is the owner. */
    public Caller owner(UUID userId) {
        Caller c = caller(userId);
        if (!c.isOwner()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the salon owner can do this.");
        }
        return c;
    }

    /** 403 NOT_ALLOWED unless the caller is the owner or a helper. */
    public Caller ownerOrHelper(UUID userId) {
        Caller c = caller(userId);
        if (!c.ownerOrHelper()) {
            throw notAllowed();
        }
        return c;
    }

    /** 403 WALK_INS_NOT_ALLOWED unless the caller is the owner or was given walk-in access. */
    public Caller walkIns(UUID userId) {
        Caller c = caller(userId);
        if (!c.walkIns()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "WALK_INS_NOT_ALLOWED",
                    "Only the owner, or someone he gave walk-in access to, can add walk-ins.");
        }
        return c;
    }

    static ApiException notAllowed() {
        return new ApiException(HttpStatus.FORBIDDEN, "NOT_ALLOWED", "You can't do this in this salon.");
    }
}
