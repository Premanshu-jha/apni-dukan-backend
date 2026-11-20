package in.apnidukan.notifications.service;

import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.notify.Language;
import in.apnidukan.common.notify.MessageText;
import in.apnidukan.common.notify.NoticeTexts;
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
import java.time.Instant;
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
    private final InboxVersions versions;
    private final UserDirectory users;
    private final boolean strictTexts;

    /**
     * @param strictTexts app.notifications.strict-texts: a message whose English differs from its text in
     *                    notices/en.properties fails (the tests turn it on; production doesn't check)
     */
    public NotificationService(NotificationStore jobs, PushSubscriptionStore devices, SmsTemplates smsTemplates,
                               Clock clock, InboxVersions versions, UserDirectory users,
                               @Value("${app.push.vapid-public-key:}") String vapidPublicKey,
                               @Value("${app.notifications.strict-texts:false}") boolean strictTexts) {
        this.versions = versions;
        this.users = users;
        this.strictTexts = strictTexts;
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
        Notice cleaned = clean(inReadersLanguage(List.of(notice)).get(0));
        jobs.insert(cleaned, Channel.PUSH, clock.instant());
        if (!cleaned.smsVariables().isEmpty() && smsTemplates.forType(notice.type()).isPresent()) {
            jobs.insert(cleaned, Channel.SMS, clock.instant());
        }
        versions.bumpAfterCommit(List.of(notice.userId()));   // their bell's answer changes
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void queueAll(List<Notice> notices) {
        List<Map.Entry<Notice, Channel>> rows = new ArrayList<>();
        for (Notice notice : inReadersLanguage(notices)) {
            Notice cleaned = clean(notice);
            rows.add(Map.entry(cleaned, Channel.PUSH));
            if (!cleaned.smsVariables().isEmpty() && smsTemplates.forType(notice.type()).isPresent()) {
                rows.add(Map.entry(cleaned, Channel.SMS));
            }
        }
        jobs.insertAll(rows, clock.instant());
        versions.bumpAfterCommit(notices.stream().map(Notice::userId).toList());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelPendingAll(Collection<UUID> refIds) {
        jobs.cancelPendingAll(refIds, clock.instant());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void replacePendingAll(List<Notice> notices) {
        jobs.rewritePendingAll(inReadersLanguage(notices).stream().map(NotificationService::clean).toList());
        versions.bumpAfterCommit(notices.stream().map(Notice::userId).toList());   // one already due shows new text
    }

    /**
     * Each notice's title and body in its reader's language (users.language; none: English), looked up for the whole
     * batch in one query, and only when some notice has a text (see Notice.text). A language without that text, or
     * a piece of it: the English. The SMS is never changed (DLT templates).
     */
    List<Notice> inReadersLanguage(List<Notice> notices) {
        List<UUID> readers = notices.stream().filter(n -> n.text() != null).map(Notice::userId).distinct().toList();
        if (readers.isEmpty()) {
            return notices;
        }
        if (strictTexts) {   // the tests: every English the code writes is what en.properties says
            notices.forEach(this::checkEnglish);
        }
        Map<UUID, String> languages = users.languages(readers);
        if (languages.isEmpty()) {
            return notices;
        }
        List<Notice> out = new ArrayList<>(notices.size());
        for (Notice n : notices) {
            Language language = Language.orEnglish(languages.get(n.userId()));
            if (n.text() == null || language == Language.EN) {
                out.add(n);
                continue;
            }
            // Both in the language or both in English: never a title in one and its body in the other
            java.util.Optional<String> title = NoticeTexts.title(language, n.text());
            java.util.Optional<String> body = NoticeTexts.body(language, n.text());
            out.add(title.isPresent() && body.isPresent() ? n.withWords(title.get(), body.get()) : n);
        }
        return out;
    }

    /**
     * The English written by the code is what en.properties says (so the translations, made from that file, say the
     * same). Checked only when strict (the tests), where a difference fails: in production it would only cost a
     * render of every English text on every message.
     */
    private void checkEnglish(Notice n) {
        if (n.text() == null) {
            return;
        }
        String title = NoticeTexts.title(Language.EN, n.text()).orElse(null);
        String body = NoticeTexts.body(Language.EN, n.text()).orElse(null);
        boolean same = title != null && body != null
                && MessageText.cut(title, MessageText.MAX_TITLE).equals(MessageText.cut(n.title(), MessageText.MAX_TITLE))
                && MessageText.cut(body, MessageText.MAX_BODY).equals(MessageText.cut(n.body(), MessageText.MAX_BODY));
        if (!same) {
            throw new IllegalStateException("notices/en.properties differs from the code for " + n.text().key()
                    + ": [" + title + "] [" + body + "] vs [" + n.title() + "] [" + n.body() + "]");
        }
    }

    /** Title, body and SMS variables cut to what the columns and DLT allow. */
    private static Notice clean(Notice notice) {
        Map<String, String> sms = new java.util.TreeMap<>();
        // No "…" in SMS: it isn't a GSM character and would make the whole SMS Unicode (shorter parts, higher cost)
        notice.smsVariables().forEach((k, v) -> sms.put(k, cutPlain(v, MAX_SMS_VARIABLE)));
        return new Notice(notice.userId(), notice.type(), notice.refId(), notice.sendAt(),
                cut(notice.title(), 100), cut(notice.body(), 500), notice.url(), sms, notice.aboutUserId(),
                notice.text());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelPending(UUID refId) {
        jobs.cancelPending(refId, clock.instant());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelPending(UUID refId, String type) {
        jobs.cancelPending(refId, type, clock.instant());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void replacePending(Notice notice) {
        jobs.rewritePending(clean(inReadersLanguage(List.of(notice)).get(0)));
        versions.bumpAfterCommit(List.of(notice.userId()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void forgetSalonMessages(UUID userId) {
        forgetSalonMessages(List.of(userId));
    }

    /**
     * Hidden from their lists at once and deleted by SalonMessagePurgeJob a slice at a time (a salon owner can have
     * lakhs: one DELETE here would run past the request's 5 s and fail the close or hand-over for good). The ones still
     * waiting are cancelled now (few rows), so nothing more of that salon reaches them.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void forgetSalonMessages(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        jobs.cancelPendingSalon(userIds, now);
        jobs.queueSalonPurges(userIds, now);
        versions.bumpAfterCommit(userIds);
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

    /** Shortens to max characters without an ellipsis, never splitting an emoji in two (see MessageText). */
    static String cutPlain(String text, int max) {
        return MessageText.cutPlain(text, max);
    }

    /** Shortens to max characters, never splitting an emoji (a surrogate pair) in two (see MessageText). */
    static String cut(String text, int max) {
        return MessageText.cut(text, max);
    }

    private static ApiException invalid(String field, String problem) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid",
                Map.of(field, problem));
    }
}
