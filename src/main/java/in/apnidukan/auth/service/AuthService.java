package in.apnidukan.auth.service;

import in.apnidukan.auth.domain.AdminRole;
import in.apnidukan.auth.domain.RevokeReason;
import in.apnidukan.auth.domain.User;
import in.apnidukan.auth.notify.EmailSender;
import in.apnidukan.auth.notify.SmsSender;
import in.apnidukan.auth.repo.RefreshTokenRepository;
import in.apnidukan.auth.repo.RefreshTokenStore;
import in.apnidukan.auth.repo.UserRepository;
import in.apnidukan.common.async.BackgroundTasks;
import in.apnidukan.common.context.AccountDeletionListener;
import in.apnidukan.common.context.PersonDetailsListener;
import in.apnidukan.common.context.SignOutEverywhereListener;
import in.apnidukan.common.context.ShopMemberships;
import in.apnidukan.common.context.TestAccountShops;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.ClientIp;
import in.apnidukan.common.security.JwtProperties;
import in.apnidukan.common.security.JwtService;
import in.apnidukan.common.security.RateLimiter;
import in.apnidukan.common.security.SessionRevocations;
import in.apnidukan.common.text.TextCleaner;
import in.apnidukan.common.text.TextCleaner.TextField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Sign-in with phone OTP, refresh-token rotation, sign-out, email verification and account recovery.
 * See the "Sign-in" diagrams in the README.
 *
 * Order inside each call: limits and code checks first (Redis, no database connection held), then one short
 * database transaction, then notices (SMS, email) after it has committed.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Per caller network (see SignInLimits): codes requested and codes tried are counted per hour. */
    static final Duration HOUR = Duration.ofHours(1);
    /** Email codes one account may request per day (each goes out through our mail account). */
    static final int EMAIL_SENDS_PER_ACCOUNT_PER_DAY = 10;
    /**
     * A swapped refresh token coming back within this time is treated as the app retrying (a reply lost on a weak
     * mobile network, or two calls at once), not as a copy: it gets a new pair under the refresh grace (see
     * refresh()), otherwise just "sign in again" without signing out every device. Later than this, it means the
     * token was copied.
     */
    static final Duration RETRY_GRACE = Duration.ofSeconds(60);
    /** A person's name: 2 to 60 characters after cleaning (the column allows 100). */
    static final TextField NAME = new TextField("name", 2, 60);

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final OtpService otp;
    private final SmsSender sms;
    private final EmailSender email;
    private final JwtService jwt;
    private final Clock clock;
    private final Duration refreshValidity;
    private final RateLimiter limits;
    private final SessionRevocations revocations;
    private final BackgroundTasks background;
    private final TransactionTemplate tx;
    private final ShopMemberships memberships;
    private final HumanCheck humans;
    private final List<AccountDeletionListener> deletionListeners;
    private final List<PersonDetailsListener> detailsListeners;
    /** See refresh(): how long a swapped token may come back for a new pair while its successor is unused. */
    private final Duration refreshGrace;
    private final List<SignOutEverywhereListener> signOutListeners;
    private final SignInLimits signInLimits;
    private final TestModeService testMode;
    private final SmsBudget smsBudget;
    private final RefreshTokenStore tokenStore;
    private final TestAccountShops testAccountShops;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, OtpService otp,
                       SmsSender sms, EmailSender email, JwtService jwt, JwtProperties props, Clock clock,
                       RateLimiter limits, SessionRevocations revocations, BackgroundTasks background,
                       PlatformTransactionManager transactions, ShopMemberships memberships, HumanCheck humans,
                       List<AccountDeletionListener> deletionListeners,
                       List<SignOutEverywhereListener> signOutListeners,
                       SignInLimits signInLimits, TestModeService testMode,
                       List<PersonDetailsListener> detailsListeners, SmsBudget smsBudget,
                       RefreshTokenStore tokenStore, TestAccountShops testAccountShops,
                       @Value("${app.auth.refresh-grace-seconds:30}") long refreshGraceSeconds) {
        if (refreshGraceSeconds < 0 || refreshGraceSeconds > RETRY_GRACE.toSeconds()) {
            // Longer than the retry time would let an old token get new pairs after it should raise the alarm
            throw new IllegalStateException("app.auth.refresh-grace-seconds must be 0 to " + RETRY_GRACE.toSeconds());
        }
        this.refreshGrace = Duration.ofSeconds(refreshGraceSeconds);
        this.testMode = testMode;
        this.smsBudget = smsBudget;
        this.tokenStore = tokenStore;
        this.testAccountShops = testAccountShops;
        this.detailsListeners = detailsListeners;
        this.memberships = memberships;
        this.signOutListeners = signOutListeners;
        this.humans = humans;
        this.deletionListeners = deletionListeners;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.otp = otp;
        this.sms = sms;
        this.email = email;
        this.jwt = jwt;
        this.clock = clock;
        this.refreshValidity = Duration.ofDays(props.refreshDays());
        this.limits = limits;
        this.revocations = revocations;
        this.background = background;
        this.tx = new TransactionTemplate(transactions);
        this.signInLimits = signInLimits;
    }

    // ---------------- phone OTP sign-in ----------------

    /**
     * OTP test mode (TestModeService): a test number's code is any 6 digits while it is on. Any other number, or
     * anything that isn't 6 digits, goes through the normal check.
     */
    private boolean testCodeAccepted(String phone, String code) {
        return code != null && code.trim().matches("\\d{6}") && phone != null && testMode.acceptsAnyCode(phone);
    }

    public void sendLoginOtp(String phoneInput, String ip, String captchaToken) {
        String phone = PhoneNumbers.normalize(phoneInput);
        limitSends(ip);
        humans.require(captchaToken, ip);
        if (testMode.acceptsAnyCode(phone)) {
            return;   // OTP test mode: a test number signs in with any 6 digits, no SMS
        }
        SmsBudget.To to = SmsBudget.To.signIn(phone);
        smsBudget.requireRoom(to);   // before a new code replaces the one they already have
        String code = otp.issue(OtpPurpose.LOGIN, phone, ip);
        sendSms(to, () -> sms.sendOtp(phone, code));
    }

    /** Correct code: signs in, creating the account the first time this phone is used. */
    public AuthTokens verifyLoginOtp(String phoneInput, String code, String device, String ip) {
        String phone = PhoneNumbers.normalize(phoneInput);
        limitVerifies(ip);
        boolean testSignIn = testCodeAccepted(phone, code);
        if (!testSignIn) {
            otp.verify(OtpPurpose.LOGIN, phone, code, ip);
        }
        return tx.execute(status -> {
            User existing = users.findByPhone(phone).orElse(null);
            if (existing != null) {
                if (testSignIn && (existing.isAdmin() || !existing.isTestAccount())) {
                    // Changed between the check and now (made an admin, or a real account): the real code only
                    throw new ApiException(HttpStatus.BAD_REQUEST, "WRONG_CODE", "That code is not correct");
                }
                if (!testSignIn && existing.isTestAccount()) {
                    // The real holder of a former test number signed in with the SMS code: the account is theirs
                    // now, and every tester still signed in to it is signed out
                    becomeReal(existing);
                }
                return issueTokens(existing, device, false);
            }
            User created;
            try {
                User fresh = new User(UUID.randomUUID(), phone, Instant.now(clock));
                if (testSignIn) {
                    fresh.markTestAccount();   // only such an account ever takes any code (TestModeService)
                }
                created = users.saveAndFlush(fresh);
            } catch (DataIntegrityViolationException e) {
                // The same phone signed up at the same moment from another request: retry once
                throw new ApiException(HttpStatus.CONFLICT, "TRY_AGAIN", "Please try again.");
            }
            return issueTokens(created, device, true);
        });
    }

    /**
     * A test account claimed with a real code: from now on it is the number's real holder's, normal, and nothing the
     * testers left in it stays. Their email goes (it is the recovery address: a tester could otherwise take the
     * account back through "lost phone"), and their name; a shop membership they made ends (a helper or barber leaves;
     * a shop they registered is closed); everyone signed in to it is signed out. The holder starts with a bare
     * account and adds their own email and name.
     */
    private void becomeReal(User user) {
        Instant now = Instant.now(clock);
        // The shop first (its row, then the membership): the order every shop step takes them in, before this person's
        testAccountShops.endFor(user.getId());
        User claimed = users.lockById(user.getId()).orElseThrow();
        claimed.claimByRealHolder(now);
        users.saveAndFlush(claimed);
        refreshTokens.revokeAllForUser(user.getId(), now);
        // Before the new token is made (as recovery does), so the new one carries the new generation
        revocations.revokeAllOrWarn(user.getId(), "Test account claimed by the number's holder");
    }

    // ---------------- refresh and sign-out ----------------

    /**
     * Swaps a refresh token for a new pair (rotation). Each refresh token works once, with one exception, the grace
     * ({@code app.auth.refresh-grace-seconds}, 30; 0 = none): a swapped token coming back within that time of its
     * swap, while the token it was swapped for has never been used, gets a new pair, and that unused token stops
     * working. That is the app reloaded (or lost the reply) while its refresh was on the way and never saved the
     * new pair. Otherwise a swapped token coming back within {@link #RETRY_GRACE} (60 s) just gets "sign in again" on
     * that device; later than that it means it was copied: every session of that person ends, once per copied token
     * (so whoever holds it can't keep signing the owner out).
     * A token swapped by another request that was still running when this one came in (two at once with the same
     * token) gets 409 TRY_AGAIN: the other request's pair is the live one, and the app reads its saved token again.
     * An expired, signed-out or recovered token just gets "sign in again".
     * The person's row is locked for the whole call, so refreshes of one person run one after the other (no two can
     * fork the chain) and none races with "sign out everywhere".
     */
    @Transactional(noRollbackFor = ApiException.class)
    public AuthTokens refresh(String refreshToken, String device) {
        // Swap times are written and compared on the database's clock, the one all copies of the backend share.
        // Six round trips in all: the owner with the arrival time (before waiting for any lock), the person's lock,
        // the token with the time now, the swap, the membership (for the access token) and the new token.
        String hash = Hashing.sha256Hex(nullToEmpty(refreshToken));
        RefreshTokenStore.Arrival arrival = tokenStore.arrival(hash)
                .orElseThrow(() -> unauthorized("INVALID_REFRESH_TOKEN", "Sign in again."));
        Instant arrived = arrival.at();
        User user = users.lockById(arrival.userId()).filter(u -> !u.isDeleted())
                .orElseThrow(() -> unauthorized("INVALID_REFRESH_TOKEN", "Sign in again."));
        // Read now, under the person's lock: a refresh of the same token that held the lock first is seen
        RefreshTokenStore.Stored stored = tokenStore.read(hash)
                .orElseThrow(() -> unauthorized("INVALID_REFRESH_TOKEN", "Sign in again."));
        Instant now = stored.now();

        if (stored.revokedAt() != null) {
            RevokeReason reason = stored.revokeReason();
            boolean rotated = reason == RevokeReason.ROTATED;
            if (rotated && !stored.revokedAt().isBefore(arrived)) {
                // Swapped by a request that was running when this one came in (two at once with the same token)
                throw new ApiException(HttpStatus.CONFLICT, "TRY_AGAIN",
                        "This sign-in was just renewed by another request. Use the newest one.");
            }
            boolean inGrace = !now.isAfter(stored.revokedAt().plus(refreshGrace));
            if (rotated && inGrace && stored.successorUnused()) {
                requireLive(stored, user, now);   // an expired token or a test account out of test mode: no pair
                // The new pair never reached the app (it reloaded): it gets another, the unused one ends. If
                // that one ever comes back after the grace, someone else had this token (see GRACE_REPLACED)
                refreshTokens.revokeIfActive(stored.replacedBy(), now, RevokeReason.GRACE_REPLACED);
                UUID next = UUID.randomUUID();
                refreshTokens.replacedBy(stored.id(), next);
                return issueTokens(user, device != null ? device : stored.device(), false, next);
            }
            // A token replaced under the grace: the real phone presenting it after the grace means the old token was
            // used by someone else. Within the grace it is "sign in again" only.
            boolean copied = reason == RevokeReason.GRACE_REPLACED ? !inGrace
                    // Within the retry time, a swap the grace doesn't cover (its successor was used, or 30 to 60 s)
                    // is still the app retrying: "sign in again" on that device only. Later, it means a copy.
                    : rotated && stored.revokedAt().plus(RETRY_GRACE).isBefore(now);
            if (copied && refreshTokens.markReuseDetected(stored.id()) == 1) {
                refreshTokens.revokeAllForUser(user.getId(), now);
                // The database's sign-out stands even if Redis doesn't answer (an error here would undo it)
                revocations.revokeAllOrWarn(user.getId(), "A copied refresh token came back: signed out everywhere");
                log.warn("Refresh token reused for user {}: all sessions ended", user.getId());
                throw unauthorized("REFRESH_TOKEN_REUSED",
                        "For your safety you were signed out everywhere. Sign in again.");
            }
            throw unauthorized("INVALID_REFRESH_TOKEN", "Sign in again.");
        }
        requireLive(stored, user, now);
        UUID next = UUID.randomUUID();
        if (!tokenStore.rotate(stored.id(), now, next)) {
            throw unauthorized("INVALID_REFRESH_TOKEN", "Sign in again.");   // a sign-out of this device just now
        }
        return issueTokens(user, device != null ? device : stored.device(), false, next);
    }

    /** 401 for a token past its 30 days, or a test account whose test mode ended (an active one is ended too). */
    private void requireLive(RefreshTokenStore.Stored stored, User user, Instant now) {
        if (!stored.expiresAt().isAfter(now)) {
            refreshTokens.revokeIfActive(stored.id(), now, RevokeReason.EXPIRED);
            throw unauthorized("REFRESH_TOKEN_EXPIRED", "Sign in again.");
        }
        if (user.isTestAccount() && !testMode.acceptsAnyCode(user.getPhone())) {
            // A test account lives only while OTP test mode is on for its number
            refreshTokens.revokeIfActive(stored.id(), now, RevokeReason.EXPIRED);
            throw unauthorized("TEST_MODE_OFF", "Test mode is off. Sign in again.");
        }
    }

    /** Ends this device's session. Always succeeds, even for an unknown token. */
    @Transactional
    public void logout(String refreshToken) {
        refreshTokens.findByTokenHash(Hashing.sha256Hex(nullToEmpty(refreshToken)))
                .ifPresent(t -> refreshTokens.revokeIfActive(t.getId(), Instant.now(clock), RevokeReason.SIGNED_OUT));
    }

    /** Signs this person out of every device at once (a lost phone, a shared laptop). */
    @Transactional
    public void logoutEverywhere(UUID userId) {
        me(userId);
        refreshTokens.revokeAllForUser(userId, Instant.now(clock));
        signOutListeners.forEach(l -> l.signedOutEverywhere(userId));   // a lost phone stops getting messages too
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    revocations.revokeAll(userId);   // access tokens in use stop at once too
                } catch (DataAccessException redisDown) {
                    // Done in the database (no refresh works, no device gets messages); tokens in use expire
                    // within 15 minutes. An error here would wrongly tell the person nothing happened.
                    log.warn("Signed out everywhere for {}, but Redis didn't take it ({})", userId,
                            redisDown.getClass().getSimpleName());
                }
            }
        });
    }

    // ---------------- email verification ----------------

    /**
     * Replacing an already verified email (the recovery address) needs a code from the person's own phone first,
     * so someone who briefly gets hold of a signed-in token can't swap in their own email and take the account.
     */
    public void sendEmailChangePhoneCode(UUID userId, String ip) {
        User user = me(userId);
        limitSends(ip);
        SmsBudget.To to = SmsBudget.To.signedIn(user.getPhone());
        smsBudget.requireRoom(to);   // before a new code replaces the one they already have
        String code = otp.issue(OtpPurpose.CHANGE_EMAIL, user.getPhone(), ip);
        sendSms(to, () -> sms.sendOtp(user.getPhone(), code));
    }

    /**
     * Sends the code even if another account already uses this email: refusing here would let anyone find out which
     * emails have accounts. The clash is reported at verify, to someone who has proved they own the inbox.
     * @param phoneCode required only when replacing an already verified email (see sendEmailChangePhoneCode)
     */
    public void sendEmailCode(UUID userId, String emailInput, String phoneCode, String ip) {
        String address = Emails.normalize(emailInput);
        User user = me(userId);
        limitSends(ip);
        // The daily email limit is checked before the phone code is used up (a refused send keeps the code)
        if (limits.current("email-send-user:" + userId) >= EMAIL_SENDS_PER_ACCOUNT_PER_DAY) {
            throw tooManyEmailCodes(userId);
        }
        if (user.isEmailVerified() && !address.equals(user.getEmail())) {
            if (phoneCode == null || phoneCode.isBlank()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "PHONE_CODE_REQUIRED",
                        "To change your verified email, first confirm with a code sent to your phone.");
            }
            otp.verify(OtpPurpose.CHANGE_EMAIL, user.getPhone(), phoneCode, ip);
        }
        if (!limits.tryHit("email-send-user:" + userId, EMAIL_SENDS_PER_ACCOUNT_PER_DAY, Duration.ofHours(24))) {
            throw tooManyEmailCodes(userId);
        }
        String code = otp.issue(OtpPurpose.EMAIL_VERIFY, userId + ":" + address, ip);
        email.send(address, "Your Clozest verification code",
                "Your Clozest verification code is " + code + ".\n\nIt works for 10 minutes. "
                        + "If you didn't ask for it, ignore this email.");
    }

    public User verifyEmail(UUID userId, String emailInput, String code, String ip) {
        String address = Emails.normalize(emailInput);
        limitVerifies(ip);
        otp.verify(OtpPurpose.EMAIL_VERIFY, userId + ":" + address, code, ip);
        String[] previous = new String[1];
        User saved = tx.execute(status -> {
            User user = users.lockById(userId).filter(u -> !u.isDeleted())   // never onto a deleted account
                    .orElseThrow(() -> unauthorized("NOT_SIGNED_IN", "Sign in first."));
            users.findByVerifiedEmail(address)
                    .filter(other -> !other.getId().equals(userId))
                    .ifPresent(other -> {
                        throw emailInUse();
                    });
            if (user.isEmailVerified() && !address.equals(user.getEmail())) {
                previous[0] = user.getEmail();
            }
            user.verifyEmail(address, Instant.now(clock));
            try {
                return users.saveAndFlush(user);
            } catch (DataIntegrityViolationException e) {
                throw emailInUse();
            }
        });
        if (previous[0] != null) {
            String old = previous[0];
            background.runAlways("email-changed notice", () -> email.send(old, "Your Clozest email was changed",
                    "The recovery email of your Clozest account was changed to " + mask(address) + ".\n\n"
                            + "If this wasn't you, contact Clozest support immediately."));
        }
        return saved;
    }

    // ---------------- account recovery (lost phone number) ----------------

    /**
     * Step 1: sends a code to the verified email and an OTP to the new phone.
     * Behaves exactly the same whether or not the email has an account: same limits, the SMS is always sent, and
     * the email goes out in the background, so not even the response time differs.
     */
    public void startRecovery(String emailInput, String newPhoneInput, String ip, String captchaToken) {
        String address = Emails.normalize(emailInput);
        String newPhone = PhoneNumbers.normalize(newPhoneInput);
        limitSends(ip);
        humans.require(captchaToken, ip);
        boolean exists = users.findByVerifiedEmail(address).isPresent();
        SmsBudget.To to = SmsBudget.To.recovery(newPhone);
        smsBudget.requireRoom(to);   // before a new code replaces the one they already have
        String emailCode = otp.issue(OtpPurpose.RECOVER_EMAIL, address, ip);
        String phoneCode = otp.issue(OtpPurpose.RECOVER_PHONE, newPhone, ip);
        if (exists) {
            background.run("recovery email", () -> email.send(address, "Recover your Clozest account",
                    "Your recovery code is " + emailCode + ".\n\nIt works for 10 minutes. "
                            + "If you didn't ask for this, ignore this email; your account stays as it is."));
        }
        sendSms(to, () -> sms.sendOtp(newPhone, phoneCode));
    }

    /**
     * Step 2: both codes correct → the account moves to the new phone and every old session ends at once.
     * After the change is saved, the old phone and the email are told (so a real owner notices).
     */
    public AuthTokens completeRecovery(String emailInput, String emailCode, String newPhoneInput, String phoneCode,
                                       String device, String ip) {
        String address = Emails.normalize(emailInput);
        String newPhone = PhoneNumbers.normalize(newPhoneInput);
        limitVerifies(ip);
        // Codes first: without them, the answer never depends on whether the account exists.
        // Both are checked before either is used up: a typo in one keeps the other for the next try
        otp.check(OtpPurpose.RECOVER_EMAIL, address, emailCode, ip);
        otp.check(OtpPurpose.RECOVER_PHONE, newPhone, phoneCode, ip);
        otp.consume(OtpPurpose.RECOVER_EMAIL, address);
        otp.consume(OtpPurpose.RECOVER_PHONE, newPhone);
        String[] oldPhone = new String[1];
        AuthTokens tokens = tx.execute(status -> {
            UUID userId = users.findIdByVerifiedEmail(address)
                    .orElseThrow(() -> ApiException.badRequest("RECOVERY_FAILED", "These details don't match an account"));
            // Others' screens are told before the row lock (PersonDetailsListener), and only if the phone changes
            boolean told = users.findPhoneById(userId).map(p -> !newPhone.equals(p)).orElse(true);
            if (told) {
                detailsListeners.forEach(l -> l.detailsChanging(userId));
            }
            // Locked and read fresh: an account deleted a moment ago is never brought back by moving it
            User user = users.lockById(userId).filter(u -> !u.isDeleted())
                    .orElseThrow(() -> ApiException.badRequest("RECOVERY_FAILED", "These details don't match an account"));
            if (!told && !newPhone.equals(user.getPhone())) {
                throw tryAgain();   // moved by another request in between: start again, so the screens are told
            }
            users.findByPhone(newPhone)
                    .filter(other -> !other.getId().equals(userId))
                    .ifPresent(other -> {
                        throw phoneInUse();
                    });
            oldPhone[0] = user.getPhone();
            Instant now = Instant.now(clock);
            user.changePhone(newPhone, now);
            user.becomeRealAccount(now);   // proved with real codes to a real phone and inbox (if a test account)
            try {
                users.saveAndFlush(user);
            } catch (DataIntegrityViolationException e) {
                throw phoneInUse();
            }
            refreshTokens.revokeAllForUser(userId, now);
            signOutListeners.forEach(l -> l.signedOutEverywhere(userId));   // the old phone stops getting messages
            revocations.revokeAll(userId);
            return issueTokens(user, device, false);
        });
        // Saved: now tell the owner. A failed notice never undoes the recovery.
        String ending = newPhone.substring(6);
        String previousPhone = oldPhone[0];
        if (!previousPhone.equals(newPhone)) {
            // Not under the daily SMS cap: this warning must get out even if someone used up the cap
            background.runAlways("account-moved SMS", () -> sms.sendAccountMoved(previousPhone, ending));
        }
        background.runAlways("account-moved email", () -> email.send(address, "Your Clozest account moved to a new phone",
                "Your account now uses the phone number ending " + ending + ". Every other device was signed out."
                        + "\n\nIf this wasn't you, contact Clozest support immediately."));
        return tokens;
    }

    public User me(UUID userId) {
        return users.findById(userId).filter(u -> !u.isDeleted())
                .orElseThrow(() -> unauthorized("NOT_SIGNED_IN", "Sign in first."));
    }

    // ---------------- deleting the account ----------------

    /**
     * Step 1: a code to the person's own phone, so a borrowed or stolen session can't delete the account.
     * 409 SHOP_OWNER for a shop owner: they close or hand over the shop first.
     */
    public void sendDeleteCode(UUID userId, String ip) {
        User user = me(userId);
        requireNotOwner(userId);
        Instant at = Instant.now(clock);
        deletionListeners.forEach(l -> l.checkCanDelete(userId, at));   // e.g. a booking about to start
        limitSends(ip);
        SmsBudget.To to = SmsBudget.To.signedIn(user.getPhone());
        smsBudget.requireRoom(to);   // before a new code replaces the one they already have
        String code = otp.issue(OtpPurpose.DELETE_ACCOUNT, user.getPhone(), ip);
        sendSms(to, () -> sms.sendOtp(user.getPhone(), code));
    }

    /**
     * Step 2: with the code, the account is deleted, in one transaction:
     * <ul>
     *   <li>every module cleans up what is still open (AccountDeletionListener): upcoming salon bookings are
     *       cancelled (the salon is told; no strike), a barber's sign-in is unlinked, waiting messages and devices
     *       go;</li>
     *   <li>a helper or barber leaves their shop;</li>
     *   <li>every device is signed out, and the phone, name and email are wiped (User.anonymise).</li>
     * </ul>
     * Past bookings, strikes and shop records stay, linked to the nameless account. The phone can sign up again
     * as a new account.
     */
    public void deleteAccount(UUID userId, String code, String ip) {
        User me = me(userId);
        limitVerifies(ip);
        // Refused before the code is used up, so it still works once the booking is over (checked again below)
        Instant at = Instant.now(clock);
        deletionListeners.forEach(l -> l.checkCanDelete(userId, at));
        otp.verify(OtpPurpose.DELETE_ACCOUNT, me.getPhone(), code, ip);
        tx.executeWithoutResult(status -> {
            requireNotOwner(userId);
            Instant now = Instant.now(clock);
            // The modules first: they take their own locks (a customer's bookings) before this person's row,
            // the order a booking takes them in too
            deletionListeners.forEach(l -> l.accountDeleted(userId, now));
            memberships.find(userId).ifPresent(m -> memberships.removeMember(m.shopId(), userId));
            User user = users.lockById(userId).filter(u -> !u.isDeleted())
                    .orElseThrow(() -> unauthorized("NOT_SIGNED_IN", "Sign in first."));   // deleted meanwhile
            requireNotOwner(userId);   // again, under the lock: a shop registered meanwhile held this row first
            refreshTokens.revokeAllForUser(userId, now);
            refreshTokens.forgetDevices(userId);
            user.anonymise(now);
            users.saveAndFlush(user);
        });
        try {
            revocations.revokeAll(userId);   // after the commit: tokens still in use stop working now
        } catch (DataAccessException redisDown) {
            // The account is gone either way: its tokens find no live account (BookingService, me()) and expire
            // within 15 minutes. Answering an error here would tell the app nothing was done.
            log.warn("Account {} deleted, but Redis didn't take the sign-out ({})", userId,
                    redisDown.getClass().getSimpleName());
        }
    }

    private ApiException tooManyEmailCodes(UUID userId) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_CODES",
                "Too many email codes today. Try again tomorrow.")
                .retryAfter(limits.secondsLeft("email-send-user:" + userId));
    }

    private void requireNotOwner(UUID userId) {
        if (memberships.find(userId).filter(m -> "OWNER".equals(m.role())).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "SHOP_OWNER",
                    "You own a shop on Clozest. Close it or hand it over before deleting your account.");
        }
        // Without the super admin nobody could manage admins or switch test mode off until the database is edited
        if (users.findById(userId).map(User::getAdminRole).orElse(AdminRole.NONE) == AdminRole.SUPER_ADMIN) {
            throw new ApiException(HttpStatus.CONFLICT, "SUPER_ADMIN",
                    "You are the super admin. Hand the role to someone else in the database first.");
        }
    }

    /** Sets the person's name, cleaned like shop names (see TextCleaner). */
    @Transactional
    public User rename(UUID userId, String name) {
        // Locked: saving a copy read before a deletion committed would write the old phone and email back
        String cleaned = TextCleaner.clean(NAME, name);
        // Others' screens are told before the row lock (PersonDetailsListener), and only if the name changes
        boolean told = users.findNameById(userId).map(n -> !cleaned.equals(n)).orElse(true);
        if (told) {
            detailsListeners.forEach(l -> l.detailsChanging(userId));
        }
        User user = users.lockById(userId).filter(u -> !u.isDeleted())
                .orElseThrow(() -> unauthorized("NOT_SIGNED_IN", "Sign in first."));
        if (!told && !cleaned.equals(user.getName())) {
            throw tryAgain();   // renamed by another request in between: start again, so the screens are told
        }
        if (!cleaned.equals(user.getName())) {
            user.rename(cleaned, Instant.now(clock));
            users.saveAndFlush(user);
        }
        return user;
    }

    // ---------------- helpers ----------------

    private AuthTokens issueTokens(User user, String device, boolean newUser) {
        return issueTokens(user, device, newUser, UUID.randomUUID());
    }

    /** @param refreshId the new refresh token's id (a swap records it on the token it replaces) */
    private AuthTokens issueTokens(User user, String device, boolean newUser, UUID refreshId) {
        Instant now = Instant.now(clock);
        // The shop the person works in, and their role there, go into the token (looked up fresh every time)
        ShopMemberships.Membership membership = memberships.find(user.getId()).orElse(null);
        JwtService.IssuedToken access = jwt.issue(new AuthPrincipal(user.getId(),
                        membership == null ? null : membership.shopId(),
                        membership == null ? null : membership.role(), user.isAdmin()),
                revocations.generationForNewToken(user.getId()));
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant refreshExpires = now.plus(refreshValidity);
        tokenStore.insert(refreshId, user.getId(), Hashing.sha256Hex(refresh), trimDevice(device), now,
                refreshExpires);
        return new AuthTokens(access.token(), access.expiresAt(), refresh, refreshExpires, user, newUser);
    }

    private void limitSends(String ip) {
        String network = ClientIp.limitKey(ip);
        String key = "ip-send:" + network;
        if (!limits.tryHit(key, signInLimits.sendsPerHour(network), HOUR)) {
            throw tooManyRequests(limits.secondsLeft(key));
        }
    }

    private void limitVerifies(String ip) {
        String network = ClientIp.limitKey(ip);
        String key = "ip-verify:" + network;
        if (!limits.tryHit(key, signInLimits.verifiesPerHour(network), HOUR)) {
            throw tooManyRequests(limits.secondsLeft(key));
        }
    }

    /**
     * Every code SMS goes through here: the SMS budget (SmsBudget: a daily budget, an hourly burst cap, and people who
     * signed in recently first near the cap) protects the bill even if all other limits are dodged.
     */
    private void sendSms(SmsBudget.To to, Runnable send) {
        smsBudget.spend(to);   // counted only: the room was checked before the code was made (requireRoom)
        send.run();
    }

    /** "shop.owner@gmail.com" → "s***@gmail.com", for notices that must not repeat the full new address. */
    static String mask(String address) {
        int at = address.indexOf('@');
        return at <= 1 ? "***" + address.substring(Math.max(at, 0)) : address.charAt(0) + "***" + address.substring(at);
    }

    private static ApiException tooManyRequests(long retryAfter) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                "Too many requests from this network. Try again later.").retryAfter(retryAfter);
    }

    private static ApiException emailInUse() {
        return new ApiException(HttpStatus.CONFLICT, "EMAIL_IN_USE", "This email is already used by another account");
    }

    private static ApiException phoneInUse() {
        return new ApiException(HttpStatus.CONFLICT, "PHONE_IN_USE", "This phone number already has its own account");
    }

    private static ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String trimDevice(String device) {
        if (device == null || device.isBlank()) {
            return null;
        }
        return device.length() > 100 ? device.substring(0, 100) : device;
    }

    /** Two requests changed the person at once; nothing was saved by this one, sending it again works. */
    private static ApiException tryAgain() {
        return new ApiException(HttpStatus.CONFLICT, "TRY_AGAIN",
                "Someone else changed the same thing at the same moment. Try again.");
    }
}
