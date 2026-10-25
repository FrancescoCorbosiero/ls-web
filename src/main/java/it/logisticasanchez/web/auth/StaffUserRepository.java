package it.logisticasanchez.web.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Staff accounts; used only inside the {@code auth} module (chapter 10 §2). */
public interface StaffUserRepository extends JpaRepository<StaffUser, Long> {

    /** Looks a user up by the normalised (trimmed, lower-case) username. */
    Optional<StaffUser> findByUsername(String username);

    /** By the normalised (trimmed, lower-case) e-mail; unique when present (V002). */
    Optional<StaffUser> findByEmail(String email);

    boolean existsByRole(StaffRole role);
}
