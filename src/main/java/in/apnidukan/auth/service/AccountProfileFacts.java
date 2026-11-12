package in.apnidukan.auth.service;

import in.apnidukan.auth.domain.User;
import in.apnidukan.auth.repo.UserRepository;
import in.apnidukan.common.context.ProfileFacts;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** The account's own details for a support ticket: name, phone, email and since when they use the app. */
@Component
public class AccountProfileFacts implements ProfileFacts {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
            .withZone(ZoneId.of("Asia/Kolkata"));

    private final UserRepository users;

    public AccountProfileFacts(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public Section about(UUID userId) {
        User u = users.findById(userId).orElse(null);
        if (u == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        if (u.isDeleted()) {
            lines.add("Deleted their account");
            return new Section(10, "Account", lines);
        }
        lines.add("Name: " + (u.getName() == null || u.getName().isBlank() ? "not given yet" : u.getName()));
        lines.add("Phone: " + u.getPhone());
        lines.add("Email: " + (u.getEmail() == null ? "none" : u.getEmail()
                + (u.isEmailVerified() ? " (verified)" : " (not verified)")));
        lines.add("Using the app since " + DAY.format(u.getCreatedAt()));
        if (u.isAdmin()) {
            lines.add("On the Clozest team");
        }
        return new Section(10, "Account", lines);
    }
}
