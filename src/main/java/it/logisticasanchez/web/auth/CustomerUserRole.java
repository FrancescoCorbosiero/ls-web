package it.logisticasanchez.web.auth;

/** Role of a login identity inside its customer: M1 has exactly one OWNER per customer (Q-11). */
public enum CustomerUserRole {
    OWNER,
    MEMBER
}
