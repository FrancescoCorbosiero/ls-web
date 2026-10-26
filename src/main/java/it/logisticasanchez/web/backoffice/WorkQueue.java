package it.logisticasanchez.web.backoffice;

/**
 * What waits for staff (ADM-02 and ADM-04 queues): shown as counts in the navigation and on the
 * home (ADR 0029). {@code leads}: the new website requests (ENH-06, ADR 0036), null while
 * {@code leads.inbox} is off; they are not part of {@link #total()}, the approvals.
 */
public record WorkQueue(long customers, long orders, Long leads) {

    public long total() {
        return customers + orders;
    }
}
