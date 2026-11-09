package in.apnidukan.auth.service;

import in.apnidukan.auth.domain.AdminDesignation;
import in.apnidukan.auth.domain.AdminRole;
import in.apnidukan.auth.domain.User;
import in.apnidukan.auth.notify.EmailSender;
import in.apnidukan.auth.notify.SmsSender;
import in.apnidukan.auth.repo.AdminPincodeStore;
import in.apnidukan.auth.repo.RefreshTokenRepository;
import in.apnidukan.auth.repo.UserRepository;
import in.apnidukan.common.async.BackgroundTasks;
import in.apnidukan.common.context.FieldAssignments;
import in.apnidukan.common.context.PersonDetailsListener;
import in.apnidukan.common.context.SignOutEverywhereListener;
import in.apnidukan.common.context.TeamLock;
import in.apnidukan.common.error.ApiException;
import in.apnidukan.common.security.SessionRevocations;
import in.apnidukan.common.text.TextCleaner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The admin website's team, run by the super admins: who is on it, adding someone (phone, email, name, role; an ADMIN
 * also needs a designation and the pincodes they cover), editing anything about a member, removing one, and which
 * pincodes are covered by whom.
 *
 * <p>Being on the team is a flag on the person's one Clozest account (the same account they use as a customer or
 * in a salon), so a change of phone or email here changes that account everywhere. No code is sent when a member is
 * added or edited: they prove the phone by signing in with its code, and the email is stored unverified, so the
 * website asks them to confirm it before anything else. A change of phone or email signs them out everywhere.
 *
 * <p>When a field agent stops covering a pincode (removed from their list, made a super admin, taken off the team),
 * the businesses waiting for a visit that were theirs there go to another agent who covers it, or to Unassigned
 * (FieldAssignments, in the shops module).
 *
 * <p>Who may do this is always read from the database, not from the sign-in token (which can be up to 15 minutes
 * old). Every change takes the team lock (TeamLock) first, so it never crosses an assignment in progress.
 */
@Service
public class AdminTeamService {

    private static final Logger log = LoggerFactory.getLogger(AdminTeamService.class);

    /** Advisory lock for changes to admins and test numbers (so a number can't become both at the same moment). */
    static final int LOCK = TeamLock.KEY;

    /** Most pincodes one agent may cover (a city's worth). */
    static final int MAX_PINCODES = 300;

    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final TestModeService testMode;
    private final SessionRevocations revocations;
    private final RefreshTokenRepository refreshTokens;
    private final AdminEvents events;
    private final AdminPincodeStore pincodes;
    private final FieldAssignments assignments;
    private final List<PersonDetailsListener> detailsListeners;
    private final List<SignOutEverywhereListener> signOutListeners;
    private final EmailSender email;
    private final SmsSender sms;
    private final BackgroundTasks background;
    private final Clock clock;

    public AdminTeamService(UserRepository users, JdbcTemplate jdbc, TestModeService testMode,
                            SessionRevocations revocations, RefreshTokenRepository refreshTokens,
                            AdminEvents events, AdminPincodeStore pincodes, FieldAssignments assignments,
                            List<PersonDetailsListener> detailsListeners,
                            List<SignOutEverywhereListener> signOutListeners, EmailSender email, SmsSender sms,
                            BackgroundTasks background, Clock clock) {
        this.sms = sms;
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.jdbc = jdbc;
        this.testMode = testMode;
        this.revocations = revocations;
        this.events = events;
        this.pincodes = pincodes;
        this.assignments = assignments;
        this.detailsListeners = detailsListeners;
        this.signOutListeners = signOutListeners;
        this.email = email;
        this.background = background;
        this.clock = clock;
    }

    /** Who is signed in to the admin website, and whether OTP test mode is on (for the banner). */
    @Transactional(readOnly = true)
    public Me me(UUID userId) {
        User u = users.findById(userId).filter(User::isAdmin).filter(x -> !x.isDeleted())
                .orElseThrow(() -> ApiException.notFound("Page"));
        return new Me(u.getId(), u.getName(), u.getPhone(), u.getAdminRole(), u.getAdminDesignation(),
                testMode.current());
    }

    /** Super admins first, then admins, each by when they were added. */
    @Transactional(readOnly = true)
    public List<Member> team(UUID superAdminId) {
        requireSuperAdmin(users, superAdminId);
        List<User> list = users.findAllById(jdbc.queryForList(
                "SELECT id FROM users WHERE admin_role <> 'NONE' AND deleted_at IS NULL", UUID.class));
        return members(list).stream()
                .sorted(Comparator.comparing((Member m) -> m.adminRole() != AdminRole.SUPER_ADMIN)
                        .thenComparing(Member::since).thenComparing(Member::userId))
                .toList();
    }

    /** Every covered pincode with the agents who cover it, by pincode. */
    @Transactional(readOnly = true)
    public List<Covered> coverage(UUID superAdminId) {
        requireSuperAdmin(users, superAdminId);
        List<Covered> out = new ArrayList<>();
        pincodes.coverage().forEach((pincode, agents) -> out.add(new Covered(pincode, agents.stream()
                .map(a -> new CoveringAgent(a.userId(), a.name(), a.phone())).toList())));
        return out;
    }

    /**
     * Puts someone on the team. A phone with no account gets one, with this name and email; an existing account (a
     * customer's, say) keeps its history and takes this name and email. 409 ALREADY_ADMIN, TEST_PHONE (a test number,
     * or an account made in test mode), TEST_EMAIL, EMAIL_IN_USE (another account's verified email); 400
     * VALIDATION_FAILED naming the field.
     */
    @Transactional
    public Member add(UUID superAdminId, MemberInput input) {
        requireSuperAdmin(users, superAdminId);
        Cleaned in = clean(input);
        lockTeam(jdbc);
        requireSuperAdmin(users, superAdminId);   // again, under the lock: demoted or removed meanwhile
        if (testMode.isTestPhone(in.phone())) {
            throw new ApiException(HttpStatus.CONFLICT, "TEST_PHONE",
                    "That is a test number. Remove it from the test numbers first.");
        }
        refuseTestEmail(in.email());
        Instant now = now();
        // The id only: lockById below then reads the row fresh (an entity loaded here would be returned as it was,
        // and could write back an account deleted meanwhile)
        UUID existingId = users.findIdByPhone(in.phone()).orElse(null);
        boolean told = false;
        if (existingId != null) {
            refuseEmailOfAnother(in.email(), existingId);
            String oldName = jdbc.queryForList("SELECT name FROM users WHERE id = ?", String.class, existingId)
                    .stream().filter(Objects::nonNull).findFirst().orElse(null);
            if (!in.name().equals(oldName)) {
                detailsListeners.forEach(l -> l.detailsChanging(existingId));   // before the row lock (see there)
                told = true;
            }
        } else {
            refuseEmailOfAnother(in.email(), null);
        }
        User user;
        if (existingId == null) {
            try {
                user = users.saveAndFlush(new User(UUID.randomUUID(), in.phone(), now));
            } catch (DataIntegrityViolationException e) {
                throw new ApiException(HttpStatus.CONFLICT, "TRY_AGAIN", "Please try again.");
            }
        } else {
            user = users.lockById(existingId).filter(u -> !u.isDeleted())
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "TRY_AGAIN", "Please try again."));
        }
        if (user.isAdmin()) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_ADMIN", "That person is an admin already.");
        }
        if (user.isTestAccount()) {
            throw new ApiException(HttpStatus.CONFLICT, "TEST_PHONE",
                    "That number's account was made in test mode, so others may be signed in to it. Use another "
                            + "number.");
        }
        if (!in.name().equals(user.getName())) {
            if (existingId != null && !told) {
                throw tryAgain();   // renamed by themselves in between: start again, so others' screens are told
            }
            user.rename(in.name(), now);
        }
        String oldVerifiedEmail = changeEmail(user, in.email(), now);
        user.makeAdmin(in.role(), in.designation(), now);
        user.bumpAdminVersion();
        save(user);
        pincodes.replace(user.getId(), in.pincodes(), now);
        if (in.role() == AdminRole.ADMIN) {
            assignments.coverageAdded(user.getId(), in.pincodes(), superAdminId);   // businesses waiting with nobody
        }
        // Anyone already signed in to this account starts again: admin rights come only with a new sign-in
        refreshTokens.revokeAllForUser(user.getId(), now);
        signOutAfterCommit(user.getId());
        noticeAfterCommit(oldVerifiedEmail, in.email());
        events.record(superAdminId, "ADMIN_ADDED", in.phone(), describe(in));
        return members(List.of(user)).get(0);
    }

    /**
     * Changes anything about a member: name, phone, email, role, designation, pincodes. {@code version} is the one the
     * Team page showed (409 MEMBER_CHANGED if another super admin changed this member since). A new phone or email
     * signs them out everywhere; the email is then confirmed at their next sign-in, and the old verified address is
     * told. 404 if they aren't on the team; 409 PHONE_IN_USE, EMAIL_IN_USE, TEST_PHONE, TEST_EMAIL; 400
     * VALIDATION_FAILED.
     */
    @Transactional
    public Member edit(UUID superAdminId, UUID userId, long version, MemberInput input) {
        requireSuperAdmin(users, superAdminId);
        Cleaned in = clean(input);
        lockTeam(jdbc);
        requireSuperAdmin(users, superAdminId);   // again, under the lock: demoted or removed meanwhile
        Map<String, Object> before = jdbc.queryForList("SELECT name, phone, admin_version FROM users WHERE id = ? "
                + "AND admin_role <> 'NONE' AND deleted_at IS NULL", userId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Admin"));
        // Under the team lock every Team change waits for this one, so this is still the version below
        if (!Objects.equals(before.get("admin_version"), version)) {
            throw memberChanged();
        }
        boolean newPhone = !in.phone().equals(before.get("phone"));
        if (newPhone) {
            if (testMode.isTestPhone(in.phone())) {
                throw new ApiException(HttpStatus.CONFLICT, "TEST_PHONE",
                        "That is a test number. Use another number.");
            }
            if (users.findIdByPhone(in.phone()).isPresent()) {
                throw phoneInUse();
            }
        }
        boolean told = newPhone || !in.name().equals(before.get("name"));
        if (told) {
            detailsListeners.forEach(l -> l.detailsChanging(userId));   // before the row lock (see there)
        }
        User user = users.lockById(userId).filter(User::isAdmin).filter(u -> !u.isDeleted())
                .orElseThrow(() -> ApiException.notFound("Admin"));
        if (user.getAdminVersion() != version) {
            throw memberChanged();
        }
        Instant now = now();
        List<String> changed = new ArrayList<>();
        if (!in.name().equals(user.getName())) {
            if (!told) {
                throw tryAgain();   // renamed by themselves in between: start again, so others' screens are told
            }
            user.rename(in.name(), now);
            changed.add("name");
        }
        String oldPhone = user.getPhone();
        String verifiedEmail = user.isEmailVerified() ? user.getEmail() : null;
        if (newPhone) {
            changed.add("phone " + oldPhone + " → " + in.phone());
            user.changePhone(in.phone(), now);
        }
        boolean newEmail = !in.email().equalsIgnoreCase(Objects.toString(user.getEmail(), ""));
        String oldVerifiedEmail = null;
        if (newEmail) {
            refuseTestEmail(in.email());
            refuseEmailOfAnother(in.email(), userId);
            oldVerifiedEmail = changeEmail(user, in.email(), now);
            changed.add("email");
        }
        boolean wasFieldAgent = user.getAdminRole() == AdminRole.ADMIN;
        if (user.getAdminRole() != in.role() || user.getAdminDesignation() != in.designation()) {
            changed.add("role " + label(user.getAdminRole(), user.getAdminDesignation()) + " → "
                    + label(in.role(), in.designation()));
            user.setAdminRole(in.role(), in.designation(), now);
        }
        user.bumpAdminVersion();
        save(user);
        Set<String> pincodesBefore = pincodes.of(userId);
        Set<String> removed = pincodes.replace(userId, in.pincodes(), now);
        Set<String> added = new TreeSet<>(in.pincodes());
        added.removeAll(pincodesBefore);
        if (!pincodesBefore.equals(in.pincodes())) {
            changed.add("pincodes");
        }
        if (wasFieldAgent && in.role() != AdminRole.ADMIN) {
            assignments.coverageChanged(userId, null, superAdminId);   // no longer a field agent: all of them
        } else if (wasFieldAgent && !removed.isEmpty()) {
            assignments.coverageChanged(userId, removed, superAdminId);
        }
        if (in.role() == AdminRole.ADMIN && !added.isEmpty()) {
            assignments.coverageAdded(userId, added, superAdminId);
        }
        if (newPhone || newEmail) {
            refreshTokens.revokeAllForUser(userId, now);
            signOutListeners.forEach(l -> l.signedOutEverywhere(userId));   // the old phone stops getting messages
            signOutAfterCommit(userId);
        }
        if (newPhone) {
            // One email to the old address saying everything that changed (its phone and, if so, its email)
            phoneNoticeAfterCommit(oldPhone, in.phone(), verifiedEmail, oldVerifiedEmail == null ? null : in.email());
        } else {
            noticeAfterCommit(oldVerifiedEmail, in.email());
        }
        events.record(superAdminId, "ADMIN_EDITED", user.getPhone(),
                changed.isEmpty() ? "nothing changed" : String.join("; ", changed));
        return members(List.of(user)).get(0);
    }

    /**
     * Takes someone off the team; their sessions end at once (the app signs in again without admin rights), and the
     * businesses waiting for their visit go to another agent or to Unassigned. Any member, a super admin too, but not
     * yourself (409 CANT_REMOVE_YOURSELF: ask another super admin).
     */
    @Transactional
    public void remove(UUID superAdminId, UUID adminId) {
        requireSuperAdmin(users, superAdminId);
        if (adminId.equals(superAdminId)) {
            throw new ApiException(HttpStatus.CONFLICT, "CANT_REMOVE_YOURSELF",
                    "You can't remove yourself. Ask another super admin.");
        }
        lockTeam(jdbc);
        requireSuperAdmin(users, superAdminId);   // again, under the lock: two super admins removing each other
        User user = users.lockById(adminId).filter(User::isAdmin).orElseThrow(() -> ApiException.notFound("Admin"));
        Instant now = now();
        user.removeAdmin(now);
        user.bumpAdminVersion();
        users.saveAndFlush(user);
        pincodes.replace(adminId, Set.of(), now);
        assignments.coverageChanged(adminId, null, superAdminId);
        events.record(superAdminId, "ADMIN_REMOVED", user.getPhone(), null);
        signOutAfterCommit(adminId);
    }

    /**
     * First thing in an account deletion's transaction: for a team member, takes the team lock (before any row, as
     * every team change does). Then {@link #accountDeleted} once the account is wiped.
     */
    void accountDeleting(UUID userId) {
        // Always (deletions are rare): whether they are on the team can change before their row is locked, and the
        // team lock must never be taken after a row lock
        lockTeam(jdbc);
    }

    /**
     * A field agent deleted their own account (already wiped: no longer on the team): their pincodes go, and the
     * businesses waiting for their visit go to another agent or to Unassigned. Nothing for anyone else.
     */
    void accountDeleted(UUID userId) {
        if (!pincodes.of(userId).isEmpty() || !assignments.pendingVisits(List.of(userId)).isEmpty()) {
            pincodes.replace(userId, Set.of(), now());
            assignments.coverageChanged(userId, null, null);
        }
    }

    /** Validated and cleaned input; a super admin's designation and pincodes are dropped (they have none). */
    private Cleaned clean(MemberInput in) {
        Map<String, Object> errors = new LinkedHashMap<>();
        String phone = null;
        try {
            phone = PhoneNumbers.normalize(Objects.toString(in.phone(), ""));
        } catch (ApiException e) {
            errors.put("phone", "enter a 10-digit Indian mobile number");
        }
        String address = null;
        try {
            address = Emails.normalize(Objects.toString(in.email(), ""));
        } catch (ApiException e) {
            errors.put("email", "enter a valid email address");
        }
        String name = null;
        try {
            name = TextCleaner.clean(AuthService.NAME, in.name());
        } catch (ApiException e) {
            errors.put("name", "enter a name of 2 to 60 letters");
        }
        AdminRole role = in.role();
        if (role == null || role == AdminRole.NONE) {
            errors.put("role", "choose Admin or Super admin");
        }
        AdminDesignation designation = null;
        Set<String> codes = new TreeSet<>();
        if (role == AdminRole.ADMIN) {
            designation = in.designation();
            if (designation == null) {
                errors.put("designation", "choose a designation");
            }
            List<String> bad = new ArrayList<>();
            for (String p : in.pincodes() == null ? List.<String>of() : in.pincodes()) {
                String code = p == null ? "" : p.strip();
                if (code.matches("[1-9][0-9]{5}")) {
                    codes.add(code);
                } else {
                    bad.add(code);
                }
            }
            if (!bad.isEmpty()) {
                errors.put("pincodes", "not valid pincodes: " + String.join(", ", bad.stream().limit(10).toList()));
            } else if (codes.isEmpty()) {
                errors.put("pincodes", "add at least one pincode");
            } else if (codes.size() > MAX_PINCODES) {
                errors.put("pincodes", "at most " + MAX_PINCODES + " pincodes");
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are not valid", errors);
        }
        return new Cleaned(phone, address, name, role, designation, codes);
    }

    /**
     * Stores the typed email unverified (a verified one equal to it, any case, stays as it is). Returns the old
     * verified address when one was replaced (it is told), else null.
     */
    private static String changeEmail(User user, String address, Instant now) {
        if (user.isEmailVerified() && address.equalsIgnoreCase(user.getEmail())) {
            return null;
        }
        String old = user.isEmailVerified() ? user.getEmail() : null;
        if (!address.equals(user.getEmail()) || user.isEmailVerified()) {
            user.setUnverifiedEmail(address, now);
        }
        return old;
    }

    private void refuseTestEmail(String address) {
        if (testMode.isTestEmail(address)) {
            throw new ApiException(HttpStatus.CONFLICT, "TEST_EMAIL",
                    "That is a test email. Remove it from the test emails first, or use another.");
        }
    }

    /** 409 EMAIL_IN_USE when another account has proved this email is theirs. */
    private void refuseEmailOfAnother(String address, UUID self) {
        UUID holder = users.findIdByVerifiedEmail(address).orElse(null);
        if (holder != null && !holder.equals(self)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_IN_USE",
                    "This email is already used by another account");
        }
    }

    private void save(User user) {
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw phoneInUse();   // the only unique column changed here (a verified email is never set here)
        }
    }

    private static ApiException memberChanged() {
        return new ApiException(HttpStatus.CONFLICT, "MEMBER_CHANGED",
                "Someone changed this member after you opened it. Reload and try again.");
    }

    private static ApiException tryAgain() {
        return new ApiException(HttpStatus.CONFLICT, "TRY_AGAIN", "Please try again.");
    }

    /**
     * The phone was changed by the team: the old phone and the verified email are told (as account recovery tells
     * them), so a real owner notices at once if it wasn't asked for. After the change is saved; a failed notice
     * never undoes it.
     */
    private void phoneNoticeAfterCommit(String oldPhone, String newPhone, String verifiedEmail, String newEmail) {
        String ending = newPhone.substring(6);
        String emailToo = newEmail == null ? "" : " and its email to " + AuthService.mask(newEmail);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // Not under the daily SMS cap: this warning must get out
                background.runAlways("account-moved SMS", () -> sms.sendAccountMoved(oldPhone, ending));
                if (verifiedEmail != null) {
                    background.runAlways("phone-changed notice", () -> email.send(verifiedEmail,
                            "Your Clozest phone number was changed", "The Clozest team moved your Clozest account to "
                                    + "the phone number ending " + ending + emailToo + ". Every device was signed "
                                    + "out.\n\nIf you didn't ask for this, contact Clozest support immediately."));
                }
            }
        });
    }

    private static ApiException phoneInUse() {
        return new ApiException(HttpStatus.CONFLICT, "PHONE_IN_USE",
                "Another Clozest account already uses this phone number.");
    }

    private List<Member> members(Collection<User> list) {
        List<UUID> ids = list.stream().map(User::getId).toList();
        Map<UUID, List<String>> codes = pincodes.of(ids);
        Map<UUID, Long> pending = assignments.pendingVisits(ids);
        return list.stream().map(u -> new Member(u.getId(), u.getName(), u.getPhone(), u.getEmail(),
                u.isEmailVerified(), u.getAdminRole(), u.getAdminDesignation(), codes.getOrDefault(u.getId(),
                List.of()), pending.getOrDefault(u.getId(), 0L),
                u.getAdminSince() == null ? u.getCreatedAt() : u.getAdminSince(), u.getAdminVersion())).toList();
    }

    /** The old verified address is told its account's email was changed by the team (after the change is saved). */
    private void noticeAfterCommit(String oldVerifiedEmail, String newAddress) {
        if (oldVerifiedEmail == null) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                background.runAlways("email-changed notice", () -> email.send(oldVerifiedEmail,
                        "Your Clozest email was changed", "The Clozest team changed the email of your Clozest "
                                + "account to " + AuthService.mask(newAddress) + ".\n\nIf you didn't ask for this, "
                                + "contact Clozest support immediately."));
            }
        });
    }

    /** After the change is saved: a token refreshed in between would otherwise still carry the old rights. */
    private void signOutAfterCommit(UUID userId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    revocations.revokeAll(userId);
                } catch (DataAccessException redisDown) {
                    // Every admin page reads the role from the database anyway; the token expires within 15 min
                    log.warn("Redis didn't answer; sessions of {} end when their tokens expire", userId);
                }
            }
        });
    }

    private static String label(AdminRole role, AdminDesignation designation) {
        return designation == null ? role.name() : role.name() + "/" + designation.name();
    }

    private static String describe(Cleaned in) {
        return label(in.role(), in.designation()) + (in.pincodes().isEmpty() ? ""
                : " " + String.join(",", in.pincodes()));
    }

    /** 403 SUPER_ADMIN_ONLY unless this person is a super admin (404 if not an admin at all). */
    static void requireSuperAdmin(UserRepository users, UUID userId) {
        // Read fresh from the row: no entity is loaded (editing yourself must lock and read your own row fresh), and
        // checked again after the team lock, it sees a role change committed meanwhile
        AdminRole role = users.findTeamRoleById(userId).map(r -> AdminRole.valueOf(r.split(":")[0]))
                .orElse(AdminRole.NONE);
        if (role == AdminRole.NONE) {
            throw ApiException.notFound("Page");
        }
        if (role != AdminRole.SUPER_ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SUPER_ADMIN_ONLY", "Only a super admin can do this.");
        }
    }

    static void lockTeam(JdbcTemplate jdbc) {
        TeamLock.exclusive(jdbc);
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    public record Me(UUID userId, String name, String phone, AdminRole adminRole, AdminDesignation designation,
                     TestModeService.Switch testMode) {
    }

    /**
     * What the Team page sends to add or edit a member.
     * @param role        ADMIN or SUPER_ADMIN
     * @param designation required for ADMIN (FIELD_AGENT); ignored for SUPER_ADMIN
     * @param pincodes    6 digits each, at least one for ADMIN; ignored for SUPER_ADMIN
     */
    public record MemberInput(String phone, String email, String name, AdminRole role,
                              AdminDesignation designation, List<String> pincodes) {
        public MemberInput {
            // A null sent in the list becomes "" (reported as an invalid pincode, never a 500)
            pincodes = pincodes == null ? List.of()
                    : List.copyOf(pincodes.stream().map(p -> Objects.toString(p, "")).toList());
        }
    }

    private record Cleaned(String phone, String email, String name, AdminRole role, AdminDesignation designation,
                           Set<String> pincodes) {
    }

    /**
     * @param emailVerified false until they confirm it at sign-in (an email typed on the Team page)
     * @param pendingVisits businesses waiting for their visit
     * @param since         when they joined the team (for the first super admin, set by hand: when the account was
     *                      made)
     * @param version       send it back with an edit
     */
    public record Member(UUID userId, String name, String phone, String email, boolean emailVerified,
                         AdminRole adminRole, AdminDesignation designation, List<String> pincodes, long pendingVisits,
                         Instant since, long version) {
        public Member {
            pincodes = List.copyOf(pincodes);
        }
    }

    public record Covered(String pincode, List<CoveringAgent> agents) {
        public Covered {
            agents = List.copyOf(agents);
        }
    }

    public record CoveringAgent(UUID userId, String name, String phone) {
    }
}
