package it.logisticasanchez.web.platform.events;

/**
 * The in-process domain events of the app (plan §4.1 "platform", ADR 0025): the only extension
 * point for the chapter 09 "Later" backlog (WhatsApp, driver app, RKS, e-invoicing), which would
 * subscribe with {@code @EventListener}. No subscriber exists in M1/M2. Events carry ids only.
 */
public sealed interface DomainEvent {

    record CustomerVerified(long customerId) implements DomainEvent {}

    record CustomerApproved(long customerId, long staffUserId) implements DomainEvent {}

    record CustomerDeclined(long customerId, long staffUserId) implements DomainEvent {}

    /** Staff changed a customer's registration data (ADM-12). */
    record CustomerChanged(long customerId, long staffUserId) implements DomainEvent {}

    record OrderCreated(long orderId, long customerId) implements DomainEvent {}

    record OrderApproved(long orderId, long staffUserId) implements DomainEvent {}

    record OrderDeclined(long orderId, long staffUserId) implements DomainEvent {}

    /** An order's lines, shipment data or recipient changed (CUS-07, ADM-11). */
    record OrderChanged(long orderId) implements DomainEvent {}

    /** An order was withdrawn by the customer or cancelled by staff (CUS-07, ADM-11). */
    record OrderCancelled(long orderId) implements DomainEvent {}

    record TrackingChanged(long orderId, int trackingStateId, long staffUserId) implements DomainEvent {}

    record LeadStored(long leadId) implements DomainEvent {}
}
