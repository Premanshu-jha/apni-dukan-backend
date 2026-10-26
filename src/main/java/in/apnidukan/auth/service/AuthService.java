package in.apnidukan.auth.service;

import in.apnidukan.auth.domain.RefreshToken;
import in.apnidukan.auth.domain.RevokeReason;
import in.apnidukan.auth.domain.User;
import in.apnidukan.auth.notify.EmailSender;
import in.apnidukan.auth.notify.SmsSender;
import in.apnidukan.auth.repo.RefreshTokenRepository;
import in.apnidukan.auth.repo.UserRepository;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.JwtProperties;
import in.apnidukan.common.security.JwtService;
import in.apnidukan.common.security.RateLimiter;
import in.apnidukan.common.security.SessionRevocations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Sign-in with phone OTP, refresh-token rotation, sign-out, email verification and account recovery.
 * See the "Sign-in" diagrams in the README.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Per caller IP: codes requested (SMS or email) and codes tried, per hour. Stops one machine spraying numbers. */
    static final int IP_SENDS_PER_HOUR = 10;
    static final int IP_VERIFIES_PER_HOUR = 30;
    static final Duration HOUR = Duration.ofHours(1);

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
    private final int smsDailyLimit;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, OtpService otp,
                       SmsSender sms, EmailSender email, JwtService jwt, JwtProperties props, Clock clock,
                       RateLimiter limits, SessionRevocations revocations,
                       @Value("${app.sms.daily-limit:500}") int smsDailyLimit) {
        this.limits = limits;
        this.revocations = revocations;
        this.smsDailyLimit = smsDailyLimit;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.otp = otp;
        this.sms = sms;
        this.email = email;
        this.jwt = jwt;
        this.clock = clock;
        this.refreshValidity = Duration.ofDays(props.refreshDays());
    }

    // ---------------- phone OTP sign-in ----------------

    public void sendLoginOtp(String phoneInput, String ip) {
        String phone = PhoneNumbers.normalize(phoneInput);
        limitSends(ip);
        String code = otp.issue(OtpPurpose.LOGIN, phone);
        sendSms(phone, () -> sms.sendOtp(phone, code));
    }

    /** Correct code: signs in, creating the account the first time this phone is used. */
    @Transactional
    public AuthTokens verifyLoginOtp(String phoneInput, String code, String device, String ip) {
        String phone = PhoneNumbers.normalize(phoneInput);
        limitVerifies(ip);
        otp.verify(OtpPurpose.LOGIN, phone, code);
        boolean created = false;
        User user = users.findByPhone(phone).orElse(null);
        if (user == null) {
            user = users.saveAndFlush(new User(UUID.randomUUID(), phone, Instant.now(clock)));
            created = true;
        }
        return issueTokens(user, device, created);
    }

    // ---------------- refresh and sign-out ----------------

    /**
     * Swaps a refresh token for a new pair (rotation). Each refresh token works once.
     * If an already-swapped token comes back, it was probably copied: every session of that person is ended,
     * once per copied token (so whoever holds it can't keep signing the owner out).
     * A token ended by sign-out or recovery just gets "sign in again": the old device may well still try it.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public AuthTokens refresh(String refreshToken, String device) {
        Instant now = Instant.now(clock);
        RefreshToken stored = refreshTokens.findByTokenHash(Hashing.sha256Hex(nullToEmpty(refreshToken)))
                .orElseThrow(() -> unauthorized("INVALID_REFRESH_TOKEN", "Sign in again."));
        boolean copied = stored.getRevokedAt() != null
                ? stored.getRevokeReason() == RevokeReason.ROTATED
                // Revoked between our read and now: a parallel refresh with the same token just rotated it
                : refreshTokens.revokeIfActive(stored.getId(), now, RevokeReason.ROTATED) == 0;
        if (stored.getRevokedAt() != null && !copied) {
            throw unauthorized("INVALID_REFRESH_TOKEN", "Sign in again.");
        }
        if (copied) {
            if (refreshTokens.markReuseDetected(stored.getId()) == 0) {
                throw unauthorized("INVALID_REFRESH_TOKEN", "Sign in again.");
            }
            refreshTokens.revokeAllForUser(stored.getUserId(), now);
            revocations.revokeAll(stored.getUserId());
            log.warn("Refresh token reused for user {}: all sessions ended", stored.getUserId());
            throw unauthorized("REFRESH_TOKEN_REUSED", "For your safety you were signed out everywhere. Sign in again.");
        }
        if (!stored.getExpiresAt().isAfter(now)) {
            throw unauthorized("REFRESH_TOKEN_EXPIRED", "Sign in again.");
        }
        User user = users.findById(stored.getUserId())
                .orElseThrow(() -> unauthorized("INVALID_REFRESH_TOKEN", "Sign in again."));
        return issueTokens(user, device != null ? device : stored.getDevice(), false);
    }

    /** Ends this device's session. Always succeeds, even for an unknown token. */
    @Transactional
    public void logout(String refreshToken) {
        refreshTokens.findByTokenHash(Hashing.sha256Hex(nullToEmpty(refreshToken)))
                .ifPresent(t -> refreshTokens.revokeIfActive(t.getId(), Instant.now(clock), RevokeReason.SIGNED_OUT));
    }

    // ---------------- email verification ----------------

    /**
     * Sends the code even if another account already uses this email: refusing here would let anyone find out which
     * emails have accounts. The clash is reported at verify, to someone who has proved they own the inbox.
     */
    public void sendEmailCode(UUID userId, String emailInput, String ip) {
        String address = Emails.normalize(emailInput);
        limitSends(ip);
        String code = otp.issue(OtpPurpose.EMAIL_VERIFY, userId + ":" + address);
        email.send(address, "Your Apni Dukan verification code",
                "Your Apni Dukan verification code is " + code + ".\n\nIt works for 10 minutes. "
                        + "If you didn't ask for it, ignore this email.");
    }

    @Transactional
    public User verifyEmail(UUID userId, String emailInput, String code, String ip) {
        String address = Emails.normalize(emailInput);
        limitVerifies(ip);
        otp.verify(OtpPurpose.EMAIL_VERIFY, userId + ":" + address, code);
        User user = users.findById(userId).orElseThrow(() -> unauthorized("NOT_SIGNED_IN", "Sign in first."));
        users.findByVerifiedEmail(address)
                .filter(other -> !other.getId().equals(userId))
                .ifPresent(other -> {
                    throw new ApiException(HttpStatus.CONFLICT, "EMAIL_IN_USE",
                            "This email is already used by another account");
                });
        user.verifyEmail(address, Instant.now(clock));
        try {
            return users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_IN_USE", "This email is already used by another account");
        }
    }

    // ---------------- account recovery (lost phone number) ----------------

    /**
     * Step 1: sends a code to the verified email and an OTP to the new phone.
     * Behaves exactly the same whether or not the email has an account (same limits, the SMS is always sent),
     * so it can't be used to find out who has an account.
     */
    public void startRecovery(String emailInput, String newPhoneInput, String ip) {
        String address = Emails.normalize(emailInput);
        String newPhone = PhoneNumbers.normalize(newPhoneInput);
        limitSends(ip);
        boolean exists = users.findByVerifiedEmail(address).isPresent();
        String emailCode = otp.issue(OtpPurpose.RECOVER_EMAIL, address);
        String phoneCode = otp.issue(OtpPurpose.RECOVER_PHONE, newPhone);
        if (exists) {
            email.send(address, "Recover your Apni Dukan account",
                    "Your recovery code is " + emailCode + ".\n\nIt works for 10 minutes. "
                            + "If you didn't ask for this, ignore this email; your account stays as it is.");
        }
        sendSms(newPhone, () -> sms.sendOtp(newPhone, phoneCode));
    }

    /**
     * Step 2: both codes correct → the account moves to the new phone, every old session ends at once, and the
     * old phone and the email are told (so a real owner notices if someone else did this).
     */
    @Transactional
    public AuthTokens completeRecovery(String emailInput, String emailCode, String newPhoneInput, String phoneCode,
                                       String device, String ip) {
        String address = Emails.normalize(emailInput);
        String newPhone = PhoneNumbers.normalize(newPhoneInput);
        limitVerifies(ip);
        // Codes first: without them, the answer never depends on whether the account exists.
        otp.verify(OtpPurpose.RECOVER_EMAIL, address, emailCode);
        otp.verify(OtpPurpose.RECOVER_PHONE, newPhone, phoneCode);
        User user = users.findByVerifiedEmail(address)
                .orElseThrow(() -> ApiException.badRequest("RECOVERY_FAILED", "These details don't match an account"));
        users.findByPhone(newPhone)
                .filter(other -> !other.getId().equals(user.getId()))
                .ifPresent(other -> {
                    throw new ApiException(HttpStatus.CONFLICT, "PHONE_IN_USE",
                            "This phone number already has its own account");
                });
        String oldPhone = user.getPhone();
        Instant now = Instant.now(clock);
        user.changePhone(newPhone, now);
        users.saveAndFlush(user);
        refreshTokens.revokeAllForUser(user.getId(), now);
        revocations.revokeAll(user.getId());
        String ending = newPhone.substring(6);
        if (!oldPhone.equals(newPhone)) {
            try {
                sendSms(oldPhone, () -> sms.sendAccountMoved(oldPhone, ending));
            } catch (ApiException e) {
                // The owner has already proved the email and the new phone: a failed notice SMS (MSG91 down,
                // daily cap) must not undo the recovery. It is logged, and the email below still goes out.
                log.warn("Could not tell the old phone of user {} about the recovery: {}", user.getId(), e.getCode());
            }
        }
        email.send(address, "Your Apni Dukan account moved to a new phone",
                "Your account now uses the phone number ending " + ending + ". Every other device was signed out."
                        + "\n\nIf this wasn't you, contact Apni Dukan support immediately.");
        return issueTokens(user, device, false);
    }

    public User me(UUID userId) {
        return users.findById(userId).orElseThrow(() -> unauthorized("NOT_SIGNED_IN", "Sign in first."));
    }

    // ---------------- helpers ----------------

    private AuthTokens issueTokens(User user, String device, boolean newUser) {
        Instant now = Instant.now(clock);
        // The shop and role are added to the token once shops exist (next PR).
        JwtService.IssuedToken access = jwt.issue(new AuthPrincipal(user.getId(), null, null, user.isAdmin()),
                revocations.currentGeneration(user.getId()));
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant refreshExpires = now.plus(refreshValidity);
        refreshTokens.save(new RefreshToken(UUID.randomUUID(), user.getId(), Hashing.sha256Hex(refresh),
                trimDevice(device), now, refreshExpires));
        return new AuthTokens(access.token(), access.expiresAt(), refresh, refreshExpires, user, newUser);
    }

    private void limitSends(String ip) {
        if (!limits.tryHit("ip-send:" + ip, IP_SENDS_PER_HOUR, HOUR)) {
            throw tooManyRequests();
        }
    }

    private void limitVerifies(String ip) {
        if (!limits.tryHit("ip-verify:" + ip, IP_VERIFIES_PER_HOUR, HOUR)) {
            throw tooManyRequests();
        }
    }

    /** Every SMS goes through here: a hard daily cap protects the SMS bill even if all other limits are dodged. */
    private void sendSms(String phone, Runnable send) {
        if (!limits.tryHit("sms-day", smsDailyLimit, Duration.ofHours(24))) {
            log.error("Daily SMS limit ({}) reached: SMS paused. Check for abuse before raising SMS_DAILY_LIMIT.",
                    smsDailyLimit);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SMS_PAUSED",
                    "We can't send codes right now. Please try again later.");
        }
        send.run();
    }

    private static ApiException tooManyRequests() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                "Too many requests from this network. Try again in an hour.");
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
}
