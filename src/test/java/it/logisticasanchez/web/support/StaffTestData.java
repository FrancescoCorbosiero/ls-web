package it.logisticasanchez.web.support;

import it.logisticasanchez.web.auth.StaffPrincipal;
import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.mock.web.MockHttpSession;

/** Synthetic staff accounts for the back-office tests and the HTTP session of a signed-in staff member. */
public final class StaffTestData {

    private StaffTestData() {}

    public static StaffUser staff(StaffUserRepository users, StaffRole role) {
        String username =
                role.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        StaffUser user =
                new StaffUser(username, role == StaffRole.ADMIN ? "Amministratore Test" : "Operatore Test", role);
        user.setPasswordHash("$2a$10$test-only-not-a-real-hash");
        return users.save(user);
    }

    /** A session carrying the staff principal, as the form login stores it. */
    public static MockHttpSession session(StaffUser user) {
        return Sessions.staff(
                new StaffPrincipal(user, Instant.now()), user.getRole().name(), Instant.now());
    }
}
