package it.logisticasanchez.web.auth;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Remembered devices (ADR 0031); used only inside the {@code auth} module. */
public interface RememberedSignInRepository extends JpaRepository<RememberedSignIn, Long> {

    Optional<RememberedSignIn> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from RememberedSignIn r where r.tokenHash = :tokenHash")
    int deleteByTokenHash(String tokenHash);

    /** Logout everywhere: a decline, an e-mail change, the anonymisation (chapter 10 §2). */
    @Modifying
    @Query("delete from RememberedSignIn r where r.customerUserId = :customerUserId")
    int deleteByCustomerUserId(long customerUserId);

    /** Retention: expired devices are useless and deleted by the nightly job. */
    @Modifying
    @Query("delete from RememberedSignIn r where r.expiresAt < :before")
    int deleteExpiredBefore(Instant before);
}
