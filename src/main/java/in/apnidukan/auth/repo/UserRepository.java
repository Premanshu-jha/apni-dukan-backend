package in.apnidukan.auth.repo;

import in.apnidukan.auth.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByPhone(String phone);

    @Query("SELECT u FROM User u WHERE lower(u.email) = lower(:email) AND u.emailVerifiedAt IS NOT NULL")
    Optional<User> findByVerifiedEmail(String email);
}
