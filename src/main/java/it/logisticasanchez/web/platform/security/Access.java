package it.logisticasanchez.web.platform.security;

/** Who may call a declared route. */
public enum Access {
    /** Anyone, no session. */
    PUBLIC,
    /** A customer session ({@code ROLE_CUSTOMER}). */
    CUSTOMER,
    /** A staff session, ADMIN or OPERATOR. */
    STAFF,
    /** A staff session with ADMIN. */
    ADMIN
}
