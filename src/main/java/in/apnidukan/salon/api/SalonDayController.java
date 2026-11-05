package in.apnidukan.salon.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.salon.api.BookingRequests.AddBlock;
import in.apnidukan.salon.api.BookingRequests.AddClosure;
import in.apnidukan.salon.api.BookingRequests.Pause;
import in.apnidukan.salon.api.BookingRequests.Reassign;
import in.apnidukan.salon.api.BookingRequests.SalonCancel;
import in.apnidukan.salon.api.BookingRequests.Start;
import in.apnidukan.salon.api.BookingRequests.WalkIn;
import in.apnidukan.salon.api.BookingRequests.WalkInAccess;
import in.apnidukan.salon.api.BookingResponses.AppointmentView;
import in.apnidukan.salon.api.BookingResponses.HistoryView;
import in.apnidukan.salon.api.BookingResponses.ClosureView;
import in.apnidukan.salon.api.BookingResponses.DayView;
import in.apnidukan.salon.api.BookingResponses.MemberView;
import in.apnidukan.salon.api.BookingResponses.PauseView;
import in.apnidukan.salon.api.BookingResponses.WalkInOption;
import in.apnidukan.salon.service.SalonDayService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The salon's daily work in the shop app. Who may do what is checked in SalonAccess: owner and helper see every
 * barber, a barber sees his own day; walk-ins only by the owner and people he gave walk-in access to.
 * Appointments of another salon or (for a barber) another barber answer 404.
 */
@RestController
@RequestMapping("/api/v1/salon")
public class SalonDayController {

    private final SalonDayService day;
    private final CurrentUserProvider currentUser;

    public SalonDayController(SalonDayService day, CurrentUserProvider currentUser) {
        this.day = day;
        this.currentUser = currentUser;
    }

    /** The day screen: a column per barber with his hours and appointments. date = India date, default today. */
    @GetMapping("/day")
    public DayView day(@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate date) {
        return day.day(me(), date);
    }

    /**
     * The owner's history: bookings and walk-ins on the India dates from..to (at most 31 days), latest first, with
     * a count per status and the completed amount at list price. Owner or helper.
     */
    @GetMapping("/history")
    public HistoryView history(@RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
                               @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return day.history(me(), from, to);
    }

    /**
     * Upcoming bookings across the coming days, soonest first (up to 200): the owner's "what's coming" list. A
     * barber without walk-in access sees only his own.
     */
    @GetMapping("/upcoming")
    public List<AppointmentView> upcoming() {
        return day.upcoming(me());
    }

    /**
     * Undo a No-show pressed by mistake (owner or helper, within 24 hours): the strike goes, the booking is closed as
     * cancelled by the salon ("No-show undone") and the customer is told. 409 WRONG_STATUS (details.reason TOO_LATE).
     */
    @PostMapping("/appointments/{id}/undo-no-show")
    public AppointmentView undoNoShow(@PathVariable UUID id) {
        return day.undoNoShow(me(), id);
    }

    /** Walk-in suggestions per barber for these services: free now, or his earliest start today. */
    @GetMapping("/walk-ins/options")
    public List<WalkInOption> walkInOptions(@RequestParam List<UUID> services) {
        return day.walkInOptions(me(), services);
    }

    /** Add a walk-in (Idempotency-Key header required). 201. 409 BARBER_BUSY if his time was just taken. */
    @PostMapping("/walk-ins")
    @ResponseStatus(HttpStatus.CREATED)
    public AppointmentView addWalkIn(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                     @Valid @RequestBody WalkIn body) {
        return day.addWalkIn(me(), key, body);
    }

    /** Start: {"code": "4827"} for an online booking, or {"withoutCode": true} (owner/helper); {} for a walk-in. */
    @PostMapping("/appointments/{id}/start")
    public AppointmentView start(@PathVariable UUID id, @Valid @RequestBody(required = false) Start body) {
        return day.start(me(), id, body);
    }

    /** Done before the planned end (IN_SERVICE → COMPLETED now): the rest of the barber's time is free again. */
    @PostMapping("/appointments/{id}/finish")
    public AppointmentView finish(@PathVariable UUID id) {
        return day.finish(me(), id);
    }

    /** No-show, from 15 minutes after the start. Frees the time; an online customer gets a strike. */
    @PostMapping("/appointments/{id}/no-show")
    public AppointmentView noShow(@PathVariable UUID id) {
        return day.noShow(me(), id);
    }

    /**
     * Give it to another barber (owner or helper): a booking at the same time; a walk-in already in the chair starts
     * again with him now.
     */
    @PostMapping("/appointments/{id}/reassign")
    public AppointmentView reassign(@PathVariable UUID id, @Valid @RequestBody Reassign body) {
        return day.reassign(me(), id, body);
    }

    /** The salon cancels, with a reason sent to the customer (owner or helper). */
    @PostMapping("/appointments/{id}/cancel")
    public AppointmentView cancel(@PathVariable UUID id, @Valid @RequestBody SalonCancel body) {
        return day.cancel(me(), id, body);
    }

    /** The bookings and walk-ins a block between from and to would hit. */
    @GetMapping("/staff/{id}/blocks/conflicts")
    public List<AppointmentView> conflicts(@PathVariable UUID id,
                                           @RequestParam @DateTimeFormat(iso = ISO.DATE_TIME) Instant from,
                                           @RequestParam @DateTimeFormat(iso = ISO.DATE_TIME) Instant to) {
        return day.conflicts(me(), id, from, to);
    }

    /** Add a break or leave. 201. 409 BLOCK_CONFLICTS (nothing saved) if appointments are in the way. */
    @PostMapping("/staff/{id}/blocks")
    @ResponseStatus(HttpStatus.CREATED)
    public AppointmentView addBlock(@PathVariable UUID id, @Valid @RequestBody AddBlock body) {
        return day.addBlock(me(), id, body);
    }

    /** Remove a break or leave. 204. */
    @DeleteMapping("/staff/{id}/blocks/{blockId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeBlock(@PathVariable UUID id, @PathVariable UUID blockId) {
        day.removeBlock(me(), id, blockId);
    }

    /** Upcoming closed days. */
    @GetMapping("/closures")
    public List<ClosureView> closures() {
        return day.closures(me());
    }

    /** Close the salon for a day (owner). 201. 409 BLOCK_CONFLICTS if anything is on that day. */
    @PostMapping("/closures")
    @ResponseStatus(HttpStatus.CREATED)
    public ClosureView addClosure(@Valid @RequestBody AddClosure body) {
        return day.addClosure(me(), body);
    }

    /** Open a closed day again (owner). 204. */
    @DeleteMapping("/closures/{closureId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeClosure(@PathVariable UUID closureId) {
        day.removeClosure(me(), closureId);
    }

    /** Whether online bookings are paused, and until when. */
    @GetMapping("/pause")
    public PauseView pauseStatus() {
        return day.pauseStatus(me());
    }

    /** Pause online bookings (owner or helper); until left out = end of today. */
    @PostMapping("/pause")
    public PauseView pause(@Valid @RequestBody(required = false) Pause body) {
        return day.pause(me(), body == null ? null : body.until());
    }

    @PostMapping("/resume")
    public PauseView resume() {
        return day.resume(me());
    }

    /** Everyone in the salon and whether they may add walk-ins (owner). */
    @GetMapping("/members")
    public List<MemberView> members() {
        return day.members(me());
    }

    /** Give or take back walk-in access for a helper or barber (owner). */
    @PutMapping("/members/{userId}/walk-in-access")
    public List<MemberView> setWalkInAccess(@PathVariable UUID userId, @Valid @RequestBody WalkInAccess body) {
        return day.setWalkInAccess(me(), userId, body.allowed());
    }

    private UUID me() {
        return currentUser.get().userId();
    }
}
