package it.logisticasanchez.web.auth;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** One-time links; used only inside the {@code auth} module. */
public interface LoginTokenRepository extends JpaRepository<LoginToken, Long> {

    Optional<LoginToken> findByTokenHash(String tokenHash);

    /**
     * Retention (chapter 10 §2): rows are deleted 24 hours after they expire. A row still
     * referenced by an outbox e-mail (not yet delivered) is left for the next run, so the job
     * never fails on the foreign key.
     */
    @Modifying
    @Query(
            value = "delete from login_token t where t.expires_at < :before"
                    + " and not exists (select 1 from email_outbox e where e.login_token_id = t.id)",
            nativeQuery = true)
    int deleteExpiredBefore(Instant before);
}
