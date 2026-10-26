package it.logisticasanchez.web.quotes;

/** The states of a saved quote (ENH-20, ADR 0037): SENT until the customer orders it (ACCEPTED) or staff withdraw it. */
public enum QuoteStatus {
    SENT,
    ACCEPTED,
    WITHDRAWN
}
