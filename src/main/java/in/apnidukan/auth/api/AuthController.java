package in.apnidukan.auth.api;

import in.apnidukan.common.web.ClientAddress;

import in.apnidukan.auth.api.AuthRequests.CompleteRecovery;
import in.apnidukan.auth.api.AuthRequests.Logout;
import in.apnidukan.auth.api.AuthRequests.Refresh;
import in.apnidukan.auth.api.AuthRequests.SendEmailCode;
import in.apnidukan.auth.api.AuthRequests.SendOtp;
import in.apnidukan.auth.api.AuthRequests.StartRecovery;
import in.apnidukan.auth.api.AuthRequests.UpdateMe;
import in.apnidukan.auth.api.AuthRequests.VerifyEmail;
import in.apnidukan.auth.api.AuthRequests.VerifyOtp;
import in.apnidukan.auth.api.AuthResponses.TokenResponse;
import in.apnidukan.auth.api.AuthResponses.UserResponse;
import in.apnidukan.auth.service.AuthService;
import in.apnidukan.common.context.CurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final CurrentUserProvider currentUser;
    private final ClientAddress client;

    public AuthController(AuthService auth, CurrentUserProvider currentUser, ClientAddress client) {
        this.auth = auth;
        this.currentUser = currentUser;
        this.client = client;
    }

    /**
     * Step 1 of sign-in: sends a 6-digit OTP to the phone. 202; 429 after 3 codes in 10 minutes; 400
     * CAPTCHA_REQUIRED / CAPTCHA_FAILED without a genuine robot-check token (once Turnstile is switched on).
     */
    @PostMapping("/otp/send")
    public ResponseEntity<Void> sendOtp(@Valid @RequestBody SendOtp body, HttpServletRequest request) {
        auth.sendLoginOtp(body.phone(), client.of(request), body.captchaToken());
        return ResponseEntity.accepted().build();
    }

    /** Step 2: correct OTP → tokens. Creates the account on first use (newUser: true). */
    @PostMapping("/otp/verify")
    public TokenResponse verifyOtp(@Valid @RequestBody VerifyOtp body, HttpServletRequest request) {
        return TokenResponse.from(auth.verifyLoginOtp(body.phone(), body.code(), body.device(),
                client.of(request)));
    }

    /** Swap the refresh token for a fresh pair (call when the access token expires, every 15 minutes). */
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody Refresh body) {
        return TokenResponse.from(auth.refresh(body.refreshToken(), body.device()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody Logout body) {
        auth.logout(body.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public UserResponse me() {
        return UserResponse.from(auth.me(currentUser.get().userId()));
    }

    /** Set your name (2 to 60 characters; spaces and look-alike letters are tidied). Shown to salons you book. */
    @PatchMapping("/me")
    public UserResponse updateMe(@Valid @RequestBody UpdateMe body) {
        return UserResponse.from(auth.rename(currentUser.get().userId(), body.name()));
    }

    /** Signed in, replacing an already verified email: first a code to your own phone. 202. */
    @PostMapping("/email/change-phone-code")
    public ResponseEntity<Void> sendEmailChangePhoneCode(HttpServletRequest request) {
        auth.sendEmailChangePhoneCode(currentUser.get().userId(), client.of(request));
        return ResponseEntity.accepted().build();
    }

    /**
     * Signed in: send a 6-digit code to the email to prove it's yours. Replacing an already verified email also
     * needs phoneCode (from /email/change-phone-code), otherwise 403 PHONE_CODE_REQUIRED.
     */
    @PostMapping("/email/send-code")
    public ResponseEntity<Void> sendEmailCode(@Valid @RequestBody SendEmailCode body, HttpServletRequest request) {
        auth.sendEmailCode(currentUser.get().userId(), body.email(), body.phoneCode(), client.of(request));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/email/verify")
    public UserResponse verifyEmail(@Valid @RequestBody VerifyEmail body, HttpServletRequest request) {
        return UserResponse.from(auth.verifyEmail(currentUser.get().userId(), body.email(), body.code(),
                client.of(request)));
    }

    /** Lost your phone number: codes go to your verified email and to the new phone. Always 202, account or not. */
    @PostMapping("/recover/start")
    public ResponseEntity<Void> startRecovery(@Valid @RequestBody StartRecovery body, HttpServletRequest request) {
        auth.startRecovery(body.email(), body.newPhone(), client.of(request), body.captchaToken());
        return ResponseEntity.accepted().build();
    }

    /** Both codes correct → your account now uses the new phone; all old sessions are signed out. */
    @PostMapping("/recover/complete")
    public TokenResponse completeRecovery(@Valid @RequestBody CompleteRecovery body, HttpServletRequest request) {
        return TokenResponse.from(auth.completeRecovery(body.email(), body.emailCode(), body.newPhone(),
                body.phoneCode(), body.device(), client.of(request)));
    }
}
