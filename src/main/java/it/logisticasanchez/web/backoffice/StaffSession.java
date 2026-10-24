package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.auth.StaffPrincipal;
import it.logisticasanchez.web.platform.security.Authorities;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * The signed-in staff member as the back-office pages see it. Pages only need the name and the
 * role; actions need the id, which exists only for a real {@link StaffPrincipal} session
 * ({@link #requireId} answers 401 otherwise).
 */
public record StaffSession(Long id, String displayName, boolean admin) {

    public static StaffSession of(Authentication authentication) {
        if (authentication == null) {
            return new StaffSession(null, "", false);
        }
        boolean admin = false;
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            admin |= Authorities.ROLE_ADMIN.equals(authority.getAuthority());
        }
        if (authentication.getPrincipal() instanceof StaffPrincipal staff) {
            return new StaffSession(staff.id(), staff.displayName(), admin);
        }
        return new StaffSession(null, authentication.getName(), admin);
    }

    /** The staff user id for an action; 401 through the error model when the session carries none. */
    public long requireId() {
        if (id == null) {
            throw new ClientErrorException(ErrorCode.UNAUTHENTICATED);
        }
        return id;
    }
}
