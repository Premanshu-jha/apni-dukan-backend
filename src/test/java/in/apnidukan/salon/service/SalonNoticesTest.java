package in.apnidukan.salon.service;

import in.apnidukan.common.context.ShopDirectory.ShopCard;
import in.apnidukan.common.context.ShopMemberships;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.notify.Notifications;
import in.apnidukan.common.notify.Notifications.Notice;
import in.apnidukan.salon.domain.Appointment;
import in.apnidukan.salon.domain.AppointmentKind;
import in.apnidukan.salon.domain.AppointmentStatus;
import in.apnidukan.salon.domain.BookedService;
import in.apnidukan.salon.domain.SalonStaff;
import in.apnidukan.salon.repo.SalonStaffRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A new booking's messages: names and the barber's sign-in come from the setup in memory, everything is queued in one
 * batch, and the owner gets the booking by push only unless app.notifications.owner-booking-sms is on (the barber
 * keeps his SMS).
 */
class SalonNoticesTest {

    private static final Instant NOW = Instant.parse("2026-11-10T04:30:00Z");
    private final UUID salon = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final UUID helper = UUID.randomUUID();
    private final UUID barberLogin = UUID.randomUUID();
    private final UUID raju = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();
    private final Notifications notifications = mock(Notifications.class);
    private final ShopMemberships memberships = mock(ShopMemberships.class);
    private final SalonStaffRepository staff = mock(SalonStaffRepository.class);
    private final UserDirectory users = mock(UserDirectory.class);

    private List<Notice> booked(boolean ownerSms) {
        return booked(ownerSms, barberLogin);
    }

    /** @param rajuLogin Raju's sign-in: his own, or the owner's when the owner is Raju */
    private List<Notice> booked(boolean ownerSms, UUID rajuLogin) {
        when(memberships.members(salon)).thenReturn(rajuLogin.equals(owner)
                ? List.of(new ShopMemberships.Member(owner, "OWNER"), new ShopMemberships.Member(helper, "HELPER"))
                : List.of(new ShopMemberships.Member(owner, "OWNER"), new ShopMemberships.Member(helper, "HELPER"),
                        new ShopMemberships.Member(barberLogin, "BARBER")));
        when(users.find(any())).thenReturn(Map.of(customer, new UserDirectory.Person(customer, "Ravi", "9000000101")));
        SalonNotices notices = new SalonNotices(notifications, memberships, staff, users, ownerSms);
        Instant start = NOW.plusSeconds(3 * 3600);
        Appointment a = new Appointment(UUID.randomUUID(), salon, raju, AppointmentKind.BOOKING, null, null, customer,
                "AB12CDE", null, "4827", start, start.plusSeconds(1800), AppointmentStatus.CONFIRMED, null, null, null,
                customer, UUID.randomUUID(), NOW, NOW);
        ShopCard card = new ShopCard(salon, "Sharma Hair", "Shop 4", "Kondapur", "Hyderabad", "500084",
                new BigDecimal("17.465000"), new BigDecimal("78.364000"), "APPROVED", "SALON", null, null, null);
        SalonSetups.Setup setup = new SalonSetups.Setup(1, List.of(new SalonSetups.Barber(raju, "Raju", NOW,
                Set.of(), List.of(), rajuLogin)), List.of(), 0, 7, List.of());
        notices.booked(a, card, List.of(new BookedService(UUID.randomUUID(), "Haircut", 30, 15000, false)), setup,
                NOW);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Notice>> queued = ArgumentCaptor.forClass(List.class);
        verify(notifications, times(1)).queueAll(queued.capture());   // one batch
        verify(notifications, never()).queue(any());
        verify(staff, never()).findById(any());   // the barber's name and sign-in came from the setup
        verify(staff, never()).findAllById(any());
        return queued.getValue();
    }

    private static Map<String, String> smsOf(List<Notice> notices, UUID person) {
        return notices.stream().filter(n -> n.userId().equals(person) && n.type().equals("SALON_NEW_BOOKING"))
                .findFirst().orElseThrow().smsVariables();
    }

    @Test
    void theOwnerGetsTheBookingByPushOnlyAndTheBarberAlsoBySms() {
        List<Notice> notices = booked(false);
        assertThat(notices).extracting(Notice::type).containsExactlyInAnyOrder("BOOKING_CONFIRMED", "BOOKING_REMINDER",
                "SALON_NEW_BOOKING", "SALON_NEW_BOOKING", "SALON_NEW_BOOKING");
        assertThat(smsOf(notices, owner)).isEmpty();
        assertThat(smsOf(notices, helper)).isEmpty();
        assertThat(smsOf(notices, barberLogin)).containsEntry("BARBER", "Raju").containsEntry("CUSTOMER", "Ravi");
    }

    @Test
    void withTheSettingOnTheOwnerGetsTheSmsToo() {
        List<Notice> notices = booked(true);
        assertThat(smsOf(notices, owner)).containsEntry("BOOKING", "AB12CDE");
        assertThat(smsOf(notices, helper)).isEmpty();
    }

    @Test
    void anOwnerWhoIsAlsoTheBarberIsToldOnceAndFollowsTheOwnersSmsSetting() {
        List<Notice> off = booked(false, owner);
        assertThat(off).filteredOn(n -> n.userId().equals(owner)).hasSize(1);
        assertThat(smsOf(off, owner)).isEmpty();
        org.mockito.Mockito.reset(notifications);
        List<Notice> on = booked(true, owner);
        assertThat(on).filteredOn(n -> n.userId().equals(owner)).hasSize(1);
        assertThat(smsOf(on, owner)).containsEntry("BOOKING", "AB12CDE");
    }

    @Test
    void aCancelledWalkInInTheOwnersOwnChairTellsHimOnce() {
        when(memberships.members(salon)).thenReturn(List.of(new ShopMemberships.Member(owner, "OWNER"),
                new ShopMemberships.Member(helper, "HELPER")));
        SalonStaff me = new SalonStaff(salon, "Sharma", NOW);
        me.linkLogin(owner, NOW);
        when(staff.findById(raju)).thenReturn(java.util.Optional.of(me));
        when(staff.findAllById(any())).thenReturn(List.of(me));
        SalonNotices notices = new SalonNotices(notifications, memberships, staff, users, false);
        Instant start = NOW.minusSeconds(600);
        Appointment a = new Appointment(UUID.randomUUID(), salon, raju, AppointmentKind.WALK_IN, null, null, null,
                null, "Guest", null, start, start.plusSeconds(1800), AppointmentStatus.CONFIRMED, null, null, null,
                helper, UUID.randomUUID(), NOW, NOW);
        notices.walkInCancelled(a, "added by mistake", helper, NOW);
        ArgumentCaptor<Notice> queued = ArgumentCaptor.forClass(Notice.class);
        verify(notifications, times(1)).queue(queued.capture());
        assertThat(queued.getValue().userId()).isEqualTo(owner);
        assertThat(queued.getValue().type()).isEqualTo("SALON_WALK_IN_CANCELLED");
    }
}
