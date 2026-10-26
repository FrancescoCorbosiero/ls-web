package it.logisticasanchez.web.orders;

import it.logisticasanchez.web.notifications.EmailMessage;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.tracking.DeliveryEstimate;
import it.logisticasanchez.web.tracking.TrackingCatalog;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * ENH-05 «e-mail at every tracking change» (flag {@code tracking.notifyCustomer}; ADR 0034): the
 * single place that decides whether a change of tracking state e-mails the customer. Only the
 * states the customer has not been told about otherwise: TRANSITO, TERMINAL_DEST, ASSEGNATO —
 * PRESA comes with the approval e-mail (EML-05), CONSEGNATO with EML-09, NON_GESTITO is a
 * correction, not news. {@link OrderService#setTrackingState} queues the message in the same
 * transaction as the event, so it goes once per change and never for a state set again.
 */
@Component
class TrackingUpdateNotice {

    static final Set<Integer> NOTIFIED_STATES = Set.of(2, 3, 4);

    private final FeatureFlags flags;
    private final OrderMails mails;
    private final DeliveryEstimate estimates;

    TrackingUpdateNotice(FeatureFlags flags, OrderMails mails, DeliveryEstimate estimates) {
        this.flags = flags;
        this.mails = mails;
        this.estimates = estimates;
    }

    /** The e-mail for an approved order that has just moved to {@code state}, or empty. */
    Optional<EmailMessage> mailFor(OrderView order, TrackingCatalog.StateView state) {
        if (!NOTIFIED_STATES.contains((int) state.id()) || !flags.isEnabled(FlagKey.TRACKING_NOTIFY_CUSTOMER)) {
            return Optional.empty();
        }
        return Optional.of(mails.trackingUpdate(
                order,
                state,
                estimates
                        .estimate(order.recipient().postalCode(), order.approvedAt())
                        .orElse(null)));
    }
}
