package it.logisticasanchez.web.backoffice;

/**
 * What waits for staff (ADM-02 and ADM-04 queues): shown as counts in the navigation and on the
 * home (ADR 0029). {@code leads}: the new website requests (ENH-06, ADR 0036), null while
 * {@code leads.inbox} is off; they are not part of {@link #total()}, the approvals.
 * {@code customerChanges}: the customers' data changes waiting for approval (ENH-16, ADR 0038),
 * null while {@code customers.profileEdit} is off; listed on «In Approvazione» with the customers.
 */
public record WorkQueue(long customers, long orders, Long leads, Long customerChanges) {

    /** What «In Approvazione» lists: the customers to approve and, with ENH-16, their data changes. */
    public long customerApprovals() {
        return customers + (customerChanges == null ? 0 : customerChanges);
    }

    public long total() {
        return customerApprovals() + orders;
    }
}
