package in.apnidukan.salon.service;

import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.context.ShopDirectory.ShopCard;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.text.TextCleaner;
import in.apnidukan.common.text.TextCleaner.TextField;
import in.apnidukan.salon.repo.AppointmentStore;
import in.apnidukan.salon.repo.StrikeStore;
import in.apnidukan.salon.repo.StrikeStore.Strike;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Apni Dukan support (users.admin) helping a customer who says a strike was unfair: see the customer's strikes
 * and whether booking is blocked, and take one back (logged in the booking's history with the reason).
 * Like the shop pages, the admin flag is checked again in the database (404 for anyone else).
 */
@Service
public class SalonSupportService {

    static final TextField REASON = new TextField("reason", 2, 200);

    private final UserDirectory users;
    private final StrikeStore strikes;
    private final AppointmentStore appointments;
    private final ShopDirectory shops;
    private final Clock clock;

    public SalonSupportService(UserDirectory users, StrikeStore strikes, AppointmentStore appointments,
                               ShopDirectory shops, Clock clock) {
        this.users = users;
        this.strikes = strikes;
        this.appointments = appointments;
        this.shops = shops;
        this.clock = clock;
    }

    /** @param blockedUntil while booking online is blocked for the customer: until when; else null */
    public record CustomerStrikes(UUID customerId, Instant blockedUntil, List<StrikeView> strikes) {

        public CustomerStrikes {
            strikes = List.copyOf(strikes);
        }

        @Override
        public List<StrikeView> strikes() {
            return List.copyOf(strikes);   // already immutable: the same list, no copying
        }
    }

    /** @param salonName the salon of the booking the strike came from */
    public record StrikeView(UUID appointmentId, String bookingNo, String salonName, String kind, Instant struckAt) {
    }

    /** A customer's strikes, by phone. 404 CUSTOMER_NOT_FOUND if nobody has that phone. */
    @Transactional(readOnly = true)
    public CustomerStrikes strikes(UUID adminId, String phone) {
        requireAdmin(adminId);
        UUID customer = users.findByPhone(phone).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "CUSTOMER_NOT_FOUND", "Nobody has an account with this phone."));
        Instant now = clock.instant();
        List<StrikeView> list = strikes.forCustomer(customer).stream().map(this::view).toList();
        Optional<Instant> until = BookingService.blockedUntil(list.stream().map(StrikeView::struckAt).toList(), now);
        return new CustomerStrikes(customer, until.orElse(null), list);
    }

    /** Takes back the strike from this booking. 404 STRIKE_NOT_FOUND if it has none. */
    @Transactional
    public void removeStrike(UUID adminId, UUID appointmentId, String reasonInput) {
        requireAdmin(adminId);
        String reason = TextCleaner.clean(REASON, reasonInput);
        if (!strikes.remove(appointmentId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "STRIKE_NOT_FOUND", "This booking has no strike.");
        }
        appointments.event(appointmentId, clock.instant(), adminId, "STRIKE_REMOVED", reason);
    }

    private StrikeView view(Strike s) {
        String salon = shops.find(s.shopId()).map(ShopCard::name).orElse(null);
        return new StrikeView(s.appointmentId(), s.bookingNo(), salon, s.kind().name(), s.struckAt());
    }

    private void requireAdmin(UUID userId) {
        if (!users.isAdmin(userId)) {
            throw ApiException.notFound("Page");
        }
    }
}
