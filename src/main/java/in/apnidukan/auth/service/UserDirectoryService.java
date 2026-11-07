package in.apnidukan.auth.service;

import in.apnidukan.auth.domain.User;
import in.apnidukan.auth.repo.UserRepository;
import in.apnidukan.common.context.UserDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    public UserDirectoryService(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findByPhone(String phoneInput) {
        return users.findByPhone(PhoneNumbers.normalize(phoneInput)).map(User::getId);
    }

    @Override
    public String normalizePhone(String phoneInput) {
        return PhoneNumbers.normalize(phoneInput);
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
