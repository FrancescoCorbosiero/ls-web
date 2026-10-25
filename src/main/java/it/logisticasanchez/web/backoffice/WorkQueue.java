package it.logisticasanchez.web.backoffice;

/** What waits for staff (ADM-02 and ADM-04 queues): shown as counts in the navigation and on the home (ADR 0029). */
public record WorkQueue(long customers, long orders) {

    public long total() {
        return customers + orders;
    }
}
