package in.apnidukan.salon.service;

import in.apnidukan.common.context.ShopChangeListener;
import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.salon.domain.SalonStaff;
import in.apnidukan.salon.repo.AppointmentStore;
import in.apnidukan.salon.repo.SalonAccessStore;
import in.apnidukan.salon.repo.SalonClosingStore;
import in.apnidukan.salon.repo.SalonStaffRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * What the salon does when its shop changes:
 * <ul>
 *   <li>moved (new address or map pin): pending booking reminders are rewritten with the new place;</li>
 *   <li>suspended by Apni Dukan: upcoming online bookings are cancelled and each customer is told;</li>
 *   <li>someone left or was removed: their walk-in access goes, and a barber's sign-in is unlinked (his name,
 *       hours and bookings stay with the salon);</li>
 *   <li>closed by its owner: like a suspension, upcoming online bookings are cancelled and each customer is told,
 *       in the background (SalonClosingJob);</li>
 *   <li>a barber's sign-in invitation accepted: his profile is linked to the new member;</li>
 *   <li>taken over by one of its helpers or barbers: like leaving, without losing the salon's messages.</li>
 * </ul>
 */
@Component
class SalonShopChanges implements ShopChangeListener {

    /** What customers are told; the field team's own reason stays between them and the owner. */
    static final String SUSPENDED_REASON = "The salon isn't taking online bookings for now.";
    /** What customers are told when the owner closes the salon for good. */
    static final String CLOSED_REASON = "The salon has closed.";
    /** The appointment history's note for those cancellations. */
    static final String CLOSED_DETAIL = "salon closed by its owner";
    /** The history's note for a suspension's cancellations. */
    static final String SUSPENDED_DETAIL = "salon suspended by Apni Dukan";

    private final AppointmentStore appointments;
    private final ShopDirectory shops;
    private final SalonNotices notices;
    private final SalonAccessStore access;
    private final SalonStaffRepository staff;
    private final SalonClosingStore closings;
    private final Clock clock;

    SalonShopChanges(AppointmentStore appointments, ShopDirectory shops, SalonNotices notices,
                     SalonAccessStore access, SalonStaffRepository staff, SalonClosingStore closings, Clock clock) {
        this.closings = closings;
        this.appointments = appointments;
        this.shops = shops;
        this.notices = notices;
        this.access = access;
        this.staff = staff;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void memberRemoved(UUID shopId, UUID userId) {
        access.revokeWalkIns(shopId, userId);
        notices.forgetSalonMessages(userId);
        unlinkBarber(shopId, userId);
    }

    /** Still in the salon, now as its owner: his messages stay; walk-in access and a barber's sign-in go. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void becameOwner(UUID shopId, UUID userId) {
        access.revokeWalkIns(shopId, userId);
        unlinkBarber(shopId, userId);
    }

    /**
     * Everyone but the owner left at once (the salon closed): walk-in access and the salon's messages of all of them in
     * one statement each, and the barbers' sign-ins unlinked (one read for all; each profile saved, at most a few
     * dozen).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void membersRemoved(UUID shopId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        access.revokeWalkIns(shopId, userIds);
        notices.forgetSalonMessages(userIds);
        Instant now = clock.instant();
        for (SalonStaff barber : staff.findByShopIdAndUserIdIn(shopId, userIds)) {
            barber.unlinkLogin(now);
            staff.saveAndFlush(barber);
        }
    }

    private void unlinkBarber(UUID shopId, UUID userId) {
        staff.findByUserId(userId).filter(b -> b.getShopId().equals(shopId)).ifPresent(barber -> {
            barber.unlinkLogin(clock.instant());
            staff.saveAndFlush(barber);
        });
    }

    /**
     * Runs inside the closing's transaction, after the shop's row was locked (and saved CLOSED: no booking can be
     * made from now on): the salon is queued for SalonClosingJob, which cancels every upcoming online booking a slice
     * at a time and tells each customer. A salon with thousands of bookings ahead still closes in one short request.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void closed(UUID shopId) {
        Instant now = clock.instant();
        closings.queue(shopId, CLOSED_REASON, CLOSED_DETAIL, now.minus(SalonDayService.LATE_ALLOWANCE), now);
    }

    /** The owner is cancelling a barber's sign-in invitation: his profile's lock first (see ShopInvitesService). */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void barberInviteChanging(UUID shopId, UUID staffId) {
        staff.lockByIdAndShopId(staffId, shopId);
    }

    /**
     * Someone is accepting the invitation to sign in as this barber: his profile, locked, must still be switched on
     * and without a sign-in (409 INVITE_GONE otherwise: the owner switched him off or gave him another one).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void barberJoining(UUID shopId, UUID staffId) {
        boolean open = staff.lockByIdAndShopId(staffId, shopId).filter(b -> b.isActive() && b.getUserId() == null)
                .isPresent();
        if (!open) {
            throw new ApiException(HttpStatus.CONFLICT, "INVITE_GONE", "This invitation isn't open any more.",
                    Map.of("status", "STAFF_CHANGED"));
        }
    }

    /** They joined as this barber: his profile is linked to their sign-in (it is locked since barberJoining). */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void barberJoined(UUID shopId, UUID staffId, UUID userId) {
        SalonStaff barber = staff.lockByIdAndShopId(staffId, shopId).orElseThrow();
        barber.linkLogin(userId, clock.instant());
        staff.saveAndFlush(barber);
    }

    /**
     * Runs inside the shop change's transaction: if that rolls back, the old reminders stay. Every pending reminder of
     * the salon's bookings still to come is written again with the new place, in one statement (the bookings are never
     * loaded: a busy salon can have thousands ahead).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void locationChanged(UUID shopId) {
        shops.find(shopId).ifPresent(card -> appointments.rewriteReminders(shopId, clock.instant(),
                SalonDayService.LATE_ALLOWANCE, notices.reminderText(card)));
    }

    /**
     * Runs inside the suspension's transaction, after the shop's row was locked (and saved SUSPENDED: no booking or
     * move can be made from now on). As for a closing, the salon is queued for SalonClosingJob, which cancels its
     * upcoming online bookings a slice at a time (also those whose customer may still be on the way, held 15 minutes:
     * left open they'd become no-shows) and tells each customer. A big salon's suspension stays one short request.
     * Reinstated before the job is done: the job stops, the bookings not cancelled yet stay.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void suspended(UUID shopId) {
        Instant now = clock.instant();
        closings.queue(shopId, SUSPENDED_REASON, SUSPENDED_DETAIL, now.minus(SalonDayService.LATE_ALLOWANCE), now);
    }
}
