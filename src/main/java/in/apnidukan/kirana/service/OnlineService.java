package in.apnidukan.kirana.service;

import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.context.TeamRole;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.files.FileRefs;
import in.apnidukan.common.text.TextCleaner;
import in.apnidukan.kirana.api.OrderRequests.Fssai;
import in.apnidukan.kirana.api.OrderRequests.FssaiDecision;
import in.apnidukan.kirana.api.OrderResponses.AdminPayoutView;
import in.apnidukan.kirana.api.OrderResponses.FailedRefundView;
import in.apnidukan.kirana.api.OrderResponses.FssaiCheckView;
import in.apnidukan.kirana.api.OrderResponses.FssaiView;
import in.apnidukan.kirana.api.OrderResponses.OnlineView;
import in.apnidukan.kirana.api.OrderResponses.OpenOrderView;
import in.apnidukan.kirana.api.OrderResponses.PayoutView;
import in.apnidukan.kirana.api.OrderResponses.UpiRecheckView;
import in.apnidukan.kirana.repo.KiranaSettingsStore;
import in.apnidukan.kirana.repo.MoneyStore;
import in.apnidukan.kirana.repo.OnlineStore;
import in.apnidukan.kirana.repo.OnlineStore.Online;
import in.apnidukan.kirana.repo.OrderStore;
import in.apnidukan.kirana.service.KiranaAccess.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A kirana's online orders, the shop's side: whether customers can order from it now and what stops it, the owner's
 * switch, its FSSAI licence (read by the AI, FssaiCheckJob), what it earned and its payouts. Customers can order only
 * from a shop that is approved by the field team, has its UPI ID, has a valid FSSAI licence and has its switch on.
 * Also the admin website's checks: a super admin's decision on a certificate, the field team's re-check of a UPI ID
 * changed after approval (payouts wait for it), failed refunds and payouts.
 */
@Service
public class OnlineService {

    static final int LIST_MAX = 100;
    /** A paid order still open this long after it was placed shows on the admin website. */
    static final java.time.Duration STUCK_AFTER = java.time.Duration.ofHours(2);

    private final KiranaAccess access;
    private final OnlineStore online;
    private final KiranaSettingsStore settings;
    private final MoneyStore money;
    private final ShopDirectory shops;
    private final FileRefs files;
    private final UserDirectory users;
    private final FssaiCheckJob fssaiJob;
    private final OrderStore orders;
    private final OrderFlow flow;
    private final Clock clock;

    public OnlineService(KiranaAccess access, OnlineStore online, KiranaSettingsStore settings, MoneyStore money,
                         ShopDirectory shops, FileRefs files, UserDirectory users, FssaiCheckJob fssaiJob,
                         OrderStore orders, OrderFlow flow, Clock clock) {
        this.fssaiJob = fssaiJob;
        this.orders = orders;
        this.flow = flow;
        this.access = access;
        this.online = online;
        this.settings = settings;
        this.money = money;
        this.shops = shops;
        this.files = files;
        this.users = users;
        this.clock = clock;
    }

    /** The shop's online orders (owner or helper; earnings and payouts only for the owner). */
    @Transactional(readOnly = true)
    public OnlineView get(UUID userId) {
        Caller c = access.caller(userId);
        return view(c, online.find(c.shopId()).orElse(null));
    }

    /**
     * The owner's switch. Switching on needs everything else in place: 409 NOT_READY naming what is missing
     * (APPROVAL, UPI_ID, FSSAI). Switching off always works; orders already placed go on.
     */
    @Transactional
    public OnlineView setTakingOrders(UUID userId, boolean on) {
        Caller c = access.owner(userId);
        Instant now = KiranaChecks.now(clock);
        Online o = online.lock(c.shopId(), now);
        if (on) {
            List<String> missing = missing(c.shopId(), o, true);
            if (!missing.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "NOT_READY", "Customers can order once "
                        + String.join(", ", missing.stream().map(OnlineService::words).toList()) + ".",
                        Map.of("missing", missing));
            }
        }
        if (o.takingOrders() != on) {
            online.setTakingOrders(c.shopId(), on, now);
        }
        return view(c, online.find(c.shopId()).orElseThrow());
    }

    /**
     * The owner adds or replaces the shop's FSSAI licence: the 14-digit number and the certificate (a photo or a PDF
     * uploaded as a registration document). The AI reads it within a few minutes (CHECKING, then VERIFIED or REJECTED
     * with the reason); until it is verified customers can't order. 400 naming number or fileId.
     */
    @Transactional
    public OnlineView setFssai(UUID userId, Fssai in) {
        Caller c = access.owner(userId);
        String number = in.number().replaceAll("[\\s-]", "");
        if (!number.matches("[0-9]{14}")) {
            throw KiranaChecks.invalid("number", "An FSSAI number has 14 digits");
        }
        FileRefs.FileRef file = files.find(in.fileId())
                .filter(f -> c.shopId().equals(f.shopId()) && "REG_DOC".equals(f.purpose()))
                .orElseThrow(() -> KiranaChecks.invalid("fileId", "Upload the certificate first"));
        Instant now = KiranaChecks.now(clock);
        Online o = online.lock(c.shopId(), now);
        if (number.equals(o.fssaiNumber()) && file.id().equals(o.fssaiFileId())) {
            if ("REJECTED".equals(o.fssaiStatus())) {
                // The same certificate again after a refusal: the owner asks a person to look at it
                online.fssaiToPerson(c.shopId(), FssaiCheckJob.MAX_TRIES, now);
                return view(c, online.find(c.shopId()).orElseThrow());
            }
            return view(c, o);   // sent again: nothing new to check
        }
        files.keep(file.id());
        if (o.fssaiFileId() != null && !o.fssaiFileId().equals(file.id())) {
            files.release(o.fssaiFileId());
        }
        online.setFssai(c.shopId(), number, file.id(), now);
        return view(c, online.find(c.shopId()).orElseThrow());
    }

    // ---------- the admin website ----------

    /** A super admin's decision on a shop's certificate (the AI couldn't read it, or a second look). */
    @Transactional
    public FssaiView decideFssai(UUID adminId, UUID shopId, FssaiDecision in) {
        requireTeam(adminId, true);
        Instant now = KiranaChecks.now(clock);
        Online o = online.lock(shopId, now);
        if (o.fssaiFileId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_FSSAI", "This shop hasn't added its FSSAI certificate.");
        }
        boolean verified = Boolean.TRUE.equals(in.verified());
        if (verified && (in.validUntil() == null || in.validUntil().isBefore(KiranaChecks.today(now)))) {
            throw KiranaChecks.invalid("validUntil", "Enter the date the licence is valid until, as printed (not past)");
        }
        String reason = verified ? null
                : in.reason() == null || in.reason().isBlank() ? "The certificate couldn't be accepted." : in.reason().strip();
        online.setFssai(shopId, o.fssaiNumber(), o.fssaiFileId(), now);
        String status = verified ? "VERIFIED" : "REJECTED";
        online.fssaiResult(shopId, o.fssaiNumber(), o.fssaiFileId(), status, reason, verified ? in.validUntil() : null,
                now);
        fssaiJob.tellDecision(shopId, status, reason, now);
        return fssai(online.find(shopId).orElseThrow());
    }

    /**
     * Certificates for a person: CHECKING (the AI couldn't decide, or the owner asked again after a refusal), oldest
     * first, or REJECTED, newest first. Super admins.
     */
    @Transactional(readOnly = true)
    public List<FssaiCheckView> fssaiChecks(UUID adminId, String status) {
        requireTeam(adminId, true);
        boolean rejected = "REJECTED".equals(status);
        return online.fssaiChecks(rejected ? "REJECTED" : "CHECKING", rejected ? 0 : FssaiCheckJob.MAX_TRIES, LIST_MAX)
                .stream().map(f -> new FssaiCheckView(f.shopId(), f.shopName(), f.number(), f.fileId(), f.status(),
                        f.reason(), f.tries(), f.checkedAt())).toList();
    }

    /** UPI IDs changed after approval, waiting for the field team (payouts to them wait too). */
    @Transactional(readOnly = true)
    public List<UpiRecheckView> upiRechecks(UUID adminId) {
        requireTeam(adminId, false);
        return online.rechecks(LIST_MAX).stream()
                .map(r -> new UpiRecheckView(r.shopId(), r.shopName(), r.upiId(), r.changedAt())).toList();
    }

    /** The field team checked a shop's new UPI ID with the owner: payouts go to it again. 404 when none was waiting. */
    @Transactional
    public void upiChecked(UUID adminId, UUID shopId) {
        requireTeam(adminId, false);
        if (!online.clearUpiRecheck(shopId, adminId, KiranaChecks.now(clock))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOTHING_TO_CHECK", "This shop's UPI ID isn't waiting for a check.");
        }
        money.releaseFailedOf(shopId, KiranaChecks.now(clock));   // what failed to the old UPI ID goes to the new one
    }

    /** Refunds Razorpay refused, newest first (a super admin settles them by hand). */
    @Transactional(readOnly = true)
    public List<FailedRefundView> failedRefunds(UUID adminId) {
        requireTeam(adminId, true);
        return money.failedRefunds(LIST_MAX).stream().map(r -> new FailedRefundView(r.id(), r.checkoutId(),
                r.amountPaise(), r.reason(), r.failure(), r.updatedAt())).toList();
    }

    /** Payouts in a status (FAILED, REVERSED, SENDING, ...), newest first. */
    @Transactional(readOnly = true)
    public List<AdminPayoutView> payouts(UUID adminId, String status) {
        requireTeam(adminId, true);
        return money.payoutsIn(status == null ? "FAILED" : status, LIST_MAX).stream().map(p -> new AdminPayoutView(p.id(),
                p.shopId(), p.amountPaise(), p.upiId(), p.status(), p.failure(), p.updatedAt())).toList();
    }

    /**
     * A super admin sends a failed or reversed payout again (after the owner fixed his UPI ID, say): its earnings go
     * into the next run's payout. 409 when it isn't failed or reversed.
     */
    @Transactional
    public void retryPayout(UUID adminId, UUID payoutId) {
        requireTeam(adminId, true);
        MoneyStore.Payout p = money.lockPayout(payoutId).orElseThrow(() -> ApiException.notFound("Payout"));
        if (!"FAILED".equals(p.status()) && !"REVERSED".equals(p.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_FAILED", "Only a failed or reversed payout is sent again.");
        }
        money.releaseEarnings(p.id());
        money.payoutNote(p.id(), (p.failure() == null ? "" : p.failure() + " · ") + "sent again",
                KiranaChecks.now(clock));
    }

    // ---------- shared ----------

    /**
     * What stops customers ordering from the shop, if anything: APPROVAL, UPI_ID, FSSAI (missing, being checked,
     * refused or expired), and SWITCHED_OFF when {@code ignoreSwitch} is false.
     */
    List<String> missing(UUID shopId, Online o, boolean ignoreSwitch) {
        List<String> out = new ArrayList<>();
        String status = shops.find(shopId).map(ShopDirectory.ShopCard::status).orElse("");
        if (!"APPROVED".equals(status)) {
            out.add("APPROVAL");
        }
        if (!settings.hasUpiId(shopId)) {
            out.add("UPI_ID");
        }
        if (o == null || !o.fssaiValidOn(KiranaChecks.today(clock.instant()))) {
            out.add("FSSAI");
        }
        if (!ignoreSwitch && (o == null || !o.takingOrders())) {
            out.add("SWITCHED_OFF");
        }
        return out;
    }

    private OnlineView view(Caller c, Online o) {
        LocalDate today = KiranaChecks.today(clock.instant());
        long[] owed = c.isOwner() ? money.owed(c.shopId(), today) : new long[] {0, 0};
        List<PayoutView> payouts = c.isOwner() ? money.payouts(c.shopId(), 20).stream().map(p -> new PayoutView(p.id(),
                p.amountPaise(), p.upiId(), p.status(), p.failure(), p.createdAt())).toList() : List.of();
        List<String> missing = missing(c.shopId(), o, false);
        return new OnlineView(o != null && o.takingOrders(), missing.isEmpty(), missing,
                o == null ? new FssaiView(null, null, "NONE", null, null, null) : fssai(o), o != null && o.upiRecheck(),
                o == null || o.ratingCount() == 0 ? null : Math.round(10.0 * o.ratingSum() / o.ratingCount()) / 10.0,
                o == null ? 0 : o.ratingCount(), owed[0], owed[1], payouts);
    }

    private FssaiView fssai(Online o) {
        String status = o.fssaiStatus();
        String reason = o.fssaiReason();
        LocalDate today = KiranaChecks.today(clock.instant());
        if ("CHECKING".equals(status) && o.fssaiTries() >= FssaiCheckJob.MAX_TRIES) {
            reason = "Our team is checking it, usually within a day.";
        }
        if ("VERIFIED".equals(status) && !o.fssaiValidOn(today)) {
            status = "REJECTED";
            reason = "Your FSSAI licence expired on " + KiranaChecks.day(o.fssaiValidUntil())
                    + ". Add the renewed certificate.";
        }
        return new FssaiView(o.fssaiNumber(), o.fssaiFileId(), status, reason, o.fssaiValidUntil(),
                o.fssaiCheckedAt());
    }

    private static String words(String missing) {
        return switch (missing) {
            case "APPROVAL" -> "the field team approves your shop";
            case "UPI_ID" -> "you add your UPI ID";
            case "FSSAI" -> "your FSSAI licence is verified";
            default -> "you switch on online orders";
        };
    }

    /** The field team (super admins and field agents), or super admins only. */
    /** Paid orders still open more than 2 hours after they were placed, oldest first: the team calls the shop. */
    @Transactional(readOnly = true)
    public List<OpenOrderView> openOrders(UUID adminId) {
        requireTeam(adminId, false);
        List<OrderStore.Order> list = orders.openBefore(KiranaChecks.now(clock).minus(STUCK_AFTER), LIST_MAX);
        List<UUID> shopIds = list.stream().map(OrderStore.Order::shopId).distinct().toList();
        Map<UUID, ShopDirectory.ShopCard> cards = shops.findAll(shopIds);
        Map<UUID, String> phones = orders.ownerPhones(shopIds);
        return list.stream().map(o -> new OpenOrderView(o.id(), o.orderNo(), o.shopId(),
                cards.containsKey(o.shopId()) ? cards.get(o.shopId()).name() : "", phones.get(o.shopId()), o.status(),
                o.placedAt())).toList();
    }

    /**
     * A super admin cancels a paid order no one can finish (a shop gone quiet, a customer who deleted their account):
     * refunded in full, every shop's stock back. 409 WRONG_STATUS once delivered or cancelled; 404 for no such order.
     */
    @Transactional
    public void cancelOrder(UUID adminId, UUID orderId, String reason) {
        requireTeam(adminId, true);
        OrderStore.Order peek = orders.order(orderId).orElseThrow(() -> ApiException.notFound("Order"));
        OrderStore.Checkout c = orders.lockCheckout(peek.checkoutId()).orElseThrow();
        OrderStore.Order o = orders.lockOrder(orderId).orElseThrow();
        if (!List.of("PLACED", "ACCEPTED", "PACKED", "OUT_FOR_DELIVERY").contains(o.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "WRONG_STATUS", "Only an open, paid order can be cancelled.",
                    Map.of("status", o.status()));
        }
        String why = TextCleaner.clean(KiranaChecks.VOID_REASON, reason);
        flow.cancelOrder(c, o, orders.lockPartsOf(o.id()), "Cancelled by Clozest: " + why, "CANCELLED",
                KiranaChecks.now(clock));
    }

    private void requireTeam(UUID adminId, boolean superAdminOnly) {
        TeamRole role = users.teamRole(adminId);
        if (superAdminOnly ? role != TeamRole.SUPER_ADMIN : !role.onTeam()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_ALLOWED", "You can't do this.", Map.of());
        }
    }
}
