package in.apnidukan.salon.service;

import in.apnidukan.common.context.ShopMemberships;
import in.apnidukan.common.context.ShopMemberships.Membership;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.context.UserDirectory.Person;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.files.FileRefs;
import in.apnidukan.common.security.SessionRevocations;
import in.apnidukan.common.text.TextCleaner.TextField;
import in.apnidukan.salon.api.SalonRequests.CreateService;
import in.apnidukan.salon.api.SalonRequests.CreateStaff;
import in.apnidukan.salon.api.SalonRequests.SalonDayIn;
import in.apnidukan.salon.api.SalonRequests.SetSalonHours;
import in.apnidukan.salon.api.SalonRequests.SetHours;
import in.apnidukan.salon.api.SalonRequests.SetServices;
import in.apnidukan.salon.api.SalonRequests.ShiftIn;
import in.apnidukan.salon.api.SalonRequests.UpdateService;
import in.apnidukan.salon.api.SalonRequests.UpdateSettings;
import in.apnidukan.salon.api.SalonRequests.UpdateStaff;
import in.apnidukan.salon.api.BookingResponses;
import in.apnidukan.salon.api.SalonResponses.CatalogService;
import in.apnidukan.salon.api.SalonResponses.HoursResponse;
import in.apnidukan.salon.api.SalonResponses.ServiceResponse;
import in.apnidukan.salon.api.SalonResponses.SettingsResponse;
import in.apnidukan.salon.api.SalonResponses.StaffOutside;
import in.apnidukan.salon.api.SalonResponses.StaffResponse;
import in.apnidukan.salon.domain.Appointment;
import in.apnidukan.salon.domain.AppointmentKind;
import in.apnidukan.salon.domain.AppointmentStatus;
import in.apnidukan.salon.domain.Availability;
import in.apnidukan.salon.domain.BlockType;
import in.apnidukan.salon.domain.BookedService;
import in.apnidukan.salon.domain.SalonOpenDay;
import in.apnidukan.salon.domain.SalonServiceItem;
import in.apnidukan.salon.domain.SalonSettings;
import in.apnidukan.salon.domain.SalonStaff;
import in.apnidukan.salon.domain.SalonTime;
import in.apnidukan.salon.domain.ServiceDetails;
import in.apnidukan.salon.domain.Shift;
import in.apnidukan.salon.domain.StaffShift;
import in.apnidukan.salon.domain.StaffSkill;
import in.apnidukan.salon.repo.AppointmentStore;
import in.apnidukan.salon.repo.SalonAccessStore;
import in.apnidukan.salon.repo.SalonOpenDayRepository;
import in.apnidukan.salon.repo.SalonServiceItemRepository;
import in.apnidukan.salon.repo.SalonSettingsRepository;
import in.apnidukan.salon.repo.SalonStaffRepository;
import in.apnidukan.salon.repo.StaffShiftRepository;
import in.apnidukan.salon.repo.StaffSkillRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static in.apnidukan.common.error.DatabaseErrors.violated;
import static in.apnidukan.common.text.TextCleaner.clean;

/**
 * Salon setup: services, barbers, which services each barber does, their weekly hours, and booking settings.
 *
 * Who may do what: anyone in the salon (owner, helper or barber) can read; only the owner can change anything. Both the
 * salon and the role are looked up in the database for every call (ShopMemberships), never taken from the token or
 * the request. Every service and barber is found by its id AND the caller's salon, so one salon can't see or change
 * another's, not even by guessing ids (it gets 404).
 */
@Service
public class SalonSetupService {

    /** Each text field's allowed length after cleaning; max is the column size in V4. */
    static final TextField SERVICE_NAME = new TextField("name", 2, 60);
    static final TextField STAFF_NAME = new TextField("name", 2, 60);

    /** Limits that keep one salon's lists a sensible size (and the app's screens fast). */
    static final int MAX_SERVICES = 100;
    static final int MAX_STAFF = 50;

    /** Unique key: two active services in one salon can't share a name (named in V4). */
    static final String SERVICE_NAME_TAKEN = "ux_salon_services_name";
    /** Primary key of salon_settings: hit when two first saves happen at the same moment. */
    static final String ONE_SETTINGS_ROW = "salon_settings_pkey";

    private static final String SALON = "SALON";
    private static final String OWNER = "OWNER";

    private final SalonServiceItemRepository services;
    private final SalonStaffRepository staff;
    private final StaffSkillRepository skills;
    private final StaffShiftRepository shifts;
    private final SalonSettingsRepository settings;
    private final ShopMemberships memberships;
    private final UserDirectory users;
    private final SessionRevocations sessions;
    private final SalonAccessStore salonAccess;
    private final FileRefs files;
    private final AppointmentStore appointments;
    private final SalonNotices notices;
    private final SalonOpenDayRepository openDays;
    private final Clock clock;

    public SalonSetupService(SalonServiceItemRepository services, SalonStaffRepository staff,
                             StaffSkillRepository skills, StaffShiftRepository shifts,
                             SalonSettingsRepository settings, ShopMemberships memberships, UserDirectory users,
                             SessionRevocations sessions, SalonAccessStore salonAccess, FileRefs files,
                             AppointmentStore appointments, SalonNotices notices, SalonOpenDayRepository openDays,
                             Clock clock) {
        this.openDays = openDays;
        this.services = services;
        this.staff = staff;
        this.skills = skills;
        this.shifts = shifts;
        this.settings = settings;
        this.memberships = memberships;
        this.users = users;
        this.sessions = sessions;
        this.salonAccess = salonAccess;
        this.files = files;
        this.appointments = appointments;
        this.notices = notices;
        this.clock = clock;
    }

    // ---------- services ----------

    @Transactional(readOnly = true)
    public List<ServiceResponse> services(UUID userId) {
        Membership salon = salon(userId);
        return services.findByShopIdOrderBySortOrderAscNameAsc(salon.shopId()).stream()
                .map(ServiceResponse::from)
                .toList();
    }

    @Transactional
    public ServiceResponse addService(UUID userId, CreateService in) {
        Membership salon = owner(userId);
        requireRoomForService(salon, 1);
        SalonServiceItem item = new SalonServiceItem(salon.shopId(), details(in, nextSortOrder(salon)), now());
        saveService(item);
        return ServiceResponse.from(item);
    }

    /**
     * Adds several services at once, all or none (registration: the ones ticked from the catalog plus any typed in).
     * Each is checked as for one; two with the same name in the list (capitals ignored) are 400 naming the second,
     * one whose name an active service already has is 409 SERVICE_NAME_TAKEN, and the limit counts them all.
     */
    @Transactional
    public List<ServiceResponse> addServices(UUID userId, List<CreateService> in) {
        Membership salon = owner(userId);
        List<ServiceDetails> all = new ArrayList<>();
        Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < in.size(); i++) {
            ServiceDetails d = details(in.get(i), -1);
            if (!names.add(d.name().toLowerCase(Locale.ROOT))) {
                throw invalid("services[" + i + "].name", "is in the list twice");
            }
            all.add(d);
        }
        requireRoomForService(salon, all.size());   // takes the salon's lock: the places below can't be taken meanwhile
        int next = nextSortOrder(salon);
        for (int i = 0; i < all.size(); i++) {
            ServiceDetails d = all.get(i);
            if (in.get(i).sortOrder() == null) {   // without one: after the salon's services, in the order sent
                all.set(i, new ServiceDetails(d.name(), d.durationMin(), d.pricePaise(), d.priceIsFrom(), d.active(),
                        next + i, d.returnAfterDays()));
            }
        }
        Instant now = now();
        List<ServiceResponse> out = new ArrayList<>();
        for (ServiceDetails d : all) {
            SalonServiceItem item = new SalonServiceItem(salon.shopId(), d, now);
            saveService(item);
            out.add(ServiceResponse.from(item));
        }
        return out;
    }

    /** The common services to tick at registration, with suggested times and come-back intervals. */
    public List<CatalogService> catalog(UUID userId) {
        salon(userId);
        return ServiceCatalog.SERVICES;
    }

    /** After the salon's services (the order customers and the setup list see them in). */
    private int nextSortOrder(Membership salon) {
        return services.findByShopIdOrderBySortOrderAscNameAsc(salon.shopId()).stream().mapToInt(s -> s.details().sortOrder()).max()
                .orElse(-1) + 1;
    }

    /** @param fallbackOrder the place when the request gives none */
    private static ServiceDetails details(CreateService in, int fallbackOrder) {
        return new ServiceDetails(clean(SERVICE_NAME, in.name()), duration(in.durationMinutes()),
                in.pricePaise(), Boolean.TRUE.equals(in.priceIsFrom()), true,
                in.sortOrder() == null ? fallbackOrder : in.sortOrder(),
                in.returnAfterDays() == null ? ServiceDetails.DEFAULT_RETURN_AFTER_DAYS : in.returnAfterDays());
    }

    /** Edit a service. A stale version (someone saved in between) is refused rather than overwritten. */
    @Transactional
    public ServiceResponse updateService(UUID userId, UUID serviceId, UpdateService in) {
        Membership salon = owner(userId);
        SalonServiceItem item = services.findByIdAndShopId(serviceId, salon.shopId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SERVICE_NOT_FOUND", "No such service."));
        if (in.version() != item.getVersion()) {
            throw serviceChanged();
        }
        ServiceDetails old = item.details();
        ServiceDetails updated = new ServiceDetails(
                in.name() == null ? old.name() : clean(SERVICE_NAME, in.name()),
                in.durationMinutes() == null ? old.durationMin() : duration(in.durationMinutes()),
                in.pricePaise() == null ? old.pricePaise() : in.pricePaise(),
                in.priceIsFrom() == null ? old.priceIsFrom() : in.priceIsFrom(),
                in.active() == null ? old.active() : in.active(),
                in.sortOrder() == null ? old.sortOrder() : in.sortOrder(),
                in.returnAfterDays() == null ? old.returnAfterDays() : in.returnAfterDays());
        if (updated.equals(old)) {
            return ServiceResponse.from(item);
        }
        if (updated.active() && !old.active()) {
            requireRoomForService(salon, 1);   // switching one back on counts like adding it
        }
        item.apply(updated, now());
        saveService(item);
        return ServiceResponse.from(item);
    }

    /**
     * Active services only: switched-off ones don't count, so switching some off makes room. Counted under the salon's
     * lock, so two adds at the same moment can't both slip under the limit.
     */
    private void requireRoomForService(Membership salon, int adding) {
        appointments.lockSalonCalendar(salon.shopId());
        if (services.countByShopIdAndActiveTrue(salon.shopId()) + adding > MAX_SERVICES) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_SERVICES",
                    "A salon can have up to " + MAX_SERVICES
                            + " active services. Switch off ones you no longer offer.");
        }
    }

    /** Saves now, so the database's own rules (unique name, version) are checked here and become clear errors. */
    private void saveService(SalonServiceItem item) {
        try {
            services.saveAndFlush(item);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw serviceChanged();
        } catch (DataIntegrityViolationException e) {
            if (violated(e, SERVICE_NAME_TAKEN)) {
                throw new ApiException(HttpStatus.CONFLICT, "SERVICE_NAME_TAKEN",
                        "There is already an active service with this name.");
            }
            throw e;
        }
    }

    // ---------- barbers ----------

    /** Every barber, oldest first, each with the services he does and his week. Three queries in all. */
    @Transactional(readOnly = true)
    public List<StaffResponse> staff(UUID userId) {
        Membership salon = salon(userId);
        List<SalonStaff> barbers = staff.findByShopIdOrderByCreatedAtAscIdAsc(salon.shopId()).stream()
                .filter(b -> !b.isRemoved()).toList();   // removed for good: out of the list
        List<UUID> ids = barbers.stream().map(SalonStaff::getId).toList();
        Map<UUID, List<UUID>> servicesOf = skills.findByStaffIdIn(ids).stream()
                .collect(Collectors.groupingBy(StaffSkill::getStaffId,
                        Collectors.mapping(StaffSkill::getServiceId, Collectors.toList())));
        Map<UUID, List<Shift>> weekOf = shifts.findByStaffIdIn(ids).stream()
                .collect(Collectors.groupingBy(StaffShift::getStaffId,
                        Collectors.mapping(StaffShift::shift, Collectors.toList())));
        // Barbers' sign-in phones (and the phones invited) are for the owner only, not for other members
        boolean owner = OWNER.equals(salon.role());
        Map<UUID, Person> logins = !owner ? Map.of() : users.find(barbers.stream()
                .map(SalonStaff::getUserId).filter(Objects::nonNull).toList());
        Map<UUID, String> invited = !owner ? Map.of() : memberships.waitingBarberInvites(salon.shopId());
        return barbers.stream()
                .map(b -> StaffResponse.from(b, sorted(servicesOf.getOrDefault(b.getId(), List.of())),
                        weekOf.getOrDefault(b.getId(), List.of()),
                        b.getUserId() == null || !logins.containsKey(b.getUserId()) ? null
                                : logins.get(b.getUserId()).phone(), invited.get(b.getId())))
                .toList();
    }

    @Transactional
    public StaffResponse addStaff(UUID userId, CreateStaff in) {
        Membership salon = owner(userId);
        appointments.lockSalonCalendar(salon.shopId());   // see joinClosedDays; also makes two adds count in turn
        requireRoomForStaff(salon);
        SalonStaff barber = new SalonStaff(salon.shopId(), clean(STAFF_NAME, in.name()), now());
        staff.saveAndFlush(barber);
        joinClosedDays(salon, barber, userId);
        if (in.phone() != null && !in.phone().isBlank()) {
            link(salon, barber, in.phone(), userId);
        }
        return staffResponse(barber);
    }

    /**
     * The owner also cuts hair here: a barber profile for him, linked to his own sign-in at once (no invitation; he
     * stays the owner). He does every active service and works the salon's opening hours (both changeable later,
     * like any barber's), and gets the salon's closed days. Counts towards the barber limit (409 TOO_MANY_STAFF).
     * 409 ALREADY_A_BARBER if he has a profile already (switched off counts: he switches it back on).
     */
    @Transactional
    public StaffResponse addOwnerAsBarber(UUID userId) {
        Membership salon = owner(userId);
        appointments.lockSalonCalendar(salon.shopId());   // see joinClosedDays; also makes two adds count in turn
        // Still the owner, his membership row locked: a hand-over accepted at this moment would otherwise unlink no
        // profile (ours isn't there yet) and leave the old owner linked to one
        if (!memberships.lockOwner(salon.shopId(), userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the salon owner can change this.");
        }
        if (staff.findByUserId(userId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_A_BARBER", "You are already one of the barbers.");
        }
        requireRoomForStaff(salon);
        Person me = users.find(Set.of(userId)).get(userId);
        String name = me == null || me.name() == null || me.name().isBlank() ? "Owner" : clean(STAFF_NAME, me.name());
        Instant now = now();
        SalonStaff barber = new SalonStaff(salon.shopId(), name, now);
        barber.linkLogin(userId, now);
        staff.saveAndFlush(barber);
        skills.saveAll(services.findByShopIdAndActiveTrueOrderBySortOrderAscNameAsc(salon.shopId()).stream()
                .map(s -> new StaffSkill(salon.shopId(), barber.getId(), s.getId())).toList());
        skills.flush();
        replaceWeek(barber, new ArrayList<>(salonWeek(salon.shopId()).values()));
        joinClosedDays(salon, barber, userId);
        return staffResponse(barber);
    }

    /**
     * Invites a phone to be this barber's own sign-in (ShopMemberships.inviteBarber): the answer is the same whether
     * or not the number has an account, and his profile shows the phone as invitedPhone. He signs in with the normal
     * OTP, accepts (GET /me/invites) and then sees his own day. 409 LOGIN_EXISTS if he already has a sign-in (unlink
     * it first); a waiting invitation with another phone is replaced.
     */
    @Transactional
    public StaffResponse linkLogin(UUID userId, UUID staffId, String phone) {
        Membership salon = owner(userId);
        SalonStaff barber = lockBarber(staffId, salon);
        if (!barber.isActive()) {
            throw new ApiException(HttpStatus.CONFLICT, "STAFF_INACTIVE", "Switch the barber on first.");
        }
        link(salon, barber, phone, userId);
        return staffResponse(barber);
    }

    /**
     * Takes a barber's sign-in away (he keeps his name, hours and history), or cancels the invitation still waiting
     * for one. His membership and any walk-in access go, and every session of his ends at once. 409 OWNER_PROFILE
     * for the owner's own profile (see unlink).
     */
    @Transactional
    public StaffResponse unlinkLogin(UUID userId, UUID staffId) {
        Membership salon = owner(userId);
        SalonStaff barber = lockBarber(staffId, salon);
        unlink(salon, barber, userId, Unlink.ASKED);
        return staffResponse(barber);
    }

    /**
     * Sets the barber's photo (optional): a photo this salon uploaded to POST /files with purpose STAFF_PHOTO, or
     * null to remove it. 400 for any other file.
     */
    @Transactional
    public StaffResponse setPhoto(UUID userId, UUID staffId, UUID fileId) {
        Membership salon = owner(userId);
        SalonStaff barber = lockBarber(staffId, salon);
        if (fileId != null && files.find(fileId)
                .filter(f -> f.shopId().equals(salon.shopId()) && "STAFF_PHOTO".equals(f.purpose()) && f.isPhoto())
                .isEmpty()) {
            throw invalid("fileId", "must be a photo uploaded with purpose STAFF_PHOTO");
        }
        if (!Objects.equals(fileId, barber.getPhotoFileId())) {
            barber.setPhoto(fileId, now());
            try {
                saveStaff(barber);
            } catch (DataIntegrityViolationException deletedMeanwhile) {
                // The file was deleted at the same moment: the database's foreign key refused the link
                throw invalid("fileId", "was just deleted; upload the photo again");
            }
        }
        return staffResponse(barber);
    }

    /** True if this file is some barber's photo (then it can't be deleted). */
    @Transactional(readOnly = true)
    public boolean usesFile(UUID fileId) {
        return staff.existsByPhotoFileId(fileId);
    }

    /**
     * A barber added or switched back on gets the salon's upcoming closed days too (same closure id), so a closed
     * day stays closed for everyone. The caller holds the salon's calendar lock, so a day being closed or reopened at
     * this very moment can't be missed. He can't have bookings then (a barber with upcoming ones can't be switched
     * off, and a switched-off one can't get new ones), but he may still have a break or leave from before:
     * <ul>
     *   <li>one inside the day: removed (the whole day is closed anyway);</li>
     *   <li>one that reaches outside the day (leave over several days): 409 BLOCK_CONFLICTS, exactly as closing a
     *       day refuses it; the owner removes it, switches him on, and adds the leave again around that day. Nothing of his leave is ever removed without the owner
     *       seeing it, and every barber always gets the CLOSED row (so removing his leave later can't open the
     *       closed day).</li>
     * </ul>
     */
    private void joinClosedDays(Membership salon, SalonStaff barber, UUID by) {
        Instant now = now();
        // The closed days he isn't part of yet, and what of his is in any of them: two queries for all the days
        List<AppointmentStore.Closure> days = appointments.closuresWithout(salon.shopId(), barber.getId(), now);
        if (days.isEmpty()) {
            return;
        }
        List<Instant[]> ranges = days.stream().map(d -> new Instant[] {d.startsAt(), d.endsAt()}).toList();
        List<UUID> inside = new ArrayList<>();
        for (AppointmentStore.Hit hit : appointments.holdingIn(barber.getId(), ranges)) {   // day by day, by start
            AppointmentStore.Closure day = days.get(hit.range());
            Appointment old = hit.appointment();
            boolean within = !old.startsAt().isBefore(day.startsAt()) && !old.endsAt().isAfter(day.endsAt());
            if (old.kind() != AppointmentKind.BLOCK || !within) {
                // Blocks can't be edited and a switched-off barber can't get new ones: the owner removes this
                // one, switches him on, then adds the leave again around the closed day
                throw SalonDayService.blockConflicts(List.of(old));
            }
            if (!inside.contains(old.id())) {
                inside.add(old.id());
            }
        }
        if (appointments.removeBlocks(inside, now) != inside.size()) {
            throw SalonDayService.tryAgain();
        }
        appointments.eventAll(inside, now, by, "BLOCK_REMOVED", "the salon is closed that day");
        List<UUID> ids = days.stream().map(d -> UUID.randomUUID()).toList();
        List<UUID> saved = appointments.insertClosedDays(salon.shopId(), barber.getId(), days, ids, by, now);
        if (saved.size() != days.size()) {
            // Something of his was added in between: name the first day it is in the way of
            for (int i = 0; i < days.size(); i++) {
                if (!saved.contains(ids.get(i))) {
                    throw SalonDayService.blockConflicts(appointments.holding(List.of(barber.getId()),
                            days.get(i).startsAt(), days.get(i).endsAt(), null));
                }
            }
        }
        appointments.eventAll(saved, now, by, "CLOSED_DAY_ADDED", "barber added or switched on");
    }

    /** The caller holds the barber's lock (taken before the invitation's, the order accepting takes them in). */
    private void link(Membership salon, SalonStaff barber, String phone, UUID by) {
        if (barber.getUserId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "LOGIN_EXISTS",
                    "This barber already has a sign-in. Remove it first to use another phone.");
        }
        memberships.inviteBarber(salon.shopId(), by, barber.getId(), phone);   // he joins when he accepts
    }

    /** The salon's barber, locked; 404 if there's no such barber or he was removed. */
    private SalonStaff lockBarber(UUID staffId, Membership salon) {
        return staff.lockByIdAndShopId(staffId, salon.shopId()).filter(b -> !b.isRemoved())
                .orElseThrow(SalonSetupService::noStaff);
    }

    /**
     * Removes a barber for good (a contract barber who won't come back): switched off, his sign-in and any waiting
     * invitation go, and he is out of the salon's lists; his past bookings keep his name. Switching him off instead
     * keeps him for when he returns. Owner only. 409 STAFF_HAS_BOOKINGS while he has upcoming bookings or walk-ins.
     */
    @Transactional
    public void removeStaff(UUID userId, UUID staffId) {
        Membership salon = owner(userId);
        appointments.lockSalonCalendar(salon.shopId());   // before the barber's row lock (see joinClosedDays)
        SalonStaff barber = lockBarber(staffId, salon);
        if (appointments.upcomingForStaff(barber.getId(), now()) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "STAFF_HAS_BOOKINGS",
                    "This barber still has upcoming bookings or walk-ins. Reassign or cancel them first.");
        }
        barber.remove(now());
        saveStaff(barber);
        unlink(salon, barber, userId, Unlink.REMOVED);
    }

    /** Why a barber's sign-in is being unlinked: the owner's own profile keeps his link unless it is removed. */
    private enum Unlink { REMOVED, SWITCHED_OFF, ASKED }

    private void unlink(Membership salon, SalonStaff barber, UUID by, Unlink why) {
        memberships.cancelBarberInvite(salon.shopId(), barber.getId(), by);   // a waiting one goes too
        UUID login = barber.getUserId();
        if (login == null) {
            return;
        }
        if (memberships.find(login).filter(m -> m.shopId().equals(salon.shopId()) && OWNER.equals(m.role()))
                .isPresent()) {
            // The owner's own barber profile. He can't be invited back (it's his own number), so the link stays
            // while it's switched off and goes only when it's removed; he stays the owner, signed in, either way.
            switch (why) {
                case ASKED -> throw new ApiException(HttpStatus.CONFLICT, "OWNER_PROFILE",
                        "This is your own barber profile. Switch it off or remove it instead.");
                case SWITCHED_OFF -> { }
                case REMOVED -> {
                    barber.unlinkLogin(now());
                    saveStaff(barber);
                }
            }
            return;
        }
        memberships.removeMember(salon.shopId(), login);
        salonAccess.revokeWalkIns(salon.shopId(), login);
        notices.forgetSalonMessages(login);
        barber.unlinkLogin(now());
        saveStaff(barber);
        // After the commit: a token refreshed in between would otherwise still name the salon for 15 minutes
        sessions.revokeAllAfterCommit(login, "Barber sign-in removed");
    }

    private void saveStaff(SalonStaff barber) {
        try {
            staff.saveAndFlush(barber);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw staffChanged();
        }
    }

    /** Rename a barber, or switch him off and on. Stale version → 409 STAFF_CHANGED. */
    @Transactional
    public StaffResponse updateStaff(UUID userId, UUID staffId, UpdateStaff in) {
        Membership salon = owner(userId);
        appointments.lockSalonCalendar(salon.shopId());   // before the barber's row lock (see joinClosedDays)
        // Locked: a booking for him takes a shared lock on the same row, so switching him off and a new booking
        // run one after the other (see AppointmentStore.lockActiveBarber)
        SalonStaff barber = lockBarber(staffId, salon);
        if (in.version() != barber.getVersion()) {
            throw staffChanged();
        }
        String name = in.name() == null ? barber.getName() : clean(STAFF_NAME, in.name());
        boolean active = in.active() == null ? barber.isActive() : in.active();
        if (!name.equals(barber.getName()) || active != barber.isActive()) {
            if (active && !barber.isActive()) {
                requireRoomForStaff(salon);   // switching one back on counts like adding him
            }
            if (!active && barber.isActive() && appointments.upcomingForStaff(barber.getId(), now()) > 0) {
                throw new ApiException(HttpStatus.CONFLICT, "STAFF_HAS_BOOKINGS",
                        "This barber still has upcoming bookings or walk-ins. Reassign or cancel them first.");
            }
            boolean switchedOn = active && !barber.isActive();
            barber.apply(name, active, now());
            saveStaff(barber);
            if (!active) {
                unlink(salon, barber, userId, Unlink.SWITCHED_OFF);   // a barber who left can't sign in to the salon any more
            }
            if (switchedOn) {
                joinClosedDays(salon, barber, userId);
                fitToSalonHours(salon, barber);
            }
        }
        return staffResponse(barber);
    }

    /**
     * Replaces the list of services a barber does. Newly added ids must be active services of this salon; ids he
     * already has stay even if that service was switched off since (the app sends back the list it shows).
     * Only the differences are written, so the history shows exactly what was added and removed.
     */
    @Transactional
    public StaffResponse setServices(UUID userId, UUID staffId, SetServices in) {
        Membership salon = owner(userId);
        SalonStaff barber = lockedBarber(salon, staffId, in.version());
        Set<UUID> wanted = new LinkedHashSet<>(in.serviceIds());
        List<StaffSkill> current = skills.findByStaffId(barber.getId());
        Set<UUID> had = current.stream().map(StaffSkill::getServiceId).collect(Collectors.toSet());
        List<UUID> newIds = wanted.stream().filter(id -> !had.contains(id)).toList();
        Set<UUID> usable = services.findAllById(newIds).stream()
                .filter(s -> s.getShopId().equals(salon.shopId()) && s.isActive())
                .map(SalonServiceItem::getId)
                .collect(Collectors.toSet());
        for (UUID serviceId : newIds) {
            if (!usable.contains(serviceId)) {
                throw invalid("serviceIds", "has a service that doesn't exist in this salon or is switched off: "
                        + serviceId);
            }
        }
        requireBookingsStillFit(barber, null, wanted);
        List<StaffSkill> removed = current.stream().filter(s -> !wanted.contains(s.getServiceId())).toList();
        List<StaffSkill> added = newIds.stream()
                .map(id -> new StaffSkill(salon.shopId(), barber.getId(), id))
                .toList();
        skills.deleteAll(removed);
        skills.saveAll(added);
        skills.flush();
        if (!removed.isEmpty() || !added.isEmpty()) {
            touch(barber);
        }
        return staffResponse(barber);
    }

    /**
     * Replaces a barber's whole week. Times are "HH:mm" in salon time on 15-minute steps; blocks on the same day
     * may touch but not overlap. Only the differences are written.
     */
    @Transactional
    public StaffResponse setHours(UUID userId, UUID staffId, SetHours in) {
        Membership salon = owner(userId);
        // Before the barber's row lock (see AppointmentStore.lockSalonCalendar): the salon's hours can't change while
        // his are checked against them
        appointments.lockSalonCalendar(salon.shopId());
        SalonStaff barber = lockedBarber(salon, staffId, in.version());
        List<Shift> week = parseWeek(in.hours());
        requireInsideSalonHours(salonWeek(salon.shopId()), week);
        requireBookingsStillFit(barber, week, null);
        replaceWeek(barber, week);
        return staffResponse(barber);
    }

    /** Writes only the differences between his week now and the new one (see setHours). */
    private void replaceWeek(SalonStaff barber, List<Shift> week) {
        List<StaffShift> current = shifts.findByStaffId(barber.getId());
        List<StaffShift> removed = current.stream().filter(s -> !week.contains(s.shift())).toList();
        Set<Shift> kept = current.stream().map(StaffShift::shift).collect(Collectors.toSet());
        List<StaffShift> added = week.stream()
                .filter(s -> !kept.contains(s))
                .map(s -> new StaffShift(barber.getId(), s))
                .toList();
        // Removals must reach the database before additions: Hibernate would otherwise insert first, and a new
        // 10:00-14:00 next to the old 10:00-13:00 would hit the no-overlap rule. parseWeek has already refused
        // overlaps and the barber's row is locked, so the database's own rule is only a safety net here.
        shifts.deleteAll(removed);
        shifts.flush();
        shifts.saveAll(added);
        shifts.flush();
        if (!removed.isEmpty() || !added.isEmpty()) {
            touch(barber);
        }
    }

    /**
     * A barber's hours must lie inside the salon's (when the salon has set them): each block on an open day, between
     * opening and closing. 400 VALIDATION_FAILED naming the first block outside, and the salon's hours that day.
     */
    static void requireInsideSalonHours(Map<Integer, Shift> salonWeek, List<Shift> week) {
        if (salonWeek.isEmpty()) {
            return;
        }
        for (Shift s : week) {
            Shift open = salonWeek.get(s.weekday());
            if (open == null) {
                throw invalid("hours", "the salon is closed on " + dayName(s.weekday()) + "s");
            }
            if (s.startMin() < open.startMin() || s.endMin() > open.endMin()) {
                throw invalid("hours", dayName(s.weekday()) + " " + range(s) + " is outside the salon's hours ("
                        + range(open) + ")");
            }
        }
    }

    // ---------- the salon's opening hours ----------

    /** The salon's week, with a version to send back when changing it (null while none are set). */
    @Transactional(readOnly = true)
    public HoursResponse hours(UUID userId) {
        Membership salon = salon(userId);
        return hoursResponse(openDays.findByShopIdOrderByWeekdayAsc(salon.shopId()).stream()
                .map(SalonOpenDay::day).toList());
    }

    /**
     * Replaces the salon's week (owner). A weekday left out is closed; at least one day is open; one block a day on
     * 15-minute steps. Send the version from GET (null while none are set): 409 HOURS_CHANGED if someone saved in
     * between. Barbers whose hours fall outside the new week: 409 STAFF_HOURS_OUTSIDE naming them, or, with
     * trimStaffHours, their hours are cut to fit (each like PUT /staff/{id}/hours: 409 BOOKINGS_IN_THE_WAY, nothing
     * saved, if an upcoming booking of theirs wouldn't fit any more).
     */
    @Transactional
    public HoursResponse setSalonHours(UUID userId, SetSalonHours in) {
        Membership salon = owner(userId);
        // The salon's calendar lock, then barber rows in id order (the order every writer takes them)
        appointments.lockSalonCalendar(salon.shopId());
        List<SalonOpenDay> current = openDays.findByShopIdOrderByWeekdayAsc(salon.shopId());
        List<Shift> before = current.stream().map(SalonOpenDay::day).toList();
        if (!Objects.equals(in.version(), hoursVersion(before))) {
            throw new ApiException(HttpStatus.CONFLICT, "HOURS_CHANGED",
                    "Someone else saved the salon's hours. Reload and try again.");
        }
        List<Shift> week = parseSalonWeek(in.days());
        Map<Integer, Shift> byDay = week.stream().collect(Collectors.toMap(Shift::weekday, s -> s));
        // Working barbers only: a switched-off one's hours are cut to fit when he is switched on (fitToSalonHours)
        List<SalonStaff> barbers = staff.findByShopIdOrderByCreatedAtAscIdAsc(salon.shopId()).stream()
                .filter(SalonStaff::isActive).toList();
        Map<UUID, List<Shift>> weeks = shifts.findByStaffIdIn(barbers.stream().map(SalonStaff::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(StaffShift::getStaffId,
                        Collectors.mapping(StaffShift::shift, Collectors.toList())));
        List<SalonStaff> outside = barbers.stream()
                .filter(b -> !trimmed(weeks.getOrDefault(b.getId(), List.of()), byDay)
                        .equals(sortedWeek(weeks.getOrDefault(b.getId(), List.of()))))
                .sorted(Comparator.comparing(SalonStaff::getId))
                .toList();
        if (!outside.isEmpty() && !Boolean.TRUE.equals(in.trimStaffHours())) {
            throw new ApiException(HttpStatus.CONFLICT, "STAFF_HOURS_OUTSIDE",
                    "Some barbers work outside these hours. Change their hours first, or let the app cut them to fit.",
                    Map.of("staff", outside.stream().map(b -> new StaffOutside(b.getId(), b.getName())).toList()));
        }
        for (SalonStaff b : outside) {
            SalonStaff barber = staff.lockByIdAndShopId(b.getId(), salon.shopId())
                    .orElseThrow(SalonSetupService::noStaff);
            List<Shift> cut = trimmed(shifts.findByStaffId(barber.getId()).stream().map(StaffShift::shift).toList(),
                    byDay);
            requireBookingsStillFit(barber, cut, null);
            replaceWeek(barber, cut);
        }
        Instant now = now();
        Set<Shift> keep = new java.util.HashSet<>(week);
        List<SalonOpenDay> removed = current.stream().filter(d -> !keep.contains(d.day())).toList();
        Set<Shift> had = new java.util.HashSet<>(before);
        List<SalonOpenDay> added = week.stream().filter(s -> !had.contains(s))
                .map(s -> new SalonOpenDay(salon.shopId(), s, now)).toList();
        // Removals first: a changed Monday is a delete and an insert of the same (shop, weekday)
        openDays.deleteAll(removed);
        openDays.flush();
        openDays.saveAll(added);
        openDays.flush();
        return hoursResponse(week);
    }

    /**
     * A barber switched back on: his hours (kept from before, possibly from before the salon's hours were set or
     * changed) are cut to the salon's, so none fall outside them. Switched off he had no upcoming bookings, so
     * nothing can be stranded.
     */
    private void fitToSalonHours(Membership salon, SalonStaff barber) {
        Map<Integer, Shift> salonWeek = salonWeek(salon.shopId());
        if (salonWeek.isEmpty()) {
            return;
        }
        List<Shift> his = shifts.findByStaffId(barber.getId()).stream().map(StaffShift::shift).toList();
        List<Shift> cut = trimmed(his, salonWeek);
        if (!cut.equals(sortedWeek(his))) {
            replaceWeek(barber, cut);
        }
    }

    /** The salon's open days by weekday; empty while none are set. */
    private Map<Integer, Shift> salonWeek(UUID shopId) {
        return openDays.findByShopIdOrderByWeekdayAsc(shopId).stream()
                .map(SalonOpenDay::day)
                .collect(Collectors.toMap(Shift::weekday, s -> s));
    }

    /** A barber's week cut to the salon's open hours: blocks on closed days go, the rest are clipped. */
    static List<Shift> trimmed(List<Shift> week, Map<Integer, Shift> salonWeek) {
        List<Shift> out = new ArrayList<>();
        for (Shift s : sortedWeek(week)) {
            Shift open = salonWeek.get(s.weekday());
            if (open == null) {
                continue;
            }
            int start = Math.max(s.startMin(), open.startMin());
            int end = Math.min(s.endMin(), open.endMin());
            if (end > start) {
                out.add(new Shift(s.weekday(), start, end));
            }
        }
        return out;
    }

    private static List<Shift> sortedWeek(List<Shift> week) {
        return week.stream()
                .sorted(Comparator.comparingInt(Shift::weekday).thenComparingInt(Shift::startMin))
                .toList();
    }

    /** Checks and converts the salon's week: valid times, close after open, each weekday at most once. */
    static List<Shift> parseSalonWeek(List<SalonDayIn> in) {
        List<Shift> week = new ArrayList<>();
        Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < in.size(); i++) {
            SalonDayIn d = in.get(i);
            String at = "days[" + i + "]";
            if (!seen.add(d.weekday())) {
                throw invalid(at + ".weekday", dayName(d.weekday()) + " is in the list twice");
            }
            int open = SalonTime.minutes(at + ".open", d.open());
            if (open == SalonTime.END_OF_DAY) {
                throw invalid(at + ".open", "must be before 24:00");
            }
            int close = SalonTime.minutes(at + ".close", d.close());
            if (close <= open) {
                throw invalid(at + ".close", "must be after open");
            }
            week.add(new Shift(d.weekday(), open, close));
        }
        week.sort(Comparator.comparingInt(Shift::weekday));
        return week;
    }

    private static HoursResponse hoursResponse(List<Shift> week) {
        return new HoursResponse(hoursVersion(week), sortedWeek(week).stream()
                .map(s -> new BookingResponses.DayHours(s.weekday(), SalonTime.format(s.startMin()),
                        SalonTime.format(s.endMin())))
                .toList());
    }

    /**
     * The week's version: its content itself, e.g. "1:600-1260,2:600-1260" (null while none are set). Two saves from
     * the same old screen: the second sees a different week and gets 409 HOURS_CHANGED. Equal weeks have equal
     * versions, which is right: nothing the screen showed was changed.
     */
    static String hoursVersion(List<Shift> week) {
        if (week.isEmpty()) {
            return null;
        }
        return sortedWeek(week).stream()
                .map(s -> s.weekday() + ":" + s.startMin() + "-" + s.endMin())
                .collect(Collectors.joining(","));
    }

    /**
     * New hours or services must not strand a booking: each upcoming booking of his (CONFIRMED, not started) has
     * to stay inside his new hours that day and use only services he still does. Otherwise 409
     * BOOKINGS_IN_THE_WAY listing them, and nothing is saved: the owner reassigns or cancels them first. His row is
     * locked (lockedBarber), and booking holds it in shared mode, so no booking can land meanwhile.
     * @param week   the new week, or null when only the services change
     * @param skills the new services, or null when only the hours change
     */
    private void requireBookingsStillFit(SalonStaff barber, List<Shift> week, Set<UUID> skills) {
        Instant now = now();
        // 31 days: the longest booking window a salon can set is 30
        List<Appointment> upcoming = appointments.holding(List.of(barber.getId()), now,
                now.plus(Duration.ofDays(31)), null).stream()
                .filter(a -> a.kind() != AppointmentKind.BLOCK && a.status() == AppointmentStatus.CONFIRMED)
                .toList();
        Map<UUID, List<BookedService>> booked = skills == null ? Map.of()
                : appointments.services(upcoming.stream().map(Appointment::id).toList());
        List<Appointment> stranded = upcoming.stream().filter(a -> {
            if (week != null && Availability.working(week, LocalDate.ofInstant(a.startsAt(), SalonTime.ZONE))
                    .stream().noneMatch(w -> w.contains(a.time()))) {
                return true;
            }
            return skills != null && !skills.containsAll(SalonDayService.ids(booked.getOrDefault(a.id(), List.of())));
        }).toList();
        if (!stranded.isEmpty()) {
            ApiException conflicts = SalonDayService.blockConflicts(stranded);
            throw new ApiException(HttpStatus.CONFLICT, "BOOKINGS_IN_THE_WAY",
                    "Some of his upcoming bookings wouldn't fit any more. Reassign or cancel them first.",
                    conflicts.getDetails());
        }
    }

    /** Checks and converts the week: valid times, end after start, no duplicates, no overlaps on one day. */
    static List<Shift> parseWeek(List<ShiftIn> in) {
        List<Shift> week = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            ShiftIn s = in.get(i);
            String at = "hours[" + i + "]";
            int start = SalonTime.minutes(at + ".start", s.start());
            if (start == SalonTime.END_OF_DAY) {
                throw invalid(at + ".start", "must be before 24:00");
            }
            int end = SalonTime.minutes(at + ".end", s.end());
            if (end <= start) {
                throw invalid(at + ".end", "must be after start");
            }
            week.add(new Shift(s.weekday(), start, end));
        }
        week.sort(Comparator.comparingInt(Shift::weekday).thenComparingInt(Shift::startMin));
        for (int i = 1; i < week.size(); i++) {
            Shift before = week.get(i - 1);
            Shift next = week.get(i);
            if (before.overlaps(next)) {
                throw invalid("hours", dayName(next.weekday()) + " " + range(before) + " and " + range(next)
                        + " overlap");
            }
        }
        return week;
    }

    // ---------- booking settings ----------

    /** The salon's settings, or the defaults (version null) if the owner hasn't saved any yet. */
    @Transactional(readOnly = true)
    public SettingsResponse settings(UUID userId) {
        Membership salon = salon(userId);
        return SettingsResponse.from(settings.findById(salon.shopId())
                .orElseGet(() -> new SalonSettings(salon.shopId(), now())));
    }

    /**
     * Saves the settings; the first save creates the row. Send the version from GET (null while on the defaults);
     * if someone saved in between, 409 SETTINGS_CHANGED.
     */
    @Transactional
    public SettingsResponse updateSettings(UUID userId, UpdateSettings in) {
        Membership salon = owner(userId);
        SalonSettings current = settings.findById(salon.shopId()).orElse(null);
        Long currentVersion = current == null ? null : current.getVersion();
        if (!Objects.equals(in.version(), currentVersion)) {
            throw settingsChanged();
        }
        SalonSettings s = current != null ? current : new SalonSettings(salon.shopId(), now());
        int minLead = in.minLeadMinutes() == null ? s.getMinLeadMin() : in.minLeadMinutes();
        int maxDays = in.maxDaysAhead() == null ? s.getMaxDaysAhead() : in.maxDaysAhead();
        if (current != null && minLead == s.getMinLeadMin() && maxDays == s.getMaxDaysAhead()) {
            return SettingsResponse.from(s);
        }
        s.apply(minLead, maxDays, now());
        try {
            settings.saveAndFlush(s);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw settingsChanged();
        } catch (DataIntegrityViolationException e) {
            // Two first saves at the same moment: the second hits the primary key. Anything else is a real error.
            if (violated(e, ONE_SETTINGS_ROW)) {
                throw settingsChanged();
            }
            throw e;
        }
        return SettingsResponse.from(s);
    }

    // ---------- who is asking ----------

    /** The caller's salon. 404 NO_SHOP without a shop; 409 NOT_A_SALON if the shop is a kirana. */
    private Membership salon(UUID userId) {
        Membership m = memberships.find(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_SHOP",
                "You haven't registered or joined a shop yet."));
        if (!SALON.equals(m.shopType())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_A_SALON", "This is only for salons.");
        }
        return m;
    }

    /** Like salon(), and the caller must be its owner (403 OWNER_ONLY otherwise). */
    private Membership owner(UUID userId) {
        Membership m = salon(userId);
        if (!OWNER.equals(m.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the salon owner can change this.");
        }
        return m;
    }

    // ---------- helpers ----------

    /**
     * The barber, locked (SELECT ... FOR UPDATE) until the transaction ends, after checking the caller saw his
     * latest version. Two edits of one barber's services or hours then run one after the other, and the second,
     * made from a screen that still shows the old list, gets 409 STAFF_CHANGED instead of overwriting the first.
     */
    private SalonStaff lockedBarber(Membership salon, UUID staffId, long version) {
        SalonStaff barber = lockBarber(staffId, salon);
        if (version != barber.getVersion()) {
            throw staffChanged();
        }
        return barber;
    }

    /** Raises the barber's version after his services or hours changed (see SalonStaff.touch). */
    private void touch(SalonStaff barber) {
        barber.touch(now());
        staff.saveAndFlush(barber);
    }

    /** Active barbers only: switched-off ones (who left) don't count, so switching some off makes room. */
    private void requireRoomForStaff(Membership salon) {
        if (staff.countByShopIdAndActiveTrue(salon.shopId()) >= MAX_STAFF) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_STAFF",
                    "A salon can have up to " + MAX_STAFF + " active barbers. Switch off ones who have left.");
        }
    }

    private StaffResponse staffResponse(SalonStaff barber) {
        List<UUID> serviceIds = sorted(skills.findByStaffId(barber.getId()).stream()
                .map(StaffSkill::getServiceId).toList());
        List<Shift> week = shifts.findByStaffId(barber.getId()).stream().map(StaffShift::shift).toList();
        String phone = barber.getUserId() == null ? null
                : users.find(List.of(barber.getUserId())).values().stream().findFirst().map(Person::phone)
                        .orElse(null);
        // Only the owner changes a barber, so only the owner gets this answer
        String invited = barber.getUserId() != null ? null
                : memberships.waitingBarberInvites(barber.getShopId()).get(barber.getId());
        return StaffResponse.from(barber, serviceIds, week, phone, invited);
    }

    /** Same order every time, so the app's list doesn't jump around. */
    private static List<UUID> sorted(List<UUID> ids) {
        return ids.stream().sorted().toList();
    }

    /** 5 to 480 is checked on the request; here, the 5-minute steps. */
    private static int duration(int minutes) {
        if (minutes % 5 != 0) {
            throw invalid("durationMinutes", "must be in steps of 5 minutes");
        }
        return minutes;
    }

    private static String dayName(int weekday) {
        return DayOfWeek.of(weekday).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    private static String range(Shift s) {
        return SalonTime.format(s.startMin()) + "-" + SalonTime.format(s.endMin());
    }

    /** Cut to microseconds, what the database stores, so the response right after saving matches a later GET. */
    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static ApiException invalid(String field, String problem) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                Map.of(field, problem));
    }

    private static ApiException noStaff() {
        return new ApiException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND", "No such barber.");
    }

    private static ApiException serviceChanged() {
        return new ApiException(HttpStatus.CONFLICT, "SERVICE_CHANGED",
                "Someone else saved changes to this service. Reload and try again.");
    }

    private static ApiException staffChanged() {
        return new ApiException(HttpStatus.CONFLICT, "STAFF_CHANGED",
                "Someone else saved changes to this barber. Reload and try again.");
    }

    private static ApiException settingsChanged() {
        return new ApiException(HttpStatus.CONFLICT, "SETTINGS_CHANGED",
                "Someone else saved the booking settings. Reload and try again.");
    }
}
