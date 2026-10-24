package it.logisticasanchez.web.orders;

import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.customers.CustomerView;
import it.logisticasanchez.web.notifications.NotificationService;
import it.logisticasanchez.web.platform.audit.AuditActorType;
import it.logisticasanchez.web.platform.audit.AuditEntry;
import it.logisticasanchez.web.platform.audit.AuditService;
import it.logisticasanchez.web.platform.config.OrdersProperties;
import it.logisticasanchez.web.platform.events.DomainEvent;
import it.logisticasanchez.web.platform.events.DomainEvents;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import it.logisticasanchez.web.tracking.TrackingCatalog;
import it.logisticasanchez.web.tracking.TrackingState;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The orders module's public service (plan §4.1): the wizard submission (CUS-04: ACTIVE sender
 * only, temporary code {@code TEMP-xxxxxxxx}, idempotent on the draft's key, totals, snapshots,
 * EML-04 + EML-06), the customer's list and detail (CUS-05: own orders only), and the back-office
 * rules of chapter 08 §2.4 — approve with a unique code and an amount > 0 (ADM-04, Q-06, 12c
 * §4.2; PRESA event, EML-05), decline (EML-05), tracking state changes as events with EML-09
 * only when the state becomes CONSEGNATO (ADM-06), the internal note (ADM-07). Every change
 * writes the audit log in the same transaction.
 */
@Service
public class OrderService {

    static final int CODE_MAX = 64;
    private static final String TEMP_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ShipmentOrderRepository orders;
    private final TrackingEventRepository trackingEvents;
    private final OrderInternalNoteRepository notes;
    private final CustomerService customers;
    private final TrackingCatalog catalog;
    private final OrderMails mails;
    private final NotificationService notifications;
    private final AuditService audit;
    private final DomainEvents events;
    private final OrdersProperties rules;
    private final Clock clock;

    public OrderService(
            ShipmentOrderRepository orders,
            TrackingEventRepository trackingEvents,
            OrderInternalNoteRepository notes,
            CustomerService customers,
            TrackingCatalog catalog,
            OrderMails mails,
            NotificationService notifications,
            AuditService audit,
            DomainEvents events,
            OrdersProperties rules,
            Clock clock) {
        this.orders = orders;
        this.trackingEvents = trackingEvents;
        this.notes = notes;
        this.customers = customers;
        this.catalog = catalog;
        this.mails = mails;
        this.notifications = notifications;
        this.audit = audit;
        this.events = events;
        this.rules = rules;
        this.clock = clock;
    }

    /**
     * CUS-04: creates the PENDING order of the signed-in customer from a complete draft. A draft
     * whose idempotency key already produced an order returns that order and creates nothing
     * (OP-02 fix). Refused unless the customer is ACTIVE (OP-R2, CUS-03).
     */
    @Transactional
    public OrderView submit(long customerId, long customerUserId, OrderDraft draft) {
        Optional<ShipmentOrder> existing = orders.findByIdempotencyKey(draft.idempotencyKey());
        if (existing.isPresent()) {
            return view(existing.get());
        }
        CustomerView customer = customers.find(customerId).orElseThrow();
        if (!customer.canOrder()) {
            throw new RuleViolationException("Solo un cliente approvato può inviare ordini.");
        }
        if (!draft.isComplete()) {
            throw new RuleViolationException("L'ordine non è completo.");
        }
        Instant now = clock.instant();
        ShipmentOrder.Party sender = new ShipmentOrder.Party(
                customer.companyName(),
                customer.addressLine(),
                customer.province(),
                customer.city(),
                customer.postalCode(),
                customer.phone(),
                customer.email());
        OrderDraft.Recipient r = draft.recipient();
        ShipmentOrder.Party recipient = new ShipmentOrder.Party(
                r.name(), r.addressLine(), r.province(), r.city(), r.postalCode(), r.phone(), r.email());
        ShipmentOrder order = new ShipmentOrder(customerId, tempCode(), draft.idempotencyKey(), now, sender, recipient);
        String packages = ItalianFormats.trim(draft.packageCount());
        order.setDetails(
                ItalianFormats.trim(draft.transportDocumentCode()),
                packages.isEmpty() ? null : Integer.valueOf(packages),
                draft.accessoryServiceId(),
                ItalianFormats.trim(draft.notes()).isEmpty() ? null : ItalianFormats.trim(draft.notes()));
        for (OrderDraft.Line line : draft.lines()) {
            order.addLine(
                    line.kind(), line.heightCm(), line.lengthCm(), line.widthCm(), line.weightKg(), line.quantity());
        }
        order = orders.save(order);
        OrderView view = view(order);
        notifications.enqueue(mails.orderReceived(view));
        notifications.enqueue(mails.newOrder(view));
        audit.record(AuditEntry.of(AuditActorType.CUSTOMER, customerUserId, "order.submit")
                .entity("shipment_order", order.getId(), order.getTempCode())
                .change(
                        null,
                        Map.of(
                                "status", order.getStatus().name(),
                                "lines", order.getLines().size(),
                                "totalWeightKg", order.getTotalWeightKg(),
                                "totalVolumeM3", order.getTotalVolumeM3())));
        events.publish(new DomainEvent.OrderCreated(order.getId(), customerId));
        return view;
    }

    /** CUS-05: the customer's orders, newest first. */
    @Transactional(readOnly = true)
    public List<OrderView> listForCustomer(long customerId) {
        return orders.findByCustomerIdOrderBySubmittedAtDesc(customerId).stream()
                .map(this::view)
                .toList();
    }

    /** CUS-05: the customer's order with this code or temporary code; empty for another customer's order. */
    @Transactional(readOnly = true)
    public Optional<OrderView> findForCustomer(long customerId, String code) {
        String key = ItalianFormats.trim(code).toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return orders.findByCustomerIdAndAnyCode(customerId, key).map(this::view);
    }

    @Transactional(readOnly = true)
    public Optional<OrderView> find(long orderId) {
        return orders.findById(orderId).map(this::view);
    }

    /**
     * ADM-05: the back-office list, newest first, paginated server-side, with the optional
     * filters of {@link OrderSearch}; the date range is inclusive in Europe/Rome days.
     */
    @Transactional(readOnly = true)
    public Page<OrderView> search(OrderSearch search, int page, int size) {
        Specification<ShipmentOrder> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (search.status() != null) {
                where.add(cb.equal(root.get("status"), search.status()));
            }
            if (search.trackingStateId() != null) {
                where.add(cb.equal(
                        root.get("trackingStateId"), search.trackingStateId().shortValue()));
            }
            if (search.customerId() != null) {
                where.add(cb.equal(root.get("customerId"), search.customerId()));
            }
            if (search.from() != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("submittedAt"), startOfDay(search.from())));
            }
            if (search.to() != null) {
                where.add(cb.lessThan(
                        root.get("submittedAt"), startOfDay(search.to().plusDays(1))));
            }
            String code = ItalianFormats.trim(search.code()).toLowerCase(Locale.ROOT);
            if (!code.isEmpty()) {
                String like =
                        "%" + code.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                where.add(cb.or(
                        cb.like(cb.lower(root.get("code")), like, '\\'),
                        cb.like(cb.lower(root.get("tempCode")), like, '\\')));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        Pageable pageable =
                PageRequest.of(Math.max(0, page), size, Sort.by(Sort.Order.desc("submittedAt"), Sort.Order.desc("id")));
        return orders.findAll(spec, pageable).map(this::view);
    }

    /** The approved order with this definitive code (trimmed, ignoring case): the tracking page (ADM-06). */
    @Transactional(readOnly = true)
    public Optional<OrderView> findApprovedByCode(String code) {
        String key = ItalianFormats.trim(code).toLowerCase(Locale.ROOT);
        return key.isEmpty() ? Optional.empty() : orders.findApprovedByCode(key).map(this::view);
    }

    /**
     * ADM-09: the approved orders of one customer submitted between {@code from} and {@code to}
     * (inclusive, Europe/Rome days), optionally only the delivered ones, oldest first. Never
     * another customer's order.
     */
    @Transactional(readOnly = true)
    public List<OrderView> approvedForReport(long customerId, LocalDate from, LocalDate to, boolean onlyDelivered) {
        Specification<ShipmentOrder> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("customerId"), customerId));
            where.add(cb.equal(root.get("status"), OrderStatus.APPROVED));
            where.add(cb.greaterThanOrEqualTo(root.get("submittedAt"), startOfDay(from)));
            where.add(cb.lessThan(root.get("submittedAt"), startOfDay(to.plusDays(1))));
            if (onlyDelivered) {
                where.add(cb.equal(root.get("trackingStateId"), TrackingState.CONSEGNATO));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        return orders.findAll(spec, Sort.by(Sort.Order.asc("submittedAt"), Sort.Order.asc("id"))).stream()
                .map(this::view)
                .toList();
    }

    /** Erasure (NFR-03): removes the staff notes of every order of the customer (the orders stay with their snapshots). */
    @Transactional
    public int eraseInternalNotes(long customerId) {
        return notes.deleteByCustomerId(customerId);
    }

    /** Every order of a customer, oldest first (the export tool, NFR-03). */
    @Transactional(readOnly = true)
    public List<OrderView> allForCustomer(long customerId) {
        return orders.findByCustomerIdOrderByIdAsc(customerId).stream()
                .map(this::view)
                .toList();
    }

    private static Instant startOfDay(LocalDate day) {
        return day.atStartOfDay(ItalianFormats.ROME).toInstant();
    }

    /** ADM-04: the orders waiting for approval, oldest first. */
    @Transactional(readOnly = true)
    public List<OrderView> pendingApproval() {
        return orders.findByStatusOrderBySubmittedAtAsc(OrderStatus.PENDING).stream()
                .map(this::view)
                .toList();
    }

    /**
     * ADM-04 approve: {@code codeText} trimmed, non-empty, unique ignoring case (Q-06);
     * {@code amountText} a euro amount > 0 with comma or point (12c §4.2). Writes the PRESA
     * tracking event (source APPROVAL) and queues EML-05.
     */
    @Transactional
    public OrderView approve(long orderId, long staffUserId, String codeText, String amountText) {
        ShipmentOrder order = orders.findById(orderId).orElseThrow();
        String code = ItalianFormats.trim(codeText);
        if (code.isEmpty()) {
            throw new RuleViolationException("Inserisci il codice della spedizione.");
        }
        if (code.length() > CODE_MAX) {
            throw new RuleViolationException("Il codice può avere al massimo " + CODE_MAX + " caratteri.");
        }
        if (orders.existsByCodeNormalised(code.toLowerCase(Locale.ROOT))) {
            throw new RuleViolationException("E' già stata effettuata una registrazione con questo codice."
                    + " Si prega di riprovare con un nuovo codice.");
        }
        BigDecimal amount = ItalianFormats.parseDecimal(amountText)
                .filter(a -> a.signum() > 0 && a.stripTrailingZeros().scale() <= 2)
                .orElseThrow(
                        () -> new RuleViolationException("Inserisci un importo maggiore di zero, per esempio 180,50."))
                .setScale(2);
        Instant now = clock.instant();
        order.approve(code, amount, rules.amount().vatIncludedDefault(), staffUserId, now, TrackingState.PRESA); // Q-05
        trackingEvents.save(
                new TrackingEvent(order.getId(), TrackingState.PRESA, now, TrackingEvent.SOURCE_APPROVAL, staffUserId));
        OrderView view = view(order);
        notifications.enqueue(mails.approved(view));
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "order.approve")
                .entity("shipment_order", order.getId(), code)
                .change(
                        Map.of("status", OrderStatus.PENDING.name()),
                        Map.of("status", OrderStatus.APPROVED.name(), "code", code, "amount", amount)));
        events.publish(new DomainEvent.OrderApproved(order.getId(), staffUserId));
        events.publish(new DomainEvent.TrackingChanged(order.getId(), TrackingState.PRESA, staffUserId));
        return view;
    }

    /** ADM-04 decline: DECLINED + EML-05 (decline text quoting the temporary code). */
    @Transactional
    public OrderView decline(long orderId, long staffUserId, String reason) {
        ShipmentOrder order = orders.findById(orderId).orElseThrow();
        order.decline(staffUserId, clock.instant(), reason);
        OrderView view = view(order);
        notifications.enqueue(mails.declined(view));
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "order.decline")
                .entity("shipment_order", order.getId(), order.getTempCode())
                .change(Map.of("status", OrderStatus.PENDING.name()), Map.of("status", OrderStatus.DECLINED.name())));
        events.publish(new DomainEvent.OrderDeclined(order.getId(), staffUserId));
        return view;
    }

    /**
     * ADM-06: sets the tracking state of an approved order (any of the six may follow any other);
     * one event per change, none when the state is set again; EML-09 only when it becomes
     * CONSEGNATO.
     */
    @Transactional
    public OrderView setTrackingState(long orderId, long staffUserId, int stateId) {
        ShipmentOrder order = orders.findById(orderId).orElseThrow();
        TrackingCatalog.StateView state =
                catalog.state(stateId).orElseThrow(() -> new RuleViolationException("Stato di tracking sconosciuto."));
        short previous = order.getTrackingStateId();
        Instant now = clock.instant();
        if (order.setTrackingState(state.id(), now)) {
            trackingEvents.save(
                    new TrackingEvent(order.getId(), state.id(), now, TrackingEvent.SOURCE_STAFF, staffUserId));
            if (state.id() == TrackingState.CONSEGNATO) {
                notifications.enqueue(mails.delivered(view(order)));
            }
            audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "order.tracking")
                    .entity("shipment_order", order.getId(), order.getCode())
                    .change(Map.of("trackingStateId", previous), Map.of("trackingStateId", state.id())));
            events.publish(new DomainEvent.TrackingChanged(order.getId(), state.id(), staffUserId));
        }
        return view(order);
    }

    /** ADM-06: the tracking history of an order, oldest first. */
    @Transactional(readOnly = true)
    public List<OrderView.TrackingChange> trackingHistory(long orderId) {
        return trackingEvents.findByOrderIdOrderByOccurredAtAscIdAsc(orderId).stream()
                .map(e -> new OrderView.TrackingChange(
                        e.getStateId(), label(e.getStateId()), e.getOccurredAt(), e.getSource()))
                .toList();
    }

    /** ADM-07: the staff note, separate from the customer's note, with the before/after text in the audit detail. */
    @Transactional
    public void setInternalNote(long orderId, long staffUserId, String body) {
        orders.findById(orderId).orElseThrow();
        String text = ItalianFormats.trim(body);
        if (text.length() > 5000) {
            throw new RuleViolationException("La nota può avere al massimo 5000 caratteri.");
        }
        Instant now = clock.instant();
        OrderInternalNote note = notes.findById(orderId).orElse(null);
        String before = note == null ? null : note.getBody();
        if (note == null) {
            if (text.isEmpty()) {
                return;
            }
            notes.save(new OrderInternalNote(orderId, text, staffUserId, now));
        } else if (text.isEmpty()) {
            notes.delete(note);
        } else {
            note.update(text, staffUserId, now);
        }
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "order.note")
                .entity("shipment_order", orderId, null)
                .personal(
                        before == null ? null : Map.of("note", before), text.isEmpty() ? null : Map.of("note", text)));
    }

    @Transactional(readOnly = true)
    public Optional<String> internalNote(long orderId) {
        return notes.findById(orderId).map(OrderInternalNote::getBody);
    }

    OrderView view(ShipmentOrder o) {
        return new OrderView(
                o.getId(),
                o.getCustomerId(),
                o.getStatus(),
                o.getTempCode(),
                o.getCode(),
                o.getTransportDocumentCode(),
                o.getPackageCount(),
                o.getAccessoryServiceId(),
                o.getAccessoryServiceId() == null
                        ? catalog.activeServices().stream()
                                .filter(TrackingCatalog.ServiceView::none)
                                .map(TrackingCatalog.ServiceView::label)
                                .findFirst()
                                .orElse("-")
                        : catalog.service(o.getAccessoryServiceId())
                                .map(TrackingCatalog.ServiceView::label)
                                .orElse("-"),
                o.getCustomerNotes(),
                o.getAmount(),
                o.getAmountVatIncluded(),
                o.getTotalWeightKg(),
                o.getTotalVolumeM3(),
                o.getTrackingStateId(),
                label(o.getTrackingStateId()),
                o.getTrackingUpdatedAt(),
                o.sender(),
                o.recipient(),
                o.getSubmittedAt(),
                o.getApprovedAt(),
                o.getDeclinedAt(),
                o.getDeclineReason(),
                o.getLines().stream()
                        .map(l -> new OrderView.Line(
                                l.getLineNo(),
                                l.getKind(),
                                l.getHeightCm(),
                                l.getLengthCm(),
                                l.getWidthCm(),
                                l.getWeightKg(),
                                l.getQuantity(),
                                l.getLineWeightKg(),
                                l.getLineVolumeM3()))
                        .toList());
    }

    private String label(short stateId) {
        return catalog.state(stateId).map(TrackingCatalog.StateView::label).orElse("-");
    }

    /** {@code TEMP-} plus 8 random base-32 characters (chapter 08 §2.5): keeps the legacy marker, cannot be guessed. */
    static String tempCode() {
        StringBuilder sb = new StringBuilder("TEMP-");
        for (int i = 0; i < 8; i++) {
            sb.append(TEMP_ALPHABET.charAt(RANDOM.nextInt(TEMP_ALPHABET.length())));
        }
        return sb.toString();
    }
}
