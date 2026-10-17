package it.logisticasanchez.web.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Customer login identities; used only inside the {@code auth} module. */
public interface CustomerUserRepository extends JpaRepository<CustomerUser, Long> {

    /** Looks an identity up by the normalised (trimmed, lower-case) e-mail. */
    Optional<CustomerUser> findByEmail(String email);

    List<CustomerUser> findByCustomerId(Long customerId);
}
