package it.logisticasanchez.web.auth;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** One-time links; used only inside the {@code auth} module. */
public interface LoginTokenRepository extends JpaRepository<LoginToken, Long> {

    Optional<LoginToken> findByTokenHash(String tokenHash);

    /** Retention (chapter 10 §2): rows are deleted 24 hours after they expire. */
    @Modifying
    @Query("delete from LoginToken t where t.expiresAt < :before")
    int deleteExpiredBefore(Instant before);
}
