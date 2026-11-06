package in.apnidukan.notifications.service;

import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.notify.Notifications;
import in.apnidukan.notifications.domain.Channel;
import in.apnidukan.notifications.repo.NotificationStore;
import in.apnidukan.notifications.repo.PushSubscriptionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Queues messages (see {@link Notifications}) and keeps each person's devices. */
@Service
public class NotificationService implements Notifications {

    /** Devices kept per person; the oldest go first. */
    static final int MAX_DEVICES = 10;
    /** DLT allows at most 30 characters in one variable of a template. */
    static final int MAX_SMS_VARIABLE = 30;

    private final NotificationStore jobs;
    private final PushSubscriptionStore devices;
    private final SmsTemplates smsTemplates;
    private final Clock clock;
    private final String vapidPublicKey;

    public NotificationService(NotificationStore jobs, PushSubscriptionStore devices, SmsTemplates smsTemplates,
                               Clock clock,
                               @Value("${app.push.vapid-public-key:}") String vapidPublicKey) {
        this.jobs = jobs;
        this.devices = devices;
        this.smsTemplates = smsTemplates;
        this.clock = clock;
        this.vapidPublicKey = vapidPublicKey.strip();
    }

    /**
     * Joins the caller's transaction: the message exists only if the change it reports is saved. One Web Push row,
     * and one SMS row when a template is set for this type (SmsTemplates) and the notice has SMS variables.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void queue(Notice notice) {
        Notice cleaned = clean(notice);
        jobs.insert(cleaned, Channel.PUSH, clock.instant());
        if (!cleaned.smsVariables().isEmpty() && smsTemplates.forType(notice.type()).isPresent()) {
            jobs.insert(cleaned, Channel.SMS, clock.instant());
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void queueAll(List<Notice> notices) {
        List<Map.Entry<Notice, Channel>> rows = new ArrayList<>();
        for (Notice notice : notices) {
            Notice cleaned = clean(notice);
            rows.add(Map.entry(cleaned, Channel.PUSH));
            if (!cleaned.smsVariables().isEmpty() && smsTemplates.forType(notice.type()).isPresent()) {
                rows.add(Map.entry(cleaned, Channel.SMS));
            }
        }
        jobs.insertAll(rows, clock.instant());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelPendingAll(Collection<UUID> refIds) {
        jobs.cancelPendingAll(refIds, clock.instant());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void replacePendingAll(List<Notice> notices) {
        jobs.rewritePendingAll(notices.stream().map(NotificationService::clean).toList());
    }

    /** Title, body and SMS variables cut to what the columns and DLT allow. */
    private static Notice clean(Notice notice) {
        Map<String, String> sms = new java.util.TreeMap<>();
        // No "…" in SMS: it isn't a GSM character and would make the whole SMS Unicode (shorter parts, higher cost)
        notice.smsVariables().forEach((k, v) -> sms.put(k, cutPlain(v, MAX_SMS_VARIABLE)));
        return new Notice(notice.userId(), notice.type(), notice.refId(), notice.sendAt(),
                cut(notice.title(), 100), cut(notice.body(), 500), notice.url(), sms, notice.aboutUserId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelPending(UUID refId) {
        jobs.cancelPending(refId, clock.instant());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void replacePending(Notice notice) {
        jobs.rewritePending(clean(notice));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void forgetSalonMessages(UUID userId) {
        jobs.deleteSalonMessages(userId);
    }

    /**
     * Saves this device (the browser's PushSubscription). Only real push services are accepted (PushEndpoints),
     * and the keys must have the right sizes.
     */
    @Transactional
    public void subscribe(UUID userId, String endpoint, String p256dh, String auth) {
        if (!PushEndpoints.allowed(endpoint)) {
            throw invalid("endpoint", "is not a known push service address");
        }
        if (decodedLength(p256dh) != 65 || Base64.getUrlDecoder().decode(p256dh)[0] != 4) {
            throw invalid("keys.p256dh", "must be a 65-byte public key, base64url");
        }
        if (decodedLength(auth) != 16) {
            throw invalid("keys.auth", "must be a 16-byte secret, base64url");
        }
        devices.save(userId, endpoint, p256dh, auth, clock.instant());
        devices.trim(userId, MAX_DEVICES);
    }

    @Transactional
    public void unsubscribe(UUID userId, String endpoint) {
        devices.delete(userId, endpoint);
    }

    /** The key the app passes to the browser when subscribing; empty while Web Push isn't configured. */
    public String publicKey() {
        return vapidPublicKey;
    }

    private static int decodedLength(String base64url) {
        try {
            return Base64.getUrlDecoder().decode(base64url).length;
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    /** Shortens to max characters without an ellipsis, never splitting an emoji in two. */
    static String cutPlain(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }

    /** Shortens to max characters, never splitting an emoji (a surrogate pair) in two. */
    static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = max - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }

    private static ApiException invalid(String field, String problem) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                Map.of(field, problem));
    }
}
