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

    /** Erasure (NFR-03): every link of a customer user; the caller has already detached the outbox rows. */
    @Modifying
    @Query(
            value = "delete from login_token t where t.customer_user_id = :customerUserId"
                    + " and not exists (select 1 from email_outbox e where e.login_token_id = t.id)",
            nativeQuery = true)
    int deleteByCustomerUserId(long customerUserId);

    /** Erasure (NFR-03): the REGISTER links issued to an e-mail (stored only as its HMAC). */
    @Modifying
    @Query(
            value = "delete from login_token t where t.email_hash = :emailHash"
                    + " and not exists (select 1 from email_outbox e where e.login_token_id = t.id)",
            nativeQuery = true)
    int deleteByEmailHash(String emailHash);
}
