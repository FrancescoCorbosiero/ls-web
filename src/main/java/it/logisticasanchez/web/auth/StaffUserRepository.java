package it.logisticasanchez.web.auth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Staff accounts; used only inside the {@code auth} module (chapter 10 §2). */
public interface StaffUserRepository extends JpaRepository<StaffUser, Long> {

    /** Looks a user up by the normalised (trimmed, lower-case) username. */
    Optional<StaffUser> findByUsername(String username);

    /** By the normalised (trimmed, lower-case) e-mail; unique when present (V002). */
    Optional<StaffUser> findByEmail(String email);

    boolean existsByRole(StaffRole role);

    /** The row locked until the transaction ends (ENH-09: one code check at a time, so a code is used once). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from StaffUser u where u.id = :id")
    Optional<StaffUser> lockById(@Param("id") long id);
}
