package in.apnidukan.auth.service;

import in.apnidukan.auth.domain.RefreshToken;
import in.apnidukan.auth.domain.User;
import in.apnidukan.auth.notify.EmailSender;
import in.apnidukan.auth.notify.SmsSender;
import in.apnidukan.auth.repo.RefreshTokenRepository;
import in.apnidukan.auth.repo.UserRepository;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.security.AuthPrincipal;
import in.apnidukan.common.security.JwtProperties;
import in.apnidukan.common.security.JwtService;
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

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final OtpService otp;
    private final SmsSender sms;
    private final EmailSender email;
    private final JwtService jwt;
    private final Clock clock;
    private final Duration refreshValidity;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, OtpService otp,
                       SmsSender sms, EmailSender email, JwtService jwt, JwtProperties props, Clock clock) {
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

    public void sendLoginOtp(String phoneInput) {
        String phone = PhoneNumbers.normalize(phoneInput);
        String code = otp.issue(OtpPurpose.LOGIN, phone);
        sms.send(phone, "Your Apni Dukan code is " + code + ". It works for 5 minutes. Don't share it with anyone.");
    }

    /** Correct code: signs in, creating the account the first time this phone is used. */
    @Transactional
    public AuthTokens verifyLoginOtp(String phoneInput, String code, String device) {
        String phone = PhoneNumbers.normalize(phoneInput);
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
     * If an already-used token comes back, it was probably copied: every session of that person is ended.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public AuthTokens refresh(String refreshToken, String device) {
        Instant now = Instant.now(clock);
        RefreshToken stored = refreshTokens.findByTokenHash(Hashing.sha256Hex(nullToEmpty(refreshToken)))
                .orElseThrow(() -> unauthorized("INVALID_REFRESH_TOKEN", "Sign in again."));
        if (stored.getRevokedAt() != null || refreshTokens.revokeIfActive(stored.getId(), now) == 0) {
            refreshTokens.revokeAllForUser(stored.getUserId(), now);
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
                .ifPresent(t -> refreshTokens.revokeIfActive(t.getId(), Instant.now(clock)));
    }

    // ---------------- email verification ----------------

    public void sendEmailCode(UUID userId, String emailInput) {
        String address = Emails.normalize(emailInput);
        users.findByVerifiedEmail(address)
                .filter(other -> !other.getId().equals(userId))
                .ifPresent(other -> {
                    throw new ApiException(HttpStatus.CONFLICT, "EMAIL_IN_USE",
                            "This email is already used by another account");
                });
        String code = otp.issue(OtpPurpose.EMAIL_VERIFY, userId + ":" + address);
        email.send(address, "Your Apni Dukan verification code",
                "Your Apni Dukan verification code is " + code + ".\n\nIt works for 10 minutes. "
                        + "If you didn't ask for it, ignore this email.");
    }

    @Transactional
    public User verifyEmail(UUID userId, String emailInput, String code) {
        String address = Emails.normalize(emailInput);
        otp.verify(OtpPurpose.EMAIL_VERIFY, userId + ":" + address, code);
        User user = users.findById(userId).orElseThrow(() -> unauthorized("NOT_SIGNED_IN", "Sign in first."));
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
     * Answers the same way whether or not the email exists, so it can't be used to find out who has an account.
     */
    public void startRecovery(String emailInput, String newPhoneInput) {
        String address = Emails.normalize(emailInput);
        String newPhone = PhoneNumbers.normalize(newPhoneInput);
        if (users.findByVerifiedEmail(address).isEmpty()) {
            return;
        }
        String emailCode = otp.issue(OtpPurpose.RECOVER_EMAIL, address);
        String phoneCode = otp.issue(OtpPurpose.RECOVER_PHONE, newPhone);
        email.send(address, "Recover your Apni Dukan account",
                "Your recovery code is " + emailCode + ".\n\nIt works for 10 minutes. "
                        + "If you didn't ask for this, ignore this email; your account stays as it is.");
        sms.send(newPhone, "Your Apni Dukan recovery code is " + phoneCode + ". Don't share it with anyone.");
    }

    /** Step 2: both codes correct → the account moves to the new phone and every old session is ended. */
    @Transactional
    public AuthTokens completeRecovery(String emailInput, String emailCode, String newPhoneInput, String phoneCode,
                                       String device) {
        String address = Emails.normalize(emailInput);
        String newPhone = PhoneNumbers.normalize(newPhoneInput);
        User user = users.findByVerifiedEmail(address)
                .orElseThrow(() -> ApiException.badRequest("RECOVERY_FAILED", "These details don't match an account"));
        otp.verify(OtpPurpose.RECOVER_EMAIL, address, emailCode);
        otp.verify(OtpPurpose.RECOVER_PHONE, newPhone, phoneCode);
        users.findByPhone(newPhone)
                .filter(other -> !other.getId().equals(user.getId()))
                .ifPresent(other -> {
                    throw new ApiException(HttpStatus.CONFLICT, "PHONE_IN_USE",
                            "This phone number already has its own account");
                });
        Instant now = Instant.now(clock);
        user.changePhone(newPhone, now);
        users.saveAndFlush(user);
        refreshTokens.revokeAllForUser(user.getId(), now);
        return issueTokens(user, device, false);
    }

    public User me(UUID userId) {
        return users.findById(userId).orElseThrow(() -> unauthorized("NOT_SIGNED_IN", "Sign in first."));
    }

    // ---------------- helpers ----------------

    private AuthTokens issueTokens(User user, String device, boolean newUser) {
        Instant now = Instant.now(clock);
        // The shop and role are added to the token once shops exist (next PR).
        JwtService.IssuedToken access = jwt.issue(new AuthPrincipal(user.getId(), null, null, user.isAdmin()));
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant refreshExpires = now.plus(refreshValidity);
        refreshTokens.save(new RefreshToken(UUID.randomUUID(), user.getId(), Hashing.sha256Hex(refresh),
                trimDevice(device), now, refreshExpires));
        return new AuthTokens(access.token(), access.expiresAt(), refresh, refreshExpires, user, newUser);
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
