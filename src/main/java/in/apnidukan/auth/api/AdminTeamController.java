package in.apnidukan.auth.api;

import in.apnidukan.auth.service.AdminTeamService;
import in.apnidukan.auth.service.AdminTeamService.Me;
import in.apnidukan.auth.service.AdminTeamService.Member;
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
 * The admin website's own pages: who is signed in, the team (super admin only) and OTP test mode (super admin
 * only). Everything under /api/v1/admin needs an admin's sign-in (security config); the services check the role
 * again in the database: an ordinary admin gets 403 SUPER_ADMIN_ONLY on the super admin's pages.
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

    /** Makes this phone an admin (the account is made if it has none). 409 ALREADY_ADMIN / TEST_PHONE. */
    @PostMapping("/team")
    public ResponseEntity<Member> add(@Valid @RequestBody Phone body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(team.add(caller(), body.phone()));
    }

    /** Removes an admin; their sessions end at once. 409 CANT_REMOVE_SUPER_ADMIN. */
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

    private UUID caller() {
        return currentUser.get().userId();
    }

    public record Phone(@NotBlank @Size(max = 20) String phone) {
    }

    public record TurnOn(@NotNull Integer hours) {
    }

    /** @param label who or what it is for, e.g. "Prem's test barber" */
    public record TestPhone(@NotBlank @Size(max = 20) String phone, @NotBlank @Size(max = 60) String label) {
    }
}
