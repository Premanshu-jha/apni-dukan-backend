package in.apnidukan.auth.api;

import in.apnidukan.auth.api.AuthRequests.CompleteRecovery;
import in.apnidukan.auth.api.AuthRequests.Logout;
import in.apnidukan.auth.api.AuthRequests.Refresh;
import in.apnidukan.auth.api.AuthRequests.SendEmailCode;
import in.apnidukan.auth.api.AuthRequests.SendOtp;
import in.apnidukan.auth.api.AuthRequests.StartRecovery;
import in.apnidukan.auth.api.AuthRequests.VerifyEmail;
import in.apnidukan.auth.api.AuthRequests.VerifyOtp;
import in.apnidukan.auth.api.AuthResponses.TokenResponse;
import in.apnidukan.auth.api.AuthResponses.UserResponse;
import in.apnidukan.auth.service.AuthService;
import in.apnidukan.common.context.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final CurrentUserProvider currentUser;

    public AuthController(AuthService auth, CurrentUserProvider currentUser) {
        this.auth = auth;
        this.currentUser = currentUser;
    }

    /** Step 1 of sign-in: sends a 6-digit OTP to the phone. 202; 429 after 3 codes in 10 minutes. */
    @PostMapping("/otp/send")
    public ResponseEntity<Void> sendOtp(@Valid @RequestBody SendOtp body) {
        auth.sendLoginOtp(body.phone());
        return ResponseEntity.accepted().build();
    }

    /** Step 2: correct OTP → tokens. Creates the account on first use (newUser: true). */
    @PostMapping("/otp/verify")
    public TokenResponse verifyOtp(@Valid @RequestBody VerifyOtp body) {
        return TokenResponse.from(auth.verifyLoginOtp(body.phone(), body.code(), body.device()));
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

    /** Signed in: send a 6-digit code to the email to prove it's yours. */
    @PostMapping("/email/send-code")
    public ResponseEntity<Void> sendEmailCode(@Valid @RequestBody SendEmailCode body) {
        auth.sendEmailCode(currentUser.get().userId(), body.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/email/verify")
    public UserResponse verifyEmail(@Valid @RequestBody VerifyEmail body) {
        return UserResponse.from(auth.verifyEmail(currentUser.get().userId(), body.email(), body.code()));
    }

    /** Lost your phone number: codes go to your verified email and to the new phone. Always 202. */
    @PostMapping("/recover/start")
    public ResponseEntity<Void> startRecovery(@Valid @RequestBody StartRecovery body) {
        auth.startRecovery(body.email(), body.newPhone());
        return ResponseEntity.accepted().build();
    }

    /** Both codes correct → your account now uses the new phone; all old sessions are signed out. */
    @PostMapping("/recover/complete")
    public TokenResponse completeRecovery(@Valid @RequestBody CompleteRecovery body) {
        return TokenResponse.from(auth.completeRecovery(body.email(), body.emailCode(), body.newPhone(),
                body.phoneCode(), body.device()));
    }
}
