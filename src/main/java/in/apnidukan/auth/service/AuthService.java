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
import in.apnidukan.common.context.PhoneChangeListener;
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
    /** OTP test mode: tries at verifying a test email with any code, per account (TestModeService). */
    static final int TEST_EMAIL_VERIFIES_PER_ACCOUNT_PER_HOUR = 10;
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
    private final List<PhoneChangeListener> phoneListeners;
    private final SignInLimits signInLimits;
    private final TestModeService testMode;
    private final SmsBudget smsBudget;
    private final RefreshTokenStore tokenStore;
    private final TestAccountShops testAccountShops;
    private final AdminTeamService team;
    private final EditUnlockStore editUnlocks;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, OtpService otp,
                       SmsSender sms, EmailSender email, JwtService jwt, JwtProperties props, Clock clock,
                       RateLimiter limits, SessionRevocations revocations, BackgroundTasks background,
                       PlatformTransactionManager transactions, ShopMemberships memberships, HumanCheck humans,
                       List<AccountDeletionListener> deletionListeners,
                       List<SignOutEverywhereListener> signOutListeners,
                       SignInLimits signInLimits, TestModeService testMode,
                       List<PersonDetailsListener> detailsListeners, SmsBudget smsBudget,
                       RefreshTokenStore tokenStore, TestAccountShops testAccountShops, AdminTeamService team,
                       List<PhoneChangeListener> phoneListeners, EditUnlockStore editUnlocks,
                       @Value("${app.auth.refresh-grace-seconds:30}") long refreshGraceSeconds) {
        if (refreshGraceSeconds < 0 || refreshGraceSeconds > RETRY_GRACE.toSeconds()) {
            // Longer than the retry time would let an old token get new pairs after it should raise the alarm
            throw new IllegalStateException("app.auth.refresh-grace-seconds must be 0 to " + RETRY_GRACE.toSeconds());
        }
        this.refreshGrace = Duration.ofSeconds(refreshGraceSeconds);
        this.phoneListeners = phoneListeners;
        this.editUnlocks = editUnlocks;
        this.team = team;
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

    /**
     * OTP test mode for the email step: a test account verifying an email on the test list
     * (TestModeService#acceptsAnyEmailCode) gets no email and takes any 6 digits. Any other email, a real account, an
     * admin, or test mode off: the real code, as always. Never used for recovery or account deletion.
     */
    private boolean testEmail(UUID userId, String address) {
        return testMode.acceptsAnyEmailCode(userId, address);
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
        if (testEmail(userId, address)) {
            return;   // OTP test mode: a test account's test address takes any 6 digits, and no email goes out
        }
        String code = otp.issue(OtpPurpose.EMAIL_VERIFY, userId + ":" + address, ip);
        email.send(address, "Your Clozest verification code",
                "Your Clozest verification code is " + code + ".\n\nIt works for 10 minutes. "
                        + "If you didn't ask for it, ignore this email.");
    }

    /**
     * Whether a real person who just proved, with the real code, that this inbox is theirs takes it from its holder:
     * only from a test account, never an admin's, and only while (read under its row lock) it still holds exactly
     * this verified email.
     */
    static boolean releasable(User holder, String address) {
        return holder.isTestAccount() && !holder.isAdmin() && holder.isEmailVerified()
                && address.equalsIgnoreCase(holder.getEmail());
    }

    public User verifyEmail(UUID userId, String emailInput, String code, String ip) {
        String address = Emails.normalize(emailInput);
        limitVerifies(ip);
        boolean testCode = code != null && code.trim().matches("\\d{6}") && testEmail(userId, address);
        if (testCode && !limits.tryHit("email-test-verify-user:" + userId, TEST_EMAIL_VERIFIES_PER_ACCOUNT_PER_HOUR,
                HOUR)) {
            // Any code works here, so tries are capped per account too (the normal path's code locks after 5 wrong)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                    "Too many tries. Try again later.")
                    .retryAfter(limits.secondsLeft("email-test-verify-user:" + userId));
        }
        if (!testCode) {
            otp.verify(OtpPurpose.EMAIL_VERIFY, userId + ":" + address, code, ip);
        }
        String[] previous = new String[1];
        boolean[] testAccount = new boolean[1];
        User saved = tx.execute(status -> {
            User user = users.lockById(userId).filter(u -> !u.isDeleted())   // never onto a deleted account
                    .orElseThrow(() -> unauthorized("NOT_SIGNED_IN", "Sign in first."));
            if (testCode && (user.isAdmin() || !user.isTestAccount()
                    || !testMode.acceptsAnyEmailCodeHeld(userId, address))) {
                // Changed between the check and now (test mode off, number or email removed, made an admin, or
                // claimed by the number's holder): the real code only, and nothing is stored. Passing, it holds the
                // test-mode, number and email rows until the save commits, so none of those changes can land
                // in between
                throw new ApiException(HttpStatus.BAD_REQUEST, "WRONG_CODE", "That code is not correct");
            }
            if (testCode && user.isEmailVerified() && !address.equals(user.getEmail())) {
                // Replacing a verified email needs the phone code first (sendEmailCode); the test path skips that
                // step, so it never replaces one
                throw new ApiException(HttpStatus.FORBIDDEN, "PHONE_CODE_REQUIRED",
                        "To change your verified email, first confirm with a code sent to your phone.");
            }
            UUID holderId = users.findIdByVerifiedEmail(address)
                    .filter(other -> !other.equals(userId))
                    .orElse(null);
            if (holderId != null) {
                if (testCode) {
                    throw emailInUse();   // a test account never takes an email from anyone (not even locked)
                }
                // Locked and read fresh (only the id was looked up), so what is checked is what is changed
                User holder = users.lockById(holderId).orElseThrow(AuthService::emailInUse);
                if (!releasable(holder, address)) {
                    throw emailInUse();
                }
                // Someone proved with the real code that the inbox is theirs, and a test account holds it: the test
                // account lets it go, so a tester never blocks a real person's email
                holder.releaseEmail(Instant.now(clock));
                users.saveAndFlush(holder);
            }
            if (user.isEmailVerified() && !address.equals(user.getEmail())) {
                previous[0] = user.getEmail();
                testAccount[0] = user.isTestAccount();
            }
            user.verifyEmail(address, Instant.now(clock));
            try {
                return users.saveAndFlush(user);
            } catch (DataIntegrityViolationException e) {
                throw emailInUse();
            }
        });
        // No notice only for a test account's test email (no real inbox behind it); a real account always gets one,
        // even at an address on the test list
        if (previous[0] != null && !(testAccount[0] && testMode.isTestEmail(previous[0]))) {
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

    // ---------------- changing the phone number (signed in) ----------------

    /** Phone-change codes one account may ask for a day (each SMS goes to a number of their choosing). */
    static final int PHONE_CHANGES_PER_ACCOUNT_PER_DAY = 5;

    /** Where the second code went: the verified email, or (without one) the number the account has now. */
    public enum SecondCode { EMAIL, PHONE }

    /** Step 1's answer: where the second code went, masked ("r***@gmail.com", "ending 2345"). */
    public record PhoneChangeSent(SecondCode secondCodeTo, String sentTo) {
    }

    /**
     * Step 1: a code to the new number, and a second code so a borrowed or stolen session can't move the account
     * alone: to the verified email, or, without one, to the number the account has now. Each code is bound to this
     * account and this new number.
     * <ul>
     *   <li>400 INVALID_PHONE, 400 SAME_PHONE;</li>
     *   <li>403 ADMIN_PHONE: an admin's number is changed on the admin Team page (its edits carry a version);</li>
     *   <li>409 TEST_ACCOUNT: a test account can't take a real number (sign in with that number instead);</li>
     *   <li>409 TEST_PHONE: a test number can't be anyone's real number;</li>
     *   <li>409 PHONE_IN_USE: another account has it. Counted in the day's tries like a send, so the answer can't be
     *       used to look up numbers freely;</li>
     *   <li>429 TOO_MANY_CODES past {@value #PHONE_CHANGES_PER_ACCOUNT_PER_DAY} tries a day, and the usual limits.</li>
     * </ul>
     */
    public PhoneChangeSent sendPhoneChangeCode(UUID userId, String newPhoneInput, String ip) {
        User user = me(userId);
        String newPhone = PhoneNumbers.normalize(newPhoneInput);
        if (newPhone.equals(user.getPhone())) {
            throw ApiException.badRequest("SAME_PHONE", "This is already your number.");
        }
        requireMayChangePhone(user, newPhone);
        limitSends(ip);
        String dayKey = "phone-change-user:" + userId;
        if (!limits.tryHit(dayKey, PHONE_CHANGES_PER_ACCOUNT_PER_DAY, Duration.ofHours(24))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_CODES",
                    "You've asked for codes to change your number " + PHONE_CHANGES_PER_ACCOUNT_PER_DAY
                            + " times today. Try again tomorrow.").retryAfter(limits.secondsLeft(dayKey));
        }
        if (users.findByPhone(newPhone).isPresent()) {
            throw phoneInUse();   // after the day's count: at most 5 numbers a day can be asked about
        }
        SmsBudget.To to = SmsBudget.To.recovery(newPhone);   // a number new to us: counted as new
        smsBudget.requireRoom(to);
        String address = user.isEmailVerified() ? user.getEmail() : null;
        // The second code first: if its own limits refuse, no code to the new number is left behind unsent
        String second;
        if (address != null) {
            second = otp.issue(OtpPurpose.CHANGE_PHONE_EMAIL, secondTarget(userId, address, newPhone), ip);
        } else {
            SmsBudget.To own = SmsBudget.To.signedIn(user.getPhone());
            smsBudget.requireRoom(own);
            second = otp.issue(OtpPurpose.CHANGE_PHONE_CURRENT, secondTarget(userId, user.getPhone(), newPhone), ip);
        }
        String phoneCode = otp.issue(OtpPurpose.CHANGE_PHONE, userId + ":" + newPhone, ip);
        String ending = newPhone.substring(6);
        sendSms(to, () -> sms.sendOtp(newPhone, phoneCode));
        if (address != null) {
            background.run("phone-change email", () -> email.send(address, "Change your Clozest phone number",
                    "Your code to move your Clozest account to the phone number ending " + ending + " is " + second
                            + ".\n\nIt works for 10 minutes. If you didn't ask for this, don't share it with anyone "
                            + "and contact Clozest support."));
            return new PhoneChangeSent(SecondCode.EMAIL, mask(address));
        }
        String current = user.getPhone();
        sendSms(SmsBudget.To.signedIn(current), () -> sms.sendOtp(current, second));
        return new PhoneChangeSent(SecondCode.PHONE, "ending " + current.substring(6));
    }

    /** A second code's target: this account, where it went, and the number it allows moving to. */
    private static String secondTarget(UUID userId, String sentTo, String newPhone) {
        return userId + ":" + sentTo + ":" + newPhone;
    }

    /** The refusals that don't depend on the codes (also checked again under the lock). */
    private void requireMayChangePhone(User user, String newPhone) {
        if (user.isAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_PHONE",
                    "An admin's number is changed by the super admin, on the Team page.");
        }
        if (user.isTestAccount()) {
            throw new ApiException(HttpStatus.CONFLICT, "TEST_ACCOUNT",
                    "A test account can't move to another number.");
        }
        if (testMode.isTestPhone(newPhone)) {
            throw new ApiException(HttpStatus.CONFLICT, "TEST_PHONE", "This number can't be used. Use another one.");
        }
    }

    /**
     * Step 2: both codes right → the account (its bookings, shop and history: the same account) moves to the new
     * number, waiting shop invitations and hand-over requests move with it, every other device is signed out, and this
     * one gets new tokens. The old number and the email are told afterwards. The codes are used up only in the same
     * transaction as the change, so a refusal or a failure on the way leaves them for another try.
     * 400 SEND_CODES_AGAIN (the account's email changed since the codes were sent: the second code went elsewhere);
     * the code errors (WRONG_CODE, CODE_EXPIRED, TOO_MANY_ATTEMPTS); 409 PHONE_IN_USE (taken meanwhile); the step 1
     * refusals.
     */
    public AuthTokens changePhone(UUID userId, String newPhoneInput, String phoneCode, String secondCode, String device,
                                  String ip) {
        String newPhone = PhoneNumbers.normalize(newPhoneInput);
        limitVerifies(ip);
        User me = me(userId);
        if (secondCode == null || secondCode.isBlank()) {
            throw ApiException.badRequest("SEND_CODES_AGAIN", "Enter both codes.");
        }
        Second expected = secondOf(me, newPhone);
        // Both checked before either is used up: a typo in one keeps the other
        otp.check(OtpPurpose.CHANGE_PHONE, userId + ":" + newPhone, phoneCode, ip);
        otp.check(expected.purpose(), expected.target(), secondCode, ip);
        String[] oldPhone = new String[1];
        String[] address = new String[1];
        AuthTokens tokens = tx.execute(status -> {
            // Others' screens (a salon's customer details) are told before the row lock (PersonDetailsListener)
            detailsListeners.forEach(l -> l.detailsChanging(userId));
            User user = users.lockById(userId).filter(u -> !u.isDeleted())
                    .orElseThrow(() -> unauthorized("NOT_SIGNED_IN", "Sign in first."));
            if (newPhone.equals(user.getPhone())) {
                throw ApiException.badRequest("SAME_PHONE", "This is already your number.");
            }
            requireMayChangePhone(user, newPhone);
            if (!secondOf(user, newPhone).equals(expected)) {
                // The email was verified, changed or replaced since the codes were checked above
                throw ApiException.badRequest("SEND_CODES_AGAIN",
                        "Your account changed since the codes were sent. Send the codes again.");
            }
            users.findByPhone(newPhone).filter(other -> !other.getId().equals(userId)).ifPresent(other -> {
                throw phoneInUse();
            });
            oldPhone[0] = user.getPhone();
            address[0] = user.isEmailVerified() ? user.getEmail() : null;
            Instant now = Instant.now(clock);
            user.changePhone(newPhone, now);
            try {
                users.saveAndFlush(user);
            } catch (DataIntegrityViolationException e) {
                throw phoneInUse();   // taken by a sign-up at this very moment
            }
            phoneListeners.forEach(l -> l.phoneChanged(userId, oldPhone[0], newPhone));
            refreshTokens.revokeAllForUser(userId, now);
            signOutListeners.forEach(l -> l.signedOutEverywhere(userId));   // the old phone stops getting messages
            revocations.revokeAll(userId);
            // The codes are used up last, still inside: anything refused or failing above keeps them (and one used
            // by another request meanwhile undoes this one: CODE_EXPIRED)
            otp.consume(OtpPurpose.CHANGE_PHONE, userId + ":" + newPhone);
            otp.consume(expected.purpose(), expected.target());
            return issueTokens(user, device, false);
        });
        String ending = newPhone.substring(6);
        String previousPhone = oldPhone[0];
        // Not under the daily SMS cap: this warning must get out
        background.runAlways("phone-changed SMS", () -> sms.sendAccountMoved(previousPhone, ending));
        if (address[0] != null) {
            String to = address[0];
            background.runAlways("phone-changed email", () -> email.send(to,
                    "Your Clozest account moved to a new phone",
                    "Your account now uses the phone number ending " + ending + ". Every other device was signed out."
                            + "\n\nIf this wasn't you, contact Clozest support immediately."));
        }
        return tokens;
    }

    /** Which second code this account needs for this move, and its key. */
    private record Second(OtpPurpose purpose, String target) {
    }

    private static Second secondOf(User user, String newPhone) {
        return user.isEmailVerified()
                ? new Second(OtpPurpose.CHANGE_PHONE_EMAIL, secondTarget(user.getId(), user.getEmail(), newPhone))
                : new Second(OtpPurpose.CHANGE_PHONE_CURRENT, secondTarget(user.getId(), user.getPhone(), newPhone));
    }

    // ---------------- unlocking a verified shop's details for editing ----------------

    /**
     * A code to the person's own phone, so a borrowed or stolen session can't change a verified shop's details
     * (EditUnlockStore). A test number in OTP test mode gets no SMS and takes any 6 digits, as at sign-in.
     */
    public void sendEditCode(UUID userId, String ip) {
        User user = me(userId);
        // Only a shop's owner edits its details: nobody else can make the server send these codes
        if (memberships.find(userId).filter(m -> "OWNER".equals(m.role())).isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only a shop's owner needs this code.");
        }
        limitSends(ip);
        if (testMode.acceptsAnyCode(user.getPhone())) {
            return;
        }
        SmsBudget.To to = SmsBudget.To.signedIn(user.getPhone());
        smsBudget.requireRoom(to);   // before a new code replaces the one they already have
        String code = otp.issue(OtpPurpose.EDIT_UNLOCK, user.getPhone(), ip);
        sendSms(to, () -> sms.sendOtp(user.getPhone(), code));
    }

    /**
     * With the code from sendEditCode (or any 6 digits for a test number in test mode), editing is unlocked for 15
     * minutes; returns until when. 400 WRONG_CODE / CODE_EXPIRED as for other codes.
     */
    public Instant unlockEditing(UUID userId, String code, String ip) {
        User user = me(userId);
        limitVerifies(ip);
        if (!testCodeAccepted(user.getPhone(), code)) {
            otp.verify(OtpPurpose.EDIT_UNLOCK, user.getPhone(), code, ip);
        }
        return editUnlocks.unlock(userId);
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
            team.accountDeleting(userId);   // a team member: the team lock first, as every team change takes it
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
            team.accountDeleted(userId);   // a field agent's pincodes go, their waiting visits to someone else
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
        // The last super admin gone, nobody could manage admins or switch test mode off until the database is edited
        if (users.findById(userId).map(User::getAdminRole).orElse(AdminRole.NONE) == AdminRole.SUPER_ADMIN) {
            throw new ApiException(HttpStatus.CONFLICT, "SUPER_ADMIN",
                    "You are a super admin. Ask another super admin to change your role on the Team page first.");
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

    /**
     * A verified email belongs to one account (it is how a lost number is recovered). Said only at verify, after the
     * code (on the normal path, the code emailed to that inbox; in test mode, for a test-list email, any code), so
     * sending a code tells nobody whether an address has an account. The words say what to do, since the usual cause is one person with two numbers (the customer app on one SIM, the
     * partner app on the other).
     */
    private static ApiException emailInUse() {
        return new ApiException(HttpStatus.CONFLICT, "EMAIL_IN_USE", "This email is already on another Clozest "
                + "account, which uses a different mobile number. Sign in with that number instead, or use another email.");
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
