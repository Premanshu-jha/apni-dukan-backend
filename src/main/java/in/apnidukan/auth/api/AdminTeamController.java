package in.apnidukan.auth.api;

import in.apnidukan.auth.domain.AdminDesignation;
import in.apnidukan.auth.domain.AdminRole;
import in.apnidukan.auth.service.AdminTeamService;
import in.apnidukan.auth.service.AdminTeamService.Covered;
import in.apnidukan.auth.service.AdminTeamService.Me;
import in.apnidukan.auth.service.AdminTeamService.Member;
import in.apnidukan.auth.service.AdminTeamService.MemberInput;
import in.apnidukan.auth.service.TestModeService;
import in.apnidukan.auth.service.TestModeService.State;
import in.apnidukan.common.context.CurrentUserProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The admin website's own pages: who is signed in, the team (super admins only) and OTP test mode (super admins
 * only). Everything under /api/v1/admin needs an admin's sign-in (security config); the services check the role
 * again in the database: an admin (field agent) gets 403 SUPER_ADMIN_ONLY on the super admins' pages.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminTeamController {

    private final AdminTeamService team;
    private final TestModeService testMode;
    private final CurrentUserProvider currentUser;

    public AdminTeamController(AdminTeamService team, TestModeService testMode, CurrentUserProvider currentUser) {
        this.team = team;
        this.testMode = testMode;
        this.currentUser = currentUser;
    }

    /** Who is signed in, their role, and whether OTP test mode is on (the website shows a banner while it is). */
    @GetMapping("/me")
    public Me me() {
        return team.me(caller());
    }

    @GetMapping("/team")
    public List<Member> team() {
        return team.team(caller());
    }

    /**
     * Puts this phone on the team with this email, name and role (the account is made if it has none); an ADMIN
     * also needs a designation and at least one pincode. 409 ALREADY_ADMIN / TEST_PHONE / TEST_EMAIL / EMAIL_IN_USE;
     * 400 VALIDATION_FAILED naming the field.
     */
    @PostMapping("/team")
    public ResponseEntity<Member> add(@Valid @RequestBody MemberBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(team.add(caller(), body.input()));
    }

    /**
     * Changes anything about a member. Send the {@code version} the Team page showed: 409 MEMBER_CHANGED if another
     * super admin changed them since. Also 409 PHONE_IN_USE / EMAIL_IN_USE / TEST_PHONE / TEST_EMAIL, 404 if they
     * aren't on the team.
     */
    @PutMapping("/team/{userId}")
    public Member edit(@PathVariable UUID userId, @Valid @RequestBody EditBody body) {
        return team.edit(caller(), userId, body.version(), new MemberInput(body.phone(), body.email(), body.name(),
                body.role(), body.designation(), body.pincodes()));
    }

    /** Every covered pincode with the agents covering it (the Team page's Coverage tab, and the add form's hints). */
    @GetMapping("/team/coverage")
    public List<Covered> coverage() {
        return team.coverage(caller());
    }

    /** Removes a member (a super admin too, not yourself); their sessions end at once. 409 CANT_REMOVE_YOURSELF. */
    @DeleteMapping("/team/{userId}")
    public ResponseEntity<Void> remove(@PathVariable UUID userId) {
        team.remove(caller(), userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/test-mode")
    public State testMode() {
        return testMode.state(caller());
    }

    /** On for 1 to 8 hours from now; it switches itself off then. */
    @PutMapping("/test-mode")
    public State turnOn(@Valid @RequestBody TurnOn body) {
        return testMode.turnOn(caller(), body.hours());
    }

    @DeleteMapping("/test-mode")
    public State turnOff() {
        return testMode.turnOff(caller());
    }

    /** 409 ADMIN_PHONE (an admin's number) or ALREADY_TEST_PHONE. */
    @PostMapping("/test-mode/phones")
    public ResponseEntity<State> addPhone(@Valid @RequestBody TestPhone body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(testMode.addPhone(caller(), body.phone(), body.label()));
    }

    @DeleteMapping("/test-mode/phones/{phone}")
    public State removePhone(@PathVariable String phone) {
        return testMode.removePhone(caller(), phone);
    }

    /** 409 REAL_ACCOUNT_EMAIL (a real account's or an admin's verified email) or ALREADY_TEST_EMAIL. */
    @PostMapping("/test-mode/emails")
    public ResponseEntity<State> addEmail(@Valid @RequestBody TestEmail body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(testMode.addEmail(caller(), body.email(), body.label()));
    }

    @DeleteMapping("/test-mode/emails/{email}")
    public State removeEmail(@PathVariable String email) {
        return testMode.removeEmail(caller(), email);
    }

    private UUID caller() {
        return currentUser.get().userId();
    }

    /**
     * @param role        ADMIN or SUPER_ADMIN
     * @param designation FIELD_AGENT for an ADMIN; ignored for a SUPER_ADMIN
     * @param pincodes    6 digits each, at least one for an ADMIN; ignored for a SUPER_ADMIN
     */
    public record MemberBody(@NotBlank @Size(max = 20) String phone, @NotBlank @Size(max = 254) String email,
                             @NotBlank @Size(max = 100) String name, @NotNull AdminRole role,
                             AdminDesignation designation, @Size(max = 500) List<@Size(max = 10) String> pincodes) {

        MemberInput input() {
            return new MemberInput(phone, email, name, role, designation, pincodes);
        }
    }

    /** The same fields as MemberBody, plus the version the Team page showed. */
    public record EditBody(@NotNull Long version, @NotBlank @Size(max = 20) String phone,
                           @NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 100) String name,
                           @NotNull AdminRole role, AdminDesignation designation,
                           @Size(max = 500) List<@Size(max = 10) String> pincodes) {
    }

    public record TurnOn(@NotNull Integer hours) {
    }

    /** @param label who or what it is for, e.g. "Prem's test barber" */
    public record TestPhone(@NotBlank @Size(max = 20) String phone, @NotBlank @Size(max = 60) String label) {
    }

    /** @param label who or what it is for, e.g. "Prem's test salon" */
    public record TestEmail(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 60) String label) {
    }
}
