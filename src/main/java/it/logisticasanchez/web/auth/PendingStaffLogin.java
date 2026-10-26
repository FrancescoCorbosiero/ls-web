package it.logisticasanchez.web.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;

/**
 * A staff login between the right password and the code (ENH-09, ADR 0039): held in the session,
 * never in the security context, so nothing of the back office opens until the code is right.
 * {@code newSecret}: the secret being set up by a user who has none yet.
 */
record PendingStaffLogin(long staffUserId, String username, Instant passwordAt, int wrongCodes, byte[] newSecret)
        implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    static final String SESSION_ATTRIBUTE = PendingStaffLogin.class.getName();

    /** How long the code page waits after the password. */
    static final Duration VALIDITY = Duration.ofMinutes(5);

    /** Wrong codes allowed after one password before the login starts again. */
    static final int MAX_WRONG_CODES = 5;

    static PendingStaffLogin of(StaffPrincipal staff, Instant at) {
        return new PendingStaffLogin(staff.id(), staff.getUsername(), at, 0, null);
    }

    /** The pending login of this session, if any and still within {@link #VALIDITY}. */
    static PendingStaffLogin current(HttpServletRequest request, Instant now) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(SESSION_ATTRIBUTE) instanceof PendingStaffLogin pending)) {
            return null;
        }
        if (now.isAfter(pending.passwordAt().plus(VALIDITY))) {
            session.removeAttribute(SESSION_ATTRIBUTE);
            return null;
        }
        return pending;
    }

    static void clear(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(SESSION_ATTRIBUTE);
        }
    }

    void store(HttpServletRequest request) {
        request.getSession().setAttribute(SESSION_ATTRIBUTE, this);
    }

    PendingStaffLogin withWrongCode() {
        return new PendingStaffLogin(staffUserId, username, passwordAt, wrongCodes + 1, newSecret);
    }

    PendingStaffLogin withNewSecret(byte[] secret) {
        return new PendingStaffLogin(staffUserId, username, passwordAt, wrongCodes, secret);
    }
}
