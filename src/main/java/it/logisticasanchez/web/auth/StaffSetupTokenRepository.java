package it.logisticasanchez.web.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Staff set-password links; used only inside the {@code auth} module. */
public interface StaffSetupTokenRepository extends JpaRepository<StaffSetupToken, Long> {

    Optional<StaffSetupToken> findByTokenHash(String tokenHash);
}
