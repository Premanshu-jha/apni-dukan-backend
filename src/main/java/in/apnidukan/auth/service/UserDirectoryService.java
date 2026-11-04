package in.apnidukan.auth.service;

import in.apnidukan.auth.domain.User;
import in.apnidukan.auth.repo.UserRepository;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.common.error.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** People, for other modules (see {@link UserDirectory}). */
@Service
public class UserDirectoryService implements UserDirectory {

    private final UserRepository users;
    private final Clock clock;

    public UserDirectoryService(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    /**
     * Runs in its own short transaction, so a clash with the same phone signing up at the same moment (unique
     * phone) can be retried with a fresh read instead of breaking the caller's transaction.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID findOrCreateByPhone(String phoneInput) {
        String phone = PhoneNumbers.normalize(phoneInput);
        return users.findByPhone(phone).map(User::getId).orElseGet(() -> {
            try {
                return users.saveAndFlush(new User(UUID.randomUUID(), phone, Instant.now(clock))).getId();
            } catch (DataIntegrityViolationException e) {
                // The same phone signed up at the same moment from another request
                throw new ApiException(HttpStatus.CONFLICT, "TRY_AGAIN", "Please try again.");
            }
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findByPhone(String phoneInput) {
        return users.findByPhone(PhoneNumbers.normalize(phoneInput)).map(User::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Person> find(Collection<UUID> userIds) {
        return users.findAllById(userIds).stream()
                .map(u -> new Person(u.getId(), u.getName(), u.getPhone()))
                .collect(Collectors.toMap(Person::id, Function.identity()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActive(UUID userId) {
        return users.findById(userId).filter(u -> !u.isDeleted()).isPresent();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockIfActive(UUID userId) {
        return users.lockIfActive(userId).orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> missingProfile(UUID userId) {
        User u = users.findById(userId).orElse(null);
        if (u == null) {
            return List.of("email", "name");
        }
        List<String> missing = new java.util.ArrayList<>();
        if (!u.isEmailVerified()) {
            missing.add("email");
        }
        if (u.getName() == null || u.getName().isBlank()) {
            missing.add("name");
        }
        return List.copyOf(missing);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAdmin(UUID userId) {
        return users.findById(userId).map(User::isAdmin).orElse(false);
    }
}
